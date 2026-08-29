use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

/// Until desktop sign-in lands (Backlog A3), the token is an rt_ token pasted
/// into Settings — mint one for dev with `npm run` scripts in backend/ (see
/// desktop/README.md). The file lives in Application Support, not the repo.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    pub backend_url: String,
    pub token: String,
    pub hotkey: String,
    /// "dictation" inserts the transcript verbatim; "assistant" treats speech as
    /// an instruction — the focused window's AX text + transcript go to /v1/reply
    /// and the generated draft is inserted instead (D2 instruction mode).
    pub mode: String,
    /// Dictation mode: run the transcript through the backend's `dictate` action
    /// (grammar, stutters, self-corrections) before inserting. On any failure the
    /// raw transcript is inserted instead — the user's words are never lost.
    pub clean_dictation: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            backend_url: "http://localhost:8787".into(),
            token: String::new(),
            hotkey: "alt+space".into(),
            mode: "dictation".into(),
            clean_dictation: true,
        }
    }
}

fn path() -> Result<PathBuf, String> {
    let home = std::env::var("HOME").map_err(|_| "HOME is not set".to_string())?;
    // macOS-only path is fine for D1; D3 moves this to a per-OS config dir.
    Ok(PathBuf::from(home)
        .join("Library/Application Support/com.replymint.desktop/settings.json"))
}

pub fn load() -> Settings {
    let Ok(path) = path() else { return Settings::default() };
    fs::read_to_string(path)
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

pub fn save(settings: &Settings) -> Result<(), String> {
    let path = path()?;
    if let Some(dir) = path.parent() {
        fs::create_dir_all(dir).map_err(|e| format!("create config dir: {e}"))?;
    }
    let json = serde_json::to_string_pretty(settings).map_err(|e| e.to_string())?;
    fs::write(path, json).map_err(|e| format!("write settings: {e}"))
}
