package com.replymint.spike

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Feeds PCM into a pipe on its own thread.
 *
 * A pipe holds ~64 KB; once it is full, write() blocks until the reader drains it. That must
 * never happen on the mic thread or we would drop audio and corrupt the very measurement we
 * are taking — so frames are queued and written here instead. A stalled writer is itself the
 * headline signal: it means the recognizer is not reading the pipe.
 */
class PipeFeeder(private val out: OutputStream) {

    private val queue = ArrayBlockingQueue<ByteArray>(256)
    private val thread: Thread
    @Volatile private var closing = false

    @Volatile var bytesWritten: Long = 0
        private set
    /** Frames dropped because the queue backed up — i.e. the reader could not keep up. */
    @Volatile var dropped: Int = 0
        private set
    @Volatile var brokenPipe: String? = null
        private set
    @Volatile var lastProgressAt: Long = SystemClock.elapsedRealtime()
        private set

    init {
        thread = Thread({ drain() }, "spike-pipe").apply { start() }
    }

    /** Called from the mic thread — never blocks; a backed-up queue drops frames instead. */
    fun offer(pcm: ByteArray, length: Int) {
        if (closing) return
        if (!queue.offer(pcm.copyOf(length))) dropped++
    }

    /**
     * Blocking enqueue, for replaying a finite recording. Nothing may be dropped there, so this
     * applies backpressure instead. Must be called off the main thread.
     */
    fun feed(pcm: ByteArray, offset: Int, length: Int) {
        if (closing) return
        queue.put(pcm.copyOfRange(offset, offset + length))
    }

    private fun drain() {
        try {
            while (true) {
                val chunk = queue.poll(100, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    if (closing) break else continue
                }
                out.write(chunk)              // blocks here if the recognizer isn't draining
                bytesWritten += chunk.size
                lastProgressAt = SystemClock.elapsedRealtime()
            }
            out.flush()
        } catch (e: Exception) {
            brokenPipe = e.message ?: e::class.java.simpleName
        } finally {
            runCatching { out.close() }      // EOF — tells the recognizer the utterance ended
        }
    }

    /** True when the writer has made no progress for [ms] while work is still queued. */
    fun stalled(ms: Long): Boolean =
        queue.isNotEmpty() && SystemClock.elapsedRealtime() - lastProgressAt > ms

    /** Signal end-of-audio and wait briefly for the queue to flush. */
    fun finish() {
        closing = true
        runCatching { thread.join(2_000) }
    }
}

/**
 * SpeechRecognizer, optionally fed from a pipe instead of the microphone.
 *
 * Since API 31 `EXTRA_AUDIO_SOURCE` lets us hand the recognizer a file descriptor to read
 * audio from, rather than letting it open the mic. If that works, we can own the mic and
 * still get native transcripts — which is the entire premise of the production design.
 * Whether every OEM honours it is exactly what this spike answers.
 */
