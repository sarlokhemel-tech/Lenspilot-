package com.hemel.lenspilot.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * v124 — Gboard-এর মতো সাইজ-এডিট ওভারলে।
 *  • পুরো কীবোর্ডের চারপাশে টিল বর্ডার + দৃশ্যমান হ্যান্ডেল: ওপরে দাগ (উচ্চতা), বাঁ/ডান দাগ (প্রস্থ), মাঝের ✥ (সরানো)
 *  • যেকোনো বাটনে চাপলে সেটা সিলেক্ট হয়: ডানে দাগ (প্রস্থ), নিচে দাগ (উচ্চতা), বাঁ-ওপরে "A" গোল (লেখার সাইজ)
 *  • "রিসেট" ও "হয়ে গেছে" বাটন
 * হ্যান্ডেলের বাইরের টাচ নিচের কীবোর্ডে চলে যায় (onTouchEvent false ফেরত দেয়)।
 */
class KeyboardResizeOverlay(ctx: Context, private val host: Host) : FrameLayout(ctx) {

    interface Host {
        /** সিলেক্ট-করা বাটনের স্ক্রিন-বাউন্ডস; না থাকলে null */
        fun selectedBounds(): Rect?
        /** পুরো কীবোর্ডের স্ক্রিন-বাউন্ডস */
        fun frameBounds(): Rect?
        fun buttonBegin()
        /** kind: 0 = প্রস্থ, 1 = উচ্চতা, 2 = লেখা */
        fun buttonApply(kind: Int, dx: Float, dy: Float)
        fun buttonEnd()
        fun frameBegin()
        /** kind: 0 = উচ্চতা (ওপরের দাগ), 1 = বাঁ দাগ, 2 = ডান দাগ, 3 = সরানো */
        fun frameApply(kind: Int, dx: Float, dy: Float)
        fun frameEnd()
        fun onReset()
        fun onDone()
        /** আলাদা উইন্ডোতে (অনুবাদ কার্ড) বাটন বাছতে: স্ক্রিন-পয়েন্টের বাটন সিলেক্ট করলে true */
        fun pick(rawX: Float, rawY: Float): Boolean = false
    }

