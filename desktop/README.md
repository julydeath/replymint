# ReplyMint Desktop (Mac — VOICE_PLAN D1)

A [Tauri](https://tauri.app) menu-bar app. The whole product is one loop:

```
global hotkey → record mic → stream to backend /v1/stt/stream → transcript
             → pasted into the focused field of whatever app is frontmost
```

All real logic is Rust ([src-tauri/src](src-tauri/src)); the frontend is a single
static settings page ([ui/index.html](ui/index.html)) — no bundler.

| Piece | File |
|---|---|
| Tray, hotkey, orchestration | `src/lib.rs` |
| Mic capture → 16kHz mono PCM16 (cpal) | `src/audio.rs` |
| WebSocket client for the STT proxy | `src/stt.rs` |
| Clipboard-paste insertion (⌘V via System Events) | `src/insert.rs` |
| Settings file (Application Support) | `src/settings.rs` |

## Run

Prereqs: Rust (`curl https://sh.rustup.rs | sh`), Node 20+, and the backend running.

```bash
cd desktop
npm install
npm run dev          # builds and launches the tray app
```

First use: press the hotkey (default **⌥Space**) in any app, speak, press again.
macOS will prompt for **Microphone** on first recording and
**Accessibility/Automation** on first paste — both one-time grants.

## Auth (temporary)

Desktop sign-in doesn't exist yet (Backlog A3). Mint a token against the dev DB
and paste it into the tray menu → Settings…:

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

- **D1 (this)**: dictation parity — hotkey, record, cloud STT, paste. ✅ scaffolded
- **D2**: read the focused window via the macOS AX API → context-corrected
  transcription + instruction mode (the Wispr differentiator). Also replaces
  clipboard-paste with AX insertion where possible.
- **D3**: Windows port — swap `insert.rs` osascript for a cross-platform
  synthesizer, per-OS config path, same core.
