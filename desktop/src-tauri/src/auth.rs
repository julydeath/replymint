//! Desktop Google sign-in (Backlog A3): browser-based OAuth with a loopback
//! redirect + PKCE — the gcloud-CLI pattern. We bind an ephemeral 127.0.0.1
//! port, open the system browser at Google's consent page, catch the redirect,
//! and hand the code to the backend (`POST /v1/auth/google/desktop`), which
//! holds the Desktop client secret and mints our opaque `rt_` token. No secret
//! ever ships in this binary; PKCE binds the code to this process.

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

/// Client ids are public identifiers, not secrets — a const is fine. The env
/// override remains for pointing a build at a different Google project.
const DESKTOP_CLIENT_ID: &str = match option_env!("REPLYMINT_GOOGLE_DESKTOP_CLIENT_ID") {
    Some(id) => id,
    None => "665655720291-jrojc2t6eq7vc5ul727gdq8cqinpl45a.apps.googleusercontent.com",
};

const AUTH_TIMEOUT: Duration = Duration::from_secs(300);

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MeResponse {
    pub email: String,
    pub name: Option<String>,
    pub plan: String,
    #[serde(rename = "todayCount")]
    pub today_count: u32,
    #[serde(rename = "dailyLimit")]
    pub daily_limit: u32,
}

#[derive(Deserialize)]
struct SignInResponse {
    token: Option<String>,
    error: Option<String>,
}

pub struct SignedIn {
    pub token: String,
}

fn b64url(bytes: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(bytes)
}

fn random_b64url(len: usize) -> String {
    let mut bytes = vec![0u8; len];
    rand::thread_rng().fill_bytes(&mut bytes);
    b64url(&bytes)
}

/// Runs the whole flow: loopback listener → browser consent → code → backend
/// exchange. Blocks (async) until the redirect arrives or the timeout fires.
pub async fn sign_in(backend_url: &str) -> Result<SignedIn, String> {
    let listener = TcpListener::bind("127.0.0.1:0")
        .await
        .map_err(|e| format!("couldn't open local port: {e}"))?;
    let port = listener
        .local_addr()
        .map_err(|e| format!("local addr: {e}"))?
        .port();
    let redirect_uri = format!("http://127.0.0.1:{port}");

    let verifier = random_b64url(32);
    let challenge = b64url(&Sha256::digest(verifier.as_bytes()));
    let state = random_b64url(16);

    let auth_url = format!(
        "https://accounts.google.com/o/oauth2/v2/auth\
         ?client_id={client_id}\
         &redirect_uri={redirect}\
         &response_type=code\
         &scope=openid%20email%20profile\
         &code_challenge={challenge}\
         &code_challenge_method=S256\
         &state={state}",
        client_id = urlencode(DESKTOP_CLIENT_ID),
        redirect = urlencode(&redirect_uri),
    );

    open_browser(&auth_url)?;

    let code = tokio::time::timeout(AUTH_TIMEOUT, wait_for_code(&listener, &state))
        .await
        .map_err(|_| "sign-in timed out — try again".to_string())??;

    exchange_code(backend_url, &code, &verifier, &redirect_uri).await
}

/// Accept-loop on the loopback listener until a request carries our code (or an
/// OAuth error). Stray hits — favicon requests, wrong state — are answered and
/// ignored; the browser only ever gets one redirect from Google.
async fn wait_for_code(listener: &TcpListener, state: &str) -> Result<String, String> {
    loop {
        let Ok((mut stream, _)) = listener.accept().await else { continue };
        let mut buf = vec![0u8; 4096];
        let n = stream.read(&mut buf).await.unwrap_or(0);
        let request = String::from_utf8_lossy(&buf[..n]);

        // "GET /?code=...&state=... HTTP/1.1"
        let query = request
            .lines()
            .next()
            .and_then(|line| line.split_whitespace().nth(1))
            .and_then(|path| path.split_once('?'))
            .map(|(_, q)| q)
            .unwrap_or("");
        let param = |key: &str| {
            query.split('&').find_map(|kv| {
                let (k, v) = kv.split_once('=')?;
                (k == key).then(|| urldecode(v))
            })
        };

        if let Some(err) = param("error") {
            respond(&mut stream, "Sign-in cancelled", "You can close this tab.").await;
            return Err(if err == "access_denied" {
                "sign-in cancelled".into()
            } else {
                format!("google error: {err}")
            });
        }

        match param("code") {
            Some(code) if param("state").as_deref() == Some(state) => {
                respond(&mut stream, "Signed in", "Return to ReplyMint — you can close this tab.")
                    .await;
                return Ok(code);
            }
            _ => {
                // favicon, health probes, state mismatch: 404 and keep listening.
                let _ = stream
                    .write_all(b"HTTP/1.1 404 Not Found\r\ncontent-length: 0\r\n\r\n")
                    .await;
            }
        }
    }
}

