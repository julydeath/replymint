package com.replymint.voice

import android.os.SystemClock
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Writes PCM into the recognizer's pipe on its own thread.
 *
 * A pipe holds about 64 KB; once full, `write()` blocks until the reader drains it. That must
 * never happen on the capture thread or we would drop microphone audio, so frames are queued
 * here instead. If the queue backs up, the recognizer has stopped reading — [dropped] and
 * [brokenPipe] are how [VoiceInput] notices and falls back.
 */
class PipeFeeder(private val out: OutputStream) {

    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_FRAMES)
    private val thread: Thread
    @Volatile private var closing = false

    @Volatile var bytesWritten: Long = 0L
        private set

    /** Frames discarded because the reader could not keep up. */
    @Volatile var dropped: Int = 0
        private set

    /** Non-null once the pipe broke — almost always the recognizer going away mid-session. */
    @Volatile var brokenPipe: String? = null
        private set

    init {
        thread = Thread({ drain() }, "replymint-pipe").apply { start() }
    }

    /** Called from the capture thread. Never blocks; a backed-up queue drops frames instead. */
    fun offer(pcm: ByteArray, offset: Int, length: Int) {
        if (closing) return
        if (!queue.offer(pcm.copyOfRange(offset, offset + length))) dropped++
    }

    private fun drain() {
        try {
            while (true) {
                val chunk = queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    if (closing) break else continue
                }
                out.write(chunk)
                bytesWritten += chunk.size
                chunk.fill(0)
            }
            out.flush()
        } catch (e: Exception) {
            brokenPipe = e.message ?: e::class.java.simpleName
        } finally {
            // Closing the write end is the EOF that ends the recognition session. Measured on
            // device: the recognizer returns its final result 233-387 ms later, every time.
            runCatching { out.close() }
        }
    }

    /**
     * Signal end-of-audio. Deliberately does not join: this is called from the main thread when
     * the user taps stop, and the drain thread flushes whatever is queued and closes the stream
     * on its own within [POLL_MS].
     */
    fun finish() {
        closing = true
    }

    private companion object {
        const val QUEUE_FRAMES = 256      // ~10 s of 40 ms frames
        const val POLL_MS = 50L
    }
}
