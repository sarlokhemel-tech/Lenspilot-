package com.hemel.lenspilot.learning

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import org.json.JSONArray
import org.json.JSONObject

/**
 * Draws the "highlighter" over a diagram (ImageView sits directly behind
 * this, same bounds) — one rounded rectangle per highlight in the current
 * diagram segment, faded in/out based on how far the segment's narration
 * has played (start_pct/end_pct, 0-100, as composed by the AI in
 * /api/learning/lesson). [setProgressPct] is meant to be called on a
 * ~50ms tick while that segment's audio is playing.
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

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.parseColor("#F59E0B") // warning_caution — matches the on-screen guide's highlight palette
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#33F59E0B") // same color, low alpha
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCF59E0B")
    }

    /** [highlightsJson] is the "highlights" array from a diagram segment,
     * as sent by the server — see _sanitize_learning_segments() in app.py
     * for the exact shape. */
    fun setHighlights(highlightsJson: JSONArray?) {
        val list = mutableListOf<Highlight>()
        if (highlightsJson != null) {
            for (i in 0 until highlightsJson.length()) {
                val h = highlightsJson.optJSONObject(i) ?: continue
                list.add(
                    Highlight(
                        label = h.optString("label", ""),
                        x = h.optDouble("x", 0.0).toFloat(),
                        y = h.optDouble("y", 0.0).toFloat(),
                        w = h.optDouble("w", 0.1).toFloat(),
                        h = h.optDouble("h", 0.1).toFloat(),
                        startPct = h.optInt("start_pct", 0),
                        endPct = h.optInt("end_pct", 100)
                    )
                )
            }
        }
        highlights = list
        progressPct = 0
        invalidate()
    }

    fun setProgressPct(pct: Int) {
        progressPct = pct.coerceIn(0, 100)
        invalidate()
    }

    fun clear() {
        highlights = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (highlights.isEmpty() || width == 0 || height == 0) return
        for (h in highlights) {
            if (progressPct < h.startPct || progressPct > h.endPct) continue
            val rect = RectF(
                h.x * width, h.y * height,
                (h.x + h.w) * width, (h.y + h.h) * height
            )
            canvas.drawRoundRect(rect, 16f, 16f, fillPaint)
            canvas.drawRoundRect(rect, 16f, 16f, boxPaint)
            if (h.label.isNotBlank()) {
                val textWidth = labelPaint.measureText(h.label)
                val labelLeft = rect.left
                val labelTop = (rect.top - 44f).coerceAtLeast(4f)
                canvas.drawRoundRect(
                    RectF(labelLeft, labelTop, labelLeft + textWidth + 20f, labelTop + 40f),
                    10f, 10f, labelBgPaint
                )
                canvas.drawText(h.label, labelLeft + 10f, labelTop + 28f, labelPaint)
            }
        }
    }
}
