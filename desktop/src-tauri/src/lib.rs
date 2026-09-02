//! ReplyMint desktop (VOICE_PLAN D1+D2): a menu-bar app whose whole product is
//! one loop — global hotkey → read the focused window (AX) → record mic →
//! stream to the backend STT proxy (screen keywords boost accuracy) → insert
//! into the focused field via AX (clipboard-paste fallback). In Assistant mode
//! the transcript is an instruction: /v1/reply writes the draft that lands.

mod auth;
mod ax;
mod audio;
mod insert;
mod reply;
mod screen;
mod settings;
mod stt;
mod uia;

// The per-OS screen reader/inserter behind one name (ax.rs / uia.rs share an API).
#[cfg(target_os = "macos")]
pub(crate) use ax as platform;
#[cfg(windows)]
pub(crate) use uia as platform;

use serde_json::json;
use std::str::FromStr;
use std::sync::atomic::{AtomicBool, Ordering};
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
    context: Mutex<Option<screen::ScreenContext>>,
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
            request_ax,
            sign_in,
            sign_out,
            fetch_me
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

/// Show the transcript pill bottom-center on the monitor the user is working on
/// (the one with the cursor). The window is click-through and never focused, so
/// the target field keeps focus; the page fades the pill in/out via CSS on the
/// same "dictation" events the settings window listens to.
fn show_overlay(app: &AppHandle) {
    let Some(w) = app.get_webview_window("overlay") else { return };
    let _ = w.set_ignore_cursor_events(true);
    let monitor = app
        .cursor_position()
        .ok()
        .and_then(|p| app.monitor_from_point(p.x, p.y).ok().flatten())
        .or_else(|| app.primary_monitor().ok().flatten());
    if let (Some(m), Ok(size)) = (monitor, w.outer_size()) {
        let margin = (60.0 * m.scale_factor()) as i32;
        let x = m.position().x + (m.size().width.saturating_sub(size.width) / 2) as i32;
        let y = m.position().y + m.size().height as i32 - size.height as i32 - margin;
        let _ = w.set_position(tauri::PhysicalPosition::new(x, y));
    }
    let _ = w.show();
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
        emit_ui(app, "error", "not signed in — open Settings to sign in");
        return;
    }

    // D2: snapshot the focused window BEFORE the mic starts — the target app is
    // frontmost right now (we're an Accessory app and never take focus). The
    // first hotkey press also triggers the one-time Accessibility grant prompt.
    if !platform::ensure_trusted(true) {
        emit_ui(
            app,
            "warn",
            "For direct insertion and screen context, grant ReplyMint Accessibility \
             in System Settings → Privacy & Security → Accessibility (dictation still works)",
        );
    }
    let context = platform::read_context()
        .map_err(|e| eprintln!("screen context unavailable at start: {e}"))
        .ok();
    let keywords = context.as_ref().map(screen::extract_keywords).unwrap_or_default();
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
    show_overlay(app);
    // Tell the UIs which mode this session runs in — a session in the wrong
    // mode must be visible, not a mystery ("assistant was never on").
    let mode_label = if cfg.mode == "assistant" {
        "assistant"
    } else if cfg.clean_dictation {
        "dictation+polish"
    } else {
        "dictation"
    };
    emit_ui(app, "mode", mode_label);
    emit_ui(app, "state", "recording");

    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let mut done_text: Option<String> = None;
        let result = stt::run_session(&cfg.backend_url, &cfg.token, &keywords, rx, |ev| match ev {
            SttEvent::Partial(t) => emit_ui(&app, "partial", &t),
            SttEvent::Final(t) => emit_ui(&app, "final", &t),
            SttEvent::Done(text) => done_text = Some(text),
            SttEvent::Limit => {
                // Per-session cap: stop the mic right away so the tray and
                // pill read "finishing", not "still listening".
                if let Some(rec) = app.state::<AppState>().session.lock().unwrap().take() {
                    rec.stop();
                }
                set_tray_title(&app, Some("…"));
                emit_ui(
                    &app,
                    "warn",
                    &format!(
                        "{}-minute limit per dictation reached — finishing up",
                        stt::MAX_AUDIO_SECONDS / 60
                    ),
                );
                emit_ui(&app, "state", "transcribing");
            }
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
                    let mut context = app.state::<AppState>().context.lock().unwrap().take();

                    // Second chance at the screen: Electron/Chromium apps build
                    // their AX tree lazily, so the snapshot at recording start
                    // often fails or carries toolbar labels only — by now the
                    // tree is warm. Keep the reason: it goes in the warning.
                    let mut read_error = String::new();
                    if context.as_ref().map_or(true, |c| c.lines.is_empty()) {
                        match tauri::async_runtime::spawn_blocking(platform::read_context).await {
                            Ok(Ok(ctx)) => context = Some(ctx),
                            Ok(Err(e)) => read_error = e,
                            Err(e) => read_error = e.to_string(),
                        }
                    }

                    // Assistant mode: the transcript is an instruction — insert
                    // the backend's draft (with or without screen context).
                    // Dictation mode: optionally clean the transcript up first.
                    // On any failure fall back to the transcript itself: the
                    // user's words are never lost.
                    let mut label_ok = "";
                    let text = if cfg.mode == "assistant" {
                        match &context {
                            None => emit_ui(
                                &app,
                                "warn",
                                &format!("couldn't read the screen ({read_error}) — drafting from your words alone"),
                            ),
                            Some(c) if c.lines.is_empty() && c.focused_text.is_none() => emit_ui(
                                &app,
                                "warn",
                                &format!(
                                    "{} showed no readable text — drafting from your words alone",
                                    if c.app_name.is_empty() { "the screen" } else { c.app_name.as_str() }
                                ),
                            ),
                            Some(_) => {}
                        }
                        emit_ui(&app, "state", "writing");
                        match reply::generate_draft(
                            &cfg.backend_url,
                            &cfg.token,
                            context.as_ref(),
                            &transcript,
                        )
                        .await
                        {
                            Ok(draft) => {
                                label_ok = "draft";
                                draft
                            }
                            Err(e) => {
                                emit_ui(&app, "warn", &format!("draft failed ({e}) — inserted your words instead"));
                                transcript
                            }
                        }
                    } else if cfg.clean_dictation {
                        emit_ui(&app, "state", "polishing");
                        match reply::clean_dictation(
                            &cfg.backend_url,
                            &cfg.token,
                            context.as_ref(),
                            &transcript,
                        )
                        .await
                        {
                            Ok(cleaned) => {
                                label_ok = "clean";
                                cleaned
                            }
                            Err(e) => {
                                emit_ui(&app, "warn", &format!("cleanup failed ({e}) — inserted your words as heard"));
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
                            let label = match (label_ok, how) {
                                ("", insert::Insertion::Ax) => "ax",
                                ("", insert::Insertion::Paste) => "paste",
                                (label, _) => label,
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

/// One sign-in at a time — a second click while the browser tab is open must
/// not spawn a second loopback listener + consent page.
static SIGN_IN_RUNNING: AtomicBool = AtomicBool::new(false);

/// Settings → "Sign in with Google": runs the loopback OAuth flow (auth.rs),
/// saves the minted token + email, and returns /v1/me for immediate render.
#[tauri::command]
async fn sign_in() -> Result<auth::MeResponse, String> {
    if SIGN_IN_RUNNING.swap(true, Ordering::SeqCst) {
        return Err("sign-in already in progress — check your browser".into());
    }
    let result = async {
        let cfg = settings::load();
        let signed = auth::sign_in(&cfg.backend_url).await?;
        let me = auth::fetch_me(&cfg.backend_url, &signed.token).await?;
        let mut cfg = settings::load(); // reload: don't clobber edits made mid-flow
        cfg.token = signed.token;
        cfg.email = me.email.clone();
        settings::save(&cfg)?;
        Ok(me)
    }
    .await;
    SIGN_IN_RUNNING.store(false, Ordering::SeqCst);
    result
}

/// Best-effort server revoke, then clear local token + email.
#[tauri::command]
async fn sign_out() -> Result<(), String> {
    let mut cfg = settings::load();
    if !cfg.token.is_empty() {
        auth::sign_out(&cfg.backend_url, &cfg.token).await;
    }
    cfg.token = String::new();
    cfg.email = String::new();
    settings::save(&cfg)
}

/// Plan + usage for the settings window. Errs "unauthorized" on a dead token
/// (UI flips to signed-out). Backfills the cached email for legacy pasted
/// tokens so they migrate to the signed-in UI with zero user action.
#[tauri::command]
async fn fetch_me() -> Result<auth::MeResponse, String> {
    let cfg = settings::load();
    if cfg.token.is_empty() {
        return Err("unauthorized".into());
    }
    let me = auth::fetch_me(&cfg.backend_url, &cfg.token).await?;
    if cfg.email != me.email {
        let mut cfg = settings::load();
        cfg.email = me.email.clone();
        let _ = settings::save(&cfg);
    }
    Ok(me)
}

/// Settings → "Grant Accessibility": returns whether we're trusted. If not,
/// prompts and opens the System Settings pane (macOS shows its own dialog at
/// most once, so the deep link is the reliable path afterwards).
#[tauri::command]
fn request_ax() -> bool {
    let trusted = platform::ensure_trusted(true);
    #[cfg(target_os = "macos")]
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
