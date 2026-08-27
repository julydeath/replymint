# ReplyMint Backlog

Granular, do-one-at-a-time task list. Each unchecked box is meant to be a single focused sitting.
Ordered roughly by dependency/priority. See [ROADMAP.md](ROADMAP.md) for the big-picture phases.

## ✅ Already done (core loop)
- [x] Overlay bubble — accessibility-hosted, context-aware (shows only on text-field screens), draggable
- [x] Accessibility read (`ScreenReader`) + write to input field (`FieldWriter`)
- [x] Three actions wired end-to-end: **Auto Reply**, **Voice**, **Fix**
- [x] Backend `/v1/reply` (stateless) + provider switch (Anthropic prod / Ollama Cloud dev)
- [x] Bubble/menu sizing pass; foreground-service notification removed; toasts fixed

---

## Milestone A — Ship-ready Free Tier (Personal Mode)

### A1 · Multi-app robustness (highest priority — proves the product)
- [ ] A1.1 Test the full loop on **WhatsApp** (read incoming, write reply box); note what breaks
- [ ] A1.2 Test on **Gmail** reply compose
- [ ] A1.3 Test on **Instagram DM**
- [ ] A1.4 Test on **LinkedIn** messaging
- [ ] A1.5 Distinguish **incoming vs outgoing** messages in `ScreenReader` (today it's a flat text list)
- [ ] A1.6 Pick the right input box when a screen has several editable fields (prefer focused/compose)
- [ ] A1.7 Add per-app read heuristics where the generic reader is wrong (start with WhatsApp)
- [ ] A1.8 Graceful "couldn't read this screen / no reply box" messaging per app

### A2 · Bubble & overlay UX polish
- [ ] A2.1 Persist bubble drag position across sessions (`ModeStore`)
- [ ] A2.2 Keep the action menu fully on-screen when the bubble is near an edge/bottom
- [ ] A2.3 Progress indicator while a draft is being fetched (spinner or "Writing…" in-overlay)
- [ ] A2.4 In-overlay feedback as a fallback to toasts (robust even if toasts are off)
- [ ] A2.5 Edge-snap the bubble to the nearest side after drag (optional nicety)

### A3 · Real authentication
- [ ] A3.1 DECISION: Google Sign-In vs email magic-link
- [ ] A3.2 Android: sign-in flow in onboarding
- [ ] A3.3 Android: store token in **EncryptedSharedPreferences** (replaces `ModeStore.kt:10` TODO)
- [ ] A3.4 Backend: verify a real token on `/v1/reply` (replaces stub at `server.ts:20`)
- [ ] A3.5 Backend: token issue/verify (IdP or own endpoint)
- [ ] A3.6 App handles signed-out / expired-token states

### A4 · Usage metering + free-tier limits
- [ ] A4.1 Backend: persist per-user request counts
- [ ] A4.2 Backend: enforce free-tier cap → return a clear limit error
- [ ] A4.3 Backend: basic rate limiting / abuse guard (per token/IP)
- [ ] A4.4 App: "limit reached" state + upsell to Pro

### A5 · Backend production deploy
- [ ] A5.1 DECISION: hosting (Fly / Render / Railway / VPS)
- [ ] A5.2 Deploy to `api.replymint.app` over HTTPS
- [ ] A5.3 Prod secrets management (`ANTHROPIC_API_KEY`, etc.)
- [ ] A5.4 Verify release build points at prod URL; confirm cleartext stays debug-only
- [ ] A5.5 Privacy-safe error logging / uptime monitoring

### A6 · Google Play compliance & release prep
- [ ] A6.1 Write the **Accessibility API justification** (Phase 0 gate — a real removal risk)
- [ ] A6.2 Hosted privacy policy page
- [ ] A6.3 Release signing (keystore); confirm ProGuard rules don't break OkHttp/serialization
- [ ] A6.4 Store listing: icon, screenshots, description
- [ ] A6.5 Play Data-Safety form + permission declarations (accessibility, overlay, mic)

---

## Milestone B — Professional Mode + Reply Brain (paid)

### B1 · Reply Brain storage
- [ ] B1.1 Stand up Postgres + pgvector (local + prod)
- [ ] B1.2 Schema + migrations (identity, rules, voice, contact, situation)
- [ ] B1.3 Backend CRUD endpoints for the Reply Brain
- [ ] B1.4 Move brain from inline request → server-side per user (replaces `types.ts:11`)

### B2 · Reply Brain editor UI (Android)
- [ ] B2.1 View screen — everything remembered
- [ ] B2.2 Add/edit/delete each section (identity, hard rules, voice samples, contacts)
- [ ] B2.3 Per-item delete + "forget everything" (privacy requirement)

### B3 · Retrieval + smarter generation
- [ ] B3.1 Embeddings for voice samples / contact notes
- [ ] B3.2 RAG retrieval at reply time
- [ ] B3.3 Situation detection (sales/support/negotiation/hiring/…)
- [ ] B3.4 Verify hard-rule enforcement in output (price floor, payment terms, avoid words)
- [ ] B3.5 "Approve reply → learn" feedback loop into voice/contact memory

### B4 · Billing
- [ ] B4.1 Google Play Billing (subscriptions)
- [ ] B4.2 Backend entitlement check (personal vs professional)
- [ ] B4.3 Tier gating in app + backend

---

## Milestone C — Polish & retention
- [ ] C1 30-second onboarding that teaches the bubble
- [ ] C2 Usage dashboard
- [ ] C3 More target apps + heuristics (ongoing)
- [ ] C4 Deeper privacy controls (what to remember / forget)

---

## Suggested starting point
**A1.1 — test the loop on WhatsApp** (then A1.2–A1.4). Cheapest, highest-signal: it tells us how solid
the core really is on real apps before investing in auth/billing/paid features.