class PipedSpeech(
    private val context: Context,
    private val onDevice: Boolean,
    private val preferOffline: Boolean,
    private val segmented: Boolean = false,
) {

    data class Result(
        val transcript: String,
        /** Last live partial. Proof of transcription even when the final bundle is empty. */
        val partialText: String,
        val nBest: List<String>,
        val confidences: List<Float>,
        val firstPartialMs: Long?,
        val finalMs: Long?,
        val error: String?,
    ) {
        /** Any text at all, however it arrived. */
        val bestText: String get() = transcript.ifBlank { partialText }
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var readEnd: ParcelFileDescriptor? = null

    private var startedAt = 0L
    private var firstPartialMs: Long? = null
    private var lastPartial = ""
    private var done = false

    private val segments = mutableListOf<String>()
    private var segmentNBest: List<String> = emptyList()
    private var segmentScores: List<Float> = emptyList()

    private var onPartial: ((String) -> Unit)? = null
    private var onDone: ((Result) -> Unit)? = null

    private val timeout = Runnable { finish(error = "TIMEOUT — recognizer never returned a result") }

    /**
     * Create the recognizer and start listening. Must be called on the main thread.
     *
     * @param pipe when true we supply the audio and an [OutputStream] is returned; when false
     *             the recognizer opens the microphone itself (today's behaviour).
     * @return the stream to write PCM into, or null when [pipe] is false or setup failed.
     */
    fun start(
        pipe: Boolean,
        timeoutMs: Long,
        onPartial: (String) -> Unit,
        onDone: (Result) -> Unit,
    ): OutputStream? {
        this.onPartial = onPartial
        this.onDone = onDone

        val recognizer = createRecognizer()
        if (recognizer == null) {
            finish(error = "Could not create ${if (onDevice) "on-device" else "default"} recognizer")
            return null
        }
        this.recognizer = recognizer
        recognizer.setRecognitionListener(Listener())

        val intent = baseIntent()
        var stream: OutputStream? = null

        if (pipe) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                finish(error = "EXTRA_AUDIO_SOURCE needs API 31+, device is ${Build.VERSION.SDK_INT}")
                return null
            }
            val (read, write) = try {
                ParcelFileDescriptor.createPipe()
            } catch (e: Exception) {
                finish(error = "createPipe() failed: ${e.message}")
                return null
            }
            readEnd = read
            stream = ParcelFileDescriptor.AutoCloseOutputStream(write)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, SpikeAudio.ENCODING)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SpikeAudio.SAMPLE_RATE)
            // Second lever if plain audio-source mode is refused: in segmented-session mode the
            // recognizer reads until the audio is closed, rather than endpointing on silence.
            if (segmented) {
                intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }
        }

        startedAt = SystemClock.elapsedRealtime()
        runCatching { recognizer.startListening(intent) }.onFailure {
            finish(error = "startListening() threw: ${it.message}")
            return null
        }

        // Deliberately DO NOT close our copy of the read end here. startListening() is
        // asynchronous: SpeechRecognizer binds to the RecognitionService and delivers the intent
        // once the service connects, so the binder transfer that duplicates this descriptor into
        // the recognizer's process has usually not happened yet. Closing now drops the refcount
        // to zero, destroys the pipe, and every later write fails with EPIPE while the recognizer
        // receives a dead descriptor and reports ERROR_CLIENT within milliseconds.
        //
        // Holding a read-end copy does not delay EOF: the reader sees EOF when all *write* ends
        // close, which PipeFeeder does. We release it in destroy().
        main.postDelayed(timeout, timeoutMs)
        return stream
    }

    private fun createRecognizer(): SpeechRecognizer? = runCatching {
        if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }.getOrNull()

    private fun baseIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    /** Ask the recognizer to endpoint (used when the mic path has no EOF to send). */
    fun stopListening() {
        runCatching { recognizer?.stopListening() }
    }

    fun destroy() {
        main.removeCallbacks(timeout)
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { readEnd?.close() }
        readEnd = null
    }

    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = texts(partialResults).firstOrNull().orEmpty()
            if (text.isBlank()) return
            if (firstPartialMs == null) firstPartialMs = SystemClock.elapsedRealtime() - startedAt
            lastPartial = text
            onPartial?.invoke(text)
        }

        override fun onResults(results: Bundle?) {
            val texts = texts(results)
            val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.toList().orEmpty()
            // An empty final bundle does not mean nothing was heard — keep the last partial.
            finish(transcript = texts.firstOrNull() ?: lastPartial, nBest = texts, confidences = scores)
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            val texts = texts(segmentResults)
            if (texts.isNotEmpty()) {
                segments += texts.first()
                segmentNBest = texts
                segmentScores = segmentResults.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.toList().orEmpty()
            }
        }

        override fun onEndOfSegmentedSession() {
            val joined = segments.joinToString(" ").trim()
            finish(transcript = joined.ifBlank { lastPartial }, nBest = segmentNBest, confidences = segmentScores)
        }

        override fun onError(error: Int) {
            finish(error = "${errorName(error)} ($error)")
        }
    }

    private fun texts(bundle: Bundle?): List<String> =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.filter { it.isNotBlank() }.orEmpty()

    private fun finish(
        transcript: String = lastPartial,
        nBest: List<String> = emptyList(),
        confidences: List<Float> = emptyList(),
        error: String? = null,
    ) {
        if (done) return
        done = true
        main.removeCallbacks(timeout)
        val result = Result(
            transcript = transcript.trim(),
            partialText = lastPartial.trim(),
            nBest = nBest,
            confidences = confidences,
            firstPartialMs = firstPartialMs,
            finalMs = if (startedAt > 0) SystemClock.elapsedRealtime() - startedAt else null,
            error = error,
        )
        val callback = onDone
        onDone = null
        onPartial = null
        callback?.invoke(result)
    }

    companion object {
        fun onDeviceAvailable(context: Context): Boolean = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        }

        fun errorName(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
            SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE (model not downloaded)"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "CANNOT_CHECK_SUPPORT"
            else -> "UNKNOWN"
        }
    }
}
