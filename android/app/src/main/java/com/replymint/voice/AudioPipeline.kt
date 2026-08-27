package com.replymint.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The microphone, owned by ReplyMint.
 *
 * Captured once as 16 kHz mono PCM16 — the format every STT engine wants — and fanned out to
 * however many consumers are attached. Today that is the recognizer pipe; V3 adds a WebSocket to
 * a cloud STT alongside it, from the same capture rather than a second one.
 *
 * Owning the mic is what makes the audio *gapless*. `SpeechRecognizer` endpoints on silence and
 * has to be restarted, and while it restarts it is not listening — which is where the old path
 * lost the first syllable of every sentence after a pause. Here the mic never stops; only the
 * recognizer downstream of it does, and [VoiceInput] buffers across the swap.
 *
 * Privacy: audio lives in RAM only, is never written to disk, and [release] zeroes every buffer
 * before dropping it.
 */
class AudioPipeline {

    /** Frames arrive on the capture thread, never the main thread. Do not block here. */
    fun interface FrameSink {
        fun onFrame(pcm: ByteArray, length: Int)
    }

    /** Level for the UI meter, 0..10 — also on the capture thread. */
    fun interface LevelSink {
        fun onLevel(level: Float)
    }

    private val sinks = mutableListOf<FrameSink>()
    private var recorder: AudioRecord? = null
    private var suppressor: NoiseSuppressor? = null
    private var thread: Thread? = null

    @Volatile private var running = false
    @Volatile private var levelSink: LevelSink? = null

    /**
     * Rolling window of the most recent audio, so a failed or disappointing transcription can be
     * retried — against a different engine, or the cloud — without asking the user to repeat
     * themselves. Allocated on [start], zeroed on [release].
     */
    private var ring: ByteArray? = null
    private var ringWrite = 0
    private var ringFilled = false
    private val ringLock = Any()

    /**
     * When we last heard something above the speech floor. [VoiceInput] uses this for its own
     * trailing-silence timer — owning the mic means we no longer depend on the recognizer's
     * endpointer, which differs per OEM and is invisible to us.
     */
    @Volatile var lastLoudAtMs: Long = 0L
        private set

    /** Whether anything above the speech floor has been heard at all since [start]. */
    @Volatile var heardSpeech: Boolean = false
        private set

    /**
     * Count of frames above the speech floor since [start]. Monotonic, and deliberately NOT reset
     * per recognition session — callers snapshot it and compare the delta, which is what makes
     * "was there speech during THIS session?" answerable.
     *
     * [heardSpeech] stays a whole-capture latch because the trailing-silence watchdog wants
     * exactly that; it is the wrong signal for per-session questions.
     */
    @Volatile var loudFrames: Long = 0L
        private set

    @Volatile var bytesCaptured: Long = 0L
        private set

    fun addSink(sink: FrameSink) {
        synchronized(sinks) { sinks += sink }
    }

    fun setLevelSink(sink: LevelSink?) {
        levelSink = sink
    }

