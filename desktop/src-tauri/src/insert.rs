use std::process::Command;
use std::thread::sleep;
use std::time::Duration;

/// Inserts text into whatever field has focus, Wispr-style: set the clipboard,
/// synthesize ⌘V, restore the old clipboard. The AX-API insertion (and reading
/// the focused window for context) is D2.
///
/// macOS-only for D1 — the ⌘V comes from `osascript`/System Events, which asks
/// the user for Automation + Accessibility permission on first use. D3 swaps
/// this for a cross-platform synthesizer (enigo / SendInput).
pub fn paste_text(text: &str) -> Result<(), String> {
    let mut clipboard = arboard::Clipboard::new().map_err(|e| format!("clipboard: {e}"))?;
    let previous = clipboard.get_text().ok();

    clipboard
        .set_text(text.to_string())
        .map_err(|e| format!("clipboard: {e}"))?;
    sleep(Duration::from_millis(150)); // let the clipboard settle before ⌘V

    let status = Command::new("osascript")
        .args([
            "-e",
            r#"tell application "System Events" to keystroke "v" using command down"#,
        ])
        .status()
        .map_err(|e| format!("osascript: {e}"))?;
    if !status.success() {
        return Err(
            "paste failed — grant ReplyMint Accessibility permission in \
             System Settings → Privacy & Security → Accessibility"
                .into(),
        );
    }

    // Give the frontmost app time to consume the paste before restoring.
    sleep(Duration::from_millis(400));
    if let Some(previous) = previous {
        let _ = clipboard.set_text(previous);
    }
    Ok(())
}
