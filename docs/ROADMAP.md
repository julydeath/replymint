# Roadmap

Android-first. Each phase has a clear gate before the next begins.

## Phase 0 — De-risk (before building anything on top)

Two things can sink the product. Prove them first.

1. **Google Play Accessibility policy.** Google restricts the Accessibility API and has removed apps
   that use it for automation. Draft the justification: user-triggered, reads only on tap, never
   auto-acts, assists communication. **Confirm defensible before heavy investment.**
2. **Technical spike.** Prove the full loop on real apps: bubble appears → reads incoming messages →
   writes text into the reply box. Test **WhatsApp, Gmail, LinkedIn** (each has a different node tree;
   Instagram is messier — note it).

**Gate:** proceed only if both pass. The spike code lives behind `ScreenReader` / `FieldWriter`.

## Phase 1 — Personal Mode MVP  ← current scaffold

The core loop, stateless, cheapest tier.

- [x] Project scaffold (Android + backend + docs)
- [ ] Overlay bubble service (draggable, dismissable)
- [ ] Accessibility service: read visible text, locate + write input field
- [ ] Three actions: **Fix**, **Voice**, **Auto Reply Draft**
- [ ] Sign-in (Google / email)
- [ ] Backend `/v1/reply` (stateless) + Claude (cheaper model)
- [ ] Usage metering + free-tier limits

**Goal:** prove people actually send ReplyMint's one draft.

## Phase 2 — Professional Mode + Reply Brain

The premium product. See [REPLY_BRAIN.md](REPLY_BRAIN.md).

- [ ] Reply Brain data model (identity, hard rules, voice, contact memory, situation types)
- [ ] Reply Brain editor UI (view / edit / delete everything)
- [ ] Retrieval (RAG) over Postgres + pgvector
- [ ] Situation detection (sales/support/negotiation/hiring/follow-up/complaint/scheduling)
- [ ] Hard-rule enforcement (price floors, payment terms, words to avoid)
- [ ] Stronger model + faster generation for pro tier
- [ ] "Approve reply → learn" feedback loop into voice/contact memory

**Recommendation:** RAG, **not** fine-tuning — cheaper, and instantly editable/deletable, which the
privacy promise requires. Fine-tuning cannot be "un-remembered."

## Phase 3 — Polish & retention

- [ ] More target apps + per-app read heuristics
- [ ] Usage dashboards
- [ ] Deeper privacy controls (what to remember / forget)
- [ ] Onboarding that teaches the bubble in 30 seconds

## Later — iOS (separate project)

Grammarly-style **keyboard** doing **Fix + Voice only**. A keyboard cannot read the incoming
message, so Auto Reply Draft stays Android-only. Different codebase (Swift), not in this repo.

## Later — Team / Enterprise

Shared business Reply Brains, admin controls, seat billing, custom pricing.

## Pricing (target)

| Tier | Price |
|---|---|
| Personal | Free / $4.99/mo |
| Professional | $19–$49/mo |
| Team / Enterprise | Custom |

Any paid tier sets `users.plan = 'pro'`, which is a separate, server-enforced axis from
mode: it gates **cloud STT** (`/v1/stt/stream`, Deepgram). Free accounts in either mode
use native on-device transcription (V2 context correction included — that stays free).

**Mac is pro-only for now** — the desktop app has no native STT engine, so cloud STT is
its only path (same posture as Wispr Flow). Future free options, noted in VOICE_PLAN
(Part 3 → Decisions): a small metered daily STT allowance for free accounts when pricing
launches, and eventually a true free tier via Apple's `SFSpeechRecognizer`.
