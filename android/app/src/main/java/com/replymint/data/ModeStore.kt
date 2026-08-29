package com.replymint.data

import android.content.Context
import com.replymint.auth.TokenVault
import com.replymint.model.Mode

/**
 * Tiny persisted state for the MVP: chosen mode, auth session, onboarding flag.
 * Deliberately SharedPreferences (no Room/DataStore) to stay lightweight.
 *
 * The auth token is stored encrypted via [TokenVault] (Android Keystore); email/name are
 * plain display data.
 */
class ModeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var mode: Mode
        get() = runCatching { Mode.valueOf(prefs.getString(KEY_MODE, Mode.PERSONAL.name)!!) }
            .getOrDefault(Mode.PERSONAL)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var token: String?
        get() = prefs.getString(KEY_TOKEN_ENC, null)?.let(TokenVault::decrypt)
        set(value) {
            val blob = value?.let(TokenVault::encrypt)
            // If encryption itself fails we drop the session rather than store plaintext.
            prefs.edit().apply {
                if (blob != null) putString(KEY_TOKEN_ENC, blob) else remove(KEY_TOKEN_ENC)
                remove(KEY_TOKEN_LEGACY)
            }.apply()
        }

    var email: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_EMAIL, value).apply()

    var displayName: String?
        get() = prefs.getString(KEY_NAME, null)
        set(value) = prefs.edit().putString(KEY_NAME, value).apply()

    val isSignedIn: Boolean get() = token != null

    /**
     * Effective plan, cached from /v1/me on every app open. The server is authoritative —
     * /v1/stt/stream re-checks it, so a stale "pro" here costs one rejected connect, nothing more.
     */
    var plan: String
        get() = prefs.getString(KEY_PLAN, "free") ?: "free"
        set(value) = prefs.edit().putString(KEY_PLAN, value).apply()

    /** Pro-only opt-OUT: cloud transcription is on by default for pro accounts. */
    var cloudStt: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_STT, true)
        set(value) = prefs.edit().putBoolean(KEY_CLOUD_STT, value).apply()

    fun clearAuth() {
        prefs.edit()
            .remove(KEY_TOKEN_ENC)
            .remove(KEY_TOKEN_LEGACY)
            .remove(KEY_EMAIL)
            .remove(KEY_NAME)
            .remove(KEY_PLAN)
            .apply()
    }

    var onboarded: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()

    private companion object {
        const val PREFS = "replymint"
        const val KEY_MODE = "mode"
        /** Pre-vault plaintext slot; nothing ever wrote it, removed on any token write. */
        const val KEY_TOKEN_LEGACY = "token"
        const val KEY_TOKEN_ENC = "token_enc"
        const val KEY_EMAIL = "email"
        const val KEY_NAME = "name"
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_PLAN = "plan"
        const val KEY_CLOUD_STT = "cloud_stt"
    }
}
