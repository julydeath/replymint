package com.replymint.core

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.replymint.BuildConfig
import com.replymint.accessibility.ReplyMintAccessibilityService
import com.replymint.data.ModeStore
import com.replymint.model.ReplyAction
import com.replymint.net.ReplyClient
import com.replymint.net.ReplyRequest
import com.replymint.net.ScreenPayload
import com.replymint.net.VoicePayload
import com.replymint.voice.VoiceResult

/** Outcome of one bubble action, surfaced to the user as a short status. */
sealed interface EngineResult {
    data class Success(val draft: String) : EngineResult
    data class Error(val message: String) : EngineResult
}

/**
 * The core loop, in one place:
 *   capture screen (accessibility) → build request → backend → write draft back into the box.
 *
 * Notice there is no "send" step anywhere. The engine writes the draft and stops. The user sends.
 */
object ReplyEngine {

    suspend fun run(
        context: Context,
        action: ReplyAction,
        voice: VoiceResult? = null
    ): EngineResult {
        val service = ReplyMintAccessibilityService.instance
            ?: return EngineResult.Error("Turn on ReplyMint in Accessibility settings")

        val screen = service.captureScreen()
            ?: return EngineResult.Error("Couldn't read this screen")

        log("run · action=${action.wire()} app=${screen.appPackage} " +
            "screenLines=${screen.visibleText.size} typedChars=${screen.typedText?.length ?: 0}")
        screen.visibleText.forEach { log("screen · $it") }
        if (voice != null) {
            log("voice · source=${voice.source.wire} lang=${voice.lang} " +
                "hypotheses=${voice.hypotheses.size} confidences=${voice.confidences}")
            voice.hypotheses.forEachIndexed { i, h -> log("voice · n-best[$i] \"$h\"") }
        }

        if (action == ReplyAction.FIX && screen.typedText.isNullOrBlank()) {
            return EngineResult.Error("Type something first, then tap Fix")
        }

        val store = ModeStore(context)
        val request = ReplyRequest(
            mode = store.mode.name.lowercase(),
            action = action.wire(),
            screen = ScreenPayload(
                appPackage = screen.appPackage,
                visibleText = screen.visibleText,
                typedText = screen.typedText
            ),
            // Both forms travel: the flat string keeps an old backend working, the payload
            // carries the n-best list the new backend corrects against the screen (V2).
            voiceInstruction = voice?.text,
            voice = voice?.let {
                VoicePayload(
                    hypotheses = it.hypotheses,
                    confidences = it.confidences,
                    source = it.source.wire,
                    lang = it.lang
                )
            }
        )

        val client = ReplyClient(BuildConfig.BASE_URL, store.token)
        val startedMs = SystemClock.elapsedRealtime()
        return client.requestReply(request).fold(
            onSuccess = { draft ->
                log("draft · ${SystemClock.elapsedRealtime() - startedMs}ms \"$draft\"")
                val written = service.writeDraft(draft)
                if (written == null) {
                    log("write FAILED · no editable field found")
                    EngineResult.Error("Tap the reply box, then try again")
                } else {
                    // Snapshot taken from the node we actually wrote to — see FieldWriter.write.
                    UndoStore.record(
                        UndoSnapshot(
                            appPackage = written.appPackage.ifEmpty { screen.appPackage },
                            previousText = written.previousText,
                            draft = draft,
                            atMs = SystemClock.elapsedRealtime(),
                        )
                    )
                    EngineResult.Success(draft)
                }
            },
            onFailure = {
                log("request FAILED · ${SystemClock.elapsedRealtime() - startedMs}ms ${it.message}")
                EngineResult.Error(it.message ?: "Network error")
            }
        )
    }

    /** Debug builds only — screen text, transcripts and drafts never reach logcat in release. */
    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.i("ReplyMint", message)
    }
}