    /** ✥ সরানো-হ্যান্ডেল লুকাও (পুরো-কীবোর্ড নেই এমন জায়গায়, যেমন অনুবাদ কার্ড) */
    fun hideMove() { moveHandle.visibility = View.GONE }

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val teal = Color.parseColor("#80CBC4")
    private val tealDark = Color.parseColor("#26A69A")

    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); color = teal }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = teal }
    private val selLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); color = tealDark }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = tealDark }
    private val knobText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = dp(13f); isFakeBoldText = true
    }

    private val pills = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun pill(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 15f
        setTextColor(Color.parseColor("#0B3D38"))
        gravity = Gravity.CENTER
        setPadding(dp(22f).toInt(), 0, dp(22f).toInt(), 0)
        background = GradientDrawable().apply { setColor(teal); cornerRadius = dp(26f) }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private val moveHandle = TextView(ctx).apply {
        text = "✥"
        textSize = 20f
        setTextColor(Color.parseColor("#0B3D38"))
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(teal); shape = GradientDrawable.OVAL }
        isClickable = true
    }

    // ---- টাচ-অবস্থা ----
    private val NONE = 0
    private val B_W = 1
    private val B_H = 2
    private val B_T = 3
    private val F_TOP = 4
    private val F_LEFT = 5
    private val F_RIGHT = 6
    private var mode = NONE
    private var sx = 0f
    private var sy = 0f

    init {
        setWillNotDraw(false)
        val h = dp(50f).toInt()
        pills.addView(pill("↺  রিসেট") { host.onReset() }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, h))
        pills.addView(moveHandle, LinearLayout.LayoutParams(dp(50f).toInt(), h).apply {
            marginStart = dp(14f).toInt(); marginEnd = dp(14f).toInt()
        })
        pills.addView(pill("✓  হয়ে গেছে") { host.onDone() }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, h))
        addView(pills, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // ✥ — টেনে পুরো কীবোর্ড সরানো
        var mx = 0f
        var my = 0f
        moveHandle.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mx = e.rawX; my = e.rawY
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    host.frameBegin()
                }
                MotionEvent.ACTION_MOVE -> {
                    host.frameApply(3, e.rawX - mx, e.rawY - my)
                    this@KeyboardResizeOverlay.invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    host.frameEnd()
                    this@KeyboardResizeOverlay.refresh()
                }
            }
            true
        }
    }

    /** সিলেকশন/মাপ বদলালে ডাকো — হ্যান্ডেল আবার আঁকে, বাটন-সারি নিরাপদ জায়গায় সরায়। */
    fun refresh() {
        val sel = host.selectedBounds()
        val lp = pills.layoutParams as LayoutParams
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val newGravity = when {
            sel == null -> Gravity.CENTER
            (sel.centerY() - loc[1]) < height / 2 -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            else -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        if (lp.gravity != newGravity) {
            lp.gravity = newGravity
            lp.topMargin = if ((newGravity and Gravity.TOP) == Gravity.TOP) dp(6f).toInt() else 0
            lp.bottomMargin = if ((newGravity and Gravity.BOTTOM) == Gravity.BOTTOM) dp(6f).toInt() else 0
            pills.layoutParams = lp
        }
        invalidate()
    }

    // ---- জ্যামিতি (লোকাল কোঅর্ডিনেট) ----
    private fun local(r: Rect): RectF {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return RectF((r.left - loc[0]).toFloat(), (r.top - loc[1]).toFloat(), (r.right - loc[0]).toFloat(), (r.bottom - loc[1]).toFloat())
    }

    private fun rightHandle(s: RectF) = RectF(s.right - dp(3f), s.centerY() - dp(14f), s.right + dp(3f), s.centerY() + dp(14f))
    private fun bottomHandle(s: RectF) = RectF(s.centerX() - dp(14f), s.bottom - dp(3f), s.centerX() + dp(14f), s.bottom + dp(3f))
    private fun textKnobCenter(s: RectF) = floatArrayOf(s.left, s.top)

    private fun frameTop(f: RectF) = RectF(f.centerX() - dp(30f), f.top + dp(2f), f.centerX() + dp(30f), f.top + dp(8f))
    private fun frameLeft(f: RectF) = RectF(f.left + dp(2f), f.centerY() - dp(26f), f.left + dp(8f), f.centerY() + dp(26f))
    private fun frameRight(f: RectF) = RectF(f.right - dp(8f), f.centerY() - dp(26f), f.right - dp(2f), f.centerY() + dp(26f))

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        host.frameBounds()?.let { fb ->
            val f = local(fb)
            val inset = dp(1.5f)
            c.drawRoundRect(RectF(f.left + inset, f.top + inset, f.right - inset, f.bottom - inset), dp(14f), dp(14f), border)
            c.drawRoundRect(frameTop(f), dp(3f), dp(3f), bar)
            c.drawRoundRect(frameLeft(f), dp(3f), dp(3f), bar)
            c.drawRoundRect(frameRight(f), dp(3f), dp(3f), bar)
        }
        host.selectedBounds()?.let { sb ->
            val s = local(sb)
            c.drawRoundRect(s, dp(8f), dp(8f), selLine)
            val rh = rightHandle(s); val bh = bottomHandle(s)
            c.drawRoundRect(rh, dp(3f), dp(3f), knob)
            c.drawRoundRect(bh, dp(3f), dp(3f), knob)
            val k = textKnobCenter(s)
            c.drawCircle(k[0], k[1], dp(11f), knob)
            c.drawText("A", k[0], k[1] + dp(4.5f), knobText)
        }
    }

    private fun hit(r: RectF, x: Float, y: Float, slop: Float) =
        x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop

    private fun hitTest(x: Float, y: Float): Int {
        host.selectedBounds()?.let { sb ->
            val s = local(sb)
            val k = textKnobCenter(s)
            if (Math.hypot((x - k[0]).toDouble(), (y - k[1]).toDouble()) <= dp(20f)) return B_T
            if (hit(rightHandle(s), x, y, dp(14f))) return B_W
            if (hit(bottomHandle(s), x, y, dp(14f))) return B_H
        }
        host.frameBounds()?.let { fb ->
            val f = local(fb)
            if (hit(frameTop(f), x, y, dp(16f))) return F_TOP
            if (hit(frameLeft(f), x, y, dp(16f))) return F_LEFT
            if (hit(frameRight(f), x, y, dp(16f))) return F_RIGHT
        }
        return NONE
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = hitTest(e.x, e.y)
                if (mode == NONE) {
                    if (host.pick(e.rawX, e.rawY)) { refresh(); return true }
                    return false          // নিচের কী/বাটন পাক
                }
                sx = e.rawX; sy = e.rawY
                parent?.requestDisallowInterceptTouchEvent(true)
                if (mode >= F_TOP) host.frameBegin() else host.buttonBegin()
                return true
            }
            MotionEvent.ACTION_MOVE -> if (mode != NONE) {
                val dx = e.rawX - sx
                val dy = e.rawY - sy
                when (mode) {
                    B_W -> host.buttonApply(0, dx, dy)
                    B_H -> host.buttonApply(1, dx, dy)
                    B_T -> host.buttonApply(2, dx, dy)
                    F_TOP -> host.frameApply(0, dx, dy)
                    F_LEFT -> host.frameApply(1, dx, dy)
                    F_RIGHT -> host.frameApply(2, dx, dy)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (mode != NONE) {
                if (mode >= F_TOP) host.frameEnd() else host.buttonEnd()
                mode = NONE
                refresh()
                return true
            }
        }
        return mode != NONE
    }
}
