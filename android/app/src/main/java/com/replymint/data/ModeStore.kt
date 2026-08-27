package com.replymint.data

import android.content.Context
import com.replymint.model.Mode

/**
 * Tiny persisted state for the MVP: chosen mode, auth token, onboarding flag.
 * Deliberately SharedPreferences (no Room/DataStore) to stay lightweight.
 *
 * TODO(security): migrate `token` to EncryptedSharedPreferences before release.
 */
class ModeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var mode: Mode
        get() = runCatching { Mode.valueOf(prefs.getString(KEY_MODE, Mode.PERSONAL.name)!!) }
            .getOrDefault(Mode.PERSONAL)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var onboarded: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()

    private companion object {
        const val PREFS = "replymint"
        const val KEY_MODE = "mode"
        const val KEY_TOKEN = "token"
        const val KEY_ONBOARDED = "onboarded"
    }
}
