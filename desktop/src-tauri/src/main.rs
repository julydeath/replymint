// Prevents an extra console window on Windows in release (harmless on macOS, kept for D3).
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    replymint_desktop_lib::run()
}
