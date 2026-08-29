//! Instruction mode (D2): the transcript is an instruction, not the text — the
//! focused window's AX context + the instruction go to POST /v1/reply and the
//! generated draft is what gets inserted. Mirrors android/core/ReplyEngine.

use serde::Deserialize;
use serde_json::json;
use std::time::Duration;

use crate::ax::AxContext;

#[derive(Deserialize)]
struct ReplyResponse {
    draft: Option<String>,
    error: Option<String>,
}

/// One draft from the backend. Personal mode: stateless, nothing persisted.
pub async fn generate_draft(
    backend_url: &str,
    token: &str,
    ctx: &AxContext,
    instruction: &str,
) -> Result<String, String> {
    // The window title leads the visible text — it's often the conversation's
    // subject ("Re: Invoice…") and the AX tree doesn't always repeat it inside.
    let mut visible: Vec<&str> = Vec::with_capacity(ctx.lines.len() + 1);
    if !ctx.window_title.is_empty() {
        visible.push(&ctx.window_title);
    }
    visible.extend(ctx.lines.iter().map(String::as_str));

    let body = json!({
        "mode": "personal",
        "action": "voice",
        "screen": {
            // No bundle ids on the AX path — the app name serves the same
            // "which app is this" purpose in the prompt.
            "appPackage": ctx.app_name,
            "visibleText": visible,
            "typedText": ctx.focused_text,
        },
        "voiceInstruction": instruction,
        "voice": {
            "hypotheses": [instruction],
            "source": "cloud",
            "lang": "en",
        },
    });

    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(30))
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
        401 => return Err("token rejected — paste a fresh one in Settings".into()),
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
