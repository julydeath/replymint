package com.replymint.bubble

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import com.replymint.R
import com.replymint.accessibility.ReplyMintAccessibilityService
import com.replymint.core.EngineResult
import com.replymint.core.ReplyEngine
import com.replymint.core.UndoStore
import com.replymint.model.ReplyAction
import com.replymint.voice.VoiceInput
import com.replymint.voice.VoiceResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * The floating bubble + its action menu, drawn with [WindowManager].
 *
 * Hosted by [ReplyMintAccessibilityService] (NOT a foreground service), so there is no persistent
 * notification. The service calls [show]/[hide] as the user moves between screens — the bubble
 * appears only where a reply box exists.
 *
 * The overlays are NON-FOCUSABLE on purpose: they must not steal input focus from the app
 * underneath, or the accessibility layer could not find that app's focused reply box. That is
 * also why no `EditText` may ever be placed in one.
 */
class BubbleOverlay(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /**
     * The accessibility service context carries no app theme, so anything resolving `?attr/`
     * would fail against it. Inflate through a wrapper; keep the raw context for WindowManager
     * and Toast, which want the real one.
     */
    private val inflater =
        LayoutInflater.from(ContextThemeWrapper(context, R.style.Theme_ReplyMint_Overlay))

    private var bubbleView: View? = null
    private var menuView: View? = null
    private var voicePanelView: View? = null
    private var voiceInput: VoiceInput? = null
    private var pillView: View? = null
    private var pillHideRunnable: Runnable? = null

    /** Guards the reopen-on-dismiss race — see [toggleMenu]. */
    private var menuClosedAtMs = 0L
    private var smoothedLevel = 0f

    // Persists the bubble's dragged position across hide/show within this instance.
    private val bubbleParams by lazy {
        overlayParams().apply { x = 0; y = context.dp(220) }
    }

    // ---- Visibility (called by the accessibility service) -----------------------------------

    /** Add the bubble if it isn't already on screen. Idempotent. */
    fun show() {
        if (bubbleView != null) return
        val view = inflater.inflate(R.layout.bubble, null)
        view.setOnTouchListener(BubbleTouch())
        runCatching { windowManager.addView(view, bubbleParams) }
        bubbleView = view
    }

    /** Remove the menu/voice panel (if open) and the bubble. Idempotent. */
    fun hide() {
        // No animation here: this fires on every screen change, and a pending animator would
        // leak the window if the service tore down before it finished.
        hideMenu(animate = false)
        hideVoicePanel()
        hidePill(animate = false)
        voiceInput?.release()
        voiceInput = null
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
    }

    fun destroy() {
        hide()
        scope.cancel()
    }

    // ---- Drag / tap -------------------------------------------------------------------------

    /** Drag to move; a tap (little movement) toggles the action menu. */
    private inner class BubbleTouch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = bubbleParams.x; startY = bubbleParams.y
                    dragging = false
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    // A tap must not nudge the bubble, so nothing moves until the platform's own
                    // slop is exceeded (the old TAP_SLOP = 16 raw px was density-dependent and
                    // small enough that a deliberate tap often registered as a drag).
                    if (!dragging && hypot(e.rawX - downX, e.rawY - downY) <= slop) return true
                    dragging = true
                    bubbleParams.x = startX + (e.rawX - downX).toInt()
                    bubbleParams.y = startY + (e.rawY - downY).toInt()
                    clampBubble(v)
                    runCatching { windowManager.updateViewLayout(v, bubbleParams) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (!dragging) toggleMenu()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    return true
                }
            }
            return false
        }
    }

    /**
     * Keep the bubble reachable. The transparent shadow gutter is allowed to hang off the edge,
     * so the bubble itself can sit flush against it rather than floating a pad away.
     */
    private fun clampBubble(view: View) {
        val bounds = screenBounds()
        val pad = context.dp(SHADOW_PAD_DP)
        val width = view.width.takeIf { it > 0 } ?: (context.dp(56) + pad * 2)
        val height = view.height.takeIf { it > 0 } ?: (context.dp(56) + pad * 2)
        bubbleParams.x = bubbleParams.x.coerceIn(bounds.left - pad, bounds.right - width + pad)
        bubbleParams.y = bubbleParams.y.coerceIn(bounds.top - pad, bounds.bottom - height + pad)
    }

    // ---- Action menu ------------------------------------------------------------------------

    private fun toggleMenu() {
        // Tapping the bubble while the menu is open delivers ACTION_OUTSIDE to the menu AND
        // ACTION_DOWN to the bubble — they are separate windows. Without this guard hideMenu()
        // nulls menuView and we immediately reopen, so the menu appears never to close.
        if (SystemClock.uptimeMillis() - menuClosedAtMs < REOPEN_GUARD_MS) return
        if (menuView != null) { hideMenu(); return }

        val view = inflater.inflate(R.layout.bubble_menu, null)
        view.findViewById<View>(R.id.action_auto).setOnClickListener { onAction(ReplyAction.AUTO_REPLY) }
        view.findViewById<View>(R.id.action_voice).setOnClickListener { onAction(ReplyAction.VOICE) }
        view.findViewById<View>(R.id.action_fix).setOnClickListener { onAction(ReplyAction.FIX) }
        view.findViewById<View>(R.id.action_undo).setOnClickListener { onUndo() }

        // Package + TTL only — deliberately no accessibility tree walk on every menu open. If the
        // box turns out to have changed, restore declines honestly at that point.
        val undoable = UndoStore.peek(ReplyMintAccessibilityService.instance?.currentPackage()) != null
        val undoVisibility = if (undoable) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.action_undo).visibility = undoVisibility
        view.findViewById<View>(R.id.undo_divider).visibility = undoVisibility

        val params = panelParams()
        val anchor = positionPanel(view, params)
        view.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) { hideMenu(); true } else false
        }
        runCatching { windowManager.addView(view, params) }
        menuView = view
        view.popIn(anchor)
    }

    private fun hideMenu(animate: Boolean = true) {
        val view = menuView ?: return
        menuView = null                       // null it FIRST so a re-entrant call is a no-op
        menuClosedAtMs = SystemClock.uptimeMillis()
        if (!animate) {
            runCatching { windowManager.removeView(view) }
            return
        }
        view.popOut { runCatching { windowManager.removeView(view) } }
    }

    private fun onAction(action: ReplyAction) {
        hideMenu()
        if (action == ReplyAction.VOICE) startVoiceThenReply() else runReply(action, null)
    }

    private fun onUndo() {
        hideMenu()
        val service = ReplyMintAccessibilityService.instance
            ?: return showPill(Pill.ERROR, context.getString(R.string.undo_none))
        val snapshot = UndoStore.peek(service.currentPackage())
            ?: return showPill(Pill.ERROR, context.getString(R.string.undo_none))

        val restored = service.restoreDraft(snapshot.draft, snapshot.previousText)
        UndoStore.clear()
        // If the box no longer holds our draft the user has typed since — their edit wins.
        if (restored) showPill(Pill.OK, context.getString(R.string.undo_done))
        else showPill(Pill.ERROR, context.getString(R.string.undo_changed))
    }

    // ---- Voice ------------------------------------------------------------------------------

    private fun startVoiceThenReply() {
        showVoicePanel()
        smoothedLevel = 0f
        val transcript = voicePanelView?.findViewById<TextView>(R.id.voice_transcript)
        val wave = voicePanelView?.findViewById<WaveformView>(R.id.voice_wave)
        voiceInput = VoiceInput(context).also { vi ->
            vi.start(object : VoiceInput.Listener {
                override fun onPartial(text: String) {
                    transcript?.text =
                        if (text.isBlank()) context.getString(R.string.voice_hint) else text
                }

                override fun onLevel(rms: Float) {
                    // Fast attack, slow release. Feeding each 40 ms frame straight to the wave
                    // reads as noise; smoothing symmetrically reads as lag.
                    val target = rms.coerceIn(0f, 10f) / 10f
                    val alpha = if (target > smoothedLevel) 0.5f else 0.12f
                    smoothedLevel += (target - smoothedLevel) * alpha
                    wave?.push(smoothedLevel)
                }

                override fun onFinal(result: VoiceResult) {
                    hideVoicePanel()
                    if (result.text.isBlank()) showPill(Pill.ERROR, context.getString(R.string.voice_none))
                    else runReply(ReplyAction.VOICE, result)
                }

                override fun onError(message: String) {
                    hideVoicePanel()
                    showPill(Pill.ERROR, message)
                }
            })
        }
    }

    private fun showVoicePanel() {
        hideVoicePanel()
        val view = inflater.inflate(R.layout.voice_panel, null)
        view.findViewById<View>(R.id.voice_stop).setOnClickListener { voiceInput?.stop() }
        // Deliberately NO FLAG_WATCH_OUTSIDE_TOUCH here: a stray tap must not kill a live
        // recognition session mid-sentence.
        val params = overlayParams()
        val anchor = positionPanel(view, params)
        runCatching { windowManager.addView(view, params) }
        voicePanelView = view
        view.popIn(anchor)
    }

    private fun hideVoicePanel() {
        voicePanelView?.let { runCatching { windowManager.removeView(it) } }
        voicePanelView = null
    }

    private fun runReply(action: ReplyAction, voice: VoiceResult?) {
        showPill(Pill.WRITING, context.getString(R.string.pill_writing))
        scope.launch {
            when (val result = ReplyEngine.run(context.applicationContext, action, voice)) {
                is EngineResult.Success -> showPill(Pill.OK, context.getString(R.string.pill_ready))
                is EngineResult.Error -> showPill(Pill.ERROR, result.message)
            }
        }
    }

    // ---- Status pill (replaces system toasts) -----------------------------------------------

    private enum class Pill { WRITING, OK, ERROR }

    /**
     * Glass pill floating above the host app's composer. Its window is NOT_TOUCHABLE so it can
     * never swallow a tap meant for the app underneath. Only one pill exists at a time — a new
     * state replaces the old one in place.
     */
    private fun showPill(kind: Pill, message: String) {
        hidePill(animate = false)
        val view = inflater.inflate(R.layout.overlay_pill, null)
        val card = view.findViewById<View>(R.id.pill_card)
        val spinner = view.findViewById<View>(R.id.pill_spinner)
        val icon = view.findViewById<ImageView>(R.id.pill_icon)
        spinner.visibility = if (kind == Pill.WRITING) View.VISIBLE else View.GONE
        icon.visibility = if (kind == Pill.WRITING) View.GONE else View.VISIBLE
        when (kind) {
            Pill.WRITING -> card.setBackgroundResource(R.drawable.bg_pill)
            Pill.OK -> {
                card.setBackgroundResource(R.drawable.bg_pill_ok)
                icon.setImageResource(R.drawable.ic_check_circle)
                icon.setColorFilter(context.getColor(R.color.mint))
            }
            Pill.ERROR -> {
                card.setBackgroundResource(R.drawable.bg_pill_err)
                icon.setImageResource(R.drawable.ic_alert)
                icon.setColorFilter(context.getColor(R.color.error))
            }
        }
        view.findViewById<TextView>(R.id.pill_text).text = message

        val params = overlayParams().apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = context.dp(PILL_BOTTOM_DP)
        }
        runCatching { windowManager.addView(view, params) }
        pillView = view

        view.alpha = 0f
        view.translationY = context.dp(12).toFloat()
        view.animate().alpha(1f).translationY(0f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .start()

        // WRITING keeps a long fuse purely as a watchdog: the engine result normally replaces it.
        val ttl = when (kind) {
            Pill.WRITING -> 20_000L
            Pill.OK -> 2_200L
            Pill.ERROR -> 3_000L
        }
        val hide = Runnable { hidePill() }
        pillHideRunnable = hide
        view.postDelayed(hide, ttl)
    }

    private fun hidePill(animate: Boolean = true) {
        val view = pillView ?: return
        pillView = null
        pillHideRunnable?.let(view::removeCallbacks)
        pillHideRunnable = null
        if (!animate) {
            runCatching { windowManager.removeView(view) }
            return
        }
        view.animate().alpha(0f).translationY(context.dp(10).toFloat())
            .setDuration(150)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { runCatching { windowManager.removeView(view) } }
            .start()
    }

    // ---- Positioning ------------------------------------------------------------------------

    /** Which corner a panel grew from — drives both placement and the animation pivot. */
    private enum class Anchor { BELOW_START, BELOW_END, ABOVE_START, ABOVE_END }

    /**
     * Position [view] beside the bubble, flipping side and direction so it stays fully on screen.
     *
     * The view MUST be measured here rather than assumed: the menu is three rows or four
     * depending on whether Undo is offerable, so its height is not a constant.
     */
    private fun positionPanel(view: View, params: WindowManager.LayoutParams): Anchor {
        val bounds = screenBounds()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(bounds.width(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(bounds.height(), View.MeasureSpec.AT_MOST)
        )
        val width = view.measuredWidth
        val height = view.measuredHeight
        val margin = context.dp(8)
        val gap = context.dp(2)
        val bubbleHeight = bubbleView?.height?.takeIf { it > 0 }
            ?: (context.dp(56) + context.dp(SHADOW_PAD_DP) * 2)

        var below = true
        var y = bubbleParams.y + bubbleHeight + gap
        if (y + height > bounds.bottom - margin) {
            below = false
            y = bubbleParams.y - height - gap
        }
        y = y.coerceIn(bounds.top + margin, (bounds.bottom - height - margin).coerceAtLeast(bounds.top + margin))

        var start = true
        var x = bubbleParams.x
        if (x + width > bounds.right - margin) {
            start = false
            x = bubbleParams.x + bubbleHeight - width
        }
        x = x.coerceIn(bounds.left + margin, (bounds.right - width - margin).coerceAtLeast(bounds.left + margin))

        params.x = x
        params.y = y
        return when {
            below && start -> Anchor.BELOW_START
            below -> Anchor.BELOW_END
            start -> Anchor.ABOVE_START
            else -> Anchor.ABOVE_END
        }
    }

    /** Usable overlay area, system-bar insets excluded. */
    private fun screenBounds(): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            Rect(metrics.bounds).apply {
                left += insets.left; top += insets.top
                right -= insets.right; bottom -= insets.bottom
            }
        } else {
            @Suppress("DEPRECATION")
            Point().also { windowManager.defaultDisplay.getRealSize(it) }
                .let { Rect(0, 0, it.x, it.y) }
        }

    // ---- Animation --------------------------------------------------------------------------

    /** Grow out of the bubble: the pivot is the corner the panel is anchored from. */
    private fun View.popIn(anchor: Anchor) {
        pivotX = if (anchor == Anchor.BELOW_START || anchor == Anchor.ABOVE_START) 0f else width.toFloat()
        pivotY = if (anchor == Anchor.BELOW_START || anchor == Anchor.BELOW_END) 0f else height.toFloat()
        scaleX = 0.88f; scaleY = 0.88f; alpha = 0f
        animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(140)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    private fun View.popOut(onEnd: () -> Unit) {
        animate().scaleX(0.92f).scaleY(0.92f).alpha(0f)
            .setDuration(110)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction(onEnd)
            .start()
    }

    // ---- Overlay plumbing -------------------------------------------------------------------

    private fun overlayParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // NOT_FOCUSABLE: never steal focus from the app underneath (accessibility needs its focus).
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /**
     * Params for a dismissable panel. FLAG_WATCH_OUTSIDE_TOUCH yields ACTION_OUTSIDE without
     * taking focus. Note it does not consume the tap — this is a dismiss hook, not a scrim.
     */
    private fun panelParams(): WindowManager.LayoutParams = overlayParams().apply {
        flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
    }

    private companion object {
        /** Must match @dimen/overlay_shadow_pad. */
        const val SHADOW_PAD_DP = 10
        const val REOPEN_GUARD_MS = 250L
        /** Pill offset from the bottom edge — floats above the host app's composer. */
        const val PILL_BOTTOM_DP = 96
    }
}
