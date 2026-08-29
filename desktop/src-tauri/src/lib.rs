//! ReplyMint desktop (VOICE_PLAN D1+D2): a menu-bar app whose whole product is
//! one loop — global hotkey → read the focused window (AX) → record mic →
//! stream to the backend STT proxy (screen keywords boost accuracy) → insert
//! into the focused field via AX (clipboard-paste fallback). In Assistant mode
//! the transcript is an instruction: /v1/reply writes the draft that lands.

mod ax;
mod audio;
mod insert;
mod reply;
mod settings;
mod stt;

use serde_json::json;
use std::str::FromStr;
use std::sync::Mutex;
use tauri::menu::{MenuBuilder, MenuItemBuilder};
use tauri::tray::TrayIconBuilder;
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_global_shortcut::{GlobalShortcutExt, Shortcut, ShortcutState};

use settings::Settings;
use stt::SttEvent;

const TRAY_ID: &str = "main";

/// Some(recorder) while the mic is live; None otherwise. The STT task clears it
/// on any session end, so a server-side close also stops the mic.
struct AppState {
    session: Mutex<Option<audio::Recorder>>,
    /// Focused-window snapshot taken at recording start (D2 context).
    context: Mutex<Option<ax::AxContext>>,
}

pub fn run() {
    // `replymint-desktop smoke` — headless proxy check, no GUI, no mic.
    if std::env::args().nth(1).as_deref() == Some("smoke") {
        smoke();
        return;
    }

    tauri::Builder::default()
        .manage(AppState { session: Mutex::new(None), context: Mutex::new(None) })
        .plugin(
            tauri_plugin_global_shortcut::Builder::new()
                .with_handler(|app, _shortcut, event| {
                    if event.state() == ShortcutState::Pressed {
                        toggle_dictation(app);
                    }
                })
                .build(),
        )
        .invoke_handler(tauri::generate_handler![
            get_settings,
            save_settings,
            test_backend,
            toggle,
            request_ax
        ])
        .on_window_event(|window, event| {
            // Closing the settings window hides it; the app lives in the tray.
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .setup(|app| {
            #[cfg(target_os = "macos")]
            app.set_activation_policy(tauri::ActivationPolicy::Accessory);

            let toggle_item =
                MenuItemBuilder::with_id("toggle", "Start/Stop Dictation").build(app)?;
            let settings_item = MenuItemBuilder::with_id("settings", "Settings…").build(app)?;
            let quit_item = MenuItemBuilder::with_id("quit", "Quit ReplyMint").build(app)?;
            let menu = MenuBuilder::new(app)
                .item(&toggle_item)
                .item(&settings_item)
                .separator()
                .item(&quit_item)
                .build()?;

            TrayIconBuilder::with_id(TRAY_ID)
                .icon(app.default_window_icon().expect("bundle icon").clone())
                .menu(&menu)
                .on_menu_event(|app, event| match event.id().as_ref() {
                    "toggle" => toggle_dictation(app),
                    "settings" => show_settings(app),
                    "quit" => app.exit(0),
                    _ => {}
                })
                .build(app)?;

            let hotkey = settings::load().hotkey;
            if let Err(e) = register_hotkey(app.handle(), &hotkey) {
                eprintln!("hotkey '{hotkey}' not registered: {e}");
            }
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running ReplyMint");
}

fn register_hotkey(app: &AppHandle, hotkey: &str) -> Result<(), String> {
    let shortcut = Shortcut::from_str(hotkey).map_err(|e| e.to_string())?;
    app.global_shortcut()
        .unregister_all()
        .map_err(|e| e.to_string())?;
    app.global_shortcut()
        .register(shortcut)
        .map_err(|e| e.to_string())
}

fn show_settings(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("settings") {
        let _ = w.show();
        let _ = w.set_focus();
    }
}

fn set_tray_title(app: &AppHandle, title: Option<&str>) {
    if let Some(tray) = app.tray_by_id(TRAY_ID) {
        let _ = tray.set_title(title);
    }
}

fn emit_ui(app: &AppHandle, kind: &str, text: &str) {
    let _ = app.emit("dictation", json!({ "kind": kind, "text": text }));
}

/// The hotkey. Idle → start recording; recording → stop, transcribe, paste.
fn toggle_dictation(app: &AppHandle) {
    let state = app.state::<AppState>();

    // Stop path: end capture; the closed audio channel makes the session finish.
    if let Some(recorder) = state.session.lock().unwrap().take() {
        recorder.stop();
        set_tray_title(app, Some("…"));
        emit_ui(app, "state", "transcribing");
        return;
    }

    let cfg = settings::load();
    if cfg.token.is_empty() {
        show_settings(app);
        emit_ui(app, "error", "no token set — paste one in Settings");
        return;
    }

    // D2: snapshot the focused window BEFORE the mic starts — the target app is
    // frontmost right now (we're an Accessory app and never take focus). The
    // first hotkey press also triggers the one-time Accessibility grant prompt.
    if !ax::ensure_trusted(true) {
        emit_ui(
            app,
            "error",
            "For direct insertion and screen context, grant ReplyMint Accessibility \
             in System Settings → Privacy & Security → Accessibility (dictation still works)",
        );
    }
    let context = ax::read_context();
    let keywords = context.as_ref().map(ax::extract_keywords).unwrap_or_default();
    *state.context.lock().unwrap() = context;

    let (tx, rx) = tokio::sync::mpsc::channel::<Vec<u8>>(64);
    let recorder = match audio::start(tx) {
        Ok(r) => r,
        Err(e) => {
            emit_ui(app, "error", &e);
            return;
        }
    };
    *state.session.lock().unwrap() = Some(recorder);
    set_tray_title(app, Some("●"));
    emit_ui(app, "state", "recording");

    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let mut done_text: Option<String> = None;
        let result = stt::run_session(&cfg.backend_url, &cfg.token, &keywords, rx, |ev| match ev {
            SttEvent::Partial(t) => emit_ui(&app, "partial", &t),
            SttEvent::Final(t) => emit_ui(&app, "final", &t),
            SttEvent::Done(text) => done_text = Some(text),
            SttEvent::Error | SttEvent::Ready => {}
        })
        .await;

        // Server-initiated end (error, audio cap): the mic may still be live.
        if let Some(rec) = app.state::<AppState>().session.lock().unwrap().take() {
            rec.stop();
        }

        match result {
            Ok(()) => {
                let transcript = done_text.unwrap_or_default();
                if transcript.is_empty() {
                    emit_ui(&app, "done", "");
                } else {
                    emit_ui(&app, "done", &transcript);
                    let context = app.state::<AppState>().context.lock().unwrap().take();

                    // Assistant mode: the transcript is an instruction — insert the
                    // backend's draft. On any failure fall back to the transcript
                    // itself: the user's words are never lost.
                    let mut is_draft = false;
                    let text = if cfg.mode == "assistant" {
                        match &context {
                            Some(ctx) => {
                                emit_ui(&app, "state", "writing");
                                match reply::generate_draft(
                                    &cfg.backend_url,
                                    &cfg.token,
                                    ctx,
                                    &transcript,
                                )
                                .await
                                {
                                    Ok(draft) => {
                                        is_draft = true;
                                        draft
                                    }
                                    Err(e) => {
                                        emit_ui(&app, "error", &format!("draft failed ({e}) — inserted your words instead"));
                                        transcript
                                    }
                                }
                            }
                            None => {
                                emit_ui(&app, "error", "no screen context (Accessibility not granted?) — inserted your words instead");
                                transcript
                            }
                        }
                    } else {
                        transcript
                    };

                    let inserted =
                        tauri::async_runtime::spawn_blocking(move || insert::insert_text(&text))
                            .await
                            .unwrap_or_else(|e| Err(e.to_string()));
                    match inserted {
                        Ok(how) => {
                            let label = match (is_draft, how) {
                                (true, _) => "draft",
                                (false, insert::Insertion::Ax) => "ax",
                                (false, insert::Insertion::Paste) => "paste",
                            };
                            emit_ui(&app, "inserted", label);
                        }
                        Err(e) => emit_ui(&app, "error", &e),
                    }
                }
                set_tray_title(&app, None);
                emit_ui(&app, "state", "idle");
            }
            Err(e) => {
                emit_ui(&app, "error", &e);
                set_tray_title(&app, Some("!"));
                tokio::time::sleep(std::time::Duration::from_secs(2)).await;
                set_tray_title(&app, None);
                emit_ui(&app, "state", "idle");
            }
        }
    });
}

#[tauri::command]
fn get_settings() -> Settings {
    settings::load()
}

#[tauri::command]
fn save_settings(app: AppHandle, new: Settings) -> Result<(), String> {
    let old = settings::load();
    settings::save(&new)?;
    if new.hotkey != old.hotkey {
        register_hotkey(&app, &new.hotkey)
            .map_err(|e| format!("saved, but hotkey failed to register: {e}"))?;
    }
    Ok(())
}

/// Settings → Test: 2s of silence through the proxy, returns the transcript
/// (with STT_PROVIDER=mock on the backend this proves the whole path sans mic).
#[tauri::command]
async fn test_backend() -> Result<String, String> {
    let cfg = settings::load();
    stt::silence_session(&cfg.backend_url, &cfg.token, 2).await
}

#[tauri::command]
fn toggle(app: AppHandle) {
    toggle_dictation(&app);
}

/// Settings → "Grant Accessibility": returns whether we're trusted. If not,
/// prompts and opens the System Settings pane (macOS shows its own dialog at
/// most once, so the deep link is the reliable path afterwards).
#[tauri::command]
fn request_ax() -> bool {
    let trusted = ax::ensure_trusted(true);
    if !trusted {
        let _ = std::process::Command::new("open")
            .arg("x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")
            .spawn();
    }
    trusted
}

fn smoke() {
    let backend = std::env::var("REPLYMINT_BACKEND_URL")
        .unwrap_or_else(|_| "http://localhost:8787".into());
    let token = std::env::var("REPLYMINT_TOKEN").unwrap_or_else(|_| settings::load().token);
    if token.is_empty() {
        eprintln!("no token: set REPLYMINT_TOKEN or save one in Settings first");
        std::process::exit(2);
    }
    let rt = tokio::runtime::Runtime::new().expect("tokio runtime");
    match rt.block_on(stt::silence_session(&backend, &token, 3)) {
        Ok(text) => println!("smoke ok — transcript: {text:?}"),
        Err(e) => {
            eprintln!("smoke failed: {e}");
            std::process::exit(1);
        }
    }
}
