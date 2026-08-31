# ReplyMint Desktop (Mac — VOICE_PLAN D1 + D2)

A [Tauri](https://tauri.app) menu-bar app. The whole product is one loop:

```
global hotkey → read focused window (AX) → record mic → stream to /v1/stt/stream
             (screen keywords boost accuracy) → transcript
             → Dictation mode: inserted verbatim · Assistant mode: /v1/reply writes the draft
             → AX insertion at the cursor (clipboard-paste ⌘V fallback)
```

All real logic is Rust ([src-tauri/src](src-tauri/src)); the frontend is a single
static settings page ([ui/index.html](ui/index.html)) — no bundler.

| Piece | File |
|---|---|
| Tray, hotkey, orchestration | `src/lib.rs` |
| AX: focused-window read, direct insertion, trust prompt | `src/ax.rs` |
| Mic capture → 16kHz mono PCM16 (cpal) | `src/audio.rs` |
| WebSocket client for the STT proxy | `src/stt.rs` |
| Instruction mode → POST /v1/reply | `src/reply.rs` |
| Insertion dispatch: AX first, ⌘V-paste fallback | `src/insert.rs` |
| Settings file (Application Support) | `src/settings.rs` |

## Run

Prereqs: Rust (`curl https://sh.rustup.rs | sh`), Node 20+, and the backend running.

```bash
cd desktop
npm install
npm run dev          # builds and launches the tray app
```

First use: press the hotkey (default **⌥Space**) in any app, speak, press again.
macOS will prompt for **Microphone** on first recording and **Accessibility** on
first use (or via the Settings window's *Grant Accessibility* button — it opens
the System Settings pane). With Accessibility granted, text is inserted directly
at the cursor and the focused window's text becomes context; without it, the app
still dictates via the clipboard-paste fallback (which additionally prompts for
**Automation** once).

**Modes** (Settings window): *Dictation* inserts your words verbatim. *Assistant*
treats your speech as an instruction — the focused window's text + your words go
to `/v1/reply` and the generated draft is inserted instead (the Android bubble's
voice flow, on Mac). Cloud STT is pro-gated server-side: the account behind the
token needs `users.plan='pro'` (dev-token.ts mints one).

## Auth

**Sign in with Google** (Settings window) runs a browser-based OAuth flow with a
loopback redirect + PKCE (`src/auth.rs`): the app binds an ephemeral 127.0.0.1
port, opens the consent page, and hands the returned code to the backend
(`POST /v1/auth/google/desktop`), which holds the Desktop client secret and
mints the opaque `rt_` token. Free-draft counting is per Google account, so
usage combines with the Android app automatically.

Requires the Google console **Desktop app** OAuth client (Backlog A3.7): build
with `REPLYMINT_GOOGLE_DESKTOP_CLIENT_ID=... cargo build` (or edit the const in
`src/auth.rs`), and set `GOOGLE_CLIENT_IDS`, `GOOGLE_DESKTOP_CLIENT_ID`,
`GOOGLE_DESKTOP_CLIENT_SECRET` on the backend.

**Dev escape hatch** — mint a token against the dev DB and paste it into
Settings → Advanced:

```bash
cd backend && npx tsx --env-file=.env scripts/dev-token.ts
```

## Headless smoke test

Proves the full desktop→proxy→STT path without mic, GUI, or permissions
(run the backend with `STT_PROVIDER=mock` and no Deepgram key is needed):

```bash
cd desktop/src-tauri
REPLYMINT_TOKEN=rt_... cargo run -- smoke
# smoke ok — transcript: "mock segment 1. mock segment 2. mock segment 3."
```

The Settings window's **Test backend** button does the same thing.

## Scope

- **D1**: dictation parity — hotkey, record, cloud STT, paste. ✅ live-verified
- **D2 (this)**: focused-window AX read → keyword-boosted transcription +
  Assistant (instruction) mode, and AX insertion with paste fallback. ✅ built —
  on-Mac permission run-through pending
- **D3**: Windows port — a SendInput variant behind `insert::Insertion`,
  per-OS config path, same core.
