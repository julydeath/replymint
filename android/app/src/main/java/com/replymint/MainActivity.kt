package com.replymint

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButtonToggleGroup
import com.replymint.accessibility.ReplyMintAccessibilityService
import com.replymint.data.ModeStore
import com.replymint.model.Mode
import com.replymint.voice.VoiceCapabilities

/**
 * Onboarding + control panel:
 *   1. Pick a mode (Personal / Professional)
 *   2. Grant "display over other apps"
 *   3. Enable the accessibility service
 *   4. Start the bubble
 *
 * Everything is classic Views for a small, fast-starting APK.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: ModeStore
    private lateinit var status: TextView
    private lateinit var startButton: Button

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = ModeStore(this)

        status = findViewById(R.id.status_text)
        startButton = findViewById(R.id.btn_start)

        val modeGroup = findViewById<MaterialButtonToggleGroup>(R.id.mode_group)
        modeGroup.check(if (store.mode == Mode.PROFESSIONAL) R.id.button_professional else R.id.button_personal)
        modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            store.mode = if (checkedId == R.id.button_professional) Mode.PROFESSIONAL else Mode.PERSONAL
        }

        addDebugSpikeEntry()
        addDebugVoiceReset()

        findViewById<Button>(R.id.btn_overlay).setOnClickListener { requestOverlay() }
        findViewById<Button>(R.id.btn_accessibility).setOnClickListener { openAccessibilitySettings() }
        startButton.setOnClickListener { startBubble() }

        requestRuntimePermissions()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /**
     * Debug builds get a button into the V0 mic-pipe spike. The activity lives in src/debug, so
     * it cannot be referenced directly from here — and cannot ship in a release build either.
     */
    private fun addDebugSpikeEntry() {
        if (!BuildConfig.DEBUG) return
        val container = findViewById<Button>(R.id.btn_start).parent as? ViewGroup ?: return
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
        val container = findViewById<Button>(R.id.btn_start).parent as? ViewGroup ?: return
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

    private fun requestRuntimePermissions() {
        // RECORD_AUDIO for the Voice action; POST_NOTIFICATIONS so our status/error toasts are not
        // suppressed (Android/OEMs block a background app's toasts when its notifications are off).
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            add(android.Manifest.permission.RECORD_AUDIO)
        }.toTypedArray()
        permissionLauncher.launch(needed)
    }

    private fun requestOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(
            this,
            "Turn ReplyMint ON. Tip: you can turn OFF ReplyMint's 'Accessibility button/shortcut' — the app uses its own bubble.",
            Toast.LENGTH_LONG
        ).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun startBubble() {
        if (!ready()) {
            Toast.makeText(this, "Grant both permissions first", Toast.LENGTH_SHORT).show()
            return
        }
        store.onboarded = true
        Toast.makeText(this, "You're all set — open any chat app", Toast.LENGTH_SHORT).show()
    }

    private fun ready(): Boolean =
        Settings.canDrawOverlays(this) && ReplyMintAccessibilityService.isEnabled()

    private fun refresh() {
        val overlay = Settings.canDrawOverlays(this)
        val accessibility = ReplyMintAccessibilityService.isEnabled()
        status.text = buildString {
            appendLine("Overlay permission: ${check(overlay)}")
            append("Accessibility: ${check(accessibility)}")
        }
        startButton.isEnabled = overlay && accessibility
    }

    private fun check(ok: Boolean): String = if (ok) "✓ granted" else "✗ needed"
}
