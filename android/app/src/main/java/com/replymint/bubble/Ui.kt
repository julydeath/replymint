package com.replymint.bubble

import android.content.Context
import kotlin.math.roundToInt

/**
 * `WindowManager.LayoutParams` are raw pixels.
 *
 * Every constant in this package that describes a distance on screen must go through here. The
 * previous `MENU_OFFSET_Y = 120` and bubble `y = 320` were raw pixels, i.e. a different physical
 * distance on every device — roughly 40dp on a 3x phone and 80dp on a 1.5x one.
 */
internal fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).roundToInt()
