package com.replymint

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.replymint.auth.SignInManager
import com.replymint.data.ModeStore
import com.replymint.model.Mode
import com.replymint.net.AuthClient
import com.replymint.net.AuthRequiredException
import com.replymint.ui.OnboardingActivity
import com.replymint.ui.PermissionsUi
import com.replymint.ui.padSystemBars
import com.replymint.voice.VoiceCapabilities
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Home dashboard for a set-up user: readiness status, permissions, today's usage, mode,
 * supported apps. First run (or signed-out) redirects to [OnboardingActivity].
 *
 * Everything is classic Views for a small, fast-starting APK.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: ModeStore

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ModeStore(this)

        if (!store.onboarded || !store.isSignedIn) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.home_scroll).padSystemBars()

        bindHeader()

        val modeGroup = findViewById<MaterialButtonToggleGroup>(R.id.mode_group)
        modeGroup.check(if (store.mode == Mode.PROFESSIONAL) R.id.button_professional else R.id.button_personal)
        bindModeNote()
        modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            store.mode = if (checkedId == R.id.button_professional) Mode.PROFESSIONAL else Mode.PERSONAL
            bindModeNote()
        }

        findViewById<View>(R.id.row_overlay).setOnClickListener { PermissionsUi.requestOverlay(this) }
        findViewById<View>(R.id.row_a11y).setOnClickListener { PermissionsUi.openAccessibilitySettings(this) }
        findViewById<View>(R.id.btn_replay).setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        findViewById<View>(R.id.btn_sign_out).setOnClickListener { signOut() }

        addDebugSpikeEntry()
        addDebugVoiceReset()
        requestRuntimePermissionsIfMissing()
    }

    override fun onResume() {
        super.onResume()
        if (store.onboarded && store.isSignedIn) refresh()
    }

    /** Greeting + avatar initial, derived from the signed-in email's local part. */
    private fun bindHeader() {
        // "manojkarajada.mk@gmail.com" → "Manojkarajada": the local part up to the first
        // separator is the closest thing to a first name an email address offers.
        val name = store.email?.substringBefore('@')
            ?.takeWhile { it.isLetter() }
            ?.replaceFirstChar { it.uppercase() }
            .orEmpty()
        val greeting = getString(
            when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
                in 5..11 -> R.string.greeting_morning
                in 12..17 -> R.string.greeting_afternoon
                else -> R.string.greeting_evening
            }
        )
        findViewById<TextView>(R.id.greeting_line).text =
            if (name.isEmpty()) greeting.trimEnd(',') else "$greeting\n$name"
        findViewById<TextView>(R.id.avatar_badge).text = name.take(1).ifEmpty { "•" }
    }

    private fun bindModeNote() {
        findViewById<TextView>(R.id.mode_note).setText(
            if (store.mode == Mode.PROFESSIONAL) R.string.mode_professional_sub
            else R.string.mode_personal_sub
        )
    }

    private fun refresh() {
        val overlay = PermissionsUi.overlayGranted(this)
        val accessibility = PermissionsUi.accessibilityEnabled()
        val ready = overlay && accessibility

        findViewById<TextView>(R.id.status_title)
            .setText(if (ready) R.string.home_status_ready else R.string.home_status_setup)
        findViewById<TextView>(R.id.status_sub)
            .setText(if (ready) R.string.home_status_ready_sub else R.string.home_status_setup_sub)

        bindPermissionRow(R.id.overlay_state, R.id.overlay_check, overlay)
        bindPermissionRow(R.id.a11y_state, R.id.a11y_check, accessibility)

        findViewById<TextView>(R.id.account_line).text =
            getString(R.string.signed_in_as, store.email ?: "")

        fetchUsage()
    }

    private fun bindPermissionRow(stateId: Int, checkId: Int, granted: Boolean) {
        findViewById<TextView>(stateId).apply {
            text = getString(if (granted) R.string.perm_granted else R.string.perm_needed)
            visibility = if (granted) View.GONE else View.VISIBLE
        }
        findViewById<ImageView>(checkId).visibility = if (granted) View.VISIBLE else View.GONE
    }

    /** Fills the usage card from /v1/me; the call doubles as a backend warm-up ping. */
    private fun fetchUsage() {
        val token = store.token ?: return
        lifecycleScope.launch {
            AuthClient(BuildConfig.BASE_URL).fetchMe(token).fold(
                onSuccess = { me ->
                    val used = me.todayCount.coerceAtMost(me.dailyLimit)
                    findViewById<View>(R.id.usage_card).visibility = View.VISIBLE
                    findViewById<CircularProgressIndicator>(R.id.usage_ring).apply {
                        max = me.dailyLimit
                        setProgressCompat(used, true)
                    }
                    findViewById<TextView>(R.id.usage_ring_value).text =
                        getString(R.string.usage_ring_fmt, used, me.dailyLimit)
                    findViewById<TextView>(R.id.usage_text).text =
                        getString(R.string.home_usage_left_fmt, me.dailyLimit - used)
                },
                onFailure = {
                    if (it is AuthRequiredException) {
                        store.clearAuth()
                        startActivity(Intent(this@MainActivity, OnboardingActivity::class.java))
                        finish()
                    }
                    // Other failures (offline, cold start): just leave the card hidden.
                }
            )
        }
    }

    private fun signOut() {
        lifecycleScope.launch {
            SignInManager(this@MainActivity).signOut()
            startActivity(Intent(this@MainActivity, OnboardingActivity::class.java))
            finish()
        }
    }

    private fun requestRuntimePermissionsIfMissing() {
        // Onboarding already asked once; re-ask only for what's still missing (no dialog spam).
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(android.Manifest.permission.RECORD_AUDIO)
            }
        }.toTypedArray()
        if (needed.isNotEmpty()) permissionLauncher.launch(needed)
    }

    /**
     * Debug builds get a button into the V0 mic-pipe spike. The activity lives in src/debug, so
     * it cannot be referenced directly from here — and cannot ship in a release build either.
     */
    private fun addDebugSpikeEntry() {
        if (!BuildConfig.DEBUG) return
        val container = findViewById<ViewGroup>(R.id.home_root) ?: return
        container.addView(
            Button(this).apply {
                text = "Debug · mic-pipe spike"
                setOnClickListener {
                    runCatching {
                        startActivity(
                            Intent().setClassName(this@MainActivity, "com.replymint.spike.SpikeActivity")
                        )
                    }.onFailure {
                        Toast.makeText(this@MainActivity, "Spike screen not in this build", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    /**
     * Debug builds get a way to clear the learned per-device voice verdicts. Schema 2 clears a
     * `pipe_rejected` written by the old false-positive probe automatically, but re-testing the
     * probe by hand needs this.
     */
    private fun addDebugVoiceReset() {
        if (!BuildConfig.DEBUG) return
        val container = findViewById<ViewGroup>(R.id.home_root) ?: return
        container.addView(
            Button(this).apply {
                text = "Debug · reset voice probes"
                setOnClickListener {
                    VoiceCapabilities(this@MainActivity).reset()
                    Toast.makeText(this@MainActivity, "Voice probes reset", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}
