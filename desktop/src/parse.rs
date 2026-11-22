//! Reads the amount, shop and date out of OCR text (port of `Parser.kt`).
use crate::shops::unaccent;
use chrono::NaiveDate;
use regex::Regex;
use std::{collections::BTreeMap, sync::LazyLock};

macro_rules! re { ($name:ident, $p:expr) => { static $name: LazyLock<Regex> = LazyLock::new(|| Regex::new($p).unwrap()); }; }

const SKIP: &[&str] = &["ticket", "facture", "tel", "tél", "siret", "www", "http", "merci", "caisse", "date", "tva", "total", "net",
    "carte", "reçu", "recu", "terminal", "acquereur", "acquéreur", "autorisation", "transaction", "paiement",
    "amount", "montant", "rue", "avenue", "boulevard", "paris", "france", "cedex", "visa", "mastercard", "cb",
    "sans contact", "signature", "brut", "ref", "id ", "customn", "custom", "pan", "emv", "aid", "atc", "contact"];
const FOOTER: &[&str] = &["debit", "credit", "ticket", "client", "conserver", "comptant", "montant", "total", "net", "merci",
    "carte", "bancaire", "contact", "paiement", "visa", "mastercard", "autorisation", "auto", "terminal", "transaction", "signature"];

re!(AMOUNT, r"(\d{1,5})\s?[.,]\s?(\d+)");
re!(EURO, r"(?i)€|\b[EF][UIL]R\b");
re!(CLOCK, r"\d{1,2}[.:]\d{2}[.:]\d{2}");
re!(DATE_LINE, r"(?i)^\s*le\s+\d{1,2}\s*[/.]\s*[0-9OIl]{1,2}\s*[/.]\s*\d{2,4}");
re!(DATE_NUM, r"([0-9OIl]{1,2})\s?[/.\-]\s?([0-9OIl]{1,2})\s?[/.\-]\s?((?:20)?[0-9OIl]{2})(.{0,25})");
re!(DATE_WORDS, r"\b([0-3]?\d)\s+(juin|juil|jan|fev|mar|avr|mai|aou|sep|oct|nov|dec)[a-z]*\.?\s+((?:20)?\d{2})\b(.{0,25})");
re!(TIME, r"\b([0-2]?\d)\s?[:.h]\s?([0-5]\d)");
re!(NOT_LETTER, "[^a-zà-ÿ]+");

/// Amounts (in cents) found in a string: "14.8" is 14.80, a single decimal digit is a tenth, not a cent.
fn nums(s: &str) -> Vec<i64> {
    AMOUNT.captures_iter(s).filter(|c| c[2].len() <= 2).map(|c| {
        let d = if c[2].len() == 1 { format!("{}0", &c[2]) } else { c[2].to_string() };
        c[1].parse::<i64>().unwrap() * 100 + d.parse::<i64>().unwrap()
    }).collect()
}

fn is_date_or_time(l: &str) -> bool { l.contains(':') || l.contains('/') || CLOCK.is_match(l) }

pub fn amount(text: &str) -> Option<f64> {
    let lines: Vec<&str> = text.lines().filter(|l| !is_date_or_time(l)).collect();
    // The OCR often splits "Total" from its value, so also look at amounts carrying a currency sign.
    let euro: Vec<i64> = lines.iter().filter(|l| EURO.is_match(l)).flat_map(|l| nums(l)).collect();
    let cents = if !euro.is_empty() {
        let top = euro.iter().map(|v| euro.iter().filter(|x| *x == v).count()).max().unwrap();
        euro.iter().filter(|v| euro.iter().filter(|x| x == v).count() == top).max().copied()
    } else {
        let strong: Vec<i64> = lines.iter().filter(|l| { let l = l.to_lowercase(); l.contains("total") || l.contains("payer") || l.contains("montant") })
            .flat_map(|l| nums(l)).collect();
        if !strong.is_empty() { strong.into_iter().max() } else { lines.iter().flat_map(|l| nums(l)).max() }
    };
    cents.map(|c| c as f64 / 100.0)
}

/// On card-terminal receipts the shop is printed under the "LE 14/11/26 A 17:56:36" line, before the footer.
fn below_date_line(text: &str) -> Option<String> {
    let lines: Vec<&str> = text.lines().map(str::trim).collect();
    let i = lines.iter().position(|l| DATE_LINE.is_match(l))?;
    lines[i + 1..].iter().take(3).find(|l| {
        let low = l.to_lowercase();
        l.chars().filter(|c| c.is_alphabetic()).count() >= 3 && l.chars().count() <= 40 && !l.chars().all(|c| c.is_ascii_digit() || c.is_whitespace())
            && !NOT_LETTER.split(&low).any(|w| FOOTER.contains(&w))
    }).map(|l| l.to_string())
}

