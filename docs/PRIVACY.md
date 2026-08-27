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

## Threat model notes

- **Accessibility is powerful** — it can read any screen. We narrow this to on-tap only and document
  it prominently so Play review and users understand the scope.
- **Token storage** — auth token in `SharedPreferences` for MVP; migrate to `EncryptedSharedPreferences`
  (`security-crypto`) before release.
- **Transport** — HTTPS only; certificate pinning is a Phase 3 hardening item.

## Play Store transparency

The Data Safety form must state exactly what is (and isn't) collected per mode. The Accessibility
usage disclosure must match the runtime behavior above: user-triggered, read-on-tap, no automation.
