package com.replymint.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log
import com.replymint.BuildConfig
import java.util.ArrayDeque
import java.util.Locale

/**
 * Continuous, user-controlled speech capture for the Voice action.
 *
 * Android's [SpeechRecognizer] is one-shot: it endpoints after a short silence, so a single
 * session cuts the user off mid-thought. We therefore run a chain of sessions. The old
 * implementation restarted the recognizer and let it re-open the microphone each time, which lost
 * 150-300 ms of audio per restart — reliably the first syllable after every pause.
 *
 * Now **we** own the microphone ([AudioPipeline]) and feed the recognizer through a pipe. The mic
 * never stops; only the recognizer downstream of it restarts, and frames captured during the
 * changeover are buffered and replayed into the next session. The audio is gapless.
 *
 * Devices that ignore `EXTRA_AUDIO_SOURCE` (API < 31, or an OEM engine that opens the mic anyway)
 * fall back to the old behaviour automatically, and the outcome is remembered per device in
 * [VoiceCapabilities] so the probe happens once, not every session.
 */
class VoiceInput(private val context: Context) {

    interface Listener {
        /** Live transcript (finalized segments + current partial) while listening. */
        fun onPartial(text: String)
        /** Mic level, 0..10, for the UI indicator. */
        fun onLevel(rms: Float)
        /**
         * Listening ended. [VoiceResult.text] is the full instruction and may be blank if
         * nothing was heard; the hypotheses and confidences alongside it are the extra signal
         * V2's screen-context correction consumes.
         *
         * Deliberately NOT overloaded with a `onFinal(String)` convenience taking a default
         * body: in `-Xjvm-default=disable` mode Kotlin failed to emit the `DefaultImpls` bridge
         * for the defaulted overload, leaving it abstract at runtime and throwing
         * AbstractMethodError on every recognition result.
         */
        fun onFinal(result: VoiceResult)
        /** Fatal error (e.g. no recognizer available). */
        fun onError(message: String)
    }

    private class Segment(
        val hypotheses: List<String>,
        val confidences: List<Float>,
        /** Recovered from a partial because the session ended abnormally — not an engine final. */
        val salvaged: Boolean = false,
    ) {
        val text: String get() = hypotheses.firstOrNull().orEmpty()
    }

    private val main = Handler(Looper.getMainLooper())
    private val caps = VoiceCapabilities(context)

    private var listener: Listener? = null
    private var pipeline: AudioPipeline? = null
    private var bridge: Bridge? = null
    private var session: RecognizerSession? = null
    private var feeder: PipeFeeder? = null

    private val segments = mutableListOf<Segment>()
    /**
     * Transcript heard but NOT yet committed to [segments]. Cleared at exactly two commit points:
     * the engine final, and [flushPartialToSegments]. Anything else that clears it loses speech.
     */
    private var lastPartial = ""
    private var stopped = false
    private var finished = false

    /** True once any piped session transcribed anything — proof this device honours the pipe. */
    private var pipeProven = false
    /** [AudioPipeline.loudFrames] as of the current session's start; the baseline for the delta. */
    private var sessionLoudFramesAtStart = 0L

    private var usingPipe = false
    private var onDevice = false
    /** Consecutive piped sessions that transcribed nothing from audio we know contained speech. */
    private var barrenPipedSessions = 0

    // Debug-build instrumentation. The whole point of V1 is that no audio is lost at a session
    // seam, and that is not something you can judge by ear — so the numbers that would prove it
    // go to logcat: `adb logcat -s ReplyMintVoice`.
    private var startedAtMs = 0L
    private var sessionIndex = 0
    private var sessionStartedAtMs = 0L
    private var sessionFirstPartialMs = -1L

    private val maxDurationRunnable = Runnable { stop() }

