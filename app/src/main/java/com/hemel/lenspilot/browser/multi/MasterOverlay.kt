package com.hemel.lenspilot.browser.multi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.hypot

/**
 * v162: the master bubble — a small glass bubble in a corner of the screen (draggable, snaps to the left/right edge).
 *   tap        → the glass status card (Stop-all on top, one line per session below)
 *   long press → manual session setup
 * The number on it is the count of sessions that are working right now (computed by code).
 */
@SuppressLint("ViewConstructor")
class MasterBubbleView(
    context: Context,
    private val onBubbleTap: () -> Unit,
    private val onBubbleLongPress: () -> Unit,
) : FrameLayout(context) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val icon = TextView(context)
    private val badge = TextView(context)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var offX = 0f
    private var offY = 0f
    private var dragging = false

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean { if (!dragging) onBubbleTap(); return true }
        override fun onLongPress(e: MotionEvent) {
            if (dragging) return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onBubbleLongPress()
        }
    })

    init {
        val size = dp(54)
        layoutParams = LayoutParams(size, size)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = intArrayOf(0xCCFFFFFF.toInt(), 0x88DDE6FF.toInt())
            orientation = GradientDrawable.Orientation.TL_BR
            setStroke(dp(1), 0xCCFFFFFF.toInt())
        }
        elevation = dp(8).toFloat()
        icon.text = "⚡"
        icon.textSize = 22f
        icon.gravity = Gravity.CENTER
        addView(icon, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        badge.setTextColor(Color.WHITE)
        badge.textSize = 10.5f
        badge.setTypeface(null, Typeface.BOLD)
        badge.gravity = Gravity.CENTER
        badge.minWidth = dp(18)
        badge.setPadding(dp(4), 0, dp(4), 0)
        badge.background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(0xFF2E9E5B.toInt()) }
        addView(badge, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(18), Gravity.TOP or Gravity.END))
        badge.visibility = View.GONE
        contentDescription = "Sessions — tap for status, long press to set up sessions manually"
    }

    /** [running] = sessions working now; [needsYou] = sessions waiting for the person (the badge turns orange). */
    fun setCounts(running: Int, needsYou: Int) {
        val n = running + needsYou
        badge.visibility = if (n > 0) View.VISIBLE else View.GONE
        badge.text = n.toString()
        (badge.background as GradientDrawable).setColor(if (needsYou > 0) 0xFFFF6B3D.toInt() else 0xFF2E9E5B.toInt())
    }

    fun placeAtBottomEnd() {
        val p = parent as? View ?: return
        x = (p.width - width - dp(10)).toFloat()
        y = (p.height - height - dp(110)).toFloat()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        detector.onTouchEvent(e)
        val p = parent as? View
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                offX = x - e.rawX; offY = y - e.rawY
                downX = e.rawX; downY = e.rawY
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > slop) dragging = true
                if (dragging && p != null) {
                    x = (e.rawX + offX).coerceIn(0f, (p.width - width).toFloat().coerceAtLeast(0f))
                    y = (e.rawY + offY).coerceIn(0f, (p.height - height).toFloat().coerceAtLeast(0f))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging && p != null) {
                    val target = if (x + width / 2f < p.width / 2f) dp(10).toFloat() else (p.width - width - dp(10)).toFloat()
                    animate().x(target).setDuration(160).start()
                }
            }
        }
        return true
    }
}

/**
 * v162: the glass status card. Top: the big STOP ALL button. Below: one line per session —
 * where it is, what step, what it is doing. All text comes from the sessions' own state (code), no LLM.
 */
