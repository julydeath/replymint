package com.replymint.voice

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.replymint.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * The cloud half of the V3 dual engine: a second [AudioPipeline.FrameSink] on the same mic
 * capture, streaming PCM to the backend `/v1/stt/stream` proxy (→ Deepgram) while the native
 * recognizer runs unchanged beside it.
 *
 * Design rule: **cloud is an enhancer, never a blocker.** Any failure here — connect timeout,
 * mid-stream error, `pro_required` — marks this instance dead and the native path proceeds as
 * if it never existed. [VoiceInput] waits only briefly for [Listener.onDone] at the end.
 *
 * Threading: [onFrame] arrives on the capture thread and must not block — okhttp's WebSocket
 * queues sends internally, so it's a cheap enqueue (with a queue-size cap so a stalled network
 * degrades by dropping cloud frames, never by backing up the mic). Frames that arrive while the
 * socket is still connecting are buffered and replayed on open, mirroring what [VoiceInput]'s
 * Bridge does across recognizer restarts. Listener callbacks are marshalled to the main thread.
 */
class CloudStt(
    private val baseUrl: String,
    private val token: String,
    private val keywords: List<String>,
    private val listener: Listener,
) : AudioPipeline.FrameSink {

    interface Listener {
        /** The server's full transcript ("done" frame). Main thread. May be blank. */
        fun onDone(text: String)
        /** Cloud is out of this utterance; native is unaffected. Main thread, at most once. */
        fun onFailed(code: String?)
    }

    @Serializable
    private data class WireMsg(
        val type: String? = null,
        val text: String? = null,
        val message: String? = null,
        val code: String? = null,
    )

    @Serializable
    private data class ConfigMsg(
        val type: String = "config",
        val sampleRate: Int,
        val language: String,
        val keywords: List<String>,
    )

    private val main = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true }

    private val lock = Any()
    private var ws: WebSocket? = null
    private var opened = false
    private var dead = false
    private var doneDelivered = false
    private var finishRequested = false
    /** Frames captured before the socket opened; bounded, oldest dropped. */
    private val preOpen = ArrayDeque<ByteArray>()

    fun start() {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/stt/stream")
            .header("Authorization", "Bearer $token")
            .build()
        // okhttp upgrades http(s) URLs to ws(s) itself; no scheme rewrite needed.
        client.newWebSocket(request, SocketListener())
    }

    /** No more audio is coming — ask the server to flush and send "done". */
    fun finish() {
        synchronized(lock) {
            finishRequested = true
            if (opened) ws?.send(FINISH_FRAME)
        }
    }

    /** Abandon the session; no further callbacks. */
    fun cancel() {
        synchronized(lock) {
            dead = true
            doneDelivered = true
            ws?.close(1000, "cancelled")
            ws = null
            preOpen.clear()
        }
    }

    // Capture thread — enqueue only.
    override fun onFrame(pcm: ByteArray, length: Int) {
        synchronized(lock) {
            if (dead) return
            val socket = ws
            if (socket != null && opened) {
                // A stalled uplink degrades by losing cloud frames, never by growing unbounded.
                if (socket.queueSize() < MAX_QUEUED_BYTES) {
                    socket.send(pcm.toByteString(0, length))
                }
            } else {
                if (preOpen.size >= MAX_PRE_OPEN_FRAMES) preOpen.removeFirst()
                preOpen.addLast(pcm.copyOf(length))
            }
        }
    }

    private fun fail(code: String?, why: String) {
        val notify: Boolean
        synchronized(lock) {
            notify = !dead && !doneDelivered
            dead = true
            doneDelivered = true
            ws?.close(1000, null)
            ws = null
            preOpen.clear()
        }
        log("cloud failed: $why")
        if (notify) main.post { listener.onFailed(code) }
    }

    private inner class SocketListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            val config = json.encodeToString(
                ConfigMsg.serializer(),
                ConfigMsg(
                    sampleRate = AudioPipeline.SAMPLE_RATE,
                    language = lang(),
                    keywords = keywords.take(50).map { it.take(60) },
                ),
            )
            synchronized(lock) {
                if (dead) {
                    webSocket.close(1000, null)
                    return
                }
                ws = webSocket
                webSocket.send(config)
                while (preOpen.isNotEmpty()) {
                    val frame = preOpen.removeFirst()
                    webSocket.send(frame.toByteString())
                }
                opened = true
                if (finishRequested) webSocket.send(FINISH_FRAME)
            }
            log("cloud connected · ${keywords.size} keywords")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { json.decodeFromString<WireMsg>(text) }.getOrNull() ?: return
            when (msg.type) {
                "done" -> {
                    val transcript = msg.text.orEmpty()
                    synchronized(lock) {
                        if (dead || doneDelivered) return
                        doneDelivered = true
                        dead = true
                    }
                    log("cloud done · \"$transcript\"")
                    main.post { listener.onDone(transcript) }
                }
                "error" -> fail(msg.code, msg.message ?: "server error")
                // ready/partial/final ignored: native partials own the panel (VOICE_PLAN V3).
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            fail(null, t.message ?: t::class.java.simpleName)
        }

        override fun onClosed(webSocket: WebSocket, closeCode: Int, reason: String) {
            // Normal path delivers "done" first; a close without it is a failure.
            fail(null, "closed $closeCode before done")
        }
    }

    private fun lang(): String =
        java.util.Locale.getDefault().language.ifBlank { "en" }.take(16)

    /** Debug builds only — never logs transcribed speech in a release build. */
    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "ReplyMintVoice"
        const val FINISH_FRAME = """{"type":"finish"}"""
        /** ~8 s of queued 16 kHz PCM16 — beyond this the uplink is stalled, drop frames. */
        const val MAX_QUEUED_BYTES = 256L * 1024
        /** ~4 s of pre-connection buffer, same bound as VoiceInput's Bridge backlog. */
        const val MAX_PRE_OPEN_FRAMES = 100

        /** Shared client: connect fast or not at all — dictation won't wait for cloud. */
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .build()
        }
    }
}
