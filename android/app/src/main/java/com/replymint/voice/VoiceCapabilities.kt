package com.replymint.voice

import android.content.Context
import android.os.Build

/**
 * What this particular device turned out to support, learned by trying and then remembered.
 *
 * `EXTRA_AUDIO_SOURCE` is honoured on every device we have measured, but the platform docs are
 * explicit that an engine may ignore it and open the microphone instead — and OEM recognizers
 * vary. Rather than ship a device allowlist we can never keep current, the first session probes:
 * if the recognizer produces nothing from a pipe we demonstrably filled, we record that here and
 * every later session on this device goes straight to the fallback path.
 *
 * The same applies to the on-device model, which can be absent for the user's language.
 */
class VoiceCapabilities(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init { migrate() }

    /**
     * Schema 1 could persist `pipe_rejected` from a false positive: a user who paused mid-sentence
     * produced a benign NO_MATCH, which the old whole-capture speech latch read as "the engine
     * ignored our pipe". Two pauses permanently disabled the gapless path on that device.
     *
     * Clear that one verdict once and let the corrected probe decide again. `on_device_rejected`
     * is deliberately untouched — that verdict was always sound.
     */
    private fun migrate() {
        if (prefs.getInt(KEY_SCHEMA, 1) >= SCHEMA) return
        prefs.edit().remove(KEY_PIPE_REJECTED).putInt(KEY_SCHEMA, SCHEMA).apply()
    }

    /** Can we own the mic and still get native transcripts? */
    val canPipe: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !prefs.getBoolean(KEY_PIPE_REJECTED, false)

    /** Faster and more accurate than the network engine where it exists — so, preferred. */
    val canUseOnDevice: Boolean
        get() = !prefs.getBoolean(KEY_ON_DEVICE_REJECTED, false) &&
            RecognizerSession.onDeviceAvailable(app)

    /** The recognizer ignored a pipe we know we filled. Never try it again on this device. */
    fun rejectPipe() = prefs.edit().putBoolean(KEY_PIPE_REJECTED, true).apply()

    /** No on-device model for this language. Fall back to the network engine from now on. */
    fun rejectOnDevice() = prefs.edit().putBoolean(KEY_ON_DEVICE_REJECTED, true).apply()

    /** Re-probe everything — for a settings "reset voice" action, and for our own testing. */
    fun reset() = prefs.edit().clear().putInt(KEY_SCHEMA, SCHEMA).apply()

    private companion object {
        const val PREFS = "replymint_voice"
        const val KEY_SCHEMA = "schema"
        const val SCHEMA = 2
        const val KEY_PIPE_REJECTED = "pipe_rejected"
        const val KEY_ON_DEVICE_REJECTED = "on_device_rejected"
    }
}