pub fn shop(text: &str) -> Option<String> {
    if let Some(s) = below_date_line(text) { return Some(s); }
    let cands: Vec<&str> = text.lines().map(str::trim).filter(|l| {
        let low = l.to_lowercase();
        l.chars().filter(|c| c.is_alphabetic()).count() >= 4 && l.chars().count() <= 40 && !l.chars().next().map_or(true, |c| c.is_ascii_digit())
            && SKIP.iter().all(|s| !low.contains(s)) && l.chars().filter(|c| c.is_ascii_digit()).count() <= 2
    }).collect();
    // Shop names are usually printed in capitals, on several words.
    let caps = cands.iter().filter(|l| l.contains(' ') && !l.chars().any(|c| c.is_ascii_digit()) && l.chars().filter(|c| c.is_alphabetic()).all(|c| c.is_uppercase()))
        .fold(None::<&&str>, |b, l| if b.map_or(true, |b| l.chars().count() > b.chars().count()) { Some(l) } else { b });
    caps.or(cands.first()).map(|s| s.to_string())
}

const CONFUSABLE: &[(char, char)] = &[('i', 't'), ('i', 'l'), ('i', '1'), ('t', 'l'), ('0', 'o'), ('o', 'q'), ('8', 'b'), ('5', 's'),
    ('m', 'h'), ('e', 'f'), ('c', 'g'), ('u', 'v'), ('z', '2'), ('a', 'o'), ('r', 'n'), ('u', 'n')];

fn subst(a: char, b: char) -> f64 {
    if a == b { 0.0 } else if CONFUSABLE.iter().any(|&(x, y)| (a, b) == (x, y) || (a, b) == (y, x)) { 0.3 } else { 1.0 }
}

/// Edit distance where swapping letters the OCR confuses costs little.
pub fn distance(a: &str, b: &str) -> f64 {
    let (a, b): (Vec<char>, Vec<char>) = (a.chars().collect(), b.chars().collect());
    let mut prev: Vec<f64> = (0..=b.len()).map(|i| i as f64).collect();
    for i in 1..=a.len() {
        let mut cur = vec![i as f64; b.len() + 1];
        for j in 1..=b.len() { cur[j] = (prev[j] + 1.0).min(cur[j - 1] + 1.0).min(prev[j - 1] + subst(a[i - 1], b[j - 1])); }
        prev = cur;
    }
    prev[b.len()]
}

/// A previously saved shop that matches a line of the OCR text, tolerating about 20% of typos.
pub fn known_shop(text: &str, known: &[String]) -> Option<String> {
    let lines: Vec<String> = text.lines().map(|l| l.trim().to_lowercase()).filter(|l| l.chars().count() >= 3).collect();
    known.iter().filter(|k| !k.trim().is_empty() && *k != "Unknown").filter_map(|k| {
        let d = lines.iter().map(|l| distance(l, &k.to_lowercase())).fold(f64::MAX, f64::min);
        (d <= k.chars().count() as f64 / 5.0).then(|| (k, d))
    }).fold(None::<(&String, f64)>, |b, c| if b.map_or(true, |b| c.1 < b.1) { Some(c) } else { b }).map(|x| x.0.clone())
}

fn digits(s: &str) -> Option<u32> { s.replace(['O', 'o'], "0").replace(['I', 'l'], "1").parse().ok() }

