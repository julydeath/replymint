package com.replymint.model

/** The user's chosen product tier. Personal is stateless; Professional uses the Reply Brain. */
enum class Mode { PERSONAL, PROFESSIONAL }

/** The three things the bubble can do. */
enum class ReplyAction {
    /** Read the visible conversation and draft one best reply. */
    AUTO_REPLY,

    /** Rewrite using a spoken instruction. */
    VOICE,

    /** Clean up grammar/clarity of what the user already typed. */
    FIX;

    fun wire(): String = name.lowercase()
}
