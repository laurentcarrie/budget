//! Understands what a French speaker says for a ticket: the amount, the shop and the date (port of `Spoken.kt`).
use crate::{parse, shops::unaccent};
use chrono::{Datelike, Duration, NaiveDate, Weekday};
use regex::Regex;
use std::sync::LazyLock;

fn unit(w: &str) -> Option<i64> {
    Some(match w { "zero" => 0, "un" | "une" => 1, "deux" => 2, "trois" => 3, "quatre" => 4, "cinq" => 5, "six" => 6, "sept" => 7, "huit" => 8,
        "neuf" => 9, "dix" => 10, "onze" => 11, "douze" => 12, "treize" => 13, "quatorze" => 14, "quinze" => 15, "seize" => 16, _ => return None })
}
fn ten(w: &str) -> Option<i64> {
    Some(match w { "vingt" | "vingts" => 20, "trente" => 30, "quarante" => 40, "cinquante" => 50, "soixante" => 60, "septante" => 70,
        "huitante" => 80, "nonante" => 90, _ => return None })
}

enum Tok { Num(i64, bool), Euro, Cents, Sep, Other }
#[derive(Clone, Copy, PartialEq)]
enum Item { Group(i64), Euro, Cents, Sep }

fn tokens(text: &str) -> Vec<Tok> {
    let t = unaccent(&text.to_lowercase()).replace('€', " euro ");
    let words: Vec<&str> = t.split(|c: char| !c.is_ascii_alphanumeric()).filter(|w| !w.is_empty()).collect();
    let mut out = Vec::new();
    let mut i = 0;
    while i < words.len() {
        let w = words[i];
        if w == "quatre" && matches!(words.get(i + 1), Some(&"vingt") | Some(&"vingts")) { out.push(Tok::Num(80, false)); i += 1; }
        else if w.chars().all(|c| c.is_ascii_digit()) && w.len() <= 4 { out.push(Tok::Num(w.parse().unwrap(), true)); }
        else if let Some(v) = unit(w).or_else(|| ten(w)) { out.push(Tok::Num(v, false)); }
        else {
            match w {
                "cent" | "cents" => out.push(Tok::Num(100, false)),
                "euro" | "euros" | "eur" => out.push(Tok::Euro),
                "centime" | "centimes" | "cts" => out.push(Tok::Cents),
                "virgule" => out.push(Tok::Sep),
                "et" => {}
                _ => out.push(Tok::Other),
            }
        }
        i += 1;
    }
    out
}

/// The number written in words `g` can be extended with `v` ("vingt" + "cinq", "soixante" + "onze", "cent" + "vingt").
fn join(g: i64, v: i64) -> Option<i64> {
    let low = g % 100;
    let ok = match v {
        100 => return if (1..=9).contains(&g) { Some(g * 100) } else { None },
        1..=9 => [20, 30, 40, 50, 60, 70, 80, 90].contains(&low) || (low == 10 && v >= 7) || (g > 0 && low == 0),
        10..=19 => low == 60 || low == 80 || (g > 0 && low == 0),
        _ if v >= 20 => g > 0 && low == 0,
        _ => false,
    };
    ok.then_some(g + v)
}

#[allow(unused_assignments)]
fn items(toks: &[Tok]) -> Vec<Item> {
    let (mut out, mut g, mut started) = (Vec::new(), 0, false);
    macro_rules! flush { () => {{ if started { out.push(Item::Group(g)); } g = 0; started = false; }}; }
    for t in toks {
        match t {
            Tok::Num(v, true) => { flush!(); out.push(Item::Group(*v)); }
            Tok::Num(v, false) if !started => { g = *v; started = true; }
            Tok::Num(v, false) => match join(g, *v) { Some(j) => g = j, None => { flush!(); g = *v; started = true; } },
            Tok::Euro => { flush!(); out.push(Item::Euro); }
            Tok::Cents => { flush!(); out.push(Item::Cents); }
            Tok::Sep => { flush!(); out.push(Item::Sep); }
            Tok::Other => flush!(),
        }
    }
    flush!();
    out
}

