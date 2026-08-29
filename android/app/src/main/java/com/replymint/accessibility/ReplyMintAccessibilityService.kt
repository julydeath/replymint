package com.replymint.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.replymint.bubble.BubbleOverlay

/**
 * The bridge to other apps, and the host of the floating bubble.
 *
 * Privacy: on screen changes we do ONE cheap, content-free check — "is there a reply box on
 * screen?" ([ScreenReader.hasEditableField]) — to decide whether to show the bubble. We never read
 * or collect conversation text in the background; that happens only when the user taps an action
 * ([captureScreen]). Nothing is polled and nothing leaves the device here.
 */
class ReplyMintAccessibilityService : AccessibilityService() {

    private var overlay: BubbleOverlay? = null
    private val handler = Handler(Looper.getMainLooper())
    private val evaluateVisibility = Runnable { updateBubbleVisibility() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = BubbleOverlay(this)
    }

    /**
     * Show the bubble only on screens that have a text field (any chat/compose screen), so the user
     * can tap it with the keyboard closed and the full conversation visible. Debounced because
     * content-changed events can fire rapidly.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        handler.removeCallbacks(evaluateVisibility)
        handler.postDelayed(evaluateVisibility, DEBOUNCE_MS)
    }

    private fun updateBubbleVisibility() {
        val overlay = overlay ?: return
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (pkg in TARGET_APPS && ScreenReader.hasEditableField(root)) overlay.show()
        else overlay.hide()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(evaluateVisibility)
        overlay?.destroy()
        overlay = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Read the current screen into a [ScreenContext]. Called only from a user tap. */
    fun captureScreen(): ScreenContext? = ScreenReader.read(rootInActiveWindow)

    /** Write [text] into the focused field. Returns what it replaced, or null if there was none. */
    fun writeDraft(text: String): FieldWriter.WriteResult? =
        FieldWriter.write(rootInActiveWindow, text)

    /** Put back the pre-draft text, but only if the box still holds [expected]. */
    fun restoreDraft(expected: String, previous: String): Boolean =
        FieldWriter.restore(rootInActiveWindow, expected, previous)

    /** Package of the app currently in front, or null. */
    fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    companion object {
        private const val DEBOUNCE_MS = 250L

        /**
         * ReplyMint is a reply assistant, not a dictation/notes tool: the bubble appears only in
         * the messaging apps we target, never in browsers, settings, or other apps that happen to
         * have a text field. Also serves as the implicit "not our own app" filter.
         */
        private val TARGET_APPS = setOf(
            "com.whatsapp",                        // WhatsApp
            "com.whatsapp.w4b",                    // WhatsApp Business
            "com.instagram.android",               // Instagram
            "com.google.android.gm",               // Gmail
            "com.linkedin.android",                // LinkedIn
            "com.Slack",                           // Slack
            "com.microsoft.teams",                 // Microsoft Teams
            "com.facebook.katana",                 // Facebook
            "com.facebook.orca",                   // Facebook Messenger
            "com.google.android.apps.dynamite",    // Google Chat
            "com.discord",                         // Discord
            "org.telegram.messenger",              // Telegram (Play Store build)
            "org.telegram.messenger.web",          // Telegram (direct-APK build)
            "org.thoughtcrime.securesms",          // Signal
            "com.google.android.apps.messaging",   // Google Messages (SMS/RCS)
            "com.samsung.android.messaging",       // Samsung Messages
            "com.snapchat.android",                // Snapchat
            "com.twitter.android",                 // X (Twitter DMs)
            "com.microsoft.office.outlook"         // Outlook
        )

        @Volatile
        var instance: ReplyMintAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }
}
