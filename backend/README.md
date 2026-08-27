# ReplyMint Backend

A lightweight [Hono](https://hono.dev) server that turns a screen snapshot into **one best reply**
via the Claude API. One real endpoint; stateless for personal mode.

## Run

```bash
cd backend
cp .env.example .env      # add your ANTHROPIC_API_KEY
npm install
npm run dev               # http://localhost:8787
```

## Endpoint

`POST /v1/reply` — header `Authorization: Bearer <token>` (stub auth for now).

Request:

```json
{
  "mode": "professional",
  "action": "auto_reply",
  "screen": {
    "appPackage": "com.whatsapp",
    "visibleText": ["Client: Can you reduce the price?"],
    "typedText": null
  },
  "brain": {
    "identity": { "name": "Alex", "sells": "brand design" },
    "rules": { "priceFloorPercent": 15, "paymentTerms": "50% advance" },
    "voice": { "tone": "polite but confident" },
    "contact": { "stage": "active client" }
  }
}
```

Response:

```json
{ "draft": "I understand where you're coming from. I can offer a 10% adjustment, but I wouldn't want to reduce the scope or quality beyond that. If that works for you, we can confirm with 50% advance and get started." }
```

## Model tiering

| Mode | Model | Why |
|---|---|---|
| Personal | `claude-haiku-4-5` | Cheap, fast — matches the free/low tier |
| Professional | `claude-opus-5` | Strongest model — the paid-tier lever |

Set in [src/anthropic.ts](src/anthropic.ts). This is a **product** decision (the spec calls for a
better model on the professional tier), not an arbitrary downgrade.

## Privacy in this service

- **Stateless for personal** — nothing is persisted.
- **No prompt logging** — only error class/message is logged.
- Anthropic's API does **not** train on API traffic by default, satisfying the "no training on your
  data" promise. For professional memory, Phase 2 stores *derived* Reply Brain rows (not raw chats).

## Quick test

```bash
curl -s http://localhost:8787/v1/reply \
  -H "Authorization: Bearer dev" \
  -H "Content-Type: application/json" \
  -d '{"mode":"personal","action":"auto_reply","screen":{"appPackage":"com.whatsapp","visibleText":["Are you coming tonight?"]}}'
```

## Next (Phase 2)

- Real auth (verify the bearer token against your IdP).
- Per-tier usage metering + rate limits.
- Reply Brain storage: Postgres + pgvector, retrieval at reply time (see [../docs/REPLY_BRAIN.md](../docs/REPLY_BRAIN.md)).
