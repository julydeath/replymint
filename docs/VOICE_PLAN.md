# Voice Plan — Perfect Voice on Android, PC, Mac

Voice is now the first priority; Auto Reply comes after. Targets: **Android** (exists),
**Mac** and **Windows** (new). The goal in one line:

> **Speak naturally → perfect text lands in whatever box you're typing in, on any device.**

"Perfect" means two different things, and we build for both:
1. **Transcription accuracy** — what you said is what appears (dictation quality).
2. **Instruction accuracy** — "tell him I'll be late but keep it friendly" produces the
   *right message*, not a transcript. This is what Wispr Flow / Voicy don't do, and it
   depends on screen context, which only we capture.

---

## Part 1 — What already exists (and what it's worth)

| Piece | File | State |
|---|---|---|
| Voice capture loop (native `SpeechRecognizer`, partials, silence auto-stop, 60s cap) | `android/.../voice/VoiceInput.kt` | ✅ Works, but has the **segment-clipping flaw** (below) |
| Voice UI panel (live transcript, mic level, stop) | `bubble/BubbleOverlay.kt`, `voice_panel.xml` | ✅ Works |
| End-to-end voice action (screen + instruction → backend → draft in box) | `core/ReplyEngine.kt` | ✅ Works |
| Backend voice prompt | `backend/src/prompts.ts` (`taskFor: "voice"`) | ✅ Works, but only sees a single flat string |
| Provider switch (Anthropic / Ollama) | `backend/src/llm.ts` | ✅ Done |
| WhatsApp read/write loop | tested (A1) | ✅ Verified by hand |

Roughly **70% of the Android voice pipeline exists**. What's missing is the accuracy
layer — the difference between "works in a demo" and "perfect."

### The one bug worth naming: segment clipping

`VoiceInput` destroys and recreates the recognizer between segments
(`beginSegment()` → `recognizer?.destroy()`). Every restart loses ~150–300 ms of audio at
the seam, so words get swallowed mid-sentence on long instructions. No prompt or model
fixes lost audio. This is the first thing Part 2 removes.

---

## Part 2 — Android: from "works" to "perfect"

### V0 · Spike — `EXTRA_AUDIO_SOURCE`  ✅ **passed** (Nothing A059, run 3)

Everything below assumes **our app owns the microphone** and feeds the recognizer through
a pipe (`RecognizerIntent.EXTRA_AUDIO_SOURCE`, API 31+). The platform docs are explicit that
this is best-effort: *"if this extra is not set **or the recognizer does not support this
feature**, the recognizer will open the mic for audio."* It fails **silently**, by listening
to the room instead — so a naive test passes on a device where the feature does nothing.

**Code** (debug source set — verified absent from the release APK):

- `android/app/src/debug/java/com/replymint/spike/SpikeAudio.kt` — the mic, owned by us:
  16 kHz mono PCM16, fan-out to N sinks, peak/byte/duration stats. This is the prototype of
  the production `AudioPipeline`.
- `.../spike/PipedSpeech.kt` — `PipeFeeder` (queued pipe writer, stall detection) and
  `PipedSpeech` (recognizer with or without the audio-source pipe, n-best + confidence +
  latency capture).
- `.../spike/SpikeActivity.kt` + `src/debug/res/layout/activity_spike.xml` — the three tests
  and the report.
- Entry point: **Debug · mic-pipe spike** button on the main screen (debug builds only).

**The three tests**

| | What it does | Why |
|---|---|---|
| **A · Control** | Recognizer opens the mic itself (today's behaviour) | Proves device, language model and room are fine, so a B/C failure means something |
| **B · Replay** | We record with our `AudioRecord`, **release the mic**, then feed the recording through the pipe while the room is silent | **The airtight one.** No mic is open, so a correct transcript can only have come from the pipe — this is what rules out the silent-fallback false pass |
| **C · Live** | Mic and pipe simultaneously — the exact production shape | Catches the other failure mode: recognizer grabs the mic anyway and Android's concurrent-capture rules hand one party silence (detected via our own capture's peak amplitude) |

