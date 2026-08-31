//! macOS Accessibility (AX) integration — the heart of D2.
//!
//! Three jobs, mirroring what the Android accessibility service does:
//!  1. `read_context` — read the focused window's visible text at recording start
//!     (screen context for keyword boosting and instruction mode).
//!  2. `insert_via_ax` — insert text at the cursor of the focused field directly,
//!     with no clipboard hijack and no synthesized keystrokes.
//!  3. `ensure_trusted` — the Accessibility permission dance (AX has no Info.plist
//!     key; trust is granted in System Settings → Privacy & Security → Accessibility).
//!
//! Shared caps/types/keyword rules live in screen.rs (uia.rs is the Windows twin).

#![cfg(target_os = "macos")]

use std::collections::HashSet;
use std::sync::atomic::{AtomicBool, Ordering};

use crate::screen::{ScreenContext, MAX_LINES, NODE_BUDGET};

use accessibility_sys::{
    kAXChildrenAttribute, kAXErrorSuccess, kAXFocusedApplicationAttribute,
    kAXFocusedUIElementAttribute, kAXFocusedWindowAttribute, kAXRoleAttribute,
    kAXSelectedTextAttribute, kAXTitleAttribute, kAXTrustedCheckOptionPrompt,
    kAXValueAttribute, AXIsProcessTrustedWithOptions, AXUIElementCopyAttributeValue,
    AXUIElementCreateSystemWide, AXUIElementGetTypeID, AXUIElementIsAttributeSettable,
    AXUIElementRef, AXUIElementSetAttributeValue,
};
use core_foundation::array::CFArray;
use core_foundation::base::{CFGetTypeID, CFType, CFTypeRef, TCFType};
use core_foundation::boolean::CFBoolean;
use core_foundation::dictionary::CFDictionary;
use core_foundation::string::CFString;

/// An owned AXUIElement (CF memory management via CFType).
struct Elem(CFType);

impl Elem {
    fn system_wide() -> Elem {
        // Create rule: we own the +1 reference.
        Elem(unsafe { CFType::wrap_under_create_rule(AXUIElementCreateSystemWide() as CFTypeRef) })
    }

    fn as_ax(&self) -> AXUIElementRef {
        self.0.as_CFTypeRef() as AXUIElementRef
    }

    /// Copy an attribute value (owned). None on any AX error.
    fn attr(&self, name: &str) -> Option<CFType> {
        let cf_name = CFString::new(name);
        let mut out: CFTypeRef = std::ptr::null();
        let err = unsafe {
            AXUIElementCopyAttributeValue(self.as_ax(), cf_name.as_concrete_TypeRef(), &mut out)
        };
        if err == kAXErrorSuccess && !out.is_null() {
            Some(unsafe { CFType::wrap_under_create_rule(out) })
        } else {
            None
        }
    }

    fn attr_string(&self, name: &str) -> Option<String> {
        let s = self.attr(name)?.downcast::<CFString>()?.to_string();
        let s = s.trim().to_string();
        if s.is_empty() { None } else { Some(s) }
    }

    fn attr_elem(&self, name: &str) -> Option<Elem> {
        let v = self.attr(name)?;
        if unsafe { CFGetTypeID(v.as_CFTypeRef()) } == unsafe { AXUIElementGetTypeID() } {
            Some(Elem(v))
        } else {
            None
        }
    }

    fn children(&self) -> Vec<Elem> {
        let Some(v) = self.attr(kAXChildrenAttribute) else { return Vec::new() };
        let Some(arr) = v.downcast::<CFArray<*const std::os::raw::c_void>>() else {
            return Vec::new();
        };
        arr.iter()
            .filter_map(|item| {
                let ptr = *item as CFTypeRef;
                if !ptr.is_null() && unsafe { CFGetTypeID(ptr) == AXUIElementGetTypeID() } {
                    // Get rule: the array owns the reference; wrapping retains.
                    Some(Elem(unsafe { CFType::wrap_under_get_rule(ptr) }))
                } else {
                    None
                }
            })
            .collect()
    }

    fn settable(&self, name: &str) -> bool {
        let cf_name = CFString::new(name);
        let mut out = false;
        let err = unsafe {
            AXUIElementIsAttributeSettable(self.as_ax(), cf_name.as_concrete_TypeRef(), &mut out)
        };
        err == kAXErrorSuccess && out
    }

