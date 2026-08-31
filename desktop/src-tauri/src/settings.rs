use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

/// The token is an rt_ token minted at sign-in (auth.rs); a pasted dev token
/// (backend `npm run` scripts, see desktop/README.md) still works via the
/// Advanced section. The file lives in Application Support, not the repo.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    pub backend_url: String,
    pub token: String,
    /// Signed-in Google account, cached for instant/offline render in Settings.
    /// Backfilled from /v1/me for legacy pasted tokens.
    pub email: String,
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
            // Release builds must reach production out of the box — the Advanced URL
            // field is hidden in the UI, so the default is the only path for new installs.
            backend_url: if cfg!(debug_assertions) {
                "http://localhost:8787"
            } else {
                "https://replymint-um33.onrender.com"
            }
            .into(),
            token: String::new(),
            email: String::new(),
            // Alt+Space is the window-menu key on Windows — don't shadow it there.
            hotkey: if cfg!(windows) { "ctrl+alt+space" } else { "alt+space" }.into(),
            mode: "dictation".into(),
            clean_dictation: true,
        }
    }
}

#[cfg(target_os = "macos")]
fn path() -> Result<PathBuf, String> {
    let home = std::env::var("HOME").map_err(|_| "HOME is not set".to_string())?;
    Ok(PathBuf::from(home)
        .join("Library/Application Support/com.replymint.desktop/settings.json"))
}

#[cfg(windows)]
fn path() -> Result<PathBuf, String> {
    let appdata = std::env::var("APPDATA").map_err(|_| "APPDATA is not set".to_string())?;
    Ok(PathBuf::from(appdata).join("com.replymint.desktop/settings.json"))
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
