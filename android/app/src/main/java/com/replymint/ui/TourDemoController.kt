package com.replymint.ui

import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.replymint.R
import com.replymint.bubble.WaveformView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Drives the onboarding "See it in action" page: a looping, staged simulation of the bubble
 * working inside WhatsApp (Auto Reply), Instagram (Voice) and Gmail (Fix), acted out with the
 * real overlay drawables on miniature chat skins.
 *
 * Purely presentational — nothing here touches the accessibility service or the engine.
 * The activity calls [start]/[stop] as the page enters/leaves the foreground; every cycle
 * begins from [reset], so cancellation mid-animation can never leave a corrupt frame.
 */
class TourDemoController(private val root: View) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var job: Job? = null

    private val screen: ViewGroup = root.findViewById(R.id.demo_screen)
    private val skinWa: View = root.findViewById(R.id.demo_skin_wa)
    private val skinIg: View = root.findViewById(R.id.demo_skin_ig)
    private val skinGm: View = root.findViewById(R.id.demo_skin_gm)
    private val composerWa: TextView = skinWa.findViewById(R.id.demo_composer_text)
    private val composerIg: TextView = skinIg.findViewById(R.id.demo_composer_text)
    private val composerGm: TextView = skinGm.findViewById(R.id.demo_composer_text)

    private val bubble: View = root.findViewById(R.id.demo_bubble)
    private val menu: View = root.findViewById(R.id.demo_menu)
    private val rowAuto: View = root.findViewById(R.id.demo_row_auto)
    private val rowVoice: View = root.findViewById(R.id.demo_row_voice)
    private val rowFix: View = root.findViewById(R.id.demo_row_fix)
    private val voice: View = root.findViewById(R.id.demo_voice)
    private val wave: WaveformView = root.findViewById(R.id.demo_wave)
    private val transcript: TextView = root.findViewById(R.id.demo_transcript)
    private val voiceStop: View = root.findViewById(R.id.demo_voice_stop)
    private val pill: View = root.findViewById(R.id.demo_pill)
    private val pillSpinner: View = root.findViewById(R.id.demo_pill_spinner)
    private val pillIcon: ImageView = root.findViewById(R.id.demo_pill_icon)
    private val pillText: TextView = root.findViewById(R.id.demo_pill_text)
    private val captionIcon: ImageView = root.findViewById(R.id.demo_caption_icon)
    private val caption: TextView = root.findViewById(R.id.demo_caption)

    init {
        screen.clipToOutline = true
        reset()
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                cycleWhatsApp()
                cycleInstagram()
                cycleGmail()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        reset()
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    // ---- Cycles -----------------------------------------------------------------------------

    private suspend fun cycleWhatsApp() {
        beginCycle(skinWa, R.drawable.ic_app_whatsapp, R.string.demo_caption_wa)
        composerWa.text = ""
        delay(700)
        bubbleIn(); delay(1100)
        bubblePress(); menuShow(); delay(900)
        highlight(rowAuto); delay(650); unhighlight(rowAuto); menuHide()
        pillShow(writing = true, R.string.pill_writing); delay(1300)
        pillHide(); delay(180)
        typeInto(composerWa, str(R.string.demo_wa_reply))
        pillShow(writing = false, R.string.pill_ready); delay(1900); pillHide()
        delay(700)
    }

    private suspend fun cycleInstagram() {
        beginCycle(skinIg, R.drawable.ic_app_instagram, R.string.demo_caption_ig)
        composerIg.text = ""
        delay(700)
        bubbleIn(); delay(1100)
        bubblePress(); menuShow(); delay(900)
        highlight(rowVoice); delay(650); unhighlight(rowVoice); menuHide()

        transcript.text = ""
        voice.animate().alpha(1f).setDuration(200).start()
        coroutineScope {
            // Synthetic mic levels: a bounded random walk reads as speech without a mic.
            val ticker = launch {
                var level = 0.25f
                while (isActive) {
                    level = (level + Random.nextFloat() * 0.5f - 0.25f).coerceIn(0.1f, 0.95f)
                    wave.push(level)
                    delay(50)
                }
            }
            delay(500)
            val words = str(R.string.demo_ig_voice).split(' ')
            for (i in 1..words.size) {
                transcript.text = words.take(i).joinToString(" ")
                delay(190)
            }
            delay(650)
            ticker.cancel()
        }
        voiceStop.animate().scaleX(0.94f).scaleY(0.94f).setDuration(110).start()
        delay(140)
        voiceStop.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
        delay(200)
        voice.animate().alpha(0f).setDuration(180).start()

        pillShow(writing = true, R.string.pill_writing); delay(1300)
        pillHide(); delay(180)
        typeInto(composerIg, str(R.string.demo_ig_reply))
        pillShow(writing = false, R.string.pill_ready); delay(1900); pillHide()
        delay(700)
    }

    private suspend fun cycleGmail() {
        beginCycle(skinGm, R.drawable.ic_app_gmail, R.string.demo_caption_gm)
        composerGm.text = str(R.string.demo_gm_rough)
        delay(900)
        bubbleIn(); delay(1100)
        bubblePress(); menuShow(); delay(900)
        highlight(rowFix); delay(650); unhighlight(rowFix); menuHide()
        pillShow(writing = true, R.string.pill_polishing); delay(1200)
        composerGm.animate().alpha(0.15f).setDuration(300).start(); delay(340)
        composerGm.text = ""
        composerGm.alpha = 1f
        pillHide(); delay(180)
        typeInto(composerGm, str(R.string.demo_gm_reply))
        pillShow(writing = false, R.string.pill_ready); delay(1900); pillHide()
        delay(700)
    }

    // ---- Stage directions -------------------------------------------------------------------

    private fun beginCycle(skin: View, @DrawableRes icon: Int, @StringRes label: Int) {
        reset()
        listOf(skinWa, skinIg, skinGm).forEach {
            it.visibility = if (it === skin) View.VISIBLE else View.GONE
        }
        captionIcon.setImageResource(icon)
        caption.setText(label)
    }

    private fun reset() {
        listOf(bubble, menu, voice, pill).forEach { it.animate().cancel(); it.alpha = 0f }
        listOf(rowAuto, rowVoice, rowFix).forEach { unhighlight(it) }
        voiceStop.scaleX = 1f; voiceStop.scaleY = 1f
        composerGm.animate().cancel(); composerGm.alpha = 1f
        transcript.text = ""
    }

    private suspend fun bubbleIn() {
        bubble.scaleX = 0.3f; bubble.scaleY = 0.3f
        bubble.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(380)
            .setInterpolator(OvershootInterpolator(2.2f))
            .start()
        delay(420)
    }

    private suspend fun bubblePress() {
        bubble.animate().scaleX(0.85f).scaleY(0.85f).setDuration(110).start()
        delay(130)
        bubble.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
        delay(130)
    }

    private suspend fun menuShow() {
        menu.translationY = dp(36f)
        menu.animate().alpha(1f).translationY(dp(44f)).setDuration(220).start()
        delay(240)
    }

    private fun menuHide() {
        menu.animate().alpha(0f).setDuration(150).start()
    }

    private fun highlight(row: View) = row.setBackgroundResource(R.drawable.bg_demo_row_hl)
    private fun unhighlight(row: View) = row.setBackgroundResource(0)

    private fun pillShow(writing: Boolean, @StringRes text: Int) {
        pillSpinner.visibility = if (writing) View.VISIBLE else View.GONE
        pillIcon.visibility = if (writing) View.GONE else View.VISIBLE
        pill.setBackgroundResource(if (writing) R.drawable.bg_pill else R.drawable.bg_pill_ok)
        pillText.setText(text)
        pill.animate().alpha(1f).setDuration(170).start()
    }

    private fun pillHide() {
        pill.animate().alpha(0f).setDuration(140).start()
    }

    private suspend fun typeInto(target: TextView, text: String) {
        for (i in 1..text.length) {
            target.text = text.substring(0, i)
            delay(22L + Random.nextLong(18))
        }
    }

    private fun str(@StringRes id: Int) = root.context.getString(id)
    private fun dp(value: Float) = value * root.resources.displayMetrics.density
}
