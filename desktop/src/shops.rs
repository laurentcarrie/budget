//! Shop and category names as the user sees them: "pub" and "pub 🍻" are the same one (port of `Shops.kt`).
use std::collections::HashMap;
use unicode_normalization::{char::is_combining_mark, UnicodeNormalization};

pub const DEFAULT_CATEGORIES: [&str; 12] = ["alimentation 🛒", "restaurant 🍽️", "pub 🍻", "péage 🛣️", "essence ⛽", "transport 🚆",
    "santé 💊", "loisirs 🎭", "vêtements 👕", "maison 🏠", "cadeaux 🎁", "divers 📦"];

pub fn unaccent(s: &str) -> String { s.nfd().filter(|c| !is_combining_mark(*c)).collect() }

/// What makes two names the same shop: letters and digits only, without case or accents.
pub fn key(s: &str) -> String { unaccent(s).to_lowercase().chars().filter(|c| c.is_alphanumeric()).collect() }

/// One entry per shop, the most used first; the spelling shown is the one used on most tickets.
pub fn distinct<'a>(per_ticket: impl IntoIterator<Item = &'a str>) -> Vec<String> {
    let mut groups: Vec<(String, Vec<String>)> = Vec::new();
    let mut index: HashMap<String, usize> = HashMap::new();
    for n in per_ticket.into_iter().map(str::trim).filter(|n| !n.is_empty()) {
        let k = Some(key(n)).filter(|k| !k.is_empty()).unwrap_or_else(|| n.to_lowercase());
        let i = *index.entry(k.clone()).or_insert_with(|| { groups.push((k, Vec::new())); groups.len() - 1 });
        groups[i].1.push(n.to_string());
    }
    let mut out: Vec<(String, usize)> = groups.into_iter().map(|(_, v)| {
        let mut counts: Vec<(&String, usize)> = Vec::new();
        for n in &v { match counts.iter_mut().find(|c| c.0 == n) { Some(c) => c.1 += 1, None => counts.push((n, 1)) } }
        // most used spelling, the first seen on a tie
        let best = counts.iter().fold(None::<&(&String, usize)>, |b, c| if b.map_or(true, |b| c.1 > b.1) { Some(c) } else { b }).unwrap();
        (best.0.clone(), v.len())
    }).collect();
    out.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.to_lowercase().cmp(&b.0.to_lowercase())));
    out.into_iter().map(|x| x.0).collect()
}

/// The name to save: the known spelling when the same shop is already known.
pub fn canonical(name: &str, known: &[String]) -> String {
    let n = name.trim();
    let k = key(n);
    if k.is_empty() { return n.to_string(); }
    known.iter().find(|x| key(x) == k).cloned().unwrap_or_else(|| n.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn emoji_case_accents_and_spaces_do_not_matter() {
        assert_eq!(key("Péage 🛣️ "), key("peage"));
        assert_eq!(distinct(["pub", "pub 🍻", "Pub", "pub 🍻", "maison"]), vec!["pub 🍻", "maison"]);
        assert_eq!(canonical(" ALIMENTATION ", &["alimentation 🛒".to_string()]), "alimentation 🛒");
    }
}