    /** @return null on success, or a human-readable reason the mic could not be opened. */
    @SuppressLint("MissingPermission") // caller holds RECORD_AUDIO; SecurityException handled below
    fun start(): String? {
        if (running) return null

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING)
        if (minBuf <= 0) return "Microphone unavailable on this device"

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_MASK,
                ENCODING,
                maxOf(minBuf, FRAME_BYTES * 8)
            )
        } catch (e: SecurityException) {
            return "Microphone permission not granted"
        } catch (e: IllegalArgumentException) {
            return "Microphone does not support 16 kHz mono"
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return "Microphone is busy"
        }

        // Best-effort cleanup; missing on some devices, which is fine — the recognizer does its own.
        suppressor = runCatching { NoiseSuppressor.create(recorder.audioSessionId) }
            .getOrNull()?.apply { runCatching { enabled = true } }

        this.recorder = recorder
        bytesCaptured = 0
        heardSpeech = false
        loudFrames = 0
        lastLoudAtMs = SystemClock.elapsedRealtime()
        synchronized(ringLock) {
            ring = ByteArray(RING_BYTES)
            ringWrite = 0
            ringFilled = false
        }
        running = true

        runCatching { recorder.startRecording() }.onFailure {
            release()
            return "Could not start recording"
        }
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            release()
            return "Microphone is in use by another app"
        }

        thread = Thread({ pump(recorder) }, "replymint-mic").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return null
    }

    private fun pump(recorder: AudioRecord) {
        val buffer = ByteArray(FRAME_BYTES)
        while (running) {
            val read = recorder.read(buffer, 0, buffer.size)
            if (read <= 0) continue

            bytesCaptured += read
            measure(buffer, read)
            remember(buffer, read)

            // Copied out so a slow sink cannot see the next frame overwrite this one.
            val snapshot = synchronized(sinks) { sinks.toList() }
            snapshot.forEach { runCatching { it.onFrame(buffer, read) } }
        }
    }

    /** RMS of one frame, mapped to the 0..10 scale the bubble's level bar already expects. */
    private fun measure(buffer: ByteArray, length: Int) {
        var sumSquares = 0.0
        var i = 0
        var samples = 0
        while (i + 1 < length) {
            val sample = ((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort().toInt()
            sumSquares += (sample * sample).toDouble()
            samples++
            i += 2
        }
        if (samples == 0) return

        val rms = sqrt(sumSquares / samples)
        // -50 dBFS reads as silence, 0 dBFS as full scale.
        val dbfs = 20.0 * log10((rms / Short.MAX_VALUE).coerceAtLeast(1e-6))
        val level = ((dbfs + 50.0) / 5.0).coerceIn(0.0, 10.0).toFloat()

        if (dbfs > SPEECH_FLOOR_DBFS) {
            lastLoudAtMs = SystemClock.elapsedRealtime()
            heardSpeech = true
            loudFrames++
        }
        levelSink?.let { runCatching { it.onLevel(level) } }
    }

    private fun remember(buffer: ByteArray, length: Int) {
        synchronized(ringLock) {
            val ring = ring ?: return
            var offset = 0
            var remaining = length
            while (remaining > 0) {
                val chunk = min(remaining, ring.size - ringWrite)
                System.arraycopy(buffer, offset, ring, ringWrite, chunk)
                ringWrite += chunk
                offset += chunk
                remaining -= chunk
                if (ringWrite == ring.size) {
                    ringWrite = 0
                    ringFilled = true
                }
            }
        }
    }

    /**
     * The last [RING_SECONDS] of audio in chronological order, or an empty array if capture never
     * ran. The caller owns the copy and should zero it when finished.
     */
    fun recentAudio(): ByteArray = synchronized(ringLock) {
        val ring = ring ?: return ByteArray(0)
        if (!ringFilled) return ring.copyOf(ringWrite)
        ByteArray(ring.size).also {
            val tail = ring.size - ringWrite
            System.arraycopy(ring, ringWrite, it, 0, tail)
            System.arraycopy(ring, 0, it, tail, ringWrite)
        }
    }

    /** Stops capture and wipes every buffer. Safe to call more than once. */
    fun release() {
        running = false
        thread?.let { runCatching { it.join(500) } }
        thread = null

        recorder?.let { rec ->
            runCatching { if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop() }
            runCatching { rec.release() }
        }
        recorder = null

        suppressor?.let { runCatching { it.release() } }
        suppressor = null

        levelSink = null
        synchronized(sinks) { sinks.clear() }
        synchronized(ringLock) {
            ring?.fill(0)
            ring = null
            ringWrite = 0
            ringFilled = false
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_SAMPLE = 2

        /** ~40 ms per frame — responsive enough for live partials, large enough to stay cheap. */
        const val FRAME_BYTES = 1280

        /** Anything quieter than this counts as silence for the auto-stop timer. */
        private const val SPEECH_FLOOR_DBFS = -42.0

        private const val RING_SECONDS = 60
        private const val RING_BYTES = RING_SECONDS * SAMPLE_RATE * BYTES_PER_SAMPLE // ~1.9 MB
    }
}
