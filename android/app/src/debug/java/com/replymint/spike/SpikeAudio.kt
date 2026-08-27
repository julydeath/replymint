package com.replymint.spike

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max

/**
 * The microphone, owned by us: 16 kHz mono PCM16 — the format every STT engine wants.
 *
 * This is the shape the production AudioPipeline will take: capture once, fan the same PCM
 * out to any number of consumers. The spike attaches two sinks (the recognizer pipe and an
 * in-memory buffer) to prove the fan-out works, and tracks enough stats to tell a real
 * capture apart from a silent one — which is how we detect the recognizer stealing the mic.
 */
class SpikeAudio {

    /** Frames arrive on the capture thread, never the main thread. */
    fun interface FrameSink {
        fun onFrame(pcm: ByteArray, length: Int)
    }

    private val sinks = mutableListOf<FrameSink>()
    private var recorder: AudioRecord? = null
    private var suppressor: NoiseSuppressor? = null
    private var thread: Thread? = null

    @Volatile private var running = false

    /** Loudest sample seen, 0..32767. Near zero means the mic handed us silence. */
    @Volatile var peak: Int = 0
        private set

    @Volatile var bytesCaptured: Long = 0
        private set

    /** Wall-clock capture window, for comparing against bytes to spot dropped audio. */
    @Volatile var elapsedMs: Long = 0
        private set

    fun addSink(sink: FrameSink) {
        synchronized(sinks) { sinks += sink }
    }

    /** @return null on success, or a human-readable reason the mic could not be opened. */
    fun start(): String? {
        if (running) return null

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING)
        if (minBuf <= 0) return "AudioRecord.getMinBufferSize failed ($minBuf)"

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_MASK,
                ENCODING,
                max(minBuf, FRAME_BYTES * 8)
            )
        } catch (e: SecurityException) {
            return "RECORD_AUDIO not granted"
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return "AudioRecord failed to initialize — mic busy or blocked"
        }

        // Best-effort cleanup; absent on some devices, which is fine.
        suppressor = runCatching { NoiseSuppressor.create(recorder.audioSessionId) }
            .getOrNull()?.apply { runCatching { enabled = true } }

        this.recorder = recorder
        peak = 0
        bytesCaptured = 0
        elapsedMs = 0
        running = true

        runCatching { recorder.startRecording() }.onFailure {
            stop()
            return "startRecording() failed: ${it.message}"
        }
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            stop()
            return "AudioRecord did not enter RECORDING state"
        }

        val startedAt = SystemClock.elapsedRealtime()
        thread = Thread({ pump(recorder, startedAt) }, "spike-mic").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return null
    }

    private fun pump(recorder: AudioRecord, startedAt: Long) {
        val buffer = ByteArray(FRAME_BYTES)
        while (running) {
            val read = recorder.read(buffer, 0, buffer.size)
            if (read <= 0) continue

            bytesCaptured += read
            elapsedMs = SystemClock.elapsedRealtime() - startedAt
            trackPeak(buffer, read)

            val snapshot = synchronized(sinks) { sinks.toList() }
            snapshot.forEach { runCatching { it.onFrame(buffer, read) } }
        }
    }

    /** PCM16 little-endian: every sample is two bytes, low byte first. */
    private fun trackPeak(buffer: ByteArray, length: Int) {
        var i = 0
        var localPeak = peak
        while (i + 1 < length) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val magnitude = abs(sample.toShort().toInt())
            if (magnitude > localPeak) localPeak = magnitude
            i += 2
        }
        peak = localPeak
    }

    fun stop() {
        running = false
        thread?.runCatching { join(500) }
        thread = null
        recorder?.let { rec ->
            runCatching { if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop() }
            runCatching { rec.release() }
        }
        recorder = null
        suppressor?.let { runCatching { it.release() } }
        suppressor = null
        synchronized(sinks) { sinks.clear() }
    }

    /** Seconds of audio implied by the bytes we actually received. */
    fun capturedSeconds(): Double = bytesCaptured.toDouble() / (SAMPLE_RATE * BYTES_PER_SAMPLE)

    companion object {
        const val SAMPLE_RATE = 16_000
        const val BYTES_PER_SAMPLE = 2
        const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** ~40 ms per frame — small enough for responsive partials, large enough to be cheap. */
        const val FRAME_BYTES = 1280
    }
}
