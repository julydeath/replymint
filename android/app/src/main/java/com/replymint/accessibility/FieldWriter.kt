package com.replymint.accessibility

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Writes the finished draft INTO the focused input field.
 *
 * This is the app's only way to touch another app, and it can do exactly two things: set text,
 * and put back text it previously replaced. It CANNOT press Send. That limitation is the
 * architectural backbone of the "never sends" promise.
 */
object FieldWriter {

    /** What one write replaced, and where. The raw material for Undo. */
    data class WriteResult(
        /** Verbatim contents of the field immediately before ACTION_SET_TEXT. Usually blank. */
        val previousText: String,
        /** The app that owned the field. Undo is scoped to it. */
        val appPackage: String,
    )

    /**
     * Set [text] into the focused input field, returning what was there before.
     *
     * The previous text is read from the ACTUAL target node here, not from
     * [ScreenContext.typedText] — that field is last-writer-wins across every editable node in a
     * DFS bounded by [ScreenReader]'s line cap, so on a busy conversation it can be null or
     * belong to a different box entirely. It is not trustworthy as an undo snapshot.
     *
     * @return null if no editable field was found, or the write failed.
     */
    fun write(root: AccessibilityNodeInfo?, text: String): WriteResult? {
        val field = findEditable(root) ?: return null
        val previous = currentText(field)
        val pkg = (field.packageName ?: root?.packageName)?.toString().orEmpty()
        if (!setText(field, text)) return null
        return WriteResult(previous, pkg)
    }

    /**
     * Put [previousText] back — but only if the field still holds exactly [expectedText].
     *
     * That one comparison is the entire safety model. If the user has typed since, if focus moved
     * to a different editable, or if the app rewrote the field, the comparison fails and we do
     * nothing rather than destroy words we do not own.
     */
    fun restore(
        root: AccessibilityNodeInfo?,
        expectedText: String,
        previousText: String,
    ): Boolean {
        val field = findEditable(root) ?: return false
        // trim() is the only loosening allowed here: some IMEs append a trailing space after
        // ACTION_SET_TEXT. Anything looser and Undo starts clobbering text it did not write.
        if (currentText(field).trim() != expectedText.trim()) return false
        if (!setText(field, previousText)) return false
        // Leave the caret where a human would expect it rather than at position zero.
        val end = previousText.length
        runCatching {
            field.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, end)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
                }
            )
        }
        return true
    }

    private fun setText(field: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * An empty EditText commonly reports its HINT through getText(). Treating that as the user's
     * draft would make Undo paste "Type a message" into the box.
     */
    private fun currentText(node: AccessibilityNodeInfo): String =
        if (node.isShowingHintText) "" else node.text?.toString().orEmpty()

    /** Prefer the currently focused editable field; otherwise the first editable we find. */
    private fun findEditable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { ScreenReader.isEditable(it) }
            ?.let { return it }

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (ScreenReader.isEditable(node)) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }
}
