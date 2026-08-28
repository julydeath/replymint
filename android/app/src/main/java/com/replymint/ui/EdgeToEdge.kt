package com.replymint.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * targetSdk 36 means Android 15+ enforces edge-to-edge: without this, content draws under the
 * status bar clock and the gesture bar. Adds the system-bar insets on top of whatever padding
 * the layout already declares.
 */
fun View.padSystemBars() {
    val baseLeft = paddingLeft
    val baseTop = paddingTop
    val baseRight = paddingRight
    val baseBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(
            baseLeft + bars.left, baseTop + bars.top,
            baseRight + bars.right, baseBottom + bars.bottom
        )
        insets
    }
}
