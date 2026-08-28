package com.replymint.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.replymint.R
import com.replymint.accessibility.ReplyMintAccessibilityService

/** The two setup permissions, shared between onboarding and the home screen. */
object PermissionsUi {

    fun overlayGranted(activity: Activity): Boolean = Settings.canDrawOverlays(activity)

    fun accessibilityEnabled(): Boolean = ReplyMintAccessibilityService.isEnabled()

    fun requestOverlay(activity: Activity) {
        if (!Settings.canDrawOverlays(activity)) {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}")
                )
            )
        }
    }

    /**
     * Android shows a scary-but-standard "can view your screen and perform actions" notice for
     * every enabled accessibility service. Explain it BEFORE the user meets it, in our own words,
     * so the warning confirms what we said instead of ambushing them.
     */
    fun openAccessibilitySettings(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.a11y_notice_title)
            .setMessage(R.string.a11y_notice_body)
            .setPositiveButton(R.string.a11y_notice_open) { _, _ ->
                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(R.string.a11y_notice_cancel, null)
            .show()
    }
}
