package com.replymint.spike

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.replymint.R
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale

/**
 * V0 spike — can we own the microphone and still get native SpeechRecognizer transcripts?
 *
 * The whole production design (one capture, fanned out to the native recognizer *and* cloud STT
 * *and* a replay buffer) rests on `RecognizerIntent.EXTRA_AUDIO_SOURCE` being honoured. It is
 * documented from API 31, but OEM recognizers vary, so this must be measured per device rather
 * than assumed.
 *
 * Three tests, in the order they should be run:
 *
 *  A · Control — the recognizer opens the mic itself (today's behaviour). Proves the device,
 *      the language model and the room are all fine, so a failure in B/C means something.
 *
 *  B · Replay — we record with our own AudioRecord, release the mic, and then feed the recording
 *      into the recognizer through the pipe while the room is silent. This is the airtight one:
 *      with no microphone open, a correct transcript can *only* have come from the pipe. That
 *      rules out the false pass where the recognizer quietly ignores the descriptor and listens
 *      to the room instead.
 *
 *  C · Live — mic and pipe at the same time, which is the exact production shape. Watches for
 *      the failure mode where the recognizer grabs the mic anyway and Android's concurrent-capture
 *      rules hand one of the two parties silence.
 *
 * Debug-only: this lives in src/debug, so it cannot ship in a release build.
 */
class SpikeActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())

    private lateinit var statusView: TextView
    private lateinit var reportView: TextView
    private lateinit var onDeviceBox: CheckBox
    private lateinit var offlineBox: CheckBox
    private lateinit var segmentedBox: CheckBox

    private val report = StringBuilder()
    private val verdicts = linkedMapOf<String, Boolean>()

    /** Set once any piped run yields text — the mechanism question, separate from finalization. */
    private var pipeProducedText = false

    private var audio: SpikeAudio? = null
    private var speech: PipedSpeech? = null
    private var feeder: PipeFeeder? = null
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_spike)

        statusView = findViewById(R.id.spike_status)
        reportView = findViewById(R.id.spike_report)
        onDeviceBox = findViewById(R.id.spike_on_device)
        offlineBox = findViewById(R.id.spike_offline)
        segmentedBox = findViewById(R.id.spike_segmented)

        button(R.id.spike_run_a) { runControl() }
        button(R.id.spike_run_b) { runReplay() }
        button(R.id.spike_run_c) { runLive() }
        button(R.id.spike_models) { checkModels() }
        button(R.id.spike_copy) { copyReport() }

        header()
        status("Ready. Run A, then B, then C — speak the sentence above each time.")

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
        main.removeCallbacksAndMessages(null)
    }

    // ---- Test A · Control: the recognizer owns the mic ------------------------------------

    private fun runControl() = guard("A · control (recognizer owns mic)") {
        val speech = PipedSpeech(this, onDeviceBox.isChecked, offlineBox.isChecked).also { speech = it }
        speech.start(
            pipe = false,
            timeoutMs = RECOGNIZER_TIMEOUT_MS,
            onPartial = { status("A · hearing: $it") },
            onDone = { result ->
                val ok = result.error == null && result.transcript.isNotBlank()
                finishTest(
                    key = "A",
                    name = "A · control",
                    pass = ok,
                    lines = transcriptLines(result) + listOf(
                        "meaning: ${if (ok) "device + recognizer + language model all work"
                            else "device path itself failed — fix this before trusting B or C"}"
                    )
                )
            }
        )
        countdown(CAPTURE_SECONDS, "A · speak now") { speech.stopListening() }
    }

    // ---- Test B · Replay: no mic open, audio can only come from the pipe -------------------

    private fun runReplay() = guard("B · replay through pipe") {
        val buffer = ByteArrayOutputStream()
        val audio = SpikeAudio().also { audio = it }
        audio.addSink { pcm, len -> buffer.write(pcm, 0, len) }

        audio.start()?.let { reason ->
            finishTest("B", "B · replay", pass = false, lines = listOf("mic error: $reason"))
            return@guard
        }

        countdown(CAPTURE_SECONDS, "B · phase 1/2 — speak now (we hold the mic)") {
            audio.stop()
            val pcm = buffer.toByteArray()
            val captured = "captured ${pcm.size} bytes (${"%.1f".format(audio.capturedSeconds())}s), peak ${audio.peak}/32767"

            if (audio.peak < SILENCE_PEAK) {
                finishTest(
                    "B", "B · replay", pass = false,
                    lines = listOf(captured, "meaning: our own mic capture was silent — check permissions/hardware")
                )
                return@countdown
            }
            replay(pcm, captured)
        }
    }

    /** Phase 2: the room is silent; every word the recognizer returns came out of the pipe. */
    private fun replay(pcm: ByteArray, captured: String) {
        status("B · phase 2/2 — STAY SILENT, replaying into recognizer…")

        val speech = PipedSpeech(this, onDeviceBox.isChecked, offlineBox.isChecked, segmentedBox.isChecked)
            .also { speech = it }
        val stream = speech.start(
            pipe = true,
            timeoutMs = RECOGNIZER_TIMEOUT_MS,
            onPartial = { status("B · pipe transcript: $it") },
            onDone = { result ->
                val fed = feeder
                // The mechanism question is "did the recognizer transcribe piped audio at all"
                // — a partial answers that. Clean finalization is a separate, lesser problem.
                val heardIt = result.bestText.isNotBlank()
                if (heardIt) pipeProducedText = true
                val ok = heardIt
                finishTest(
                    key = "B",
                    name = "B · replay through pipe",
                    pass = ok,
                    lines = buildList {
                        add(captured)
                        add("fed to pipe: ${fed?.bytesWritten ?: 0} bytes${if (fed?.brokenPipe != null) " (broken pipe: ${fed.brokenPipe})" else ""}")
                        addAll(transcriptLines(result))
                        add(
                            "meaning: " + when {
                                heardIt && result.error == null ->
                                    "EXTRA_AUDIO_SOURCE HONOURED, clean finish — no mic was open, so this text came from the pipe"
                                heardIt ->
                                    "EXTRA_AUDIO_SOURCE HONOURED (text came from the pipe with no mic open) but the session did not finalize cleanly: ${result.error}"
                                else ->
                                    "recognizer produced nothing from piped audio — this device needs the legacy fallback"
                            }
                        )
                    }
                )
            }
        ) ?: return

        val fed = PipeFeeder(stream).also { feeder = it }
        Thread({
            var offset = 0
            while (offset < pcm.size) {
                val len = minOf(SpikeAudio.FRAME_BYTES, pcm.size - offset)
                fed.feed(pcm, offset, len)
                offset += len
                // Real-time pacing: the endpointer reasons about audio timing, so a burst of
                // 8 seconds delivered in milliseconds is not a fair test of finalization.
                runCatching { Thread.sleep(FRAME_MS) }
            }
            fed.finish()          // closes the write end → EOF → recognizer finalizes
        }, "spike-replay").start()

        watchStall(fed) { speech.stopListening() }
    }

    // ---- Test C · Live: mic and pipe simultaneously (the production shape) -----------------

    private fun runLive() = guard("C · live mic + pipe") {
        val audio = SpikeAudio().also { audio = it }

        // Start the mic FIRST. The recognizer can fail within milliseconds, and if that tears the
        // capture down before it ever ran we would misread "no bytes" as "the mic was silenced".
        // Early frames land in a pre-roll buffer and are flushed once the pipe exists — which is
        // what production needs anyway, so the start of speech is never clipped.
        val preRoll = ByteArrayOutputStream()
        val live = AtomicReference<PipeFeeder?>(null)
        audio.addSink { pcm, len ->
            val target = live.get()
            if (target != null) target.offer(pcm, len)
            else synchronized(preRoll) { preRoll.write(pcm, 0, len) }
        }
        audio.start()?.let { reason ->
            finishTest("C", "C · live mic + pipe", pass = false, lines = listOf("mic error: $reason"))
            return@guard
        }

        val speech = PipedSpeech(this, onDeviceBox.isChecked, offlineBox.isChecked, segmentedBox.isChecked)
            .also { speech = it }

        val stream = speech.start(
            pipe = true,
            timeoutMs = RECOGNIZER_TIMEOUT_MS,
            onPartial = { status("C · hearing: $it") },
            onDone = { result ->
                val fed = feeder
                val ranLongEnough = audio.elapsedMs > MIN_JUDGE_MS
                val heardAudio = audio.peak >= SILENCE_PEAK
                val heardIt = result.bestText.isNotBlank()
                if (heardIt) pipeProducedText = true
                val ok = heardIt && heardAudio
                finishTest(
                    key = "C",
                    name = "C · live mic + pipe",
                    pass = ok,
                    lines = buildList {
                        add("our capture: ${audio.bytesCaptured} bytes (${"%.1f".format(audio.capturedSeconds())}s of ${"%.1f".format(audio.elapsedMs / 1000.0)}s wall), peak ${audio.peak}/32767")
                        add("fed to pipe: ${fed?.bytesWritten ?: 0} bytes, dropped frames: ${fed?.dropped ?: 0}${if (fed?.brokenPipe != null) " (broken pipe: ${fed.brokenPipe})" else ""}")
                        addAll(transcriptLines(result))
                        add(
                            "meaning: " + when {
                                ok && result.error == null -> "PRODUCTION SHAPE WORKS — we hold gapless raw audio AND get native transcripts from one capture"
                                ok -> "PRODUCTION SHAPE WORKS (raw audio + transcript from one capture) but the session did not finalize cleanly: ${result.error}"
                                !ranLongEnough -> "recognizer failed after only ${audio.elapsedMs}ms — too early to judge the mic; treat this as a recognizer error, NOT mic contention"
                                !heardAudio -> "we captured ${audio.elapsedMs}ms but it was silent — the recognizer took the mic instead of reading the pipe"
                                else -> "we got the audio but the recognizer returned nothing from the pipe"
                            }
                        )
                    }
                )
            }
        )
        if (stream == null) { audio.stop(); return@guard }

        val fed = PipeFeeder(stream).also { feeder = it }
        synchronized(preRoll) {
            val pending = preRoll.toByteArray()
            if (pending.isNotEmpty()) fed.offer(pending, pending.size)
            live.set(fed)
        }

        countdown(CAPTURE_SECONDS, "C · speak now (mic + pipe together)") {
            audio.stop()
            fed.finish()
            speech.stopListening()
        }
        watchStall(fed) {}
    }

    // ---- On-device language models --------------------------------------------------------

    /**
     * A missing offline model reports as LANGUAGE_UNAVAILABLE, which looks identical to "the pipe
     * failed". Check first so the spike cannot produce a false negative — and this is the same
     * call the shipping app will need to pre-download packs during onboarding.
     */
    private fun checkModels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            status("Model check needs API 33+ (device is ${Build.VERSION.SDK_INT})")
            return
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            status("No on-device recognizer on this device")
            return
        }
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        status("Checking on-device language support…")
        recognizer.checkRecognitionSupport(intent, mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                val installed = support.installedOnDeviceLanguages
                val pending = support.pendingOnDeviceLanguages
                section(
                    "on-device models",
                    listOf(
                        "locale: ${Locale.getDefault().toLanguageTag()}",
                        "installed: $installed",
                        "pending: $pending",
                        "supported (downloadable): ${support.supportedOnDeviceLanguages}",
                    )
                )
                if (installed.isEmpty()) {
                    status("No offline model installed — requesting download; retry in a few minutes")
                    runCatching { recognizer.triggerModelDownload(intent) }
                } else {
                    status("Offline model installed: $installed")
                }
                recognizer.destroy()
            }

            override fun onError(error: Int) {
                status("Model check failed: ${PipedSpeech.errorName(error)}")
                recognizer.destroy()
            }
        })
    }

    // ---- Plumbing -------------------------------------------------------------------------

    private fun guard(label: String, block: () -> Unit) {
        if (running) {
            Toast.makeText(this, "A test is already running", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        cleanup()
        running = true
        status("$label — starting…")
        block()
    }

    private fun countdown(seconds: Int, label: String, onZero: () -> Unit) {
        fun tick(left: Int) {
            if (left <= 0) { onZero(); return }
            status("$label — ${left}s")
            main.postDelayed({ tick(left - 1) }, 1_000)
        }
        tick(seconds)
    }

    /** A writer that stops making progress means the recognizer is not draining the pipe. */
    private fun watchStall(fed: PipeFeeder, onStalled: () -> Unit) {
        fun check() {
            if (!running) return
            if (fed.stalled(STALL_MS)) {
                section("pipe stall", listOf("writer blocked >${STALL_MS}ms with data queued — recognizer is not reading the descriptor"))
                onStalled()
                return
            }
            main.postDelayed({ check() }, 500)
        }
        main.postDelayed({ check() }, STALL_MS)
    }

    /** on-device vs network, and which levers were on — repeat runs are meaningless without this. */
    private fun config(): String = buildString {
        append(if (onDeviceBox.isChecked) "on-device" else "network")
        if (offlineBox.isChecked) append(", prefer-offline")
        if (segmentedBox.isChecked) append(", segmented")
    }

    private fun transcriptLines(result: PipedSpeech.Result): List<String> = buildList {
        add("transcript: ${result.transcript.ifBlank { "(empty)" }}")
        if (result.transcript.isBlank() && result.partialText.isNotBlank()) {
            add("last partial: ${result.partialText}   <- recognizer DID transcribe the piped audio")
        }
        if (result.nBest.size > 1) add("n-best: ${result.nBest.drop(1)}")
        if (result.confidences.isNotEmpty()) add("confidence: ${result.confidences}")
        add("first partial: ${result.firstPartialMs?.let { "${it}ms" } ?: "never"} · final: ${result.finalMs?.let { "${it}ms" } ?: "n/a"}")
        result.error?.let { add("error: $it") }
    }

    private fun finishTest(key: String, name: String, pass: Boolean, lines: List<String>) {
        running = false
        verdicts[key] = pass
        section("${if (pass) "PASS" else "FAIL"} · $name [${config()}]", lines)
        status("${if (pass) "PASS" else "FAIL"} · $name — see report below")
        cleanup()
    }

    private fun cleanup() {
        audio?.stop(); audio = null
        speech?.destroy(); speech = null
        feeder = null
    }

    private fun header() {
        report.setLength(0)
        report.appendLine("ReplyMint V0 spike — EXTRA_AUDIO_SOURCE")
        report.appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        report.appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        report.appendLine("locale: ${Locale.getDefault().toLanguageTag()}")
        report.appendLine("on-device recognizer available: ${PipedSpeech.onDeviceAvailable(this)}")
        report.appendLine()
        render()
    }

    private fun section(title: String, lines: List<String>) {
        report.appendLine("── $title")
        lines.forEach { report.appendLine("   $it") }
        report.appendLine()
        Log.i(TAG, "$title :: ${lines.joinToString(" | ")}")
        render()
    }

    private fun render() {
        reportView.text = report.toString() + verdict()
        reportView.scrollTo(0, 0)
    }

    /** The single line that decides whether V1 is built on the pipe or on the fallback. */
    private fun verdict(): String {
        val a = verdicts["A"]
        val b = verdicts["B"]
        val c = verdicts["C"]
        return "══ VERDICT: " + when {
            // The mechanism question is settled the moment piped audio produces any transcript.
            pipeProducedText && b == true && c == true ->
                "GO — pipe honoured on this device. Build V1 AudioPipeline."
            pipeProducedText ->
                "GO on the mechanism — piped audio IS transcribed here. Remaining work is session finalization, not feasibility."
            b == false && a == true ->
                "NO-GO on this device — recognizer ignores the pipe. Legacy path required here."
            b == false && a == false ->
                "INCONCLUSIVE — control failed too; fix the device/model first (try 'Check models')."
            else -> "run A, then B, then C"
        }
    }

    private fun button(id: Int, action: () -> Unit) {
        findViewById<Button>(id).setOnClickListener { action() }
    }

    private fun status(text: String) {
        statusView.text = text
        Log.i(TAG, text)
    }

    private fun copyReport() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("spike", reportView.text))
        Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "ReplyMintSpike"
        const val CAPTURE_SECONDS = 8
        const val RECOGNIZER_TIMEOUT_MS = 25_000L
        const val STALL_MS = 4_000L

        /** Peak below this (out of 32767) means we recorded silence, not speech. */
        const val SILENCE_PEAK = 500

        /** Below this much capture time, a silent mic tells us nothing — the recognizer just died first. */
        const val MIN_JUDGE_MS = 1_000L

        /** One 1280-byte frame of 16 kHz mono PCM16 is 40 ms of audio. */
        const val FRAME_MS = 40L
    }
}
