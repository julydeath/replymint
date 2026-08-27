package com.replymint.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.OutputStream
import java.util.Locale

/**
 * One recognition session, fed either from our pipe or — on devices that refuse the pipe — from
 * the microphone the recognizer opens itself.
 *
 * Since API 31, `EXTRA_AUDIO_SOURCE` lets us hand the recognizer a file descriptor to read audio
 * from instead of it taking the mic. The V0 spike confirmed it is honoured (see VOICE_PLAN.md),
 * which is what lets us hold the raw audio *and* get native transcripts from a single capture.
 *
 * Two behaviours the spike pinned down and this class depends on:
 *  - The session ends when the write end of the pipe closes, not when `stopListening()` is called.
 *  - Plain mode is correct; segmented-session mode was measurably worse on-device, losing most of
 *    an utterance in one run, and buys nothing since EOF already finalizes cleanly.
 */
class RecognizerSession(
    private val context: Context,
    private val onDevice: Boolean,
) {

    interface Callbacks {
        fun onPartial(text: String)
        /** Mic level, 0..10. Only fires on the fallback path — otherwise [AudioPipeline] has it. */
        fun onLevel(level: Float)
        fun onFinal(hypotheses: List<String>, confidences: List<Float>)
        fun onFailed(code: Int)
    }

    private var recognizer: SpeechRecognizer? = null
    private var readEnd: ParcelFileDescriptor? = null
    private var callbacks: Callbacks? = null

    private var lastPartial = ""
    private var done = false

    /** True once the recognizer has produced any text — the proof it is reading our pipe. */
    var sawText: Boolean = false
        private set

    /**
     * Start a session that reads from a pipe we own.
     *
     * @return the stream to write PCM into, or null if the session could not be started.
     */
    fun startPiped(callbacks: Callbacks): OutputStream? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val recognizer = attach(callbacks) ?: return null

        val (read, write) = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull()
            ?: return null
        readEnd = read

        val intent = baseIntent().apply {
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioPipeline.ENCODING)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AudioPipeline.SAMPLE_RATE)
        }

        val stream = ParcelFileDescriptor.AutoCloseOutputStream(write)
        if (!begin(recognizer, intent)) {
            runCatching { stream.close() }
            return null
        }

        // Deliberately keep our copy of the read end open. startListening() is asynchronous: the
        // binder transfer that duplicates this descriptor into the recognizer's process has
        // usually not happened yet, and closing now would drop the refcount to zero and destroy
        // the pipe under it. Holding it does not delay EOF, which the reader sees when every
        // *write* end closes. Released in destroy().
        return stream
    }

    /** Fallback for devices that ignore `EXTRA_AUDIO_SOURCE`: the recognizer opens the mic. */
    fun startMic(callbacks: Callbacks): Boolean {
        val recognizer = attach(callbacks) ?: return false
        return begin(recognizer, baseIntent().apply {
            // Only meaningful when the recognizer owns the endpointer; on the pipe path we do.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
        })
    }

    /** Ask the recognizer to endpoint. On the pipe path, close the stream instead. */
    fun stopListening() {
        runCatching { recognizer?.stopListening() }
    }

    fun destroy() {
        callbacks = null
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { readEnd?.close() }
        readEnd = null
    }

    // ---- Internals ------------------------------------------------------------------------

    private fun attach(callbacks: Callbacks): SpeechRecognizer? {
        this.callbacks = callbacks
        val recognizer = create() ?: return null
        this.recognizer = recognizer
        runCatching { recognizer.setRecognitionListener(Listener()) }
        return recognizer
    }

    private fun create(): SpeechRecognizer? = runCatching {
        if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }.getOrNull()

    private fun begin(recognizer: SpeechRecognizer, intent: Intent): Boolean =
        runCatching { recognizer.startListening(intent) }.isSuccess

    private fun baseIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // The alternatives V2's context correction reads. Free to ask for; some engines
            // return only one, which is why nothing may depend on getting more.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }

    private inner class Listener : SimpleRecognitionListener() {
        override fun onRmsChanged(rmsdB: Float) {
            callbacks?.onLevel(rmsdB.coerceIn(0f, 10f))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // A final already went out for this session — a late partial must not resurrect
            // lastPartial, or the text gets committed twice.
            if (done) return
            val text = texts(partialResults).firstOrNull().orEmpty()
            if (text.isBlank()) return
            sawText = true
            lastPartial = text
            callbacks?.onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            val texts = texts(results)
            // An empty final bundle does not mean nothing was heard — the spike saw good partials
            // followed by an empty result more than once. Keep what we already have.
            val hypotheses = texts.ifEmpty { listOfNotNull(lastPartial.takeIf { it.isNotBlank() }) }
            if (hypotheses.isNotEmpty()) sawText = true
            finish {
                it.onFinal(
                    hypotheses,
                    results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.toList().orEmpty()
                )
            }
        }

        override fun onError(error: Int) {
            finish { it.onFailed(error) }
        }
    }

    private fun texts(bundle: Bundle?): List<String> =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private fun finish(emit: (Callbacks) -> Unit) {
        if (done) return
        done = true
        callbacks?.let(emit)
    }

    companion object {
        /**
         * Whether an on-device model is installed and usable. The spike measured it as both
         * faster (~1 s sooner to first partial) and more accurate than the network recognizer,
         * so it is the preferred engine, not just the offline one.
         */
        fun onDeviceAvailable(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
                    .getOrDefault(false)
    }
}
