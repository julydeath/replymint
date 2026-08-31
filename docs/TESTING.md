# ReplyMint — Manual Test Checklist

One box = one test. Test on a real device/machine, against prod unless noted.
Date tested + result next to each box is enough; file bugs in BACKLOG.md.

---

## 1 · Android — bubble & accessibility core

### Bubble lifecycle
- [ ] Bubble appears when a text field is focused; hidden on screens with no editable field
- [ ] Bubble disappears when keyboard closes / you leave the app
- [ ] Bubble survives switching between two chat apps back-to-back
- [ ] Bubble reappears after the accessibility service is toggled off → on
- [ ] Bubble reappears after device reboot (service auto-restarts, no re-onboarding)
- [ ] Bubble works after the app is swiped away from recents (service keeps hosting it)
- [ ] Drag bubble to every screen edge/corner — stays grabbable, never off-screen
- [ ] Open the action menu with the bubble at the very bottom / very top — menu fully on-screen
- [ ] Rotate to landscape with bubble visible — position sane, menu usable
- [ ] Dark mode + light mode — bubble and menu legible in both
- [ ] Bubble does not appear over password fields / lock screen / system UI

### Per-app core loop (repeat this block for: WhatsApp, Gmail compose, Instagram DM, LinkedIn messages, Telegram, Google Messages/SMS, Slack)
- [ ] **Auto Reply**: reads the conversation, draft lands in the correct reply box
- [ ] **Fix**: rewrites text already typed in the box (typos fixed, meaning kept)
- [ ] **Voice**: dictated text is inserted at the field
- [ ] Draft goes into the *compose* box, not a search bar or other field on screen
- [ ] Incoming vs outgoing messages: reply responds to *their* last message, not your own
- [ ] Empty/new conversation (no messages yet) — graceful message, no crash
- [ ] Screen with no reply box (e.g. channel you can't post in) — clear "no reply box" feedback
- [ ] Very long conversation (100+ messages) — reasonable draft, no truncation crash
- [ ] Conversation with emojis / mixed language / non-Latin script — draft is coherent
- [ ] Existing text in the box + Auto Reply — defined behavior (replace or append), no garbled merge

### Failure & feedback
- [ ] Airplane mode → any action: clear error pill, no hang, no crash
- [ ] Backend slow (cold start) — progress indicator shows, then result or timeout message
- [ ] Two rapid taps on an action — no double request / double insert
- [ ] Overlay pill feedback shows even with system notifications/toasts disabled

## 2 · Android — auth, limits, usage

- [ ] Fresh install → onboarding → Google sign-in succeeds (correct account picker)
- [ ] Cancel sign-in mid-flow — app returns to onboarding cleanly, retry works
- [ ] Sign out → all actions blocked → sign back in works
- [ ] Expired/revoked token: 401 → auth cleared → sent to onboarding (simulate by deleting token row in Supabase)
- [ ] Free daily limit reached → 429 → friendly "limit reached" message (not raw error)
- [ ] Usage card on home matches actual request count (`/v1/me`)
- [ ] Exempt email (FREE_LIMIT_EXEMPT_EMAILS) is not capped
- [ ] Uninstall → reinstall → sign in again — same account, usage continues (server-side count intact)
- [ ] Sign in on Android + Mac with the same Google account — one user, combined usage/trial

## 3 · Voice / dictation (Android native)

- [ ] Quiet room, clear speech — accurate transcript with punctuation
- [ ] Noisy background (music/TV) — degrades gracefully, no crash
- [ ] Accented speech / proper nouns — context correction improves the raw transcript
- [ ] Long dictation (60s+) — no cutoff or memory issue
- [ ] Pause mid-sentence then continue — one coherent transcript
- [ ] Cancel mid-dictation — nothing inserted
- [ ] Mic permission denied — clear prompt to grant, no crash
- [ ] Dictate in a second language (if supported) or note behavior

## 4 · macOS desktop

### Sign-in (loopback OAuth)
- [ ] Fresh sign-in: browser opens, approve, app shows signed-in state
- [ ] Cancel/close the browser tab mid-flow — app recovers, retry works
- [ ] Sign out → sign in as a different Google account
- [ ] Quit + relaunch — still signed in (token persisted)
- [ ] Token deleted server-side → next request 401 → app prompts re-sign-in

### Dictation & assistant
- [ ] Dictate into: Notes, a browser text field (Gmail), Slack, VS Code — text lands at the cursor
- [ ] Dictate into a field, app in background focus edge cases — no insert into wrong app
- [ ] `dictate` polish action produces cleaned text (vs raw)
- [ ] Assistant with screen context — answer uses on-screen content
- [ ] Assistant with **no** context available — fallback works (no error, still answers)
- [ ] Warn-vs-error UI: warnings styled as warnings, hard failures as errors
- [ ] Mic permission denied in System Settings — clear guidance shown
- [ ] Accessibility permission denied — clear guidance, insert fails gracefully
- [ ] Hotkey works in fullscreen apps; no conflict with common shortcuts
- [ ] 50/day free cloud dictation cap: hit it → clear limit message; exempt email unlimited
- [ ] Cloud STT stream cut mid-dictation (drop Wi-Fi) — partial text or clean error, no hang

### App lifecycle
- [ ] DMG installs on a clean Mac (note current Gatekeeper friction — see signing task)
- [ ] Auto-launch/menu-bar behavior after reboot (whatever is configured)
- [ ] Settings persist across relaunch (mint settings UI)

## 5 · Windows desktop (on-Windows verify still pending)

- [ ] NSIS installer: install on clean Win 10 and Win 11 → app launches
- [ ] Upgrade install over previous version — settings/token kept
- [ ] Uninstall removes the app cleanly
- [ ] Sign-in loopback flow works (default browser opens, redirect completes)
- [ ] Dictate into: Notepad, Word, Chrome text field, an Electron app (Slack/Discord) — UIA insert works
- [ ] Field with no UIA text pattern — graceful fallback (clipboard paste or clear error)
- [ ] Hotkey works, doesn't clash with Windows shortcuts
- [ ] Mic privacy setting off → clear guidance
- [ ] Free-tier 50/day cap message
- [ ] High-DPI / scaled display (125–200%) — UI not blurry or clipped
- [ ] Works without admin rights

## 6 · Backend (curl/Postman against prod)

- [ ] `POST /v1/reply` without token → 401
- [ ] `POST /v1/reply` with garbage token → 401 (not 500)
- [ ] Valid request → reply; check latency cold vs warm
- [ ] Over free limit → 429 with the expected body
- [ ] Malformed JSON body → 400 (not crash)
- [ ] Very large conversation payload → handled (413 or trimmed, no OOM)
- [ ] `LLM_PROVIDER` switch: anthropic path and ollama path both return clean drafts
- [ ] Anthropic out-of-credit / provider 5xx → clean error to client (not a hang → app timeout)
- [ ] `GET /v1/me` returns correct usage + tier for the token's user
- [ ] `POST /v1/auth/google` with expired Google ID token → 401
- [ ] Desktop code exchange `POST /v1/auth/google/desktop` with reused/invalid code → error, no token minted
- [ ] Two requests at the same instant at limit-1 — cap not bypassed (usage race)
- [ ] STT proxy: daily dictation count increments; cap enforced; exempt email bypasses
- [ ] Usage day rollover (UTC?) — counter resets when expected

## 7 · Website & distribution

- [ ] macOS visitor sees Mac download; Windows visitor sees Windows download (platform-aware nav)
- [ ] Mac DMG link downloads the current release; Windows link hits `windows-latest` stable URL
- [ ] All nav/footer links work; site looks right on mobile
- [ ] hello@replymint.app receives mail (send yourself one)
- [ ] Downloaded artifacts open (DMG mounts; installer runs) on machines that never built the project

---

**Priority order if time-boxed:** §1 per-app loop on WhatsApp + Gmail → §2 auth/limits → §4 Mac dictation →
§6 backend error paths → the rest.
