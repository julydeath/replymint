package com.replymint.bubble

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.replymint.R

/**
 * The voice panel's live waveform: a fixed row of centred bars whose heights scroll left as new
 * mic levels arrive, so speech reads as movement rather than a single twitching meter.
 *
 * Dumb by design — [push] is fed already-smoothed levels by the overlay at the mic callback rate,
 * so there is no internal animator to leak when the panel window is torn down.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val levels = FloatArray(BAR_COUNT) { MIN_LEVEL }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.mint)
    }
    private val bar = RectF()

    /** Append one level (0..1); the oldest bar falls off the left edge. */
    fun push(level: Float) {
        System.arraycopy(levels, 1, levels, 0, BAR_COUNT - 1)
        levels[BAR_COUNT - 1] = level.coerceIn(MIN_LEVEL, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val slot = width.toFloat() / BAR_COUNT
        val barWidth = slot * 0.58f
        val radius = barWidth / 2f
        for (i in 0 until BAR_COUNT) {
            val barHeight = (h * levels[i]).coerceAtLeast(barWidth)
            val centerX = i * slot + slot / 2f
            bar.set(
                centerX - barWidth / 2f, (h - barHeight) / 2f,
                centerX + barWidth / 2f, (h + barHeight) / 2f
            )
            paint.alpha = (140 + 115 * levels[i]).toInt()
            canvas.drawRoundRect(bar, radius, radius, paint)
        }
    }

    private companion object {
        const val BAR_COUNT = 26
        const val MIN_LEVEL = 0.08f
    }
}
