package com.replymint.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Structured snapshot of what is on screen at the moment the bubble was tapped. */
data class ScreenContext(
    val appPackage: String,
    /**
     * Visible text lines in reading order, de-duplicated, most recent (bottom) lines kept when
     * capped. Chat-bubble lines carry a direction tag: "Me: " (right-aligned, sent by the user)
     * or "Them: " (left-aligned, received). Untagged lines are headers/UI/full-width text.
     */
    val visibleText: List<String>,
    /** Current contents of the focused input field, if any (used by Fix). */
    val typedText: String?
)

/**
 * Walks the active window's accessibility node tree into a [ScreenContext].
 *
 * Read happens ON DEMAND ONLY (called from the tap handler) — there is no listener and no polling,
 * which is what makes the "reads only when you tap" privacy promise true.
 *
 * NOTE: each target app renders a different tree. This generic reader is the Phase 0 baseline;
 * per-app heuristics (WhatsApp / Gmail / LinkedIn) get layered on top after the spike.
 */
object ScreenReader {

    private const val MAX_LINES = 60
    /** Traversal budget: enough for a busy chat screen, bounded so a pathological tree can't hang the tap. */
    private const val MAX_READ_NODES = 1_500
    /** A bubble inset beyond this fraction of the screen width marks the side the bubble hugs. */
    private const val ALIGN_INSET_FRACTION = 0.18f

    fun read(root: AccessibilityNodeInfo?): ScreenContext? {
        if (root == null) return null
        val pkg = root.packageName?.toString().orEmpty()
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }

        val lines = LinkedHashSet<String>()
        var typed: String? = null
        var typedFromFocused = false
        var budget = MAX_READ_NODES

        // Depth-first in DOCUMENT order: children are pushed last-first so they pop first-first.
        // (The old forward push reversed every sibling run, so "top-to-bottom" was a lie and the
        // model was asked for "the most recent message" from a scrambled list.)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty() && budget-- > 0) {
            val node = stack.removeLast()
            val text = node.text?.toString()?.trim()

            if (isEditable(node)) {
                // The input field: its text is what the user has typed, not conversation content.
                // A focused editable always wins; an empty box showing its hint contributes nothing.
                if (!text.isNullOrEmpty() && !node.isShowingHintText) {
                    if (node.isFocused) {
                        typed = text
                        typedFromFocused = true
                    } else if (!typedFromFocused) {
                        typed = text
                    }
                }
            } else if (!text.isNullOrEmpty() && text.length in 1..500 && node.isVisibleToUser) {
                lines.add(tagDirection(text, node, rootBounds))
            }

            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        return ScreenContext(
            appPackage = pkg,
            // Recency lives at the bottom of a chat; when over the cap, drop the top.
            visibleText = lines.toList().takeLast(MAX_LINES),
            typedText = typed
        )
    }

    /**
     * Incoming vs outgoing, from geometry: chat apps right-align the user's bubbles and
     * left-align the other side's. A line hugging one side with a wide inset on the other is
     * tagged; full-width text (headers, timestamps, system rows) stays untagged. Heuristic by
     * design — the prompt treats the tags as hints, not ground truth.
     */
    private fun tagDirection(text: String, node: AccessibilityNodeInfo, rootBounds: Rect): String {
        val b = Rect().also { node.getBoundsInScreen(it) }
        val width = rootBounds.width()
        if (width <= 0) return text
        val threshold = width * ALIGN_INSET_FRACTION
        val leftInset = (b.left - rootBounds.left).toFloat()
        val rightInset = (rootBounds.right - b.right).toFloat()
        return when {
            leftInset > threshold && rightInset < threshold -> "Me: $text"
            rightInset > threshold && leftInset < threshold -> "Them: $text"
            else -> text
        }
    }

    internal fun isEditable(node: AccessibilityNodeInfo): Boolean =
        node.isEditable || node.className?.contains("EditText") == true

    /**
     * Cheap "is there a reply box on this screen?" check, used to decide whether to show the bubble.
     *
     * Bounded BFS that returns on the FIRST editable node — it inspects only node types/classes,
     * never collects or sends any text. Conversation content is still read only on a user tap
     * (via [read]). Node cap keeps it fast enough to run on screen-change events.
     */
    fun hasEditableField(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        var budget = MAX_SCAN_NODES
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty() && budget-- > 0) {
            val node = stack.removeLast()
            if (isEditable(node)) return true
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return false
    }

    private const val MAX_SCAN_NODES = 400
}