static DECIMAL: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(\d{1,4})\s*[.,]\s*(\d+)").unwrap());

fn finish(x: f64) -> Option<f64> { (x > 0.0 && x < 10000.0).then(|| (x * 100.0).round() / 100.0) }

/// The amount in euros, or None when no amount was understood.
pub fn amount(text: &str) -> Option<f64> {
    // "14,80" or "14.8" as the recogniser writes them
    if let Some(c) = DECIMAL.captures_iter(text).find(|c| c[2].len() <= 2) {
        let d = if c[2].len() == 1 { format!("{}0", &c[2]) } else { c[2].to_string() };
        return finish(format!("{}.{d}", &c[1]).parse().unwrap());
    }
    let items = items(&tokens(text));
    let group = |it: Option<&Item>| match it { Some(Item::Group(v)) => Some(*v), _ => None };
    if let Some(marker) = items.iter().position(|i| matches!(i, Item::Euro | Item::Sep)) {
        let euros = group(items[..marker].iter().rev().find(|i| matches!(i, Item::Group(_))))?;
        let cents = group(items[marker + 1..].iter().find(|i| matches!(i, Item::Group(_))));
        let frac = match cents {
            None => 0.0,
            Some(c) if items[marker] == Item::Sep && c < 10 => c as f64 / 10.0, // "14 virgule 5" is 14.50
            Some(c) => c as f64 / 100.0,
        };
        return finish(euros as f64 + frac);
    }
    let groups: Vec<i64> = items.iter().filter_map(|i| group(Some(i))).collect();
    match groups.as_slice() {
        [] => None,
        [g, ..] if items.contains(&Item::Cents) => finish(*g as f64 / 100.0),
        [a, b, ..] if (0..=99).contains(b) => finish(*a as f64 + *b as f64 / 100.0), // "douze cinquante"
        [g, ..] => finish(*g as f64),
    }
}

static ART_SPACE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)^(?:chez|aux|au|[àa] la|[àa])\s+").unwrap());
static ART_ELIDED: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)^(?:[àa] l'|l')\s*").unwrap());
static ART: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)^(?:le|la|les)\s+").unwrap());

fn clean_shop(s: &str) -> String {
    let mut t = s.trim().to_string();
    for _ in 0..2 { t = ART.replace(&ART_ELIDED.replace(&ART_SPACE.replace(&t, ""), ""), "").trim().to_string(); }
    t
}

/// The shop to propose: a known shop that matches what was said (exactly, as the start of its name, or with a few
/// recognition typos), otherwise what was said with a capital letter.
pub fn shop(candidates: &[String], known: &[String]) -> Option<String> {
    let cleaned: Vec<String> = candidates.iter().map(|c| clean_shop(c)).filter(|c| !c.is_empty()).collect();
    if cleaned.is_empty() { return None; }
    let shops: Vec<String> = known.iter().filter(|k| !k.trim().is_empty() && *k != "Unknown").cloned().collect();
    let fold = |s: &str| unaccent(&s.to_lowercase());
    for c in &cleaned {
        let lc = fold(c);
        if let Some(s) = shops.iter().find(|s| fold(s) == lc) { return Some(s.clone()); }
        if lc.chars().count() >= 3 { if let Some(s) = shops.iter().find(|s| fold(s).starts_with(&lc)) { return Some(s.clone()); } }
    }
    if let Some(s) = parse::known_shop(&cleaned.join("\n"), &shops) { return Some(s); }
    let first = &cleaned[0];
    let mut ch = first.chars();
    Some(ch.next().map(|c| c.to_uppercase().collect::<String>() + ch.as_str()).unwrap())
}

#[derive(Debug, PartialEq)]
pub struct Dated { pub text: String, pub date: Option<NaiveDate> }

