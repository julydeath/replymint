# Architecture

ReplyMint is deliberately thin: a native Android client that captures context and writes drafts,
and a stateless-by-default backend that turns context into one reply via the Claude API.

## System overview

```
┌──────────────────────────── Android device ────────────────────────────┐
│                                                                         │
│  Any app (WhatsApp, Gmail, …)                                           │
│        ▲   │ reads visible text on tap                                  │
│        │   ▼                                                            │
│  ┌───────────────────────────┐     ┌──────────────────────────────┐    │
│  │ AccessibilityService      │     │ BubbleService (foreground)   │    │
│  │  • ScreenReader (read)    │◄───►│  • WindowManager overlay      │    │
│  │  • FieldWriter (write)    │     │  • 3-action menu              │    │
│  └───────────────────────────┘     └──────────────────────────────┘    │
│                     │                                                    │
│                     ▼                                                    │
│              ┌──────────────┐   mode + token (SharedPreferences)         │
│              │ ReplyEngine  │──────────────────────────────────┐         │
│              └──────────────┘                                  │         │
│                     │ HTTPS                                     │         │
└─────────────────────┼──────────────────────────────────────────┼────────┘
                      ▼                                           ▼
        ┌──────────────────────────┐              ┌──────────────────────────┐
        │ Backend (Hono / Node)    │              │ (Pro) Reply Brain store   │
        │  • /v1/reply             │──────────────│  Postgres + pgvector      │
        │  • auth + rate limit     │  retrieve    │  rules / voice / contacts │
        │  • prompt orchestration  │              └──────────────────────────┘
        │  • Claude API            │
        └──────────────────────────┘
```

## Android client

Native **Kotlin, classic Android Views** (no Compose) for a small APK and instant startup.

### Components

| Component | Responsibility |
|---|---|
| `MainActivity` | Onboarding, permission grants, mode selection, sign-in |
| `BubbleService` | Foreground service; draws the draggable bubble + action menu via `WindowManager` |
| `ReplyMintAccessibilityService` | The only component that touches other apps. Reads visible text, finds & writes the input field. Active **only while processing a tap** |
| `ScreenReader` | Walks the active window's accessibility node tree → structured `ScreenContext` |
| `FieldWriter` | Writes the final draft into the focused editable node (`ACTION_SET_TEXT`) |
| `ReplyEngine` | Orchestrates: gather context → call backend → hand draft to `FieldWriter` |
| `ReplyClient` | Thin OkHttp client to the backend |
| `ModeStore` | SharedPreferences: current mode, auth token |

### Why these choices (lightweight & fast)

- **Classic Views over Compose** — smaller binary, no Compose runtime in an overlay window.
- **WindowManager overlay** — the only correct way to float over other apps; Compose overlays are fragile.
- **OkHttp + kotlinx-serialization** — no Retrofit/Moshi/reflection weight.
- **SharedPreferences** — no DataStore/Room dependency for the tiny bit of state the MVP needs.
- **Accessibility captures on-demand** — no polling, no background scraping; zero idle cost.

### The critical dependency

The entire product rests on one capability: the accessibility service reliably (a) reading the
incoming message and (b) writing into the reply box, across the top target apps. Each app renders a
different node tree, so `ScreenReader` needs per-app heuristics. This is validated in **Phase 0**
(see [ROADMAP.md](ROADMAP.md)) before anything else is built on top of it.

## Backend

**Hono on Node** — one of the lightest HTTP frameworks, portable to edge later.

- `POST /v1/reply` — `{ mode, action, screen, voiceInstruction?, brain? }` → `{ draft }`
- Auth: bearer token (stub in MVP, real IdP later).
- Rate limiting: per-tier usage metering.
- Prompt orchestration: builds a **personal** or **professional** prompt (see [REPLY_BRAIN.md](REPLY_BRAIN.md)).
- Model tiering: cheaper model for personal, stronger for professional.
- **Stateless for personal** — nothing stored. Professional retrieves the Reply Brain.

### Data flow for one professional reply

1. Client sends screen context + action.
2. Backend detects contact + situation type (sales/support/negotiation/…).
3. Retrieves relevant Reply Brain: hard rules + this contact's memory + matching style samples.
4. Builds structured prompt; Claude returns **one** reply that respects hard rules (e.g. price floor).
5. Returns draft. Client writes it into the box. Nothing is auto-sent.

## What we deliberately do NOT build

- No message-sending code anywhere (architectural guarantee behind the privacy promise).
- No background screen listener.
- No raw-chat storage (personal stores nothing; professional stores derived memory only).

## Tech stack

| Layer | Choice |
|---|---|
| App | Kotlin, classic Views, minSdk 26 / target 36 |
| Networking | OkHttp + kotlinx-serialization |
| Backend | Hono + `@hono/node-server`, TypeScript |
| AI | Claude API (tiered by mode) |
| Pro memory | Postgres + pgvector (Phase 2) |
| Auth/billing | IdP + Google Play Billing (Phase 2) |
