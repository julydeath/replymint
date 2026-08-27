package com.replymint.net

import kotlinx.serialization.Serializable

/** What the client sends to the backend for one reply. */
@Serializable
data class ReplyRequest(
    val mode: String,
    val action: String,
    val screen: ScreenPayload,
    val voiceInstruction: String? = null
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
