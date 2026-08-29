# Privacy — enforced in code

Privacy is the product's differentiator, so each promise maps to a concrete implementation
decision, not a marketing line.

| Promise | How it is enforced |
|---|---|
| Reads the screen **only** on tap | `ReplyMintAccessibilityService` captures the node tree **only** inside the tap handler. `canRetrieveWindowContent` is used on demand; there is no polling loop, no `TYPE_WINDOW_CONTENT_CHANGED` scraping, no background reader. Idle cost is zero. |
| **Never sends** messages | There is **no send code anywhere** in the app. `FieldWriter` can only `ACTION_SET_TEXT` into a field. It cannot press Send. This is an architectural guarantee, not a setting. |
| User always controls Send | The draft is written into the box; the user reads and taps their app's own Send. |
| Raw chats not stored by default | Personal mode is **stateless** — the backend persists nothing. Professional mode stores **derived memory** (rules, notes, style samples), never raw transcripts, by default. |
| Memory is editable & deletable | The Reply Brain is plain structured rows the user can view, edit, and delete. RAG (not fine-tuning) is used precisely so deletion is real and immediate. |
| No ads | No ad SDKs are included as a dependency. |
| No selling data | No third-party analytics/data brokers in the dependency set. |
| No training public models on user data | All Claude API calls set no-retention / no-training options. |
| Pro users control what is remembered | Every write to the Reply Brain is user-initiated or user-confirmable; forget is one action. |

## Data handling by mode

### Personal (stateless)
- Screen text is sent to the backend **only** for the single request, used to generate one draft,
  and **not persisted**.
- No account-linked history of messages.

### Professional (derived memory)
- Stored: identity, business rules, voice/style samples, per-contact notes, situation types.
- **Not** stored by default: raw message transcripts.
- Retrieval pulls only the slices relevant to the current reply.

## Voice & audio

Dictation runs on one of two engines, and which one your audio ever reaches is a plan boundary,
enforced server-side (`users.plan` gates `/v1/stt/stream`):

| Plan | Engine | Where audio goes |
|---|---|---|
| Free | Native `SpeechRecognizer` | Audio **never reaches our servers**. The on-device engine is preferred; if the OS falls back to Google's network recognizer (no on-device model for the language), that traffic is between the device and Google under Google's terms — we are honest that we don't control that path. |
| Pro | Cloud STT (Deepgram) | Audio is streamed through our proxy (`/v1/stt/stream`) to Deepgram **only for the duration of the utterance**. We never store or log audio or transcripts — the backend meters seconds only (`usage_daily.stt_seconds`), and the Deepgram client logs error class/message only, never content. |

**Launch gate:** Deepgram must be configured for zero retention before cloud STT is enabled in
production — at minimum the **Model Improvement Program opt-out** on the project, plus written
retention terms compatible with "never stored". If self-serve terms can't meet that, a
zero-retention addendum from Deepgram sales is required first.

> Gate status: **not yet satisfied** — record date + terms version here once completed:
> _MIP opt-out: pending · Terms reviewed: pending_

Console checklist (account owner):
1. Create a Deepgram project; mint an API key scoped to that project (least privilege); the key
   lives only in the server environment (`DEEPGRAM_API_KEY`).
2. Console → Project → Settings: opt out of the **Model Improvement Program** (pay-as-you-go
   accounts are enrolled by default; the opt-out may carry a per-minute price premium — verify
   current terms on that page).
3. Read the current Deepgram DPA/terms; record the post-opt-out retention window for audio and
   transcripts. If it isn't effectively zero, contact Deepgram sales for a zero-retention
   addendum **before** setting `STT_PROVIDER=deepgram` in production.
4. Fill in the gate-status line above with the date and terms version.

## Threat model notes

- **Accessibility is powerful** — it can read any screen. We narrow this to on-tap only and document
  it prominently so Play review and users understand the scope.
- **Token storage** — the auth token is encrypted at rest with an Android Keystore key
  (`TokenVault`, AES/GCM) before landing in `SharedPreferences`.
- **Transport** — HTTPS only; certificate pinning is a Phase 3 hardening item.

## Play Store transparency

The Data Safety form must state exactly what is (and isn't) collected per mode. The Accessibility
usage disclosure must match the runtime behavior above: user-triggered, read-on-tap, no automation.

For voice: declare **"Voice or sound recordings"** as collected and processed ephemerally — not
stored, not used for advertising, shared only with a service provider (Deepgram) for processing,
and only on the Pro plan. Free-plan audio never leaves the device/OS speech stack, so no
collection is declared for it.