    fn set_string(&self, name: &str, value: &str) -> Result<(), String> {
        let cf_name = CFString::new(name);
        let cf_value = CFString::new(value);
        let err = unsafe {
            AXUIElementSetAttributeValue(
                self.as_ax(),
                cf_name.as_concrete_TypeRef(),
                cf_value.as_CFTypeRef(),
            )
        };
        if err == kAXErrorSuccess {
            Ok(())
        } else {
            Err(format!("AX set {name} failed (error {err})"))
        }
    }
}

/// Is this process trusted for Accessibility? With `prompt`, macOS shows the
/// grant dialog — but only once per app run (repeated prompts are just nagging;
/// the Settings window has a button to ask again deliberately).
pub fn ensure_trusted(prompt: bool) -> bool {
    static PROMPTED: AtomicBool = AtomicBool::new(false);
    let ask = prompt && !PROMPTED.swap(true, Ordering::Relaxed);
    let options = CFDictionary::from_CFType_pairs(&[(
        unsafe { CFString::wrap_under_get_rule(kAXTrustedCheckOptionPrompt) },
        CFBoolean::from(ask),
    )]);
    unsafe { AXIsProcessTrustedWithOptions(options.as_concrete_TypeRef()) }
}

fn focused_element() -> Option<Elem> {
    Elem::system_wide().attr_elem(kAXFocusedUIElementAttribute)
}

/// Snapshot the focused app/window at dictation start. None when untrusted or
/// nothing is focused. Same shape the Android ScreenReader feeds /v1/reply.
pub fn read_context() -> Option<ScreenContext> {
    if !ensure_trusted(false) {
        return None;
    }
    let system = Elem::system_wide();
    let app = system.attr_elem(kAXFocusedApplicationAttribute)?;
    let app_name = app.attr_string(kAXTitleAttribute).unwrap_or_default();
    let window = app.attr_elem(kAXFocusedWindowAttribute);
    let window_title = window
        .as_ref()
        .and_then(|w| w.attr_string(kAXTitleAttribute))
        .unwrap_or_default();

    let focused = system.attr_elem(kAXFocusedUIElementAttribute);
    let focused_text = focused.as_ref().and_then(|f| f.attr_string(kAXValueAttribute));

    let mut lines = Vec::new();
    if let Some(window) = &window {
        collect_lines(window, &mut lines);
    }
    if lines.len() > MAX_LINES {
        // Bottom of the window ≈ most recent content (chat apps, notes) — keep the tail.
        lines.drain(..lines.len() - MAX_LINES);
    }

    Some(ScreenContext { app_name, window_title, lines, focused_text })
}

/// Bounded DFS in document order collecting each element's value (or title).
fn collect_lines(root: &Elem, lines: &mut Vec<String>) {
    let mut seen = HashSet::new();
    let mut budget = NODE_BUDGET;
    // Stack of unvisited elements; children pushed reversed to keep document order.
    let mut stack = vec![Elem(root.0.clone())];
    while let Some(el) = stack.pop() {
        if budget == 0 {
            return;
        }
        budget -= 1;
        let text = el
            .attr_string(kAXValueAttribute)
            .or_else(|| el.attr_string(kAXTitleAttribute));
        if let Some(text) = text {
            for line in text.lines().map(str::trim).filter(|l| !l.is_empty()) {
                if seen.insert(line.to_string()) {
                    lines.push(line.to_string());
                }
            }
        }
        let mut children = el.children();
        children.reverse();
        stack.extend(children);
    }
}

/// Insert at the cursor of the focused field by setting AXSelectedText —
/// replaces the selection (or inserts at the caret when nothing is selected).
/// Never writes AXValue: that would clobber the field's whole content.
pub fn insert_direct(text: &str) -> Result<(), String> {
    if !ensure_trusted(false) {
        return Err("Accessibility permission not granted".into());
    }
    let el = focused_element().ok_or("no focused element")?;
    if !el.settable(kAXSelectedTextAttribute) {
        let role = el.attr_string(kAXRoleAttribute).unwrap_or_default();
        return Err(format!("focused element ({role}) does not accept AX text insertion"));
    }
    el.set_string(kAXSelectedTextAttribute, text)
}