    fun start(listener: Listener) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onError("Voice input isn't available on this device")
            return
        }
        this.listener = listener
        stopped = false
        finished = false
        segments.clear()
        lastPartial = ""
        barrenPipedSessions = 0
        pipeProven = false
        sessionLoudFramesAtStart = 0L
        onDevice = caps.canUseOnDevice
        usingPipe = caps.canPipe
        startedAtMs = SystemClock.elapsedRealtime()
        sessionIndex = 0

        if (usingPipe && !startCapture()) usingPipe = false
        log("start · path=${if (usingPipe) "PIPE (we own the mic)" else "legacy (recognizer owns the mic)"}" +
            " · engine=${if (onDevice) "on-device" else "network"} · lang=${Locale.getDefault().toLanguageTag()}")
        main.postDelayed(maxDurationRunnable, MAX_DURATION_MS)
        beginSession()
    }

    /** User tapped stop, or an auto-stop fired. Finalize whatever we have. */
    fun stop() {
        if (stopped || finished) return
        stopped = true
        main.removeCallbacks(silenceWatch)
        main.removeCallbacks(maxDurationRunnable)

        if (usingPipe) {
            // Closing the write end is the EOF that ends the session — `stopListening()` is the
            // wrong lever here. Measured on device: the final result follows within ~400 ms.
            bridge?.detach()?.finish()
            feeder = null
            pipeline?.release()   // the user said stop; the mic indicator should go out now
            pipeline = null
        } else {
            session?.stopListening()
        }
        // Guarantee completion even if the recognizer never calls back.
        main.postDelayed({ finishAll() }, STOP_FALLBACK_MS)
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        endSession()
        bridge?.clear()
        bridge = null
        pipeline?.release()
        pipeline = null
        listener = null
    }

    // ---- Capture ----------------------------------------------------------------------------

    /** @return true if we now own the microphone. */
    private fun startCapture(): Boolean {
        val pipeline = AudioPipeline()
        val error = pipeline.start()
        if (error != null) {
            // Not fatal: the recognizer can still open the mic itself. If the problem is a missing
            // permission it will fail there too, and that error reaches the user.
            pipeline.release()
            return false
        }
        val bridge = Bridge()
        pipeline.addSink(bridge)
        pipeline.setLevelSink { level -> main.post { listener?.onLevel(level) } }

        this.pipeline = pipeline
        this.bridge = bridge
        main.postDelayed(silenceWatch, SILENCE_POLL_MS)
        return true
    }

    /**
     * Holds captured frames between recognition sessions.
     *
     * Attached to the pipeline once and kept for the whole utterance. While a session is running
     * it forwards straight to that session's pipe; between sessions it buffers, and the next
     * session receives the backlog first. This is the piece that makes restarts lossless.
     */
    private inner class Bridge : AudioPipeline.FrameSink {

        private var current: PipeFeeder? = null
        private val pending = ArrayDeque<ByteArray>()

        /** @return how many buffered frames were replayed — i.e. the audio a restart used to eat. */
        @Synchronized
        fun attach(next: PipeFeeder): Int {
            var replayed = 0
            while (pending.isNotEmpty()) {
                val frame = pending.removeFirst()
                next.offer(frame, 0, frame.size)
                frame.fill(0)
                replayed++
            }
            current = next
            return replayed
        }

        @Synchronized
        fun detach(): PipeFeeder? = current.also { current = null }

        @Synchronized
        override fun onFrame(pcm: ByteArray, length: Int) {
            current?.let { it.offer(pcm, 0, length); return }
            // No session right now. Keep a bounded backlog — if it ever overflows, something is
            // badly wrong downstream and dropping the oldest audio is the least-bad option.
            if (pending.size >= MAX_PENDING_FRAMES) pending.removeFirst().fill(0)
            pending.addLast(pcm.copyOf(length))
        }

        @Synchronized
        fun clear() {
            pending.forEach { it.fill(0) }
            pending.clear()
            current = null
        }
    }

    // ---- Recognition chain ------------------------------------------------------------------

    private fun beginSession() {
        if (stopped || finished) return
        val session = RecognizerSession(context, onDevice)
        this.session = session
        sessionIndex++
        sessionStartedAtMs = SystemClock.elapsedRealtime()
        sessionFirstPartialMs = -1L
        sessionLoudFramesAtStart = pipeline?.loudFrames ?: 0L

        if (usingPipe) {
            val stream = session.startPiped(SessionCallbacks())
            if (stream != null) {
                val feeder = PipeFeeder(stream)
                this.feeder = feeder
                val replayed = bridge?.attach(feeder) ?: 0
                // The headline number: frames captured while no recognizer was listening, now
                // handed to the new session instead of being thrown away.
                log("session $sessionIndex begin · replayed $replayed frames (${replayed * FRAME_MS} ms of audio recovered)")
                return
            }
            // Failing to start at all is far more likely the on-device engine being missing than
            // the pipe being refused — refusal shows up as a silent session, not a setup error.
            // Drop that first, and only give up the pipe if it happens again without it.
            endSession()
            if (dropOnDevice()) beginSession() else demoteFromPipe(persist = false)
            return
        }

        if (!session.startMic(SessionCallbacks())) {
            endSession()
            if (dropOnDevice()) beginSession()
            else fail("Voice input isn't available on this device")
        }
    }

    /** @return true if the on-device engine was in use and has now been given up on. */
    private fun dropOnDevice(): Boolean {
        if (!onDevice) return false
        caps.rejectOnDevice()
        onDevice = false
        return true
    }

    private fun endSession() {
        // Before anything is torn down: commit what this session heard. Every way a session can
        // end reaches here — a benign NO_MATCH during a pause, a mid-utterance demotion off the
        // pipe, a failed start, an outright release — so nothing downstream may assume the engine
        // delivered a final.
        flushPartialToSegments()
        bridge?.detach()
        feeder?.finish()
        feeder = null
        session?.destroy()
        session = null
    }

    /** A natural pause ended the session. Start the next one — no audio is lost meanwhile. */
    private fun restartSoon() {
        endSession()
        if (stopped || finished) { finishAll(); return }
        main.postDelayed({ beginSession() }, RESTART_DELAY_MS)
    }

    private inner class SessionCallbacks : RecognizerSession.Callbacks {

        override fun onPartial(text: String) {
            if (sessionFirstPartialMs < 0) {
                sessionFirstPartialMs = SystemClock.elapsedRealtime() - sessionStartedAtMs
                log("session $sessionIndex first partial at ${sessionFirstPartialMs}ms")
            }
            if (usingPipe) pipeProven = true
            if (isUtteranceReset(lastPartial, text)) {
                // The engine ended an utterance WITHOUT ending the session and started a new one.
                // Nothing else will ever commit the old text — endSession() is not reached until
                // the user taps stop — so it has to happen here or the utterance is lost.
                log("utterance reset after ${lastPartial.trim().length} chars · committing")
                flushPartialToSegments()
            }
            lastPartial = text
            emitPartial()
        }

        override fun onLevel(level: Float) {
            // Only reaches us on the fallback path; otherwise AudioPipeline measures it.
            if (!usingPipe) listener?.onLevel(level)
        }

        override fun onFinal(hypotheses: List<String>, confidences: List<Float>) {
            barrenPipedSessions = 0
            if (usingPipe && hypotheses.isNotEmpty()) pipeProven = true
            appendSegment(hypotheses, confidences)
            log(
                "session $sessionIndex final at ${SystemClock.elapsedRealtime() - sessionStartedAtMs}ms · " +
                    "\"${hypotheses.firstOrNull().orEmpty()}\" · ${hypotheses.size} hypotheses · " +
                    "conf=${confidences.take(3)} · fed=${feeder?.bytesWritten ?: 0}B dropped=${feeder?.dropped ?: 0}"
            )
            lastPartial = ""
            emitPartial()
            restartSoon()
        }

        override fun onFailed(code: Int) {
            val sawText = session?.sawText == true
            log("session $sessionIndex failed: ${errorName(code)} at " +
                "${SystemClock.elapsedRealtime() - sessionStartedAtMs}ms · sawText=$sawText · " +
                "fed=${feeder?.bytesWritten ?: 0}B pipe=${feeder?.brokenPipe ?: "ok"}")
            val benign = code == SpeechRecognizer.ERROR_NO_MATCH ||
                code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

            when {
                stopped -> { endSession(); finishAll() }

                usingPipe && pipeIgnored(code, sawText) -> demoteFromPipe(persist = !pipeProven)

                // No on-device model for this language — the network engine still has it.
                languageUnavailable(code) && dropOnDevice() -> restartSoon()

                !onDevice && networkFailed(code) && caps.canUseOnDevice -> {
                    // Offline, or Google's speech service is unreachable. Keep going on-device.
                    onDevice = true
                    restartSoon()
                }

                benign -> restartSoon()

                else -> fail("Voice error (${errorName(code)})")
            }
        }
    }

    /**
     * The documented failure mode: the engine ignores `EXTRA_AUDIO_SOURCE` and opens the mic —
     * which we are holding — so it transcribes nothing however much we feed it. Two consecutive
     * barren sessions, or an immediate client/audio error, is enough to conclude that.
     */
    private fun pipeIgnored(code: Int, sawText: Boolean): Boolean {
        // Once any piped session has produced text, this device demonstrably honours
        // EXTRA_AUDIO_SOURCE and no later barren session can be evidence against it.
        if (sawText || pipeProven) return false
        // Speech heard by OUR meter during THIS session — not "at any point since the mic opened".
        // heardSpeech is a whole-capture latch (the trailing-silence watchdog needs it that way),
        // so using it here made every silent gap after the first word look like the engine
        // refusing our pipe. Two of those permanently wrote pipe_rejected and downgraded the
        // device to the clipping legacy path.
        val loudThisSession = (pipeline?.loudFrames ?: 0L) - sessionLoudFramesAtStart
        val fedRealAudio = (feeder?.bytesWritten ?: 0L) >= MIN_PROBE_BYTES &&
            loudThisSession >= MIN_LOUD_FRAMES_PER_SESSION
        if (!fedRealAudio) return false
        if (code == SpeechRecognizer.ERROR_CLIENT || code == SpeechRecognizer.ERROR_AUDIO) return true
        return ++barrenPipedSessions >= 2
    }

    /**
     * Give up the pipe and continue on the legacy path, mid-utterance — the user keeps talking
     * and never sees this happen. [persist] records the verdict so later sessions on this device
     * skip the probe; a merely transient setup failure should not earn that.
     */
    private fun demoteFromPipe(persist: Boolean = true) {
        log("PIPE ABANDONED (persist=$persist) — falling back to the legacy mic path mid-utterance")
        if (persist) caps.rejectPipe()
        usingPipe = false
        barrenPipedSessions = 0
        main.removeCallbacks(silenceWatch)
        endSession()
        bridge?.clear()
        bridge = null
        // The mic must be free before the recognizer can take it.
        pipeline?.release()
        pipeline = null
        beginSession()
    }

    // ---- Auto-stop --------------------------------------------------------------------------

    /**
     * Trailing-silence detection from our own audio rather than the recognizer's endpointer,
     * which behaves differently on every OEM and is invisible to us. Only armed on the pipe path.
     */
    private val silenceWatch = object : Runnable {
        override fun run() {
            val pipeline = pipeline ?: return
            if (stopped || finished) return
            val quietFor = SystemClock.elapsedRealtime() - pipeline.lastLoudAtMs
            if (pipeline.heardSpeech && quietFor > TRAILING_SILENCE_MS) stop()
            else main.postDelayed(this, SILENCE_POLL_MS)
        }
    }

    // ---- Committing what was heard ----------------------------------------------------------

    /**
     * Move the current session's uncommitted transcript out of [lastPartial] into [segments].
     *
     * This is the fix for long dictation: a 2-3 s pause makes the engine endpoint with NO_MATCH,
     * and that path used to return through [restartSoon] without ever committing what it heard,
     * leaving the text in [lastPartial] to be overwritten by the next session's first partial.
     * Only the final utterance survived, because [stop] happens to reach [finishAll] directly.
     *
     * Called from [endSession] so every abnormal termination is covered by construction rather
     * than by remembering to patch each branch of [SessionCallbacks.onFailed].
     */
    private fun flushPartialToSegments() {
        val salvage = lastPartial.trim()
        lastPartial = ""                       // clear FIRST — combined() must not double-append
        if (salvage.isEmpty()) return
        if (appendSegment(listOf(salvage), emptyList(), salvaged = true)) {
            log("session $sessionIndex salvaged ${salvage.length} chars from a partial")
        }
    }

    /**
     * Append a segment, dropping any leading run of words that exactly repeats the tail of the
     * previous one.
     *
     * The trim is needed because [Bridge] deliberately replays the frames around a session seam,
     * so after a salvaged partial the next session can legitimately re-transcribe words already
     * committed. It is applied ONLY after a salvaged segment: an engine final is a clean boundary,
     * and trimming there would eat genuine repetition ("no, no, I meant Tuesday").
     *
     * Biased to under-trim. A missed duplicate costs a repeated word, which the model tolerates;
     * an over-trim silently deletes speech, which it cannot recover.
     *
     * @return true if anything was appended.
     */
    private fun appendSegment(
        hypotheses: List<String>,
        confidences: List<Float>,
        salvaged: Boolean = false,
    ): Boolean {
        val head = hypotheses.firstOrNull()?.trim().orEmpty()
        if (head.isEmpty()) return false

        val prev = segments.lastOrNull()
        if (prev == null || !prev.salvaged) {
            segments += Segment(hypotheses, confidences, salvaged)
            return true
        }
        if (normalize(head) == normalize(prev.text)) return false   // a straight re-flush

        val prevWords = normalize(prev.text).split(' ').filter { it.isNotEmpty() }
        val headRaw = head.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val headWords = headRaw.map { normalize(it) }
        var overlap = 0
        for (k in minOf(MAX_OVERLAP_WORDS, prevWords.size, headWords.size) downTo 1) {
            if (prevWords.takeLast(k) == headWords.take(k)) { overlap = k; break }
        }
        if (overlap == 0) {
            segments += Segment(hypotheses, confidences, salvaged)
            return true
        }
        val trimmed = headRaw.drop(overlap).joinToString(" ")
        if (trimmed.isEmpty()) return false
        log("dedup · dropped $overlap repeated word(s) at the session $sessionIndex seam")
        // Alternates no longer line up with a trimmed head, so they are dropped rather than
        // reported inaccurately. buildResult()'s single-segment guard makes this invisible.
        segments += Segment(listOf(trimmed), emptyList(), salvaged)
        return true
    }

    /**
     * Did the engine silently start a NEW utterance inside the same recognition session?
     *
     * On-device engines endpoint internally on a pause and reset their partial to just the new
     * words, without emitting a final or ending the session. Measured on a Nothing A059: a fully
     * recognised 53-character sentence was replaced by "Also" two seconds later, and the whole
     * three-sentence dictation finished as `1 segments · 1 sessions` holding only the last line.
     *
     * The hard part is telling that apart from an ordinary REVISION, which also shortens the
     * partial — the same log showed the engine re-emitting its text with a leading space, and
     * walking "42 000 rup" back to "42000". A revision keeps the beginning; a reset does not.
     * So: shorter than before AND sharing almost no prefix.
     */
    private fun isUtteranceReset(previous: String, next: String): Boolean {
        val before = previous.trim()
        val after = next.trim()
        if (before.isEmpty() || after.isEmpty()) return false
        if (after.length >= before.length) return false          // extension or re-emission
        val a = normalize(before)
        val b = normalize(after)
        var shared = 0
        while (shared < minOf(a.length, b.length) && a[shared] == b[shared]) shared++
        return shared < MIN_SHARED_PREFIX_CHARS
    }

    /** Lowercased, punctuation-free form used only for overlap comparison. */
    private fun normalize(text: String): String =
        text.lowercase(Locale.getDefault())
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    // ---- Completion -------------------------------------------------------------------------

    private fun finishAll() {
        if (finished) return
        finished = true
        val result = buildResult()
        val captured = (pipeline?.bytesCaptured ?: 0L) /
            (AudioPipeline.SAMPLE_RATE * AudioPipeline.BYTES_PER_SAMPLE).toDouble()
        log(
            "DONE after ${SystemClock.elapsedRealtime() - startedAtMs}ms · ${segments.size} segments · " +
                "${sessionIndex} sessions · ${"%.1f".format(captured)}s captured · " +
                "engine=${result.source.wire} · \"${result.text}\""
        )
        val listener = this.listener
        release()
        listener?.onFinal(result)
    }

    private fun fail(message: String) {
        if (finished) return
        finished = true
        val listener = this.listener
        release()
        listener?.onError(message)
    }

    private fun buildResult(): VoiceResult {
        val text = combined()
        // n-best only survives a single-segment utterance. Across segments the alternatives are
        // combinatorial, and a synthesized list would be worse than none — V2 must treat
        // hypotheses beyond the first as a bonus, never a guarantee.
        val single = segments.singleOrNull()?.takeIf { !it.salvaged && lastPartial.isBlank() }
        return VoiceResult(
            text = text,
            hypotheses = single?.hypotheses ?: listOfNotNull(text.takeIf { it.isNotBlank() }),
            confidences = single?.confidences.orEmpty(),
            source = if (onDevice) VoiceSource.NATIVE_ON_DEVICE else VoiceSource.NATIVE_NETWORK,
            lang = Locale.getDefault().toLanguageTag(),
        )
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private fun emitPartial() = listener?.onPartial(combined())

    private fun combined(): String {
        val sb = StringBuilder()
        segments.forEach { segment ->
            val s = segment.text.trim()
            if (s.isEmpty()) return@forEach
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(s)
        }
        val partial = lastPartial.trim()
        if (partial.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(partial)
        }
        return sb.toString().trim()
    }

    private fun languageUnavailable(code: Int): Boolean =
        code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
            code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED

    private fun networkFailed(code: Int): Boolean =
        code == SpeechRecognizer.ERROR_NETWORK ||
            code == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
            code == SpeechRecognizer.ERROR_SERVER ||
            code == SpeechRecognizer.ERROR_SERVER_DISCONNECTED

    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "microphone"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "server"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "rate limited"
        else -> "code $code"
    }

    /** Debug builds only — never logs transcribed speech in a release build. */
    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "ReplyMintVoice"
        /** One captured frame is 1280 bytes at 16 kHz mono PCM16. */
        const val FRAME_MS = AudioPipeline.FRAME_BYTES * 1000L /
            (AudioPipeline.SAMPLE_RATE * AudioPipeline.BYTES_PER_SAMPLE)

        /** Gap between sessions. Costs no audio now — the bridge buffers across it. */
        const val RESTART_DELAY_MS = 120L
        /** Measured worst case from EOF to final result was 387 ms; this is generous. */
        const val STOP_FALLBACK_MS = 1_500L
        const val TRAILING_SILENCE_MS = 5_000L
        const val SILENCE_POLL_MS = 250L
        const val MAX_DURATION_MS = 60_000L

        /** ~2 s of 16 kHz PCM16 — enough fed audio to call a silent result the engine's fault. */
        const val MIN_PROBE_BYTES = 64_000L
        /** ~600 ms of above-floor audio inside ONE session. A pause is silent; a refusal is not. */
        const val MIN_LOUD_FRAMES_PER_SESSION = 15
        /** Longest repeated run trimmed at a session seam. */
        const val MAX_OVERLAP_WORDS = 6
        /** Below this shared prefix, a shorter partial is a new utterance rather than a revision. */
        const val MIN_SHARED_PREFIX_CHARS = 6
        /** ~4 s of backlog between sessions; a normal changeover uses three or four frames. */
        const val MAX_PENDING_FRAMES = 100
    }
}
