package com.replymint.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/** Structured snapshot of what is on screen at the moment the bubble was tapped. */
data class ScreenContext(
    val appPackage: String,
    /** Visible text lines, top-to-bottom, de-duplicated. This is the conversation context. */
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

    fun read(root: AccessibilityNodeInfo?): ScreenContext? {
        if (root == null) return null
        val pkg = root.packageName?.toString().orEmpty()
        val lines = LinkedHashSet<String>()
        var typed: String? = null

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty() && lines.size < MAX_LINES) {
            val node = stack.removeLast()
            val text = node.text?.toString()?.trim()

            if (isEditable(node)) {
                // The input field: its text is what the user has typed, not conversation content.
                if (!text.isNullOrEmpty()) typed = text
            } else if (!text.isNullOrEmpty() && text.length in 1..500) {
                lines.add(text)
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        return ScreenContext(
            appPackage = pkg,
            visibleText = lines.toList(),
            typedText = typed
        )
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
