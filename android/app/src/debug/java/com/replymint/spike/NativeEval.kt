package com.replymint.spike

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Native-STT baseline for the V3 quality gate (VOICE_PLAN Part 4).
 *
 * Runs the eval fixture clips through the piped SpeechRecognizer — the exact engine the
 * free tier uses — and writes one `<name>.hyp.txt` per clip so the backend WER script can
 * score native against cloud with identical code:
 *
 *   adb push backend/scripts/eval/fixtures/. /sdcard/Download/replymint-eval/
 *   (run this from SpikeActivity → "E · Native eval")
 *   adb pull /sdcard/Download/replymint-eval-hyp .
 *   cd backend && npm run eval -- --hyp-dir ../replymint-eval-hyp
 *
 * Inputs are read straight from Download/replymint-eval (WAVs are audio files, covered by
 * the media-read permission SpikeActivity requests); hypotheses are written through
 * MediaStore into Download/replymint-eval-hyp, which needs no permission and adb can pull.
 */
class NativeEval(
    private val context: Context,
    private val onDevice: Boolean,
    private val preferOffline: Boolean,
    private val segmented: Boolean,
    private val status: (String) -> Unit,
    private val done: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val summary = StringBuilder()
    private var speech: PipedSpeech? = null

    fun run() {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            FIXTURE_DIR
        )
        val clips = dir.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name }.orEmpty()
        if (clips.isEmpty()) {
            done("no clips in ${dir.path} — adb push backend/scripts/eval/fixtures/. ${dir.path}/")
            return
        }
        summary.appendLine("native eval: ${clips.size} clips, ${if (onDevice) "on-device" else "network"}")
        next(clips, 0)
    }

    private fun next(clips: List<File>, index: Int) {
        if (index >= clips.size) {
            done(summary.toString() + "pull with: adb pull /sdcard/Download/$HYP_DIR")
            return
        }
        val clip = clips[index]
        val name = clip.name.removeSuffix(".wav")
        status("E · ${index + 1}/${clips.size} $name")

        val pcm = try {
            readWavPcm16Mono16k(clip)
        } catch (e: Exception) {
            summary.appendLine("$name: SKIP (${e.message})")
            next(clips, index + 1)
            return
        }

        val speech = PipedSpeech(context, onDevice, preferOffline, segmented).also { speech = it }
        val stream = speech.start(
            pipe = true,
            timeoutMs = TIMEOUT_MS + pcm.size / BYTES_PER_MS, // clip duration + grace
            onPartial = { status("E · $name: $it") },
            onDone = { result ->
                this.speech?.destroy()
                this.speech = null
                val hyp = result.bestText
                writeHyp(name, hyp)
                summary.appendLine(
                    "$name: \"$hyp\"${result.error?.let { " (error: $it)" } ?: ""}"
                )
                Log.i(TAG, "$name hyp: $hyp")
                main.post { next(clips, index + 1) }
            }
        )
        if (stream == null) {
            summary.appendLine("$name: FAIL (recognizer would not start)")
            next(clips, index + 1)
            return
        }

        val feeder = PipeFeeder(stream)
        Thread({
            var offset = 0
            while (offset < pcm.size) {
                val len = minOf(SpikeAudio.FRAME_BYTES, pcm.size - offset)
                feeder.feed(pcm, offset, len)
                offset += len
                // Real-time pacing — the endpointer reasons about audio timing (see SpikeActivity).
                runCatching { Thread.sleep(FRAME_MS) }
            }
            feeder.finish() // EOF → recognizer finalizes
        }, "native-eval-feed").start()
    }

    /** Minimal RIFF walker; only PCM16 mono 16kHz is accepted (what record.sh produces). */
    private fun readWavPcm16Mono16k(file: File): ByteArray {
        val buf = file.readBytes()
        require(buf.size >= 12 && String(buf, 0, 4) == "RIFF" && String(buf, 8, 4) == "WAVE") {
            "not a WAV"
        }
        fun u16(o: Int) = (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = u16(o).toLong() or (u16(o + 2).toLong() shl 16)
        var fmtOk = false
        var off = 12
        while (off + 8 <= buf.size) {
            val id = String(buf, off, 4)
            val size = u32(off + 4).toInt()
            val body = off + 8
            if (id == "fmt ") {
                fmtOk = u16(body) == 1 && u16(body + 2) == 1 &&
                    u32(body + 4) == 16000L && u16(body + 14) == 16
            } else if (id == "data") {
                require(fmtOk) { "need PCM16 mono 16kHz" }
                return buf.copyOfRange(body, minOf(body + size, buf.size))
            }
            off = body + size + (size % 2)
        }
        throw IllegalArgumentException("no data chunk")
    }

    /** MediaStore write into Download/replymint-eval-hyp — app-owned, no permission needed. */
    private fun writeHyp(name: String, hyp: String) {
        try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "$name.hyp.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$HYP_DIR")
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { it.write((hyp + "\n").toByteArray()) }
        } catch (e: Exception) {
            Log.e(TAG, "writeHyp $name failed: ${e.message}")
            summary.appendLine("$name: hyp write FAILED (${e.message}) — transcript is in logcat")
        }
    }

    private companion object {
        const val TAG = "ReplyMintEval"
        const val FIXTURE_DIR = "replymint-eval"
        const val HYP_DIR = "replymint-eval-hyp"
        const val TIMEOUT_MS = 20_000L
        const val FRAME_MS = 40L
        /** 16kHz mono PCM16 = 32 bytes per millisecond. */
        const val BYTES_PER_MS = 32
    }
}
