package com.replymint.core

import android.content.Context
import android.os.SystemClock
import com.replymint.BuildConfig
import com.replymint.accessibility.ReplyMintAccessibilityService
import com.replymint.data.ModeStore
import com.replymint.model.ReplyAction
import com.replymint.net.ReplyClient
import com.replymint.net.ReplyRequest
import com.replymint.net.ScreenPayload

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
        voiceInstruction: String? = null
    ): EngineResult {
        val service = ReplyMintAccessibilityService.instance
            ?: return EngineResult.Error("Turn on ReplyMint in Accessibility settings")

        val screen = service.captureScreen()
            ?: return EngineResult.Error("Couldn't read this screen")

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
            voiceInstruction = voiceInstruction
        )

        val client = ReplyClient(BuildConfig.BASE_URL, store.token)
        return client.requestReply(request).fold(
            onSuccess = { draft ->
                val written = service.writeDraft(draft)
                if (written == null) {
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
            onFailure = { EngineResult.Error(it.message ?: "Network error") }
        )
    }
}