Test 0 (**Check / download on-device model**) runs first: a missing offline pack reports as
`LANGUAGE_UNAVAILABLE`, which is indistinguishable from "the pipe failed". It uses
`checkRecognitionSupport()` / `triggerModelDownload()` — the same calls V4 needs anyway.

**Runbook** (per device, ~10 minutes)

```bash
cd android && ./gradlew :app:installDebug
```

1. Open ReplyMint → **Debug · mic-pipe spike**. Grant the mic permission.
2. Tap **0 · Check / download on-device model**. If nothing is installed it requests a
   download — come back in a few minutes.
3. Run **A**, then **B**, then **C**, speaking the sentence on screen each time
   (*"Tell Sanjay the invoice for forty two thousand rupees is due on Friday"* — a name, a
   number and a weekday, i.e. precisely what native STT gets wrong and what V2 exists to fix).
   **In test B, stay silent during phase 2/2.**
4. Re-run B and C with **on-device recognizer** ticked (offline path).
5. Tap **Copy report** and paste it into the matrix below. Live logs:
   `adb logcat -s ReplyMintSpike`.

**Verdict, computed on screen**

| Outcome | Meaning |
|---|---|
| B pass + C pass | **GO** — build V1 `AudioPipeline` on the pipe |
| B pass, C fail | Pipe works but not beside a live mic — investigate concurrent capture |
| B fail, A pass | Recognizer ignores the pipe here — this device class needs the legacy path |
| B fail, A fail | Inconclusive — fix device/model first, re-run |

**Device matrix** — fill in as runs complete:

| Device | Android | A control | B replay | C live | On-device | Notes |
|---|---|---|---|---|---|---|
| Nothing A059 | 15 (API 35) | ✅ pass | ✅ **pipe honoured** | ✅ **pipe honoured** | en-GB installed | **GO** — run 3 clean sweep; on-device more accurate than network |
| Pixel | | | | | | |
| Samsung | | | | | | |
| Xiaomi/Oppo/Vivo | | | | | | |

#### Run log

**Run 1 — Nothing A059 / Android 15 — INVALID, harness bug (fixed).**
Reported NO-GO, but the numbers exonerate the device:

- `fed to pipe: 0 bytes (broken pipe: EPIPE)` — EPIPE means *no process holds the read end*.
  Had the recognizer received its duplicate of the descriptor, closing our own copy could not
  produce EPIPE. So the recognizer never got the pipe at all.
- `final: 14ms` — an `ERROR_CLIENT` far too fast to be audio processing.

Cause: `startListening()` is **asynchronous**. `SpeechRecognizer` binds to the
`RecognitionService` and delivers the intent once the service connects, so the binder transfer
that duplicates the descriptor into the recognizer's process had not happened yet when we closed
our copy — dropping the refcount to zero and destroying the pipe. Fixed by holding the read end
until `destroy()`; this does not delay EOF, which the reader sees when all *write* ends close.

Test C's "the recognizer took the mic" line was also wrong — a second harness bug. It started
the recognizer before the mic, so the 50 ms `ERROR_CLIENT` tore the capture down before it ran,
and `0 bytes` was misread as contention. Test C now starts the mic first (with a pre-roll buffer,
which production wants anyway so the start of speech is never clipped) and refuses to judge mic
contention unless capture ran for at least 1 s.

**Run 2 — Nothing A059 — MECHANISM PROVEN. `EXTRA_AUDIO_SOURCE` is honoured on this device.**

The on-screen verdict still read NO-GO, but that was the harness scoring only `onResults`. The
evidence for success is unambiguous:

- `fed to pipe: 257280 bytes` with **no EPIPE** — the descriptor fix worked; the recognizer is
  receiving and draining the pipe.