/// The ticket date as "yyyy-MM-dd" or "yyyy-MM-dd HH:mm". Future dates are rejected, and when the OCR drops a digit
/// of a month ("19/1/2026" for 19/11/2026) the candidate closest to today wins.
pub fn date(text: &str, today: NaiveDate) -> Option<String> {
    let mut found: Vec<(NaiveDate, Option<String>)> = Vec::new();
    let mut add = |d: u32, months: &[u32], year: i32, tail: &str| {
        let y = if year < 100 { year + 2000 } else { year };
        let t = TIME.captures(tail).map(|c| format!("{:02}:{}", c[1].parse::<u32>().unwrap(), &c[2]));
        for &m in months {
            if let Some(dt) = NaiveDate::from_ymd_opt(y, m, d) {
                if dt <= today && (2000..=2100).contains(&y) { found.push((dt, t.clone())); }
            }
        }
    };
    for m in DATE_NUM.captures_iter(text) {
        let (Some(d), Some(mo), Some(y)) = (digits(&m[1]), digits(&m[2]), digits(&m[3])) else { continue };
        let one = [mo];
        add(d, if mo == 1 { &[1, 10, 11, 12] } else { &one }, y as i32, &m[4]);
    }
    let low = unaccent(text).to_lowercase();
    for m in DATE_WORDS.captures_iter(&low) {
        let months = ["jan", "fev", "mar", "avr", "mai", "juin", "juil", "aou", "sep", "oct", "nov", "dec"];
        let (Ok(d), Ok(y), Some(mo)) = (m[1].parse::<u32>(), m[3].parse::<i32>(), months.iter().position(|x| *x == &m[2])) else { continue };
        add(d, &[mo as u32 + 1], y, &m[4]);
    }
    let mut counts: BTreeMap<(NaiveDate, Option<String>), usize> = BTreeMap::new();
    for f in found { *counts.entry(f).or_default() += 1; }
    let ((dt, t), _) = counts.into_iter().max_by_key(|((d, _), n)| (*d, *n))?;
    Some(match t { Some(t) => format!("{dt} {t}"), None => dt.to_string() })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn d(s: &str) -> Option<String> { date(s, NaiveDate::from_ymd_opt(2026, 11, 21).unwrap()) }
    const MATELY: &str = "CARTE BANCAIRE\nSANS CONTACT\nA0000000421010\nCB COMPTANT\nLE 14/11/26 A 17:56:36\nLE MATELY S\n92 COURBEVOIE\n8874182\n17515\n############4564\nNO AUTO:\nMONTANT:\n8,90 EUR\nDEBIT\nTICKET CLIENT\nA CONSERVER";
    const CAFE: &str = "CARTE BANCAIRE\nSANS CONTACT\nA0000000421010\nCB COMPTANT\nLE 10/11/26 A 18:27:58\nHV\n92HV CAFE\n4492759\n30004\n############4564\nN° AUTO : 731502\nMONTANT\n9,80 EUR\nDEBIT\nTICKET CLIENT A CONSERVER";

    #[test]
    fn dates() {
        assert_eq!(d("REF. 024641\n19/11/2026 22:18:39\nPAIEMENT").as_deref(), Some("2026-11-19 22:18"));
        assert_eq!(d("Le 19/1/2026 a 18:52:57").as_deref(), Some("2026-11-19 18:52"));
        assert_eq!(d("Tel : 01.48.24.02.90\njeudi 12 novembre 2026 à 20:59:43\nServi par : Antoine").as_deref(), Some("2026-11-12 20:59"));
        assert_eq!(d("MARDI 3 FEVRIER 2026 A 12:30").as_deref(), Some("2026-02-03 12:30"));
        assert_eq!(d("5 janv. 2026").as_deref(), Some("2026-01-05"));
        assert_eq!(d("30 juin 2026").as_deref(), Some("2026-06-30"));
        assert_eq!(d("14 juillet 2026").as_deref(), Some("2026-07-14"));
        assert_eq!(d("8 mars 2026").as_deref(), Some("2026-03-08"));
        assert_eq!(d("25/12/2026 10:00"), None);
        assert_eq!(d("TOTAL 36,80 EUR"), None);
    }

    #[test]
    fn amounts() {
        assert_eq!(amount(MATELY), Some(8.9));
        assert_eq!(amount(CAFE), Some(9.8));
        assert_eq!(amount("TOTAL 36,80 EUR\nTVA 6,13"), Some(36.8));
        assert_eq!(amount("MONTANT 14.8"), Some(14.8));
        assert_eq!(amount("rien"), None);
    }

    #[test]
    fn shops() {
        assert_eq!(shop(MATELY).as_deref(), Some("LE MATELY S"));
        assert_eq!(shop(CAFE).as_deref(), Some("92HV CAFE"));
        assert_eq!(shop("CB COMPTANT\nLe 19/I/2026 a 18:52:57\nTHE FROG & BRITI\nPARIS\n75013").as_deref(), Some("THE FROG & BRITI"));
        assert_eq!(shop("VTD MARKET\nCOURBEVOIE\n92400\n3391579\n19/11/2026 22:18:39\nREF. 024641 REC ID 000278").as_deref(), Some("VTD MARKET"));
    }

    #[test]
    fn a_known_shop_tolerates_ocr_typos() {
        let known = vec!["VTD MARKET".to_string(), "boulangerie".to_string()];
        assert_eq!(known_shop("VTD HARKET\nCOURBEVOIE", &known).as_deref(), Some("VTD MARKET"));
        assert_eq!(known_shop("autre chose", &known), None);
    }
}
