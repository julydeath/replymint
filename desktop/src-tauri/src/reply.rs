//! Backend text generation (D2): POST /v1/reply with the focused window's AX
//! context. Two actions — "voice" (Assistant mode: the transcript is an
//! instruction, the draft is what lands) and "dictate" (Dictation cleanup: the
//! transcript comes back with grammar fixed and stutters/self-corrections
//! resolved). Mirrors android/core/ReplyEngine.

use serde::Deserialize;
use serde_json::json;
use std::time::Duration;

use crate::screen::ScreenContext;

#[derive(Deserialize)]
struct ReplyResponse {
    draft: Option<String>,
    error: Option<String>,
}

/// Assistant mode: the transcript is an instruction; the backend's draft is the
/// text to insert. Works without context — the instruction alone still drafts.
pub async fn generate_draft(
    backend_url: &str,
    token: &str,
    ctx: Option<&ScreenContext>,
    instruction: &str,
) -> Result<String, String> {
    post_reply(backend_url, token, ctx, "voice", instruction).await
}

/// Dictation cleanup: same endpoint, `dictate` action — the transcript comes
/// back lightly edited (grammar, fillers, stutters, self-corrections).
pub async fn clean_dictation(
    backend_url: &str,
    token: &str,
    ctx: Option<&ScreenContext>,
    transcript: &str,
) -> Result<String, String> {
    post_reply(backend_url, token, ctx, "dictate", transcript).await
}

/// One draft from the backend. Personal mode: stateless, nothing persisted.
async fn post_reply(
    backend_url: &str,
    token: &str,
    ctx: Option<&ScreenContext>,
    action: &str,
    transcript: &str,
) -> Result<String, String> {
    // The window title leads the visible text — it's often the conversation's
    // subject ("Re: Invoice…") and the AX tree doesn't always repeat it inside.
    let mut visible: Vec<&str> = Vec::new();
    let mut app_name = "";
    let mut typed: Option<&str> = None;
    if let Some(ctx) = ctx {
        visible.reserve(ctx.lines.len() + 1);
        if !ctx.window_title.is_empty() {
            visible.push(&ctx.window_title);
        }
        visible.extend(ctx.lines.iter().map(String::as_str));
        app_name = &ctx.app_name;
        typed = ctx.focused_text.as_deref();
    }

    let body = json!({
        "mode": "personal",
        "action": action,
        "screen": {
            // No bundle ids on the AX path — the app name serves the same
            // "which app is this" purpose in the prompt.
            "appPackage": app_name,
            "visibleText": visible,
            "typedText": typed,
        },
        "voiceInstruction": transcript,
        "voice": {
            "hypotheses": [transcript],
            "source": "cloud",
            "lang": "en",
        },
    });

    // Longer than the backend's own LLM budget (anthropic.ts: 20s × 2 tries)
    // so a slow model surfaces as the server's error, not as our timeout.
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(45))
        .build()
        .map_err(|e| format!("http client: {e}"))?;
    let resp = client
        .post(format!("{}/v1/reply", backend_url.trim_end_matches('/')))
        .bearer_auth(token)
        .json(&body)
        .send()
        .await
        .map_err(|e| format!("reply request: {e}"))?;

    match resp.status().as_u16() {
        401 => return Err("signed out — sign in again in Settings".into()),
        429 => return Err("daily draft limit reached".into()),
        _ => {}
    }
    let status = resp.status();
    let parsed: ReplyResponse = resp
        .json()
        .await
        .map_err(|e| format!("reply response: {e}"))?;
    match parsed.draft {
        Some(draft) if !draft.is_empty() => Ok(draft),
        _ => Err(parsed
            .error
            .unwrap_or_else(|| format!("reply failed ({status})"))),
    }
}
