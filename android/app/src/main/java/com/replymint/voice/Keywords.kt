package com.replymint.voice

import com.replymint.accessibility.ScreenContext

/**
 * Screen keywords for cloud-STT biasing (SttConfigSchema.keywords → Deepgram keyterms):
 * capitalized words and adjacent runs (full names) from the visible lines.
 *
 * Deliberately the same rules as the desktop extractor (desktop ax.rs
 * `extract_keywords`) — keep them in sync. Over-collection is harmless (keyterm
 * boosting tolerates extra words); silently missing a name is what costs accuracy.
 */
object Keywords {

    private const val MAX_KEYWORDS = 50
    private const val MAX_CHARS = 60

    private val STOPWORDS = setOf(
        "The", "This", "That", "These", "Those", "And", "But", "For", "Not", "You", "Your",
        "What", "When", "Where", "Which", "Why", "How", "With", "From", "Have", "Has", "Had",
        "Will", "Would", "Could", "Should", "There", "Here", "They", "Them", "Then", "Than",
        "Are", "Was", "Were", "Yes", "Okay", "Please", "Thanks", "Thank", "Hello", "Just",
        "Also", "About", "Can", "Get", "Let", "New", "Now", "One", "Our", "Out", "See",
    )

    fun from(screen: ScreenContext): List<String> {
        val keywords = LinkedHashSet<String>()
        for (raw in screen.visibleText) {
            val line = raw.removePrefix("Me:").removePrefix("Them:").trim()
            val words = line.split(Regex("\\s+"))
                .map { it.trim { c -> !c.isLetterOrDigit() } }
            val run = mutableListOf<String>()
            for (word in words + "") {
                if (isCandidate(word)) {
                    run += word
                } else {
                    if (run.size > 1) keywords += run.joinToString(" ") // full names first
                    run.forEach { keywords += it }
                    run.clear()
                }
            }
        }
        return keywords.filter { it.length <= MAX_CHARS }.take(MAX_KEYWORDS)
    }

    private fun isCandidate(word: String): Boolean =
        word.length >= 3 &&
            word.first().isUpperCase() &&
            word.all { it.isLetter() } &&
            word !in STOPWORDS
}
