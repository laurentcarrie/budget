//! Webcam, microphone, OCR and speech recognition, by calling the usual command line tools.
use std::{io::Write, path::{Path, PathBuf}, process::Command};

fn run(cmd: &mut Command, hint: &str) -> Result<String, String> {
    let name = cmd.get_program().to_string_lossy().into_owned();
    let out = cmd.output().map_err(|e| if e.kind() == std::io::ErrorKind::NotFound { format!("{name} not found. {hint}") } else { format!("{name}: {e}") })?;
    if !out.status.success() { return Err(format!("{name} failed: {}", String::from_utf8_lossy(&out.stderr).lines().last().unwrap_or(""))); }
    Ok(String::from_utf8_lossy(&out.stdout).into_owned())
}

fn tmp(name: &str) -> PathBuf { std::env::temp_dir().join(format!("budget-{}-{name}", std::process::id())) }

/// Grabs one webcam frame (the last of a short burst, so the exposure has settled).
pub fn capture(device: &str) -> Result<PathBuf, String> {
    let out = tmp("shot.jpg");
    run(Command::new("ffmpeg").args(["-y", "-loglevel", "error", "-f", "v4l2", "-i", device, "-frames:v", "20", "-update", "1"]).arg(&out), "Check --camera.")?;
    Ok(out)
}

/// Text of a receipt photo, with Tesseract (French first, any installed language as a fallback).
pub fn ocr(image: &Path) -> Result<String, String> {
    let hint = "Install it: sudo apt install tesseract-ocr tesseract-ocr-fra";
    let first = run(Command::new("tesseract").arg(image).args(["stdout", "-l", "fra", "--psm", "6"]), hint);
    match first {
        Err(e) if e.contains("failed") => run(Command::new("tesseract").arg(image).args(["stdout", "--psm", "6"]), hint),
        r => r,
    }
}

/// Records `seconds` of 16 kHz mono audio from the default microphone.
pub fn record(seconds: u32) -> Result<PathBuf, String> {
    let out = tmp("voice.wav");
    run(Command::new("arecord").args(["-q", "-f", "S16_LE", "-r", "16000", "-c", "1", "-d", &seconds.to_string()]).arg(&out), "Install alsa-utils.")?;
    Ok(out)
}

/// French text of a wav file, with whisper.cpp (`$BUDGET_WHISPER` and `$BUDGET_WHISPER_MODEL` override the defaults).
pub fn transcribe(wav: &Path) -> Result<String, String> {
    let home = std::env::var("HOME").unwrap_or_default();
    let bin = std::env::var("BUDGET_WHISPER").unwrap_or_else(|_| "whisper-cli".into());
    let model = std::env::var("BUDGET_WHISPER_MODEL").unwrap_or_else(|_| format!("{home}/.local/share/whisper/ggml-small.bin"));
    if !Path::new(&model).exists() { return Err(format!("whisper model {model} not found (download ggml-small.bin from huggingface.co/ggerganov/whisper.cpp)")); }
    let text = run(Command::new(&bin).args(["-m", &model, "-l", "fr", "-nt", "-np", "-f"]).arg(wav), "Build whisper.cpp and put whisper-cli in PATH, or set BUDGET_WHISPER.")?;
    Ok(text.split_whitespace().collect::<Vec<_>>().join(" "))
}

/// Shows `label [default]: ` and returns what was typed (the default on an empty line).
pub fn ask(label: &str, default: &str) -> String {
    print!("{label} [{default}]: ");
    std::io::stdout().flush().ok();
    let mut line = String::new();
    std::io::stdin().read_line(&mut line).ok();
    let line = line.trim();
    if line.is_empty() { default.to_string() } else { line.to_string() }
}
