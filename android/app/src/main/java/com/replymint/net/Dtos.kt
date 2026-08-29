package com.replymint.net

import kotlinx.serialization.Serializable

/** What the client sends to the backend for one reply. */
@Serializable
data class ReplyRequest(
    val mode: String,
    val action: String,
    val screen: ScreenPayload,
    /** Best transcript as a flat string. Kept alongside [voice] so an old backend still works. */
    val voiceInstruction: String? = null,
    val voice: VoicePayload? = null
)

/**
 * Everything the recognizer told us about one utterance — the raw material for the backend's
 * screen-context correction (V2). Mirrors [com.replymint.voice.VoiceResult].
 */
@Serializable
data class VoicePayload(
    /** n-best, best first. */
    val hypotheses: List<String>,
    /** Parallel to [hypotheses]; often empty or all-zero (on-device engine). */
    val confidences: List<Float> = emptyList(),
    /** "native_offline" | "native_online" | "cloud" — see VoiceSource.wire. */
    val source: String,
    /** BCP-47, e.g. "en-IN". */
    val lang: String
)

@Serializable
data class ScreenPayload(
    val appPackage: String,
    val visibleText: List<String>,
    val typedText: String? = null
)

/** The one best draft, ready to write into the box. */
@Serializable
data class ReplyResponse(
    val draft: String
)

// --- Auth ---

@Serializable
data class AuthExchangeRequest(val idToken: String)

@Serializable
data class AuthUser(val email: String, val name: String? = null)

/** From POST /v1/auth/google: our opaque long-lived token plus display identity. */
@Serializable
data class AuthExchangeResponse(val token: String, val user: AuthUser)

/** From GET /v1/me — feeds the home-screen usage card. */
@Serializable
data class MeResponse(
    val email: String,
    val name: String? = null,
    /** Effective plan: "pro" unlocks cloud STT. Default keeps old backends working. */
    val plan: String = "free",
    val todayCount: Int,
    val dailyLimit: Int
)
