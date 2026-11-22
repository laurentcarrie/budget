//! Full-screen terminal interface: browse, search, add, edit, delete and sync the tickets.
use crate::{amount, date, matches, s3, shops, store::{Store, Ticket}};
use ratatui::{
    crossterm::event::{self, Event, KeyCode, KeyEvent, KeyEventKind, KeyModifiers},
    layout::{Constraint, Flex, Layout, Rect},
    style::{Color, Modifier, Style, Stylize},
    text::{Line, Span},
    widgets::{Block, BorderType, Cell, Clear, Paragraph, Row, Table, TableState},
    DefaultTerminal, Frame,
};
use std::path::PathBuf;

const DATE: Color = Color::LightBlue;
const SHOP: Color = Color::LightGreen;
const AMOUNT: Color = Color::Yellow;
const CATEGORY: Color = Color::Rgb(255, 165, 0);
const DESCRIPTION: Color = Color::LightMagenta;
const LABELS: [&str; 5] = ["Date", "Shop", "Amount", "Category", "Description"];

struct Form { uid: Option<String>, fields: [String; 5], focus: usize, error: String }

enum Mode { Browse, Search, Form(Form), Delete(String), Summary }

struct App<'a> {
    store: &'a mut Store,
    config: Option<PathBuf>,
    query: String,
    mode: Mode,
    table: TableState,
    status: String,
}

pub fn run(store: &mut Store, config: Option<PathBuf>) -> Result<(), String> {
    let mut term = ratatui::init();
    let mut app = App { store, config, query: String::new(), mode: Mode::Browse, table: TableState::default().with_selected(0), status: String::new() };
    let result = app.run(&mut term);
    ratatui::restore();
    result.map_err(|e| e.to_string())
}

fn centered(area: Rect, w: u16, h: u16) -> Rect {
    let [v] = Layout::vertical([Constraint::Length(h.min(area.height))]).flex(Flex::Center).areas(area);
    let [r] = Layout::horizontal([Constraint::Length(w.min(area.width))]).flex(Flex::Center).areas(v);
    r
}

fn key(label: &'static str, what: &'static str) -> [Span<'static>; 2] {
    [Span::styled(format!(" {label} "), Style::new().black().on_cyan().bold()), Span::styled(format!(" {what} "), Style::new().dark_gray())]
}