- **Partials at 608–869 ms on every run.** The recognizer was transcribing piped audio.
- Two runs returned complete transcripts, one of them
  **`"Tell Sanjay the invoice for 42 000 rupees is due on Friday"` — word-perfect, and better than
  the control**, which dropped "rupees" entirely.
- Test C: `256000 bytes captured, peak 2782, 8.0s of 8.0s wall` **and** a transcript — we held
  gapless raw audio and got native recognition from a single capture. That is the production shape
  working.

So V1 is unblocked. What remains is **session finalization**, not feasibility.

Two more harness bugs, both fixed:

1. `onSegmentResults()` / `onEndOfSegmentedSession()` are **default no-op methods** on
   `RecognitionListener`. In segmented-session mode the recognizer delivers results there rather
   than to `onResults` — not overriding them discarded the entire result and produced the 25 s
   timeouts. Now handled.
2. `onResults` sometimes arrives with an empty bundle after good partials; the harness reported
   "(empty)" and scored a failure. It now falls back to the last partial, and `Result` carries
   `partialText` so transcription is never mistaken for silence.

Also fixed: the report now stamps each run's configuration (`network`/`on-device`,
`prefer-offline`, `segmented`) into the section heading — seven identical "B · replay" blocks
could not be told apart — and the replay is fed at real time, since dumping 8 s of audio in
milliseconds gives the endpointer a burst it was never designed for.

**Open question for run 3:** which finalization path is reliable — segmented-session mode, or
plain mode with a partial-text fallback. Production may simply not need `onResults`: the last
partial is already the right text. The one thing `onResults` uniquely provides is the n-best list
and confidence scores that V2 wants, so it is worth one more round of testing before deciding.

**Product signal from test A** (valid, and useful): *"forty two thousand rupees"* came back as
*"the 42000"* — **"rupees" was dropped entirely** — and the n-best list offered *"chal Sanjay"*
for *"tell Sanjay"*, at 0.87 confidence. A dropped currency word in an invoice message is exactly
the class of error V2's screen-context correction exists to catch, and exactly the kind of thing
a dictation app has no way to recover. Worth keeping as an eval case.

**Run 3 — Nothing A059 — GO. All seven runs pass; the design questions are answered.**

| Config | 1st partial | final | Transcript | n-best | Confidence |
|---|---|---|---|---|---|
| A control · network, mic | 2231 ms | 5759 ms | drops "rupees" | 1 alt | 0.887 |
| B pipe · network | 2876 ms | 8387 ms | "dyn**wise**" ✗ | — | — |
| B pipe · network, segmented | 2341 ms | 8365 ms | ✅ exact | 3 alts | 0.88 |
| B pipe · **on-device** | **1549 ms** | 8262 ms | ✅ **+ "rupees"** | — | — |
| B pipe · on-device, segmented | 1307 ms | 8284 ms | "Do on Friday" ✗✗ | 2 alts | **0.0** |
| B pipe · on-device, segmented | 1430 ms | 8282 ms | ✅ exact | — | **0.0** |
| C live · network | 2696 ms | 8233 ms | "dyn**avoice**" ✗ | — | — |

Four conclusions, each of which decides a piece of V1:

1. **Finalization is deterministic and EOF-driven — segmented mode is not needed.** Every piped
   run finalized at 8233–8387 ms against exactly 8.0 s of audio: **233–387 ms after we closed the
   write end**, in every configuration. We control when the session ends by closing the pipe.
   `stopListening()` is the wrong lever on this path; `close()` is the right one.
2. **On-device beats network here, on both axes.** ~1 s faster to first partial (1307–1549 vs
   2341–2876 ms) *and* more accurate — it alone returned "rupees", and formatted "42 000" with
   capitalisation. Network turned "the invoice" into "dynwise"/"dynavoice" in two of three runs.
   That inverts the usual online-is-better assumption: **default to on-device where a model is
   installed**, and treat the network recognizer as the fallback, not the premium path.
