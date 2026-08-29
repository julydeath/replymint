use std::process::Command;
use std::thread::sleep;
use std::time::Duration;

/// How the text landed in the focused field. The enum is the D3 seam — Windows
/// adds a SendInput variant behind the same `insert_text` call.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Insertion {
    /// Direct AX insertion at the cursor — no clipboard touched.
    Ax,
    /// Clipboard + synthesized ⌘V fallback (apps with no settable AX text:
    /// many Electron apps, terminals, Java UIs).
    Paste,
}

/// Insert into whatever field has focus: AX first, paste fallback.
pub fn insert_text(text: &str) -> Result<Insertion, String> {
    match crate::ax::insert_via_ax(text) {
        Ok(()) => Ok(Insertion::Ax),
        Err(ax_err) => {
            eprintln!("AX insertion unavailable ({ax_err}); falling back to paste");
            paste_text(text).map(|()| Insertion::Paste)
        }
    }
}

/// The D1 path, now the fallback: set the clipboard, synthesize ⌘V, restore the
/// old clipboard (text-only, best-effort).
///
/// macOS-only — the ⌘V comes from `osascript`/System Events, which asks the
/// user for Automation + Accessibility permission on first use. D3 swaps this
/// for a cross-platform synthesizer (enigo / SendInput).
fn paste_text(text: &str) -> Result<(), String> {
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
