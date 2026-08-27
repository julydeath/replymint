package com.replymint.core

import android.os.SystemClock

/** Everything one Undo needs. Captured by FieldWriter at write time, not from ScreenReader. */
data class UndoSnapshot(
    val appPackage: String,
    /** Restored verbatim — usually blank, or a half-typed line. This is the user's text. */
    val previousText: String,
    /** What we wrote. Undo only fires if the box still holds exactly this. */
    val draft: String,
    /** [SystemClock.elapsedRealtime] — monotonic, unaffected by clock changes. */
    val atMs: Long,
)

/**
 * One-level, single-slot undo buffer for the whole process.
 *
 * Deliberately NOT owned by BubbleOverlay: the accessibility service hides the overlay on every
 * screen without a text field — including the moment the keyboard opens — and Undo has to outlive
 * that. For the same reason nothing here is cleared on hide.
 *
 * Deliberately NOT a stack: the product decision is "put back exactly what was there", not a
 * history. A second draft overwrites the slot; last write wins.
 */
object UndoStore {

    @Volatile
    private var snapshot: UndoSnapshot? = null

    fun record(value: UndoSnapshot) { snapshot = value }

    /** The snapshot if it is still plausibly offerable in [appPackage] right now, else null. */
    fun peek(appPackage: String?): UndoSnapshot? {
        val current = snapshot ?: return null
        if (appPackage == null || current.appPackage != appPackage) return null
        if (SystemClock.elapsedRealtime() - current.atMs > TTL_MS) {
            snapshot = null
            return null
        }
        return current
    }

    fun clear() { snapshot = null }

    /** Long enough to be there when you want it; short enough never to surprise. */
    private const val TTL_MS = 5 * 60_000L
}