impl App<'_> {
    fn rows(&self) -> Vec<Ticket> {
        let words: Vec<String> = self.query.split_whitespace().map(String::from).collect();
        self.store.visible().into_iter().filter(|t| matches(t, &words, &None)).cloned().collect()
    }

    fn run(&mut self, term: &mut DefaultTerminal) -> std::io::Result<()> {
        loop {
            term.draw(|f| self.draw(f))?;
            let Event::Key(k) = event::read()? else { continue };
            if k.kind != KeyEventKind::Press { continue; }
            if k.modifiers.contains(KeyModifiers::CONTROL) && k.code == KeyCode::Char('c') { return Ok(()); }
            if self.on_key(k, term)? { return Ok(()); }
        }
    }

    fn selected(&self) -> Option<Ticket> { self.rows().get(self.table.selected()?).cloned() }

    fn move_by(&mut self, delta: isize) {
        let n = self.rows().len();
        if n == 0 { return self.table.select(None); }
        let i = self.table.selected().unwrap_or(0) as isize;
        self.table.select(Some((i + delta).clamp(0, n as isize - 1) as usize));
    }

    /// Returns true to quit.
    fn on_key(&mut self, k: KeyEvent, term: &mut DefaultTerminal) -> std::io::Result<bool> {
        self.status.clear();
        match &mut self.mode {
            Mode::Browse => match k.code {
                KeyCode::Char('q') | KeyCode::Esc => return Ok(true),
                KeyCode::Down | KeyCode::Char('j') => self.move_by(1),
                KeyCode::Up | KeyCode::Char('k') => self.move_by(-1),
                KeyCode::PageDown => self.move_by(10),
                KeyCode::PageUp => self.move_by(-10),
                KeyCode::Home | KeyCode::Char('g') => self.table.select(Some(0)),
                KeyCode::End | KeyCode::Char('G') => { let n = self.rows().len(); self.table.select(n.checked_sub(1)); }
                KeyCode::Char('/') => self.mode = Mode::Search,
                KeyCode::Char('c') => self.mode = Mode::Summary,
                KeyCode::Char('a') => {
                    let now = chrono::Local::now().format("%Y-%m-%d %H:%M").to_string();
                    self.mode = Mode::Form(Form { uid: None, fields: [now, String::new(), String::new(), String::new(), String::new()], focus: 1, error: String::new() });
                }
                KeyCode::Char('e') | KeyCode::Enter => if let Some(t) = self.selected() {
                    self.mode = Mode::Form(Form { uid: Some(t.uid), fields: [t.date, t.shop, format!("{:.2}", t.amount), t.category, t.description], focus: 2, error: String::new() });
                },
                KeyCode::Char('d') | KeyCode::Delete => if let Some(t) = self.selected() { self.mode = Mode::Delete(t.uid); },
                KeyCode::Char('s') => self.sync(term)?,
                _ => {}
            },
            Mode::Search => match k.code {
                KeyCode::Enter | KeyCode::Esc => self.mode = Mode::Browse,
                KeyCode::Backspace => { self.query.pop(); self.table.select(Some(0)); }
                KeyCode::Char(c) => { self.query.push(c); self.table.select(Some(0)); }
                KeyCode::Down => { self.mode = Mode::Browse; }
                _ => {}
            },
            Mode::Summary => self.mode = Mode::Browse,
            Mode::Delete(uid) => {
                if matches!(k.code, KeyCode::Char('y') | KeyCode::Char('Y')) {
                    let uid = uid.clone();
                    match self.store.change(&uid, |t| t.deleted = true).and_then(|_| self.store.save()) {
                        Ok(_) => self.status = "Deleted (press s to sync)".into(),
                        Err(e) => self.status = e,
                    }
                    self.move_by(0);
                }
                self.mode = Mode::Browse;
            }
            Mode::Form(_) => self.form_key(k),
        }
        Ok(false)
    }

    fn form_key(&mut self, k: KeyEvent) {
        let Mode::Form(f) = &mut self.mode else { return };
        let leaving_shop = f.focus == 1;
        match k.code {
            KeyCode::Esc => { self.mode = Mode::Browse; return; }
            KeyCode::Tab | KeyCode::Down => f.focus = (f.focus + 1) % 5,
            KeyCode::BackTab | KeyCode::Up => f.focus = (f.focus + 4) % 5,
            KeyCode::Backspace => { f.fields[f.focus].pop(); }
            KeyCode::Right if f.focus == 3 => if let Some(s) = suggestion(self.store, &f.fields[3]) { f.fields[3] = s; },
            KeyCode::Char(c) => f.fields[f.focus].push(c),
            KeyCode::Enter => {
                let f = std::mem::replace(f, Form { uid: None, fields: Default::default(), focus: 0, error: String::new() });
                match self.save_form(&f) {
                    Ok(()) => self.mode = Mode::Browse,
                    Err(e) => self.mode = Mode::Form(Form { error: e, ..f }),
                }
                return;
            }
            _ => {}
        }
        if leaving_shop && f.focus != 1 && f.fields[3].is_empty() {
            f.fields[3] = self.store.usual_category(&f.fields[1]);
        }
    }

    fn save_form(&mut self, f: &Form) -> Result<(), String> {
        let (d, a) = (date(Some(f.fields[0].clone()))?, amount(&f.fields[2])?);
        let shop = shops::canonical(&f.fields[1], &self.store.shops());
        if shop.is_empty() { return Err("the shop is empty".into()); }
        let category = shops::canonical(&f.fields[3], &self.store.categories());
        let description = f.fields[4].clone();
        match &f.uid {
            None => { self.store.add(d, shop, a, category, description); }
            Some(uid) => { self.store.change(uid, |t| { t.date = d; t.shop = shop; t.amount = a; t.category = category; t.description = description; })?; }
        }
        self.store.save()?;
        self.status = "Saved (press s to sync)".into();
        Ok(())
    }

    fn sync(&mut self, term: &mut DefaultTerminal) -> std::io::Result<()> {
        self.status = "Syncing...".into();
        term.draw(|f| self.draw(f))?;
        self.status = match crate::config(self.config.clone()).and_then(|c: s3::Config| self.store.sync(&c)) {
            Ok((sent, received)) => format!("Synced: sent {sent}, received {received}"),
            Err(e) => format!("Sync failed: {e}"),
        };
        self.move_by(0);
        Ok(())
    }

    fn draw(&mut self, f: &mut Frame) {
        let [top, mid, bottom] = Layout::vertical([Constraint::Length(3), Constraint::Min(3), Constraint::Length(1)]).areas(f.area());
        let searching = matches!(self.mode, Mode::Search);
        let cursor = if searching { "▏" } else { "" };
        f.render_widget(Paragraph::new(Line::from(vec![" 🔍 ".into(), self.query.clone().white().bold(), cursor.cyan()]))
            .block(Block::bordered().border_type(BorderType::Rounded).title(" Search ").border_style(Style::new().fg(if searching { Color::Cyan } else { Color::DarkGray }))), top);

        let rows = self.rows();
        let total: f64 = rows.iter().fold(0.0, |a, t| a + t.amount);
        let synced = |uid: &str| self.store.entries.get(uid).map_or(false, |e| e.synced);
        let body: Vec<Row> = rows.iter().map(|t| Row::new([
            Cell::from(if synced(&t.uid) { "✓".green() } else { "○".dark_gray() }),
            Cell::from(t.date.clone().fg(DATE)),
            Cell::from(t.shop.clone().fg(SHOP)),
            Cell::from(Line::from(format!("{:.2} €", t.amount).fg(AMOUNT).bold()).right_aligned()),
            Cell::from(t.category.clone().fg(CATEGORY)),
            Cell::from(t.description.clone().fg(DESCRIPTION)),
        ])).collect();
        let widths = [Constraint::Length(1), Constraint::Length(16), Constraint::Length(26), Constraint::Length(11), Constraint::Length(18), Constraint::Min(10)];
        let header = Row::new(["", "Date", "Shop", "Amount", "Category", "Description"]).style(Style::new().bold().underlined().fg(Color::Gray));
        let title = Line::from(vec![format!(" {} tickets ", rows.len()).white(), "· total ".dark_gray(), format!("{total:.2} € ").fg(AMOUNT).bold()]);
        let table = Table::new(body, widths).header(header).column_spacing(2)
            .row_highlight_style(Style::new().bg(Color::Rgb(45, 50, 70)).add_modifier(Modifier::BOLD)).highlight_symbol("▶ ")
            .block(Block::bordered().border_type(BorderType::Rounded).title(" Budget ").title_bottom(title).border_style(Style::new().fg(Color::DarkGray)));
        f.render_stateful_widget(table, mid, &mut self.table);

        let help: Vec<Span> = if !self.status.is_empty() { vec![format!(" {} ", self.status).yellow().bold()] } else {
            match self.mode {
                Mode::Form(_) => [key("Tab", "next"), key("→", "complete"), key("Enter", "save"), key("Esc", "cancel")].concat(),
                Mode::Search => [key("Enter", "done"), key("Esc", "done")].concat(),
                _ => [key("/", "search"), key("a", "add"), key("e", "edit"), key("d", "delete"), key("s", "sync"), key("c", "categories"), key("q", "quit")].concat(),
            }
        };
        f.render_widget(Line::from(help), bottom);

        match &self.mode {
            Mode::Form(form) => self.draw_form(f, form),
            Mode::Delete(uid) => {
                let t = self.store.entries.get(uid).map(|e| &e.ticket);
                let what = t.map(|t| format!("{} {:.2} € {}", t.date, t.amount, t.shop)).unwrap_or_default();
                let area = centered(f.area(), 54, 5);
                f.render_widget(Clear, area);
                f.render_widget(Paragraph::new(vec![Line::from(what).bold().centered(), Line::from(""), Line::from(vec!["y".red().bold(), " delete    ".into(), "any other key".dark_gray(), " cancel".dark_gray()]).centered()])
                    .block(Block::bordered().border_type(BorderType::Rounded).border_style(Style::new().red()).title(" Delete this ticket? ")), area);
            }
            Mode::Summary => self.draw_summary(f, &rows),
            _ => {}
        }
    }

    fn draw_form(&self, f: &mut Frame, form: &Form) {
        let area = centered(f.area(), 64, 12);
        f.render_widget(Clear, area);
        let mut lines: Vec<Line> = Vec::new();
        for (i, label) in LABELS.iter().enumerate() {
            let color = [DATE, SHOP, AMOUNT, CATEGORY, DESCRIPTION][i];
            let focused = i == form.focus;
            let mut spans = vec![Span::styled(format!("{label:<12}"), Style::new().fg(color).bold()), Span::styled(form.fields[i].clone(), Style::new().white())];
            if focused {
                spans.push("▏".cyan());
                if i == 3 { if let Some(s) = suggestion(self.store, &form.fields[3]) { spans.push(format!("  → {}", s.chars().skip(form.fields[3].chars().count()).collect::<String>()).dark_gray()); } }
            }
            lines.push(Line::from(spans));
            lines.push(Line::from(""));
        }
        lines.push(Line::from(form.error.clone().red().bold()));
        let title = if form.uid.is_some() { " Edit ticket " } else { " New ticket " };
        f.render_widget(Paragraph::new(lines).block(Block::bordered().border_type(BorderType::Rounded).border_style(Style::new().cyan()).title(title).padding(ratatui::widgets::Padding::horizontal(1))), area);
    }

    fn draw_summary(&self, f: &mut Frame, rows: &[Ticket]) {
        let cats = self.store.categories();
        let mut per: std::collections::BTreeMap<String, f64> = Default::default();
        for t in rows {
            let name = cats.iter().find(|c| shops::key(c) == shops::key(&t.category)).cloned().unwrap_or_else(|| "(none)".into());
            *per.entry(name).or_default() += t.amount;
        }
        let mut v: Vec<_> = per.into_iter().collect();
        v.sort_by(|a, b| b.1.total_cmp(&a.1));
        let max = v.first().map_or(1.0, |x| x.1).max(0.01);
        let total: f64 = v.iter().fold(0.0, |a, x| a + x.1);
        let mut lines: Vec<Line> = v.iter().map(|(c, s)| {
            let bar = "█".repeat(((s / max) * 24.0).round() as usize);
            Line::from(vec![Span::styled(format!("{c:<18}"), Style::new().fg(CATEGORY)), Span::styled(format!("{s:>9.2} € "), Style::new().fg(AMOUNT).bold()), bar.cyan()])
        }).collect();
        lines.push(Line::from(""));
        lines.push(Line::from(vec![format!("{:<18}", "TOTAL").bold(), format!("{total:>9.2} €").fg(AMOUNT).bold()]));
        let area = centered(f.area(), 64, lines.len() as u16 + 2);
        f.render_widget(Clear, area);
        f.render_widget(Paragraph::new(lines).block(Block::bordered().border_type(BorderType::Rounded).border_style(Style::new().cyan()).title(" By category (current search) ").title_bottom(" any key to close ")), area);
    }
}

/// The category that completes what was typed (first known one starting with it, ignoring case and accents).
fn suggestion(store: &Store, typed: &str) -> Option<String> {
    let k = shops::key(typed);
    if k.is_empty() { return None; }
    store.categories().into_iter().find(|c| shops::key(c).starts_with(&k) && c != typed)
}
