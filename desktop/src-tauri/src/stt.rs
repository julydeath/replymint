use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use tokio::sync::mpsc::Receiver;
use tokio_tungstenite::connect_async;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::Message;

use crate::audio::SAMPLE_RATE;

/// Events from one streaming session, mirroring the proxy's wire protocol
/// (backend/src/server.ts /v1/stt/stream).
#[derive(Debug, Clone)]
pub enum SttEvent {
    Ready,
    Partial(String),
    Final(String),
    Done(String),
    /// The message travels via run_session's Err return; the event just marks it.
    Error,
}

#[derive(Deserialize)]
struct WireMsg {
    #[serde(rename = "type")]
    kind: Option<String>,
    text: Option<String>,
    message: Option<String>,
}

/// Streams audio chunks from `audio_rx` to the backend proxy and forwards
/// transcript events to `on_event`. When `audio_rx` closes (the recorder
/// stopped), sends "finish" and keeps reading until the server's "done".
pub async fn run_session(
    backend_url: &str,
    token: &str,
    mut audio_rx: Receiver<Vec<u8>>,
    mut on_event: impl FnMut(SttEvent),
) -> Result<(), String> {
    let ws_url = format!("{}/v1/stt/stream", http_to_ws(backend_url));
    let mut request = ws_url
        .into_client_request()
        .map_err(|e| format!("bad backend url: {e}"))?;
    request.headers_mut().insert(
        "authorization",
        format!("Bearer {token}")
            .parse()
            .map_err(|_| "bad token".to_string())?,
    );

    let (ws, _) = connect_async(request)
        .await
        .map_err(|e| format!("connect: {e}"))?;
    let (mut sink, mut stream) = ws.split();

    sink.send(Message::Text(format!(
        r#"{{"type":"config","sampleRate":{SAMPLE_RATE},"language":"en"}}"#
    )))
    .await
    .map_err(|e| format!("send config: {e}"))?;

    let mut finished = false;
    loop {
        tokio::select! {
            chunk = audio_rx.recv(), if !finished => match chunk {
                Some(bytes) => sink
                    .send(Message::Binary(bytes))
                    .await
                    .map_err(|e| format!("send audio: {e}"))?,
                None => {
                    finished = true;
                    sink.send(Message::Text(r#"{"type":"finish"}"#.into()))
                        .await
                        .map_err(|e| format!("send finish: {e}"))?;
                }
            },
            msg = stream.next() => match msg {
                Some(Ok(Message::Text(json))) => {
                    let Ok(m) = serde_json::from_str::<WireMsg>(&json) else { continue };
                    match m.kind.as_deref() {
                        Some("ready") => on_event(SttEvent::Ready),
                        Some("partial") => on_event(SttEvent::Partial(m.text.unwrap_or_default())),
                        Some("final") => on_event(SttEvent::Final(m.text.unwrap_or_default())),
                        Some("done") => {
                            on_event(SttEvent::Done(m.text.unwrap_or_default()));
                            return Ok(());
                        }
                        Some("error") => {
                            on_event(SttEvent::Error);
                            return Err(m.message.unwrap_or_else(|| "stt error".into()));
                        }
                        _ => {}
                    }
                }
                Some(Ok(Message::Close(_))) | None => {
                    return Err("server closed before done".into());
                }
                Some(Ok(_)) => {}
                Some(Err(e)) => return Err(format!("stream: {e}")),
            },
        }
    }
}

fn http_to_ws(url: &str) -> String {
    let trimmed = url.trim_end_matches('/');
    if let Some(rest) = trimmed.strip_prefix("https://") {
        format!("wss://{rest}")
    } else if let Some(rest) = trimmed.strip_prefix("http://") {
        format!("ws://{rest}")
    } else {
        trimmed.to_string() // already ws:// or wss://
    }
}

/// Headless end-to-end check: streams `seconds` of silence through the proxy and
/// returns the transcript. Used by `replymint-desktop smoke` and the Settings
/// window's Test button — with STT_PROVIDER=mock on the backend, no key needed.
pub async fn silence_session(backend_url: &str, token: &str, seconds: u32) -> Result<String, String> {
    let (tx, rx) = tokio::sync::mpsc::channel::<Vec<u8>>(64);
    for _ in 0..seconds * 4 {
        let _ = tx.send(vec![0u8; (SAMPLE_RATE as usize / 4) * 2]).await;
    }
    drop(tx);

    let mut transcript = String::new();
    run_session(backend_url, token, rx, |ev| {
        if let SttEvent::Done(text) = ev {
            transcript = text;
        }
    })
    .await?;
    Ok(transcript)
}
