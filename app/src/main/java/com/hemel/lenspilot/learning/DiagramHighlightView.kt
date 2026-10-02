package com.hemel.lenspilot.learning

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The "teacher's finger" over a diagram / picture. The ImageView sits directly
 * behind this view with the same bounds (inside the white diagram panel).
 *
 * FIXES ("হাইলাইট ঠিক জায়গায় পড়ে না" / "শিক্ষকের মতো আঙ্গুল দিয়ে দেখাবে"):
 *
 * 1. The highlight boxes (0-1 fractions) used to be multiplied by THIS view's
 *    width/height, but the picture inside the ImageView is letter-boxed
 *    (fitCenter) — so every box landed in the wrong place and, for a wide
 *    picture, often fell completely outside it. Now the boxes are mapped onto
 *    the picture's real on-screen rectangle ([computeImageRect], from the
 *    bitmap size given to [setImageSize]).
 *
 * 2. Instead of a static rectangle that just blinks in/out, it now behaves like
 *    a teacher: the rest of the picture is dimmed a little, a soft marker-
 *    yellow box glides from one part to the next as the narration moves on
 *    (start_pct/end_pct from /api/learning/lesson), a pulsing outline + tap
 *    ripple + a pointing finger follow it, and a small label names the part.
 *
 * [setProgressPct] is called on a ~50ms tick while the segment's audio plays;
 * the motion itself is animated by this view (frame by frame) so it stays
 * smooth between those ticks.
 */
class DiagramHighlightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Highlight(
        val label: String,
        val x: Float, val y: Float, val w: Float, val h: Float,
        val startPct: Int, val endPct: Int
    )

    private var highlights: List<Highlight> = emptyList()
    private var progressPct: Int = 0

    // Intrinsic size of the bitmap drawn by the ImageView underneath (0 = unknown -> whole view)
    private var imgW = 0
    private var imgH = 0

    // --- animation state -------------------------------------------------
    private var curL = 0f
    private var curT = 0f
    private var curR = 0f
    private var curB = 0f
    private var haveCur = false
    private var fade = 0f
    private var lastFrameMs = 0L

    private val dp = resources.displayMetrics.density

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFEB3B") // highlighter yellow
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#F59E0B") // same amber as the on-screen guide highlight
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#F59E0B")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * dp
        isFakeBoldText = true
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F59E0B")
    }
    private val fingerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 38f * dp
    }

    private val dimPath = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val tmpRect = RectF()
    private val imgRect = RectF()

    /** Tell the view how big the bitmap under it is, so boxes can be mapped
     * onto the letter-boxed picture instead of the whole view. */
    fun setImageSize(widthPx: Int, heightPx: Int) {
        imgW = widthPx
        imgH = heightPx
        invalidate()
    }

    /** [highlightsJson] is the "highlights" array from a diagram / image /
     * highlight segment, as sent by the server — the boxes were already
     * resolved there (see _resolve_learning_highlights() in app.py). */
    fun setHighlights(highlightsJson: JSONArray?) {
        val list = mutableListOf<Highlight>()
        if (highlightsJson != null) {
            for (i in 0 until highlightsJson.length()) {
                val o = highlightsJson.optJSONObject(i) ?: continue
                val w = o.optDouble("w", 0.1).toFloat()
                val h = o.optDouble("h", 0.1).toFloat()
                if (w <= 0f || h <= 0f) continue
                list.add(
                    Highlight(
                        label = o.optString("label", ""),
                        x = o.optDouble("x", 0.0).toFloat(),
                        y = o.optDouble("y", 0.0).toFloat(),
                        w = w, h = h,
                        startPct = o.optInt("start_pct", 0),
                        endPct = o.optInt("end_pct", 100)
                    )
                )
            }
        }
        highlights = list.sortedBy { it.startPct }
        progressPct = 0
        haveCur = false
        fade = 0f
        lastFrameMs = 0L
        invalidate()
    }

    fun setProgressPct(pct: Int) {
        progressPct = pct.coerceIn(0, 100)
        invalidate()
    }

    fun clear() {
        highlights = emptyList()
        haveCur = false
        fade = 0f
        lastFrameMs = 0L
        invalidate()
    }

    /** Where the picture really is inside this view (same math as ImageView's fitCenter). */
    private fun computeImageRect(): RectF {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (imgW <= 0 || imgH <= 0) {
            imgRect.set(0f, 0f, vw, vh)
            return imgRect
        }
        val scale = min(vw / imgW, vh / imgH)
        val dw = imgW * scale
        val dh = imgH * scale
        val l = (vw - dw) / 2f
        val t = (vh - dh) / 2f
        imgRect.set(l, t, l + dw, t + dh)
        return imgRect
    }

    /** Index of the part the "finger" is on right now (-1 = none): the latest
     * highlight that has started — it stays on it until the next one begins,
     * like a finger resting on the spot, and lets go shortly after its end. */
    private fun activeIndex(): Int {
        var idx = -1
        for (i in highlights.indices) {
            if (progressPct >= highlights[i].startPct) idx = i
        }
        if (idx >= 0 && progressPct > highlights[idx].endPct + 12) return -1
        return idx
    }

    // --- "detecting image" scan animation ----------------------------------
    private var scanning = false
    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#22D3EE")
    }

    /** While the server's vision model is still locating parts of the picture, a soft scan
     * band sweeps over it (stops by itself the moment a highlight is shown). */
    fun setScanning(on: Boolean) {
        scanning = on
        invalidate()
    }

    private fun drawScan(canvas: Canvas) {
        val img = computeImageRect()
        val phase = (SystemClock.uptimeMillis() % 1700L) / 1700f
        val band = 46f * dp
        val y = img.top + phase * (img.height() + band) - band
        canvas.save()
        canvas.clipRect(img)
        val steps = 8
        for (i in 0 until steps) {
            scanPaint.alpha = (10 + 26 * (i + 1) / steps)
            val top = y + band * i / steps
            canvas.drawRect(img.left, top, img.right, top + band / steps + 1f, scanPaint)
        }
        scanPaint.alpha = 200
        canvas.drawRect(img.left, y + band, img.right, y + band + 2f * dp, scanPaint)
        canvas.restore()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        if (scanning && highlights.isEmpty()) drawScan(canvas)
        if (highlights.isEmpty()) return

        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameMs == 0L) 16L else (now - lastFrameMs).coerceIn(1L, 64L)
        lastFrameMs = now

        val img = computeImageRect()
        val idx = activeIndex()
        var keepAnimating = false

        if (idx >= 0) {
            val h = highlights[idx]
            // target box on screen, kept inside the picture and never tiny
            var tl = img.left + h.x * img.width()
            var tt = img.top + h.y * img.height()
            var tr = tl + h.w * img.width()
            var tb = tt + h.h * img.height()
            val minSize = 34f * dp
            if (tr - tl < minSize) {
                val c = (tl + tr) / 2f
                tl = c - minSize / 2f
                tr = c + minSize / 2f
            }
            if (tb - tt < minSize) {
                val c = (tt + tb) / 2f
                tt = c - minSize / 2f
                tb = c + minSize / 2f
            }
            tl = max(img.left, tl)
            tt = max(img.top, tt)
            tr = min(img.right, tr)
            tb = min(img.bottom, tb)

            if (!haveCur) {
                // first part: the marker "lands" — grows out of the middle of the target
                curL = (tl + tr) / 2f
                curR = curL
                curT = (tt + tb) / 2f
                curB = curT
                haveCur = true
            }
            // glide toward the target (time constant ~110ms)
            val k = 1f - exp(-dt / 110.0).toFloat()
            curL += (tl - curL) * k
            curT += (tt - curT) * k
            curR += (tr - curR) * k
            curB += (tb - curB) * k
            fade = min(1f, fade + dt / 180f)
            keepAnimating = true // the ripple / outline pulse keep running while pointing
        } else if (fade > 0f) {
            fade = max(0f, fade - dt / 220f)
            keepAnimating = fade > 0f
        }

        if (haveCur && fade > 0.01f) {
            drawPointer(canvas, img, if (idx >= 0) highlights[idx].label else "", now)
        }
        if (keepAnimating) postInvalidateOnAnimation()
    }

    private fun drawPointer(canvas: Canvas, img: RectF, label: String, now: Long) {
        tmpRect.set(curL, curT, curR, curB)
        val radius = 10f * dp
        val phase = (now % 1100L) / 1100f
        val pulse = (sin(phase * 2.0 * Math.PI).toFloat() + 1f) / 2f // 0..1

        // 1) gently dim everything except the highlighted part (spotlight)
        dimPath.reset()
        dimPath.addRect(img, Path.Direction.CW)
        dimPath.addRoundRect(tmpRect, radius, radius, Path.Direction.CW)
        dimPaint.alpha = (70 * fade).toInt()
        canvas.drawPath(dimPath, dimPaint)

        // 2) highlighter-marker fill
        markerPaint.alpha = (95 * fade).toInt()
        canvas.drawRoundRect(tmpRect, radius, radius, markerPaint)

        // 3) pulsing outline
        outlinePaint.strokeWidth = (2.5f + 1.5f * pulse) * dp
        outlinePaint.alpha = (255 * fade).toInt()
        canvas.drawRoundRect(tmpRect, radius, radius, outlinePaint)

        // 4) label chip (above the box if there's room, otherwise below)
        if (label.isNotBlank()) {
            val padH = 8f * dp
            val chipH = 24f * dp
            val textW = labelPaint.measureText(label)
            val chipW = textW + 2 * padH
            var chipL = tmpRect.left
            if (chipL + chipW > img.right) chipL = img.right - chipW
            chipL = max(img.left, chipL)
            var chipT = tmpRect.top - chipH - 4f * dp
            if (chipT < img.top) chipT = tmpRect.bottom + 4f * dp
            labelBgPaint.alpha = (235 * fade).toInt()
            labelPaint.alpha = (255 * fade).toInt()
            canvas.drawRoundRect(chipL, chipT, chipL + chipW, chipT + chipH, chipH / 2f, chipH / 2f, labelBgPaint)
            val fm = labelPaint.fontMetrics
            val baseline = chipT + chipH / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(label, chipL + padH, baseline, labelPaint)
        }

        // 5) fingertip: tap ripple on the bottom edge of the box + a pointing finger under it
        val tipX = (tmpRect.left + tmpRect.right) / 2f
        val tipY = tmpRect.bottom
        ripplePaint.strokeWidth = 2f * dp
        ripplePaint.alpha = (200 * (1f - phase) * fade).toInt()
        canvas.drawCircle(tipX, tipY, (6f + 20f * phase) * dp, ripplePaint)

        val fingerSize = fingerPaint.textSize
        val bob = (3f * dp) * pulse
        var fingerBaseline = tipY + fingerSize * 0.92f + bob // glyph's fingertip touches the box edge
        if (fingerBaseline > height.toFloat()) {
            // no room below — rest the finger inside the lower part of the box instead
            fingerBaseline = tmpRect.bottom - 2f * dp + bob
        }
        fingerPaint.alpha = (255 * fade).toInt()
        canvas.drawText("\uD83D\uDC46", tipX, fingerBaseline, fingerPaint)
    }
}
