//! Windows UI Automation integration — the D3 twin of ax.rs.
//!
//! Same three jobs:
//!  1. `read_context` — read the focused window's visible text at recording start.
//!  2. `insert_direct` — put text into the focused field without keystrokes,
//!     where UIA allows it (ValuePattern on an empty field). UIA has no
//!     insert-at-caret equivalent of AXSelectedText, so a non-empty field
//!     defers to the clipboard-paste fallback in insert.rs rather than
//!     clobbering existing content with SetValue.
//!  3. `ensure_trusted` — a no-op: UIA needs no permission grant on Windows.
//!
//! Shared caps/types/keyword rules live in screen.rs.

#![cfg(windows)]

use std::collections::HashSet;

use uiautomation::patterns::UIValuePattern;
use uiautomation::{UIAutomation, UIElement, UITreeWalker};

use crate::screen::{ScreenContext, MAX_LINES, NODE_BUDGET};

/// Windows needs no accessibility grant for UIA — always trusted.
pub fn ensure_trusted(_prompt: bool) -> bool {
    true
}

/// Snapshot the focused app/window at dictation start. None when nothing is
/// focused. Same shape the macOS ax.rs and Android ScreenReader produce.
pub fn read_context() -> Option<ScreenContext> {
    let automation = UIAutomation::new().ok()?;
    let focused = automation.get_focused_element().ok()?;
    let focused_text = element_value(&focused);
    let walker = automation.get_control_view_walker().ok()?;
    let root = automation.get_root_element().ok()?;

    // Walk up from the focused element to the top-level window (the child of
    // the desktop root) — the UIA equivalent of AXFocusedWindow.
    let mut window = focused;
    loop {
        match walker.get_parent(&window) {
            Ok(parent) => {
                if automation.compare_elements(&parent, &root).unwrap_or(false) {
                    break;
                }
                window = parent;
            }
            Err(_) => break,
        }
    }

    let window_title = window.get_name().unwrap_or_default().trim().to_string();
    let app_name = window
        .get_process_id()
        .ok()
        .and_then(|pid| process_name(pid as u32))
        .or_else(|| window.get_classname().ok())
        .unwrap_or_default();

    let mut lines = Vec::new();
    collect_lines(&walker, &window, &mut lines);
    if lines.len() > MAX_LINES {
        // Bottom of the window ≈ most recent content (chat apps, notes) — keep the tail.
        lines.drain(..lines.len() - MAX_LINES);
    }

    Some(ScreenContext { app_name, window_title, lines, focused_text })
}

/// Bounded DFS in document order collecting each element's name and value.
fn collect_lines(walker: &UITreeWalker, root: &UIElement, lines: &mut Vec<String>) {
    let mut seen = HashSet::new();
    let mut budget = NODE_BUDGET;
    // Stack of unvisited elements; children pushed reversed to keep document order.
    let mut stack = vec![root.clone()];
    while let Some(el) = stack.pop() {
        if budget == 0 {
            return;
        }
        budget -= 1;
        if let Ok(name) = el.get_name() {
            push_lines(&name, &mut seen, lines);
        }
        if let Some(value) = element_value(&el) {
            push_lines(&value, &mut seen, lines);
        }
        let mut children = Vec::new();
        if let Ok(first) = walker.get_first_child(&el) {
            children.push(first);
            while let Ok(next) = walker.get_next_sibling(children.last().unwrap()) {
                children.push(next);
            }
        }
        children.reverse();
        stack.extend(children);
    }
}

fn push_lines(text: &str, seen: &mut HashSet<String>, lines: &mut Vec<String>) {
    for line in text.lines().map(str::trim).filter(|l| !l.is_empty()) {
        if seen.insert(line.to_string()) {
            lines.push(line.to_string());
        }
    }
}

fn element_value(el: &UIElement) -> Option<String> {
    let value = el.get_pattern::<UIValuePattern>().ok()?.get_value().ok()?;
    let value = value.trim().to_string();
    if value.is_empty() { None } else { Some(value) }
}

/// Set the focused field via ValuePattern — but only when the field is empty:
/// SetValue replaces the whole content and drops the caret, so a field with
/// text defers to the paste fallback (which inserts at the cursor).
pub fn insert_direct(text: &str) -> Result<(), String> {
    let automation = UIAutomation::new().map_err(|e| format!("uia: {e}"))?;
    let focused = automation
        .get_focused_element()
        .map_err(|_| "no focused element".to_string())?;
    let pattern = focused
        .get_pattern::<UIValuePattern>()
        .map_err(|_| "focused element does not accept UIA text insertion".to_string())?;
    if pattern.is_readonly().unwrap_or(true) {
        return Err("focused element is read-only".into());
    }
    if !pattern.get_value().unwrap_or_default().is_empty() {
        return Err("field has existing text — inserting via paste".into());
    }
    pattern.set_value(text).map_err(|e| format!("UIA set value: {e}"))
}

/// Synthesize Ctrl+V into the focused element (the paste half of insert.rs).
pub fn send_paste() -> Result<(), String> {
    let automation = UIAutomation::new().map_err(|e| format!("uia: {e}"))?;
    let focused = automation
        .get_focused_element()
        .map_err(|_| "no focused element".to_string())?;
    focused
        .send_keys("{ctrl}v", 10)
        .map_err(|e| format!("synthesize ctrl+v: {e}"))
}

/// Executable name (sans .exe) for the process owning the focused window.
fn process_name(pid: u32) -> Option<String> {
    use windows::core::PWSTR;
    use windows::Win32::Foundation::CloseHandle;
    use windows::Win32::System::Threading::{
        OpenProcess, QueryFullProcessImageNameW, PROCESS_NAME_WIN32,
        PROCESS_QUERY_LIMITED_INFORMATION,
    };
    unsafe {
        let handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid).ok()?;
        let mut buf = vec![0u16; 1024];
        let mut len = buf.len() as u32;
        let result = QueryFullProcessImageNameW(handle, PROCESS_NAME_WIN32, PWSTR(buf.as_mut_ptr()), &mut len);
        let _ = CloseHandle(handle);
        result.ok()?;
        let full = String::from_utf16_lossy(&buf[..len as usize]);
        let file = full.rsplit('\\').next().unwrap_or(&full);
        let name = file
            .strip_suffix(".exe")
            .or_else(|| file.strip_suffix(".EXE"))
            .unwrap_or(file)
            .trim()
            .to_string();
        if name.is_empty() { None } else { Some(name) }
    }
}
