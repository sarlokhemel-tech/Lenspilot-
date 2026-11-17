package com.hemel.lenspilot.browser.multi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * v162: one tile = a live session. Its own control bar (pause/resume · stop · enlarge) sits on top.
 * In the grid the page is drawn scaled-down at the width of the whole screen (so it looks like a real phone page, not a squeezed one);
 * tapping anywhere on the page enlarges the tile. When enlarged the page is touchable and a person can help the agent.
 */
@SuppressLint("ViewConstructor")
class SessionTileView(
    context: Context,
    val session: BrowserSession,
    /** The width (px) the page is laid out at — the screen width. */
    private val baseWidth: () -> Int,
    private val onToggleMaximize: (SessionTileView) -> Unit,
) : FrameLayout(context) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val headerH = dp(38)

    private val dot = View(context)
    private val title = TextView(context)
    private val sub = TextView(context)
    private val btnToggle = controlButton("⏸")
    private val btnStop = controlButton("■")
    private val btnExpand = controlButton("⤢")
    private val header = LinearLayout(context)
    private val tapCatcher = View(context)
    private val needsBar = LinearLayout(context)
    private val needsText = TextView(context)
    private val replyRow = LinearLayout(context)
    private val replyInput = EditText(context)
    private var isMax = false

    init {
        clipChildren = true
        setBackgroundColor(0xFF0F1115.toInt())

        // ---- header (control bar of this session)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setBackgroundColor(0xF2181A20.toInt())
        header.setPadding(dp(8), 0, dp(2), 0)
        dot.layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(7) }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        title.setTextColor(Color.WHITE); title.textSize = 12f; title.setTypeface(null, Typeface.BOLD)
        title.maxLines = 1; title.ellipsize = TextUtils.TruncateAt.END
        sub.setTextColor(0xFFB9BFCC.toInt()); sub.textSize = 9.5f
        sub.maxLines = 1; sub.ellipsize = TextUtils.TruncateAt.END
        col.addView(title); col.addView(sub)
        header.addView(dot)
        header.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(btnToggle); header.addView(btnStop); header.addView(btnExpand)
        btnToggle.setOnClickListener {
            when (session.state) {
                SessionState.RUNNING, SessionState.NEEDS_YOU -> session.pause()
                SessionState.PAUSED -> session.resume()
                else -> { }
            }
        }
        btnStop.setOnClickListener { session.stop() }
        btnExpand.setOnClickListener { onToggleMaximize(this) }

        // ---- transparent catcher: in the grid a tap on the page enlarges the tile
        tapCatcher.setOnClickListener { onToggleMaximize(this) }

        // ---- "needs you" bar
        needsBar.orientation = LinearLayout.VERTICAL
        needsBar.setBackgroundColor(0xF2FFF1C2.toInt())
        needsBar.setPadding(dp(8), dp(5), dp(8), dp(5))
        needsText.setTextColor(0xFF3A2D00.toInt()); needsText.textSize = 11.5f
        needsText.maxLines = 4; needsText.ellipsize = TextUtils.TruncateAt.END
        needsBar.addView(needsText)
        replyRow.orientation = LinearLayout.HORIZONTAL
        replyRow.gravity = Gravity.CENTER_VERTICAL
        replyInput.hint = "Answer / note for this session"
        replyInput.textSize = 13f
        replyInput.setTextColor(Color.BLACK)
        replyInput.setHintTextColor(0xFF7A7A7A.toInt())
        replyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        replyInput.setSingleLine(true)
        replyInput.setPadding(dp(10), dp(6), dp(10), dp(6))
        replyInput.background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.WHITE) }
        val send = pill("Send", 0xFF2F6BFF.toInt())
        val done = pill("✓ Done", 0xFF2E9E5B.toInt())
        send.setOnClickListener {
            val t = replyInput.text.toString()
            if (t.isNotBlank()) { replyInput.setText(""); session.reply(t) }
        }
        done.setOnClickListener { session.handOverDone() }
        replyRow.addView(replyInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        replyRow.addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        replyRow.addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        replyRow.visibility = View.GONE
        needsBar.addView(replyRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        needsBar.visibility = View.GONE
        needsBar.setOnClickListener { if (!isMax) onToggleMaximize(this) }

        // WebView first (bottom), then catcher, then bars
        (session.web.parent as? ViewGroup)?.removeView(session.web)
        addView(session.web)
        addView(tapCatcher)
        addView(needsBar)
        addView(header)
        refresh()
    }

    private fun controlButton(glyph: String) = TextView(context).apply {
        text = glyph
        setTextColor(Color.WHITE)
        textSize = 15f
        gravity = Gravity.CENTER
        minWidth = dp(36)
        minHeight = dp(36)
        setPadding(dp(4), 0, dp(4), 0)
        isClickable = true
        isFocusable = true
    }

    private fun pill(label: String, color: Int) = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 12.5f
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(7), dp(12), dp(7))
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(color) }
        isClickable = true
    }

    fun setMaximized(on: Boolean) {
        isMax = on
        btnExpand.text = if (on) "⤡" else "⤢"
        tapCatcher.visibility = if (on) View.GONE else View.VISIBLE
        refresh()
        requestLayout()
    }

    /** Re-reads the session (state, status, URL, step) — cheap, call whenever the session changed. */
    fun refresh() {
        title.text = session.spec.title
        val st = session.state
        val dotBg = (dot.background as? GradientDrawable) ?: GradientDrawable().also { dot.background = it }
        dotBg.shape = GradientDrawable.OVAL
        dotBg.setColor(stateColor(st))
        val parts = ArrayList<String>()
        stateLabel(st)?.let { parts.add(it) }
        session.hostName().takeIf { it.isNotBlank() }?.let { parts.add(it) }
        if (session.step > 0 && (st == SessionState.RUNNING || st == SessionState.PAUSED)) parts.add("step ${session.step}")
        val status = when (st) {
            SessionState.RUNNING -> session.statusText.ifBlank { session.lastMessage }
            SessionState.DONE -> session.resultText
            else -> ""
        }
        if (status.isNotBlank()) parts.add(status)
        sub.text = parts.joinToString(" · ")

        btnToggle.visibility = when (st) {
            SessionState.RUNNING, SessionState.NEEDS_YOU, SessionState.PAUSED -> View.VISIBLE
            else -> View.GONE
        }
        btnToggle.text = if (st == SessionState.PAUSED) "▶" else "⏸"
        btnStop.visibility = when (st) {
            SessionState.RUNNING, SessionState.NEEDS_YOU, SessionState.PAUSED, SessionState.IDLE -> View.VISIBLE
            else -> View.GONE
        }

        val needs = st == SessionState.NEEDS_YOU
        needsBar.visibility = if (needs) View.VISIBLE else View.GONE
        if (needs) needsText.text = "🙋 " + session.needsText.ifBlank { "This session needs you" }
        replyRow.visibility = if (needs && isMax) View.VISIBLE else View.GONE
    }

    private fun stateLabel(st: SessionState): String? = when (st) {
        SessionState.IDLE -> "Starting"
        SessionState.PAUSED -> "Paused"
        SessionState.NEEDS_YOU -> "Needs you"
        SessionState.DONE -> "Done"
        SessionState.STOPPED -> "Stopped"
        SessionState.FAILED -> "Failed"
        SessionState.RUNNING -> null
    }

    private fun stateColor(st: SessionState): Int = when (st) {
        SessionState.RUNNING -> 0xFF35C46E.toInt()
        SessionState.IDLE -> 0xFF8E96A8.toInt()
        SessionState.PAUSED -> 0xFFF0B429.toInt()
        SessionState.NEEDS_YOU -> 0xFFFF6B3D.toInt()
        SessionState.DONE -> 0xFF4D8DFF.toInt()
        SessionState.STOPPED -> 0xFF8E96A8.toInt()
        SessionState.FAILED -> 0xFFE5484D.toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        header.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(headerH, MeasureSpec.EXACTLY))
        val areaH = (h - headerH).coerceAtLeast(1)
        // grid: draw the page as wide as the whole screen and shrink it to the tile; enlarged: natural size
        val scale = if (isMax) 1f else (w.toFloat() / baseWidth().coerceAtLeast(w)).coerceIn(0.2f, 1f)
        val vw = (w / scale).toInt().coerceAtLeast(1)
        val vh = (areaH / scale).toInt().coerceAtLeast(1)
        session.web.measure(MeasureSpec.makeMeasureSpec(vw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(vh, MeasureSpec.EXACTLY))
        session.web.pivotX = 0f
        session.web.pivotY = 0f
        session.web.scaleX = scale
        session.web.scaleY = scale
        tapCatcher.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(areaH, MeasureSpec.EXACTLY))
        needsBar.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(areaH / 2, MeasureSpec.AT_MOST))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        header.layout(0, 0, w, headerH)
        session.web.layout(0, headerH, session.web.measuredWidth, headerH + session.web.measuredHeight)
        tapCatcher.layout(0, headerH, w, h)
        needsBar.layout(0, h - needsBar.measuredHeight, w, h)
    }
}
