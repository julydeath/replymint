use std::thread::sleep;
use std::time::Duration;

/// How the text landed in the focused field.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Insertion {
    /// Direct accessibility insertion — macOS AXSelectedText at the cursor, or
    /// Windows UIA ValuePattern on an empty field. No clipboard touched.
    Ax,
    /// Clipboard + synthesized paste fallback (apps with no settable AX text /
    /// no ValuePattern: many Electron apps, terminals, Java UIs).
    Paste,
}

/// Insert into whatever field has focus: direct first, paste fallback.
pub fn insert_text(text: &str) -> Result<Insertion, String> {
    match crate::platform::insert_direct(text) {
        Ok(()) => Ok(Insertion::Ax),
        Err(direct_err) => {
            eprintln!("direct insertion unavailable ({direct_err}); falling back to paste");
            paste_text(text).map(|()| Insertion::Paste)
        }
    }
}

/// The D1 path, now the fallback: set the clipboard, synthesize a paste
/// keystroke, restore the old clipboard (text-only, best-effort).
fn paste_text(text: &str) -> Result<(), String> {
    let mut clipboard = arboard::Clipboard::new().map_err(|e| format!("clipboard: {e}"))?;
    let previous = clipboard.get_text().ok();

    clipboard
        .set_text(text.to_string())
        .map_err(|e| format!("clipboard: {e}"))?;
    sleep(Duration::from_millis(150)); // let the clipboard settle before pasting

    synthesize_paste()?;

    // Give the frontmost app time to consume the paste before restoring.
    sleep(Duration::from_millis(400));
    if let Some(previous) = previous {
        let _ = clipboard.set_text(previous);
    }
    Ok(())
}

/// macOS: ⌘V via `osascript`/System Events, which asks the user for
/// Automation + Accessibility permission on first use.
#[cfg(target_os = "macos")]
fn synthesize_paste() -> Result<(), String> {
    let status = std::process::Command::new("osascript")
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
    Ok(())
}

/// Windows: Ctrl+V via UIA's key synthesizer (SendInput underneath) — no
/// permission needed.
#[cfg(windows)]
fn synthesize_paste() -> Result<(), String> {
    crate::platform::send_paste()
}