async fn respond(stream: &mut tokio::net::TcpStream, title: &str, body: &str) {
    let html = format!(
        "<!doctype html><meta charset=\"utf-8\"><title>{title} — ReplyMint</title>\
         <body style=\"background:#07090C;color:#F2F5F7;font-family:-apple-system,sans-serif;\
         display:grid;place-items:center;height:100vh;margin:0\">\
         <div style=\"text-align:center\">\
         <div style=\"font-size:40px;margin-bottom:12px\">&#10003;</div>\
         <h1 style=\"font-size:20px;margin:0 0 6px\">{title}</h1>\
         <p style=\"color:#9AA6B2;margin:0\">{body}</p></div></body>"
    );
    let response = format!(
        "HTTP/1.1 200 OK\r\ncontent-type: text/html; charset=utf-8\r\ncontent-length: {}\r\n\r\n{html}",
        html.len()
    );
    let _ = stream.write_all(response.as_bytes()).await;
    let _ = stream.flush().await;
}

async fn exchange_code(
    backend_url: &str,
    code: &str,
    verifier: &str,
    redirect_uri: &str,
) -> Result<SignedIn, String> {
    let client = http_client()?;
    let resp = client
        .post(format!(
            "{}/v1/auth/google/desktop",
            backend_url.trim_end_matches('/')
        ))
        .json(&serde_json::json!({
            "code": code,
            "codeVerifier": verifier,
            "redirectUri": redirect_uri,
            "platform": "macos",
            "deviceName": device_name(),
        }))
        .send()
        .await
        .map_err(|e| format!("sign-in request: {e}"))?;

    let status = resp.status();
    let parsed: SignInResponse = resp
        .json()
        .await
        .map_err(|e| format!("sign-in response: {e}"))?;
    match parsed.token {
        Some(token) => Ok(SignedIn { token }),
        None => Err(parsed
            .error
            .unwrap_or_else(|| format!("sign-in failed ({status})"))),
    }
}

/// GET /v1/me — plan + usage for the settings window. A literal
/// Err("unauthorized") means the token is dead (401): flip to signed-out.
pub async fn fetch_me(backend_url: &str, token: &str) -> Result<MeResponse, String> {
    let client = http_client()?;
    let resp = client
        .get(format!("{}/v1/me", backend_url.trim_end_matches('/')))
        .bearer_auth(token)
        .send()
        .await
        .map_err(|e| format!("me request: {e}"))?;
    if resp.status().as_u16() == 401 {
        return Err("unauthorized".into());
    }
    if !resp.status().is_success() {
        return Err(format!("me failed ({})", resp.status()));
    }
    resp.json().await.map_err(|e| format!("me response: {e}"))
}

/// Best-effort server-side revoke; local state is cleared regardless.
pub async fn sign_out(backend_url: &str, token: &str) {
    if let Ok(client) = http_client() {
        let _ = client
            .post(format!(
                "{}/v1/auth/signout",
                backend_url.trim_end_matches('/')
            ))
            .bearer_auth(token)
            .send()
            .await;
    }
}

fn http_client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .timeout(Duration::from_secs(20))
        .build()
        .map_err(|e| format!("http client: {e}"))
}

/// Launch the system browser at the consent page. Same shell-out precedent as
/// the Accessibility deep link in lib.rs.
#[cfg(target_os = "macos")]
fn open_browser(url: &str) -> Result<(), String> {
    std::process::Command::new("open")
        .arg(url)
        .spawn()
        .map(|_| ())
        .map_err(|e| format!("couldn't open browser: {e}"))
}

/// rundll32 gets the URL as a plain argv entry — no cmd.exe quoting of the
/// `&`-laden OAuth URL to get wrong.
#[cfg(windows)]
fn open_browser(url: &str) -> Result<(), String> {
    std::process::Command::new("rundll32")
        .args(["url.dll,FileProtocolHandler", url])
        .spawn()
        .map(|_| ())
        .map_err(|e| format!("couldn't open browser: {e}"))
}

#[cfg(target_os = "macos")]
fn device_name() -> String {
    std::process::Command::new("scutil")
        .args(["--get", "ComputerName"])
        .output()
        .ok()
        .and_then(|o| String::from_utf8(o.stdout).ok())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "Mac".into())
}

#[cfg(windows)]
fn device_name() -> String {
    std::env::var("COMPUTERNAME")
        .map(|s| s.trim().to_string())
        .ok()
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "Windows PC".into())
}

fn urlencode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char)
            }
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

fn urldecode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'%' if i + 2 < bytes.len() => {
                let hex = std::str::from_utf8(&bytes[i + 1..i + 3]).unwrap_or("");
                if let Ok(byte) = u8::from_str_radix(hex, 16) {
                    out.push(byte);
                    i += 3;
                } else {
                    out.push(b'%');
                    i += 1;
                }
            }
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            b => {
                out.push(b);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}