@SuppressLint("ViewConstructor")
class StatusCardView(
    context: Context,
    private val onStopAll: () -> Unit,
    private val onClose: () -> Unit,
    private val onRowClick: (index: Int) -> Unit,
) : LinearLayout(context) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val stopAll = TextView(context)
    private val headline = TextView(context)
    private val rows = LinearLayout(context)
    private val maxListH = (resources.displayMetrics.heightPixels * 0.42f).toInt()

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            colors = intArrayOf(0xEEFFFFFF.toInt(), 0xDDE9EEFF.toInt())
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            setStroke(dp(1), 0xFFFFFFFF.toInt())
        }
        elevation = dp(12).toFloat()

        val top = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        stopAll.text = "⏻  Stop all"
        stopAll.setTextColor(Color.WHITE)
        stopAll.textSize = 15f
        stopAll.setTypeface(null, Typeface.BOLD)
        stopAll.gravity = Gravity.CENTER
        stopAll.setPadding(dp(16), dp(11), dp(16), dp(11))
        stopAll.background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xFFE5484D.toInt()) }
        stopAll.isClickable = true
        stopAll.setOnClickListener { onStopAll() }
        top.addView(stopAll, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = TextView(context).apply {
            text = "✕"; textSize = 18f; setTextColor(0xFF4A4F5C.toInt()); gravity = Gravity.CENTER
            minWidth = dp(44); minHeight = dp(44); isClickable = true
            setOnClickListener { onClose() }
        }
        top.addView(close, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        addView(top, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        headline.setTextColor(0xFF2A2E3A.toInt())
        headline.textSize = 13f
        headline.setTypeface(null, Typeface.BOLD)
        addView(headline, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10); bottomMargin = dp(4) })

        rows.orientation = VERTICAL
        val scroll = object : ScrollView(context) {
            override fun onMeasure(w: Int, h: Int) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxListH, MeasureSpec.AT_MOST))
            }
        }
        scroll.addView(rows, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun render(headlineText: String, lines: List<SessionLine>, anyLive: Boolean) {
        headline.text = headlineText
        stopAll.alpha = if (anyLive) 1f else 0.4f
        stopAll.isEnabled = anyLive
        stopAll.text = if (anyLive) "⏻  Stop all" else "⏻  All stopped"
        rows.removeAllViews()
        for (ln in lines) rows.addView(row(ln), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun row(ln: SessionLine): View {
        val r = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(7), dp(4), dp(7))
            isClickable = true
            setOnClickListener { onRowClick(ln.index - 1) }
        }
        val dot = View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colorOf(ln.state)) }
        }
        r.addView(dot, LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) })
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        val t = TextView(context).apply {
            text = "${ln.index} · ${ln.title}"
            setTextColor(0xFF1E2230.toInt()); textSize = 13.5f; setTypeface(null, Typeface.BOLD)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val bits = ArrayList<String>()
        if (ln.host.isNotBlank()) bits.add(ln.host)
        if (ln.step > 0 && (ln.state == SessionState.RUNNING || ln.state == SessionState.PAUSED)) bits.add("step ${ln.step}")
        if (ln.status.isNotBlank()) bits.add(ln.status)
        val s = TextView(context).apply {
            text = bits.joinToString(" · ").ifBlank { "—" }
            setTextColor(0xFF5A6072.toInt()); textSize = 11.5f
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(t); col.addView(s)
        r.addView(col, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val st = TextView(context).apply {
            text = labelOf(ln.state)
            setTextColor(colorOf(ln.state)); textSize = 11.5f; setTypeface(null, Typeface.BOLD)
        }
        r.addView(st, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        return r
    }

    private fun labelOf(st: SessionState) = when (st) {
        SessionState.IDLE -> "Starting"
        SessionState.RUNNING -> "Running"
        SessionState.PAUSED -> "Paused"
        SessionState.NEEDS_YOU -> "Needs you"
        SessionState.DONE -> "Done"
        SessionState.STOPPED -> "Stopped"
        SessionState.FAILED -> "Failed"
    }

    private fun colorOf(st: SessionState): Int = when (st) {
        SessionState.RUNNING -> 0xFF22A559.toInt()
        SessionState.IDLE -> 0xFF8E96A8.toInt()
        SessionState.PAUSED -> 0xFFD79A00.toInt()
        SessionState.NEEDS_YOU -> 0xFFE8541E.toInt()
        SessionState.DONE -> 0xFF2F6BFF.toInt()
        SessionState.STOPPED -> 0xFF8E96A8.toInt()
        SessionState.FAILED -> 0xFFD93036.toInt()
    }
}
