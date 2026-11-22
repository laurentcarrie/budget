mod media;
mod parse;
mod s3;
mod shops;
mod spoken;
mod store;
mod tui;

use clap::{Parser, Subcommand};
use std::path::PathBuf;
use store::{Store, Ticket};

#[derive(Parser)]
#[command(about = "Read and enter laurent-budget tickets from the laptop (syncs through the same S3 prefix as the phone)")]
struct Cli {
    /// aws.properties file (default: $BUDGET_CONFIG, ~/.config/budget/aws.properties, then ./aws.properties, ../aws.properties)
    #[arg(long, global = true)]
    config: Option<PathBuf>,
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Full-screen interface: browse, search, add, edit, delete, sync
    Tui,
    /// Push local changes and pull the other devices' tickets
    Sync,
    /// List tickets, most recent first; every WORD must be found in the date, shop, category, description or amount
    List { words: Vec<String>, /// only this month (YYYY-MM) or year (YYYY)
        #[arg(long)] period: Option<String> },
    /// Total per category (and overall), optionally for one period
    Summary { #[arg(long)] period: Option<String> },
    /// Add a ticket (date defaults to now)
    Add {
        amount: String, shop: String,
        #[arg(long)] date: Option<String>, #[arg(long, default_value = "")] category: String, #[arg(long, default_value = "")] description: String,
    },
    /// Edit a ticket; ID is the uid or any unique part of it
    Edit {
        id: String,
        #[arg(long)] amount: Option<String>, #[arg(long)] shop: Option<String>, #[arg(long)] date: Option<String>,
        #[arg(long)] category: Option<String>, #[arg(long)] description: Option<String>,
    },
    /// Read a receipt photo with OCR (or a webcam shot when no PHOTO is given), then confirm and save the ticket
    Scan {
        photo: Option<PathBuf>,
        /// webcam device used when there is no PHOTO
        #[arg(long, default_value = "/dev/video0")] camera: String,
        /// save what was read without asking
        #[arg(long)] yes: bool,
    },
    /// Say the amount (and optionally a date), then the shop, in French; confirm and save the ticket
    Speak {
        /// seconds recorded for each answer
        #[arg(long, default_value_t = 5)] seconds: u32,
        /// skip the microphone: use this text for the amount (e.g. "quatorze euros quatre-vingts hier")
        #[arg(long)] amount_text: Option<String>,
        /// skip the microphone: use this text for the shop (e.g. "chez Carbone")
        #[arg(long)] shop_text: Option<String>,
        #[arg(long)] yes: bool,
    },
    /// Delete a ticket (the delete reaches the phone at its next sync)
    Delete { id: String },
}

fn fold(s: &str) -> String {
    s.to_lowercase().chars().map(|c| match c {
        'à' | 'â' | 'ä' => 'a', 'é' | 'è' | 'ê' | 'ë' => 'e', 'î' | 'ï' => 'i', 'ô' | 'ö' => 'o', 'ù' | 'û' | 'ü' => 'u', 'ç' => 'c', c => c,
    }).collect()
}

pub(crate) fn matches(t: &Ticket, words: &[String], period: &Option<String>) -> bool {
    if period.as_ref().map_or(false, |p| !t.date.starts_with(p.as_str())) { return false; }
    let hay = fold(&format!("{} {} {} {} {:.2}", t.date, t.shop, t.category, t.description, t.amount));
    words.iter().all(|w| hay.contains(&fold(w)))
}

/// "14,80" or "14.80" (a trailing € or EUR is accepted).
pub(crate) fn amount(s: &str) -> Result<f64, String> {
    let c = s.trim().trim_end_matches('€').trim_end_matches("EUR").trim().replace(',', ".");
    c.parse::<f64>().ok().filter(|a| a.is_finite() && *a >= 0.0).ok_or(format!("bad amount {s:?}"))
}

pub(crate) fn date(s: Option<String>) -> Result<String, String> {
    let Some(s) = s else { return Ok(chrono::Local::now().format("%Y-%m-%d %H:%M").to_string()) };
    let ok = chrono::NaiveDate::parse_from_str(s.get(..10).unwrap_or(""), "%Y-%m-%d").is_ok() && (s.len() == 10 || chrono::NaiveTime::parse_from_str(s.get(11..).unwrap_or(""), "%H:%M").is_ok());
    if ok { Ok(s) } else { Err(format!("bad date {s:?}, expected YYYY-MM-DD or YYYY-MM-DD HH:MM")) }
}

/// Pads to `w` terminal cells (an emoji takes two cells but is one `char`, so `{:<w}` would misalign the columns).
fn pad(s: &str, w: usize) -> String {
    let cells = unicode_width::UnicodeWidthStr::width(s);
    format!("{s}{}", " ".repeat(w.saturating_sub(cells)))
}

fn print(t: &Ticket) {
    use ratatui::crossterm::style::{Color, Stylize};
    use std::io::IsTerminal;
    let short = t.uid.strip_prefix("ticket_").unwrap_or(&t.uid);
    let cells = [(format!("{:<16}", t.date), Color::Blue), (format!("{:>9.2} €", t.amount), Color::Yellow), (pad(&t.shop, 24), Color::Green),
        (pad(&t.category, 16), Color::Rgb { r: 255, g: 165, b: 0 }), (t.description.clone(), Color::Magenta)];
    let color = std::io::stdout().is_terminal();
    let line: Vec<String> = cells.into_iter().map(|(text, c)| if color { text.with(c).to_string() } else { text }).collect();
    let tail = if color { format!("[{short}]").dark_grey().to_string() } else { format!("[{short}]") };
    println!("{} {}  {} {} {}  {tail}", line[0], line[1], line[2], line[3], line[4]);
}

pub(crate) fn config(path: Option<PathBuf>) -> Result<s3::Config, String> {
    let home = std::env::var_os("HOME").map(PathBuf::from).unwrap_or_default();
    let candidates = path.into_iter().chain(std::env::var_os("BUDGET_CONFIG").map(PathBuf::from))
        .chain([home.join(".config/budget/aws.properties"), "aws.properties".into(), "../aws.properties".into()]);
    let found = candidates.into_iter().find(|p| p.exists()).ok_or("no aws.properties found (use --config)")?;
    s3::Config::load(&found)
}

/// What was understood from a photo or from speech, to correct before saving (the app's confirmation dialog).
struct Proposal { date: Option<String>, shop: String, amount: Option<f64> }

fn save(store: &mut Store, p: Proposal, yes: bool) -> Result<(), String> {
    let known = store.shops();
    let now = chrono::Local::now().format("%Y-%m-%d %H:%M").to_string();
    let (mut d, mut shop, mut a) = (p.date.unwrap_or(now), p.shop, p.amount.map(|a| format!("{a:.2}")).unwrap_or_default());
    let mut category = store.usual_category(&shop);
    let mut description = String::new();
    if !yes {
        println!("Press Enter to keep a value. Categories: {}", store.categories().join(", "));
        d = date(Some(media::ask("Date", &d)))?;
        shop = media::ask("Shop", &shop);
        category = media::ask("Category", &if category.is_empty() { store.usual_category(&shop) } else { category });
        a = media::ask("Amount", &a);
        description = media::ask("Description", "");
    }
    let shop = shops::canonical(&shop, &known);
    if shop.is_empty() { return Err("no shop (type one, or use `budget add`)".into()); }
    let category = shops::canonical(&category, &store.categories());
    let uid = store.add(date(Some(d))?, shop, amount(&a)?, category, description);
    print(&store.entries[&uid].ticket);
    println!("saved (run `budget sync` to send it)");
    Ok(())
}

fn run(cli: Cli) -> Result<(), String> {
    let mut store = Store::load();
    match cli.cmd {
        Cmd::Tui => crate::tui::run(&mut store, cli.config)?,
        Cmd::Sync => {
            let (sent, received) = store.sync(&config(cli.config)?)?;
            println!("sent {sent}, received {received}, {} tickets", store.visible().len());
        }
        Cmd::List { words, period } => {
            let shown: Vec<_> = store.visible().into_iter().filter(|t| matches(t, &words, &period)).collect();
            shown.iter().for_each(|t| print(t));
            println!("{} tickets, total {:.2} €", shown.len(), shown.iter().fold(0.0, |a, t| a + t.amount));
        }
        Cmd::Summary { period } => {
            let mut per: std::collections::BTreeMap<String, (usize, f64)> = Default::default();
            let cats = store.categories();
            for t in store.visible().into_iter().filter(|t| matches(t, &[], &period)) {
                let name = cats.iter().find(|c| shops::key(c) == shops::key(&t.category)).cloned().unwrap_or_default();
                let e = per.entry(if name.is_empty() { "(none)".into() } else { name }).or_default();
                e.0 += 1; e.1 += t.amount;
            }
            let mut rows: Vec<_> = per.into_iter().collect();
            rows.sort_by(|a, b| b.1 .1.total_cmp(&a.1 .1));
            rows.iter().for_each(|(c, (n, s))| println!("{} {n:>4}  {s:>10.2} €", pad(c, 20)));
            println!("{:<20} {:>4}  {:>10.2} €", "TOTAL", rows.iter().map(|r| r.1 .0).sum::<usize>(), rows.iter().map(|r| r.1 .1).sum::<f64>());
        }
        Cmd::Add { amount: a, shop, date: d, category, description } => {
            let shop = shops::canonical(&shop, &store.shops());
            let category = shops::canonical(&category, &store.categories());
            let uid = store.add(date(d)?, shop, amount(&a)?, category, description);
            println!("added {uid} (run `budget sync` to send it)");
        }
        Cmd::Edit { id, amount: a, shop, date: d, category, description } => {
            let (a, d) = (a.map(|a| amount(&a)).transpose()?, d.map(|d| date(Some(d))).transpose()?);
            let uid = store.change(&id, |t| {
                if let Some(a) = a { t.amount = a; }
                if let Some(d) = d { t.date = d; }
                if let Some(s) = shop { t.shop = s.trim().to_string(); }
                if let Some(c) = category { t.category = c.trim().to_string(); }
                if let Some(x) = description { t.description = x; }
            })?;
            print(&store.entries[&uid].ticket);
        }
        Cmd::Scan { photo, camera, yes } => {
            let image = match photo { Some(p) => p, None => { println!("Taking a photo from {camera}..."); media::capture(&camera)? } };
            let text = media::ocr(&image)?;
            let known = store.shops();
            let shop = parse::known_shop(&text, &known).or_else(|| parse::shop(&text)).unwrap_or_default();
            let proposal = Proposal { date: parse::date(&text, chrono::Local::now().date_naive()), shop, amount: parse::amount(&text) };
            save(&mut store, proposal, yes)?;
        }
        Cmd::Speak { seconds, amount_text, shop_text, yes } => {
            let listen = |question: &str, given: Option<String>| -> Result<String, String> {
                if let Some(t) = given { return Ok(t); }
                media::ask(&format!("{question} - press Enter, then speak ({seconds} s)"), "");
                println!("Listening...");
                let heard = media::transcribe(&media::record(seconds)?)?;
                println!("Heard: {heard}");
                Ok(heard)
            };
            let dated = spoken::without_date(&listen("Amount (and date)", amount_text)?, chrono::Local::now().date_naive());
            let amount = spoken::amount(&dated.text);
            let said = listen("Shop", shop_text)?;
            let shop = spoken::without_date(&said, chrono::Local::now().date_naive());
            let shop_name = spoken::shop(&[shop.text.clone()], &store.shops()).unwrap_or_default();
            let date = dated.date.or(shop.date).map(|d| d.to_string());
            save(&mut store, Proposal { date, shop: shop_name, amount }, yes)?;
        }
        Cmd::Delete { id } => { let uid = store.change(&id, |t| t.deleted = true)?; println!("deleted {uid}"); }
    }
    store.save()
}

fn main() {
    if let Err(e) = run(Cli::parse()) { eprintln!("error: {e}"); std::process::exit(1); }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_amounts() {
        assert_eq!(amount("14,80").unwrap(), 14.8);
        assert_eq!(amount("14.8 €").unwrap(), 14.8);
        assert!(amount("abc").is_err() && amount("-3").is_err());
    }

    #[test]
    fn validates_dates() {
        assert!(date(Some("2026-11-07".into())).is_ok() && date(Some("2026-11-07 18:30".into())).is_ok());
        assert!(date(Some("07/11/2026".into())).is_err() && date(Some("2026-13-01".into())).is_err());
    }

    #[test]
    fn search_ignores_case_and_accents() {
        let t = Ticket { uid: "u".into(), date: "2026-11-07 10:00".into(), shop: "Boulangerie Méré".into(), amount: 3.2, currency: "EUR".into(), photo: None, deleted: false, updated_at: 1, description: String::new(), category: "alimentation".into() };
        assert!(matches(&t, &["MERE".into(), "alim".into()], &Some("2026-11".into())));
        assert!(!matches(&t, &["pub".into()], &None));
        assert!(!matches(&t, &[], &Some("2025".into())));
    }
}
