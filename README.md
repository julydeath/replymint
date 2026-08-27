# ReplyMint

> A privacy-first AI reply assistant that writes the perfect reply directly inside any app —
> using your context, voice, and business memory.

ReplyMint is an **Android-first** floating bubble. You tap it inside WhatsApp, Gmail, LinkedIn,
Instagram — any app with a text box — and it drafts **one** best reply directly into the input
field. You review and tap **Send** yourself. ReplyMint never sends anything.

## Two modes

| | Personal | Professional |
|---|---|---|
| For | Everyday quick replies | Founders, sales, consultants, recruiters |
| Does | Fix, Voice, simple reply from screen | Full **Reply Brain**: business rules, voice, contact memory |
| Memory | None (stateless) | Private, editable, deletable Reply Brain |
| Price | Free / $4.99/mo | $19–$49/mo |

## The three actions

1. **Auto Reply Draft** — reads the visible conversation, writes one best reply.
2. **Voice** — you speak an instruction ("tell him I'll be late but stay friendly"), it rewrites.
3. **Fix** — cleans up grammar/clarity of what you already typed.

## How it works (Android)

Two Android capabilities do the heavy lifting:

- **`SYSTEM_ALERT_WINDOW`** draws the floating bubble on top of every app.
- **`AccessibilityService`** reads the visible conversation *on tap only*, and writes the finished
  draft into the focused input field.

```
Tap bubble → Accessibility reads screen → pick action → backend → LLM → one draft
          → draft written into the input box → you review → you tap Send
```

## Platform scope

- **Android** — the primary product. Full experience (bubble + all three actions + Reply Brain).
- **iOS** — a *separate, later* project: a Grammarly-style keyboard doing **Fix + Voice only**.
  A keyboard cannot read the incoming message, so Auto Reply Draft is Android-only. Not in this repo yet.

## Repo layout

```
replymint/
├── docs/            Architecture, roadmap, privacy, Reply Brain spec, dev setup
├── android/         Native Kotlin app (the product)
└── backend/         Lightweight Hono + Claude API server
```

## Getting started

- **Android:** see [docs/DEV_SETUP.md](docs/DEV_SETUP.md)
- **Backend:** see [backend/README.md](backend/README.md)

## Privacy promise (enforced in code, not just marketing)

- Reads the screen **only** when you tap the bubble — never a background listener.
- **No send capability at all** — the app can only write into the box.
- Personal mode is **stateless**. Professional mode stores *derived memory*, not raw chats.
- Reply Brain is fully **editable and deletable**.
- No ads, no data selling, no training public models on your data.

See [docs/PRIVACY.md](docs/PRIVACY.md) for how each promise is implemented.
