package com.hemel.lenspilot.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.Outline
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * GPT-স্টাইল "মার্ক-করা স্ক্রিনশট" — একটা ছবির ওপর লাল বৃত্ত/রাউন্ড-বক্স + নম্বর-ব্যাজ + তীর এঁকে দেখায়
 * ইউজারকে ঠিক কোথায় চাপতে/দেখতে হবে। সার্ভার (app.py → _dg_attach_images) শুধু স্বাভাবিকীকৃত box
 * [ymin, xmin, ymax, xmax] (০–১০০০) পাঠায়; আঁকা হয় এখানে, তাই ছবি ক্রিস্প থাকে আর সার্ভারে
 * ছবি প্রসেসিং লাগে না।
 *
 * দুই মোড:
 *  - [Mode.FOCUS]  — ছোট থাম্বনেইল: প্রথম (বা [focusIndex]-তম) মার্কের চারপাশ ক্রপ করে ফিল করে, যাতে ছোট
 *    জায়গাতেও বাটনটা বোঝা যায়।
 *  - [Mode.FIT]    — ফুলস্ক্রিন: পুরো ছবি, সব মার্ক নম্বরসহ; পিঞ্চ-জুম/প্যান, ডাবল-ট্যাপে রিসেট।
 */
class MarkedImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { FOCUS, FIT }

    /** [box] = [ymin, xmin, ymax, xmax], ০–১০০০ স্কেল। */
    data class Mark(val number: Int, val box: IntArray)

    private var bitmap: Bitmap? = null
    private var marks: List<Mark> = emptyList()
    private var mode: Mode = Mode.FOCUS
    private var focusIndex: Int = 0

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val red = Color.parseColor("#E53935")
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = red; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red; style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 229, 57, 53); style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val bgPaint = Paint().apply { color = Color.parseColor("#0B0F14") }

    // ফুলস্ক্রিন জুম/প্যান
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val newZoom = (zoom * d.scaleFactor).coerceIn(1f, 5f)
            val f = newZoom / zoom
            // ফোকাস-পয়েন্ট স্থির রেখে জুম
            panX = (panX - d.focusX + width / 2f) * f + d.focusX - width / 2f
            panY = (panY - d.focusY + height / 2f) * f + d.focusY - height / 2f
            zoom = newZoom
            clampPan()
            invalidate()
            return true
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (zoom <= 1f) return false
            panX -= dx; panY -= dy
            clampPan()
            invalidate()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1f) { zoom = 1f; panX = 0f; panY = 0f } else { zoom = 2.2f }
            clampPan()
            invalidate()
            return true
        }
    })

    init {
        // ছোট থাম্বনেইলে গোলাকার কোণ — setCornerRadiusDp() দিয়ে
        clipToOutline = false
    }

    fun setCornerRadiusDp(r: Float) {
        val px = dp(r)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, px)
            }
        }
        clipToOutline = true
    }

    fun setContent(bmp: Bitmap?, marks: List<Mark>, mode: Mode, focusIndex: Int = 0) {
        this.bitmap = bmp
        this.marks = marks
        this.mode = mode
        this.focusIndex = focusIndex.coerceIn(0, max(0, marks.size - 1))
        zoom = 1f; panX = 0f; panY = 0f
        invalidate()
    }

    private fun clampPan() {
        val maxX = (width * (zoom - 1f)) / 2f
        val maxY = (height * (zoom - 1f)) / 2f
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode != Mode.FIT) return super.onTouchEvent(event)
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    // ---- আঁকা -----------------------------------------------------------

    /** bitmap-পিক্সেল → ভিউ-পিক্সেল ম্যাপিং (src ক্রপ-রেক্ট + স্কেল + অফসেট)। */
    private class Mapping(val srcL: Float, val srcT: Float, val scale: Float, val offX: Float, val offY: Float) {
        fun x(bx: Float) = offX + (bx - srcL) * scale
        fun y(by: Float) = offY + (by - srcT) * scale
    }

    private fun computeMapping(bmp: Bitmap, vw: Float, vh: Float): Pair<Mapping, RectF> {
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        if (mode == Mode.FIT) {
            val s = min(vw / bw, vh / bh)
            val dw = bw * s
            val dh = bh * s
            val m = Mapping(0f, 0f, s, (vw - dw) / 2f, (vh - dh) / 2f)
            return m to RectF(0f, 0f, bw, bh)
        }
        // FOCUS: মার্কের চারপাশ ক্রপ, ভিউ ভরাট (center-crop-এর মতো)
        val aspect = vw / vh
        var cx = bw / 2f
        var cy = bh / 2f
        var cw = bw
        var ch = bw / aspect
        val mk = marks.getOrNull(focusIndex)
        if (mk != null) {
            val b = mk.box
            val bx0 = b[1] / 1000f * bw; val bx1 = b[3] / 1000f * bw
            val by0 = b[0] / 1000f * bh; val by1 = b[2] / 1000f * bh
            cx = (bx0 + bx1) / 2f; cy = (by0 + by1) / 2f
            val boxW = bx1 - bx0; val boxH = by1 - by0
            cw = max(boxW * 3.4f, bw * 0.5f)
            ch = cw / aspect
            if (ch < boxH * 2.6f) { ch = boxH * 2.6f; cw = ch * aspect }
        }
        if (cw > bw) { cw = bw; ch = cw / aspect }
        if (ch > bh) { ch = bh; cw = ch * aspect }
        var l = cx - cw / 2f
        var t = cy - ch / 2f
        l = l.coerceIn(0f, max(0f, bw - cw))
        t = t.coerceIn(0f, max(0f, bh - ch))
        val s = vw / cw
        return Mapping(l, t, s, 0f, 0f) to RectF(l, t, l + cw, t + ch)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        if (mode == Mode.FIT) canvas.drawRect(0f, 0f, vw, vh, bgPaint)

        val (map, src) = computeMapping(bmp, vw, vh)

        canvas.save()
        if (mode == Mode.FIT) {
            canvas.translate(panX, panY)
            canvas.scale(zoom, zoom, vw / 2f, vh / 2f)
        }
        val dst = RectF(map.x(src.left), map.y(src.top), map.x(src.right), map.y(src.bottom))
        canvas.drawBitmap(bmp, android.graphics.Rect(src.left.toInt(), src.top.toInt(), src.right.toInt(), src.bottom.toInt()), dst, bitmapPaint)
        canvas.restore()

        // মার্ক — স্ক্রিন-স্পেসে আঁকা (জুম করলেও দাগের মোটা/ব্যাজ-আকার একই থাকে)
        val tx: (Float) -> Float = { x -> if (mode == Mode.FIT) (x - vw / 2f) * zoom + vw / 2f + panX else x }
        val ty: (Float) -> Float = { y -> if (mode == Mode.FIT) (y - vh / 2f) * zoom + vh / 2f + panY else y }
        val list = if (mode == Mode.FOCUS) listOfNotNull(marks.getOrNull(focusIndex)) else marks
        val thumb = mode == Mode.FOCUS
        for (mk in list) {
            val b = mk.box
            val l = tx(map.x(b[1] / 1000f * bmp.width))
            val r = tx(map.x(b[3] / 1000f * bmp.width))
            val t = ty(map.y(b[0] / 1000f * bmp.height))
            val bt = ty(map.y(b[2] / 1000f * bmp.height))
            drawMark(canvas, RectF(l, t, r, bt), mk.number, vw, vh, thumb)
        }
    }

    private fun drawMark(canvas: Canvas, box: RectF, number: Int, vw: Float, vh: Float, thumb: Boolean) {
        val pad = dp(if (thumb) 3f else 6f)
        val rb = RectF(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad)
        val sw = dp(if (thumb) 2.2f else 3.2f)
        strokePaint.strokeWidth = sw
        glowPaint.strokeWidth = sw + dp(2f)
        val corner = min(rb.width(), rb.height()) / 2f
        val rad = min(corner, dp(26f))
        canvas.drawRoundRect(rb, rad, rad, dimPaint)
        canvas.drawRoundRect(rb, rad, rad, glowPaint)
        canvas.drawRoundRect(rb, rad, rad, strokePaint)

        // তীর: বক্সের সবচেয়ে ফাঁকা দিক থেকে ঢোকে
        val badgeR = dp(if (thumb) 8f else 13f)
        val len = dp(if (thumb) 22f else 44f)
        val cx = rb.centerX()
        val cy = rb.centerY()
        val room = floatArrayOf(rb.top, vh - rb.bottom, rb.left, vw - rb.right) // up, down, left, right
        var dir = 0
        for (i in 1..3) if (room[i] > room[dir]) dir = i
        val ux: Float
        val uy: Float
        when (dir) {
            0 -> { ux = 0f; uy = -1f }
            1 -> { ux = 0f; uy = 1f }
            2 -> { ux = -1f; uy = 0f }
            else -> { ux = 1f; uy = 0f }
        }
        // তীরের মাথা = বক্সের কিনারা; লেজ = তার থেকে len দূরে (স্ক্রিনের ভেতরে ক্ল্যাম্প)
        val half = if (ux != 0f) rb.width() / 2f else rb.height() / 2f
        val headX = cx + ux * half
        val headY = cy + uy * half
        var tailX = headX + ux * len
        var tailY = headY + uy * len
        tailX = tailX.coerceIn(badgeR + sw, vw - badgeR - sw)
        tailY = tailY.coerceIn(badgeR + sw, vh - badgeR - sw)
        val ang = atan2(headY - tailY, headX - tailX)
        val lineEndX = headX
        val lineEndY = headY
        if (hypot(lineEndX - tailX, lineEndY - tailY) > badgeR) {
            canvas.drawLine(tailX, tailY, lineEndX, lineEndY, glowPaint)
            canvas.drawLine(tailX, tailY, lineEndX, lineEndY, strokePaint)
            val ah = dp(if (thumb) 6f else 10f)
            val path = Path().apply {
                moveTo(lineEndX, lineEndY)
                lineTo(lineEndX - ah * cos(ang - 0.45f), lineEndY - ah * sin(ang - 0.45f))
                lineTo(lineEndX - ah * cos(ang + 0.45f), lineEndY - ah * sin(ang + 0.45f))
                close()
            }
            canvas.drawPath(path, fillPaint)
        }
        // নম্বর-ব্যাজ
        canvas.drawCircle(tailX, tailY, badgeR + dp(1.5f), ringPaint)
        canvas.drawCircle(tailX, tailY, badgeR, fillPaint)
        textPaint.textSize = badgeR * 1.25f
        val fm = textPaint.fontMetrics
        canvas.drawText(number.toString(), tailX, tailY - (fm.ascent + fm.descent) / 2f, textPaint)
    }
}
