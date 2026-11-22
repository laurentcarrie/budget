//! Tickets cached locally, and the two-way sync (same rules as the Android `Sync.kt`: the latest `updatedAt` wins).
use crate::s3::{self, Config};
use crate::shops;
use serde::{Deserialize, Serialize};
use std::{collections::BTreeMap, fs, path::PathBuf};

/// One ticket, in the JSON shape of `<uid>.json`.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Ticket {
    #[serde(default)] pub uid: String,
    #[serde(default)] pub date: String,
    #[serde(default)] pub shop: String,
    #[serde(default)] pub amount: f64,
    #[serde(default = "eur")] pub currency: String,
    #[serde(default)] pub photo: Option<String>,
    #[serde(default)] pub deleted: bool,
    #[serde(default)] pub updated_at: i64,
    #[serde(default)] pub description: String,
    #[serde(default)] pub category: String,
}
fn eur() -> String { "EUR".into() }

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Entry { pub ticket: Ticket, pub synced: bool, #[serde(default)] pub etag: String }

#[derive(Serialize, Deserialize, Default)]
pub struct Store { pub entries: BTreeMap<String, Entry> }

#[derive(Debug, PartialEq)]
pub enum Action { Insert, Apply, Keep }

/// What to do with a downloaded ticket: the most recent change wins.
pub fn decide(local: Option<&Ticket>, remote: &Ticket) -> Action {
    match local { None => Action::Insert, Some(l) if remote.updated_at > l.updated_at => Action::Apply, _ => Action::Keep }
}

pub fn now_ms() -> i64 { chrono::Utc::now().timestamp_millis() }

impl Store {
    pub fn path() -> PathBuf {
        let base = std::env::var_os("XDG_DATA_HOME").map(PathBuf::from)
            .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".local/share"))).unwrap_or_default();
        base.join("budget/tickets.json")
    }

    pub fn load() -> Store {
        fs::read_to_string(Self::path()).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default()
    }

    pub fn save(&self) -> Result<(), String> {
        let p = Self::path();
        fs::create_dir_all(p.parent().unwrap()).map_err(|e| e.to_string())?;
        fs::write(&p, serde_json::to_string_pretty(self).unwrap()).map_err(|e| e.to_string())
    }

    /// The tickets to show, most recent first.
    pub fn visible(&self) -> Vec<&Ticket> {
        let mut v: Vec<_> = self.entries.values().map(|e| &e.ticket).filter(|t| !t.deleted).collect();
        v.sort_by(|a, b| b.date.cmp(&a.date).then(b.uid.cmp(&a.uid)));
        v
    }

    /// The shops of the saved tickets, one spelling per shop, the most used first.
    pub fn shops(&self) -> Vec<String> { shops::distinct(self.visible().iter().map(|t| t.shop.as_str())) }

    /// The categories to propose: the ones used (the most used first), then the default ones not used yet.
    pub fn categories(&self) -> Vec<String> {
        let mut used = shops::distinct(self.visible().iter().map(|t| t.category.as_str()));
        let extra: Vec<String> = shops::DEFAULT_CATEGORIES.iter().filter(|d| used.iter().all(|u| shops::key(u) != shops::key(d))).map(|d| d.to_string()).collect();
        used.extend(extra);
        used
    }

    /// The category most often given to the tickets of this shop (empty when there is none).
    pub fn usual_category(&self, shop: &str) -> String {
        let k = shops::key(shop);
        if k.is_empty() { return String::new(); }
        shops::distinct(self.visible().iter().filter(|t| shops::key(&t.shop) == k).map(|t| t.category.as_str())).into_iter().next().unwrap_or_default()
    }

    pub fn add(&mut self, date: String, shop: String, amount: f64, category: String, description: String) -> String {
        let uid = format!("ticket_{}_{}", now_ms(), "d35c");
        let ticket = Ticket { uid: uid.clone(), date, shop, amount, currency: eur(), photo: None, deleted: false, updated_at: now_ms(), description, category };
        self.entries.insert(uid.clone(), Entry { ticket, synced: false, etag: String::new() });
        uid
    }

    /// Edits a ticket found by uid (or a unique uid fragment); marks it for the next sync.
    pub fn change(&mut self, key: &str, f: impl FnOnce(&mut Ticket)) -> Result<String, String> {
        let uid = self.resolve(key)?;
        let e = self.entries.get_mut(&uid).unwrap();
        f(&mut e.ticket);
        e.ticket.updated_at = now_ms().max(e.ticket.updated_at + 1);
        e.synced = false;
        Ok(uid)
    }

    pub fn resolve(&self, key: &str) -> Result<String, String> {
        let hits: Vec<_> = self.entries.iter().filter(|(u, e)| !e.ticket.deleted && u.contains(key)).map(|(u, _)| u.clone()).collect();
        match hits.len() { 1 => Ok(hits[0].clone()), 0 => Err(format!("no ticket matches {key}")), n => Err(format!("{n} tickets match {key}, be more precise")) }
    }

    /// Pushes what changed here, then pulls what the other devices pushed. Returns (sent, received).
    pub fn sync(&mut self, cfg: &Config) -> Result<(usize, usize), String> {
        let (mut sent, mut received) = (0, 0);
        let pending: Vec<String> = self.entries.iter().filter(|(_, e)| !e.synced).map(|(u, _)| u.clone()).collect();
        for uid in pending {
            let e = self.entries.get_mut(&uid).unwrap();
            let body = serde_json::to_string_pretty(&e.ticket).unwrap();
            e.etag = s3::put(cfg, &format!("{uid}.json"), body.as_bytes())?;
            e.synced = true;
            if !e.ticket.deleted { sent += 1; }
            self.save()?;
        }
        for o in s3::list(cfg)?.into_iter().filter(|o| o.name.ends_with(".json")) {
            let uid = o.name.trim_end_matches(".json").to_string();
            if self.entries.get(&uid).map_or(false, |e| e.etag == o.etag) { continue; }
            let mut t: Ticket = serde_json::from_slice(&s3::get(cfg, &o.name)?).map_err(|e| format!("{}: {e}", o.name))?;
            t.uid = uid.clone();
            t.shop = t.shop.trim().to_string();
            t.category = t.category.trim().to_string();
            match decide(self.entries.get(&uid).map(|e| &e.ticket), &t) {
                Action::Insert | Action::Apply => {
                    if !t.deleted { received += 1; }
                    self.entries.insert(uid, Entry { ticket: t, synced: true, etag: o.etag });
                }
                Action::Keep => { if let Some(e) = self.entries.get_mut(&uid) { e.etag = o.etag; } }
            }
        }
        self.save()?;
        Ok((sent, received))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn t(updated_at: i64) -> Ticket { Ticket { uid: "u".into(), date: "2026-01-01".into(), shop: "S".into(), amount: 1.0, currency: eur(), photo: None, deleted: false, updated_at, description: String::new(), category: String::new() } }

    #[test]
    fn the_latest_change_wins() {
        assert_eq!(decide(None, &t(1)), Action::Insert);
        assert_eq!(decide(Some(&t(1)), &t(2)), Action::Apply);
        assert_eq!(decide(Some(&t(2)), &t(2)), Action::Keep);
        assert_eq!(decide(Some(&t(3)), &t(2)), Action::Keep);
    }

    #[test]
    fn reads_the_first_json_files_without_optional_fields() {
        let x: Ticket = serde_json::from_str(r#"{"date":"2026-01-02","shop":"A","amount":3.5,"photo":"a.jpg"}"#).unwrap();
        assert_eq!((x.updated_at, x.deleted, x.category.as_str(), x.photo.as_deref()), (0, false, "", Some("a.jpg")));
    }

    #[test]
    fn writes_the_android_field_names() {
        let j = serde_json::to_value(t(5)).unwrap();
        assert_eq!(j["updatedAt"], 5);
        assert!(j["photo"].is_null());
    }

    #[test]
    fn edit_marks_pending_and_delete_hides() {
        let mut s = Store::default();
        let uid = s.add("2026-02-01".into(), "Shop".into(), 9.0, String::new(), String::new());
        s.entries.get_mut(&uid).unwrap().synced = true;
        s.change(&uid[..12], |t| t.deleted = true).unwrap();
        assert!(!s.entries[&uid].synced);
        assert!(s.visible().is_empty());
    }
}