3. **Confidence scores are unusable from the on-device engine** — all `0.0`. Only
   network + segmented returned real ones. So V2's correction must work from transcript + screen
   context alone, with n-best as optional enrichment. This kills the "confidence-gated correction"
   idea before it was built.
4. **On-device + segmented is the one unstable combination** — it lost 80% of an utterance
   ("Do on Friday"). Never ship it.

Also: test C reported `256000 bytes, 8.0s of 8.0s wall, dropped frames: 0` — the writer thread
keeps up with real-time capture with headroom to spare.

**Gate: passed on Nothing A059 / Android 15.** Pixel and Samsung still to fill in; a device that
fails simply keeps today's mic path, which V1 retains as a fallback and learns per device.

### V1 · `AudioPipeline` — one mic, many consumers — **BUILT**

```
AudioRecord (16 kHz mono PCM16, VOICE_RECOGNITION source)   AudioPipeline.kt
   ├─► Bridge ─► PipeFeeder ─► pipe ─► SpeechRecognizer     VoiceInput.kt / RecognizerSession.kt
   ├─► (V3) WebSocket ─► cloud STT
   └─► ring buffer (60 s ≈ 1.9 MB RAM)                      never written to disk
```

Shipped in `android/app/src/main/java/com/replymint/voice/`:

| File | Role |
|---|---|
| `AudioPipeline.kt` | The mic, owned by us. Fan-out sinks, level metering, 60 s ring buffer. |
| `PipeFeeder.kt` | Queued writer thread — a full pipe must never block the capture thread. |
| `RecognizerSession.kt` | One recognition session, piped or (fallback) mic-owned. |
| `VoiceCapabilities.kt` | Per-device verdicts, probed once and remembered. |
| `VoiceResult.kt` | Transcript **plus** n-best, confidences, source, language — V2's input. |
| `VoiceInput.kt` | Rewritten internals; public `Listener` unchanged, `BubbleOverlay` untouched. |

**Segment clipping is fixed.** `Bridge` is attached to the pipeline for the whole utterance: while
a session runs it forwards straight to that session's pipe, and between sessions it buffers, so
the next session receives the backlog first. The mic never stops — only the recognizer downstream
of it restarts. The 150–300 ms lost per restart is now zero.

Four things run 3 changed about the original design:

- **On-device is the preferred engine, not the offline consolation.** It was ~1 s faster to first
  partial and more accurate. `VoiceInput` starts there and falls back to the network engine only
  on `LANGUAGE_UNAVAILABLE`; it also switches *back* to on-device if the network engine fails,
  so losing connectivity mid-sentence is invisible to the user.
- **Stopping means closing the pipe, not `stopListening()`.** EOF is what finalizes the session,
  measured at 233–387 ms every time.
- **We do our own trailing-silence detection** from the RMS we already compute, instead of
  depending on an endpointer that differs per OEM and that we cannot observe.
- **No segmented-session mode.** It was measurably worse and buys nothing now that EOF finalizes.

**Device fallback is learned, not hardcoded.** The docs are explicit that an engine may ignore
`EXTRA_AUDIO_SOURCE` and open the mic instead. Rather than an allowlist we can never keep current,
the first session probes: if the recognizer transcribes nothing from a pipe we demonstrably filled
with audio that demonstrably contained speech, `VoiceCapabilities` records it and every later
session skips straight to the legacy path. The demotion happens mid-utterance — the user keeps
talking and never sees it.

Privacy: audio is RAM-only, every buffer is zeroed on release and after each write, and the mic
indicator shows *our* app rather than Google's.

**Verified on device (Nothing A059, 2026-08-27):** real dictation run with mid-sentence pauses —
no clipped syllables; bridge replay confirmed in logs. Pixel/Samsung fallback check still pending.

### V2 · Context-corrected transcription — **SHIPPED (2026-08-27)**