fn weekday(w: &str) -> Option<Weekday> {
    Some(match w { "lundi" => Weekday::Mon, "mardi" => Weekday::Tue, "mercredi" => Weekday::Wed, "jeudi" => Weekday::Thu, "vendredi" => Weekday::Fri,
        "samedi" => Weekday::Sat, "dimanche" => Weekday::Sun, _ => return None })
}
fn month(w: &str) -> Option<u32> {
    Some(match w { "janvier" | "janv" => 1, "fevrier" | "fevr" => 2, "mars" => 3, "avril" | "avr" => 4, "mai" => 5, "juin" => 6, "juillet" | "juil" => 7,
        "aout" => 8, "septembre" | "sept" => 9, "octobre" | "oct" => 10, "novembre" | "nov" => 11, "decembre" | "dec" => 12, _ => return None })
}

/// What is left of `text` once the date words ("hier", "le 7 novembre", "samedi dernier", "il y a deux jours") are
/// removed, and the date they meant.
pub fn without_date(text: &str, today: NaiveDate) -> Dated {
    let words: Vec<&str> = text.split_whitespace().collect();
    let n: Vec<String> = words.iter().map(|w| unaccent(&w.to_lowercase()).replace('’', "'").trim_matches(|c| ",.;!?".contains(c)).to_string()).collect();
    let num = |i: usize| n.get(i).and_then(|w| w.parse::<i64>().ok().or_else(|| unit(w)));
    let at = |i: usize| n.get(i).map(String::as_str);
    let hit = |from: usize, to: usize, date: NaiveDate| {
        let start = if from > 0 && n[from - 1] == "le" { from - 1 } else { from }; // "le 7 novembre", "le samedi"
        Dated { text: words.iter().enumerate().filter(|(i, _)| *i < start || *i > to).map(|x| *x.1).collect::<Vec<_>>().join(" "), date: Some(date) }
    };
    for i in 0..words.len() {
        let w = n[i].as_str();
        if w == "avant" && at(i + 1) == Some("hier") { return hit(i, i + 1, today - Duration::days(2)); }
        match w {
            "avant-hier" => return hit(i, i, today - Duration::days(2)),
            "hier" => return hit(i, i, today - Duration::days(1)),
            "aujourd'hui" | "aujourdhui" => return hit(i, i, today),
            _ => {}
        }
        if w == "il" && at(i + 1) == Some("y") && at(i + 2) == Some("a") && num(i + 3).is_some() && at(i + 4).map_or(false, |x| x.starts_with("jour")) {
            return hit(i, i + 4, today - Duration::days(num(i + 3).unwrap()));
        }
        if let Some(wd) = weekday(w) {
            let last = matches!(at(i + 1), Some("dernier") | Some("derniere"));
            let back = (today.weekday().num_days_from_monday() + 7 - wd.num_days_from_monday()) % 7;
            let mut d = today - Duration::days(back as i64);
            if last && d == today { d = today - Duration::days(7); }
            return hit(i, if last { i + 1 } else { i }, d);
        }
        if let (Some(day @ 1..=31), Some(mo)) = (w.parse::<u32>().ok(), at(i + 1).and_then(month)) {
            let year = at(i + 2).filter(|y| y.len() == 4).and_then(|y| y.parse::<i32>().ok()).filter(|y| (2000..=2100).contains(y));
            let Some(mut d) = NaiveDate::from_ymd_opt(year.unwrap_or(today.year()), mo, day) else { continue };
            if year.is_none() && d > today { d = NaiveDate::from_ymd_opt(d.year() - 1, mo, day).unwrap_or(d); } // "le 25 decembre" said in november is last year's
            return hit(i, if year.is_some() { i + 2 } else { i + 1 }, d);
        }
    }
    Dated { text: text.to_string(), date: None }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn a(s: &str) -> f64 { amount(s).unwrap_or_else(|| panic!("no amount in {s:?}")) }
    fn day(y: i32, m: u32, d: u32) -> NaiveDate { NaiveDate::from_ymd_opt(y, m, d).unwrap() }
    fn d(s: &str) -> Dated { without_date(s, day(2026, 11, 21)) } // a Saturday
    fn k() -> Vec<String> { ["CARBONE 14", "boulangerie", "VTD MARKET", "restaurant entreprise RSS"].map(String::from).to_vec() }
    fn sh(c: &[&str]) -> Option<String> { shop(&c.iter().map(|s| s.to_string()).collect::<Vec<_>>(), &k()) }

    #[test]
    fn digits_and_words() {
        for (s, v) in [("14,80", 14.8), ("14.8", 14.8), ("12,50 euros", 12.5), ("14 euros 80", 14.8), ("8 euros 50", 8.5), ("36 euros", 36.0), ("12 50", 12.5),
            ("14 € 80", 14.8), ("quatorze euros quatre-vingts", 14.8), ("quatorze euros quatre vingt", 14.8), ("trois euros trente", 3.3),
            ("un euro cinquante", 1.5), ("huit euros cinquante", 8.5), ("douze euros cinq", 12.05), ("quatorze euros et quatre-vingts", 14.8),
            ("douze cinquante", 12.5), ("vingt-cinq", 25.0), ("soixante quinze centimes", 0.75), ("vingt-cinq euros", 25.0), ("vingt et un euros", 21.0),
            ("dix-huit euros", 18.0), ("dix sept euros", 17.0), ("soixante-dix euros", 70.0), ("soixante-et-onze euros", 71.0), ("soixante-dix-sept euros", 77.0),
            ("quatre-vingts euros", 80.0), ("quatre vingt un euros", 81.0), ("quatre-vingt-onze euros", 91.0), ("cent vingt euros", 120.0),
            ("quatre-vingt-dix-neuf euros quatre-vingt-dix-neuf", 99.99), ("deux cents euros", 200.0), ("deux cent cinquante euros", 250.0), ("sept euros", 7.0)] {
            assert!((a(s) - v).abs() < 0.001, "{s} gave {}", a(s));
        }
        assert!(amount("bonjour").is_none() && amount("").is_none() && amount("chez carbone").is_none());
    }

    #[test]
    fn shops() {
        assert_eq!(sh(&["à la boulangerie"]).as_deref(), Some("boulangerie"));
        assert_eq!(sh(&["chez Carbone"]).as_deref(), Some("CARBONE 14"));
        assert_eq!(sh(&["vtd harket"]).as_deref(), Some("VTD MARKET"));
        assert_eq!(sh(&["bou langerie", "la boulangerie"]).as_deref(), Some("boulangerie"));
        assert_eq!(sh(&["au pub"]).as_deref(), Some("Pub"));
        assert_eq!(sh(&["au café de la gare"]).as_deref(), Some("Café de la gare"));
        assert!(sh(&[]).is_none() && sh(&["  "]).is_none());
    }

    #[test]
    fn dates() {
        assert_eq!(d("quatorze euros hier"), Dated { text: "quatorze euros".into(), date: Some(day(2026, 11, 20)) });
        assert_eq!(d("avant hier").date, Some(day(2026, 11, 19)));
        assert_eq!(d("le 7 novembre"), Dated { text: String::new(), date: Some(day(2026, 11, 7)) });
        assert_eq!(d("le 7 novembre 2025").date, Some(day(2025, 11, 7)));
        assert_eq!(d("le 3 janv.").date, Some(day(2026, 1, 3)));
        assert_eq!(d("28 février").date, Some(day(2026, 2, 28)));
        assert_eq!(d("le 25 décembre").date, Some(day(2025, 12, 25)));
        assert_eq!(d("samedi dernier").date, Some(day(2026, 11, 14)));
        assert_eq!(d("samedi").date, Some(day(2026, 11, 21)));
        assert_eq!(d("lundi").date, Some(day(2026, 11, 16)));
        assert_eq!(d("café le mercredi"), Dated { text: "café".into(), date: Some(day(2026, 11, 18)) });
        assert_eq!(d("il y a deux jours").date, Some(day(2026, 11, 19)));
        assert_eq!(d("il y a 3 jours").date, Some(day(2026, 11, 18)));
        assert_eq!(d("chez Carbone"), Dated { text: "chez Carbone".into(), date: None });
        assert!(d("12 euros").date.is_none() && d("31 février").date.is_none());
        assert!((a(&d("quatorze euros quatre-vingts hier").text) - 14.8).abs() < 0.001);
    }
}