Implemented as designed below: `VoicePayload` (n-best + confidences + source + lang) travels
alongside the legacy `voiceInstruction`; backend renders a `[VOICE INSTRUCTION — raw speech
recognition]` block and the voice task instructs silent correction of names/numbers/currencies
against the screen (professional mode: Reply Brain names are the spelling authority). Verified
end-to-end: "sun jay" → Sanjay (from screen), dropped "rupees" → ₹42,000 restored.

Today we send one string. `SpeechRecognizer` gives us an n-best list + confidence scores;
we throw them away. Change the wire format:

```
// Dtos.kt / types.ts — replaces voiceInstruction: String
voice: {
  hypotheses: string[],      // n-best, first = best
  confidence: number[],      // parallel to hypotheses
  source: "native_offline" | "native_online" | "cloud",
  lang: string               // BCP-47, e.g. "en-IN"
}
```

Then in `prompts.ts`, the model resolves misrecognitions **using the conversation already
on screen** — "send it to sun jay" → thread says *Sanjay* → fixed. Add a glossary from the
Reply Brain (names, company, products) in professional mode.

Dictation apps cannot do this — they have no screen. **This is our accuracy moat and it
ships in the free tier.** (Keep `voiceInstruction` accepted server-side for old clients.)

### V3 · Cloud STT — the paid accuracy tier (~1–2 weeks)

**Status (2026-08-29): backend proxy shipped and smoke-tested** — `GET /v1/stt/stream`
WebSocket in `backend/src/server.ts`, provider switch in `stt.ts` (`deepgram` | `mock`),
Deepgram streaming client in `deepgram.ts`, per-user seconds metering in `usage_daily.stt_seconds`.
Verified end-to-end with the mock provider (`npm run stt:smoke`). Remaining: a Deepgram
account + key (+ zero-retention agreement — launch gate), real-audio WER check against the
gate below, and the Android dual-engine client.

- Provider: **Deepgram** streaming (lowest latency, keyword boosting, ~$0.005/min).
  Same pattern as `llm.ts`: an `stt.ts` with `STT_PROVIDER` switch so we can swap.
- Route: device → **backend WebSocket proxy** → Deepgram. Keys stay server-side, usage
  metering is trivial. (Later: short-lived direct tokens if proxy latency ever matters.)
- **Dual-engine UX**: native partials paint the panel instantly; cloud transcript arrives
  a beat later and wins if it differs. If the draft was already written from the native
  transcript and the cloud one materially differs, silently regenerate and replace the
  draft in the box. For dictation apps a changing transcript is a bug; for us the
  transcript is an intermediate — the *draft* is the product.
- Contract requirement: **zero-retention agreement** with the STT vendor, or the privacy
  promise in README/PRIVACY.md is false the day this ships.

### V4 · Offline honesty (~3 days)

Offline STT gives a transcript, not a reply (the LLM is server-side). Degrade honestly:

- Onboarding: `checkRecognitionSupport()` + `triggerModelDownload()` (API 33+) to
  pre-pull the user's offline language pack — otherwise "offline" silently fails.
- No internet → **dictation mode**: transcribe on-device, write the raw text into the box,
  label it "offline — dictated as-is."
- Bonus (availability-gated, AICore devices): on-device Fix via ML Kit GenAI Proofreading —
  "Fix works with no internet and nothing leaves your phone."

### V5 · Voice UX perfection (~1 week, parallelizable)

- One smart intent: typed text + voice → voice-guided fix; nothing typed → instruction/dictation.
- Voice-edit-in-place: draft already in box + voice again → "shorter", "less formal".
- Self-correction honored in-prompt: "no, scratch that" edits, it doesn't transcribe.
- Panel: waveform, editable transcript before send, retry last recording (from ring buffer).

---

## Part 3 — Desktop (Mac, then Windows)

Desktop dictation is *simpler* than Android — no accessibility service approval, no
overlay permission. The loop is:

```
global hotkey → record mic → stream to backend STT proxy → (optional LLM pass)
             → insert text into focused field (simulated keystrokes / clipboard-paste)
```

### Decisions

- **Stack: one cross-platform codebase — Tauri** (Rust core, tiny footprint, tray/menu-bar
  app). Native mic capture + global hotkey via Rust; text insertion via
  macOS Accessibility API (needs the user to grant Accessibility permission, same as
  Wispr) and Windows `SendInput`/UIA.
- **Mac first.** Wispr proved the buyer lives there; Windows follows with the same core.
- **Same backend.** The STT proxy (V3) and `/v1/reply` serve all platforms — build once.
- **Context still wins on desktop**: macOS AX API / Windows UI Automation can read the
  focused window's visible text. Same trick as Android's screen read → context-corrected
  transcription and instruction mode work on desktop too. Wispr is blind there; we're not.

### Order

**Status (2026-08-29): D1 scaffolded in `desktop/`** — Tauri tray app builds and runs:
global hotkey (⌥Space) → cpal mic capture → 16kHz resample → WS to `/v1/stt/stream` →
clipboard-paste into the focused field. Headless `cargo run -- smoke` verified against the
backend mock provider. Auth is a pasted dev token (`backend/scripts/dev-token.ts`) until
accounts (A3). Remaining for D1: on-Mac hotkey/mic/paste run-through with permissions, and
real STT once the Deepgram key exists.

1. **D1** Mac tray app: hotkey → record → cloud STT → paste into focused field. (Dictation parity with Wispr.)
2. **D2** Focused-window context read → context correction + instruction mode. (Differentiation.)
3. **D3** Windows port of the same core.

Prerequisite for both: **accounts** (Backlog A3) — one subscription across devices is the
whole point of multi-platform.

---

## Part 4 — Measurement (perfection is a number, not a feeling)

You cannot perfect what you don't measure. Before V2 lands, build the eval harness:

1. **Voice test set**: 150–200 recorded clips with ground-truth text. Must include:
   Indian-English, Hinglish/code-switch, names, numbers/dates/prices, noisy background,
   long instructions with pauses.
2. **WER script**: run every clip through native vs cloud vs context-corrected; track word
   error rate per engine per release. Regressions block release.
3. **Instruction set**: ~50 (screen context + spoken instruction → expected reply intent)
   cases; LLM-judged pass/fail.
4. **The product KPI: sent-as-is rate** — % of drafts sent without a single edit
   (measurable on-device via accessibility diff of the box at send time). This is the
   number that goes in marketing when it clears ~70%.

---

## Build order & rough timeline

| Step | What | Time | Gate |
|---|---|---|---|
| V0 | `EXTRA_AUDIO_SOURCE` device spike | ✅ done | Passed on Nothing A059; Pixel/Samsung pending |
| V1 | `AudioPipeline` (fixes clipping) | ✅ verified | Gapless with pauses on Nothing A059 |
| — | Eval harness (Part 4, parallel) | 1 wk | WER baseline recorded |
| V2 | N-best + screen-context correction | ✅ shipped | Name/currency correction verified on device |
| V3 | Cloud STT proxy + dual-engine (paid) | backend ✅ · client pending | Cloud beats native WER; <1.5s final transcript |
| V4 | Offline honesty | 3 days | Airplane-mode dictation works |
| V5 | Voice UX (smart intent, edit-in-place) | 1 wk | — |
| D1 | Mac tray app (dictation parity) | scaffolded ✅ · on-Mac run-through pending | Hotkey→text in any Mac app |
| D2 | Mac context read (differentiation) | 1–2 wk | Instruction mode works on Mac |
| D3 | Windows port | 2–3 wk | — |

Android voice reaches "perfect" in ~5–6 weeks; Mac lands ~4 weeks after that; Windows
~3 weeks after Mac. Auto Reply work resumes post-V5 — everything above (screen context,
pipeline, evals) feeds directly into it, nothing is throwaway.
