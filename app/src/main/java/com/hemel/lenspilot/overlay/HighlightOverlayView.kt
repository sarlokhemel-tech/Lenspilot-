package com.hemel.lenspilot.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View

/**
 * Draws every current [Highlight] as a rounded-rect outline in its own
 * semantic color, sized exactly to that element's bounding box, PLUS a
 * readable glass caption card with the actual guidance sentence (e.g.
 * "ফেসবুক আইকনে ট্যাপ করুন") — so the user isn't only shown a box, they're
 * TOLD what to do, on screen, in text, next to the box. Hosted in a
 * TYPE_ACCESSIBILITY_OVERLAY window with FLAG_NOT_TOUCHABLE (set up in
 * LenspilotAccessibilityService), so taps pass straight through to the
 * real app underneath.
 *
 * SELF-CALIBRATING POSITION: accessibility node bounds are absolute
 * physical-screen pixel coordinates. In theory this window sits at
 * screen (0,0) so those coordinates can be used directly — in practice,
 * some OEM skins/API levels still inset an "always on top" accessibility
 * overlay by the status bar or a cutout even with FLAG_LAYOUT_NO_LIMITS.
 * Rather than guess more window flags blind, this measures the view's
 * OWN actual on-screen location every draw and cancels out whatever
 * offset is really there — this is what should finally make the box
 * line up regardless of device.
 */
class HighlightOverlayView(context: Context) : View(context) {

    private var highlights: List<Highlight> = emptyList()
    private var caption: String? = null
    private var thinking: Boolean = false

    private val locationBuf = IntArray(2)

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 13f
        alpha = 70
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 26f
    }

    // ---- caption card ----
    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.parseColor("#E6111827") // near-black glass
    }
    private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = android.graphics.Color.parseColor("#332563EB")
    }
    private val cardTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 34f
    }
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = cardBgPaint.color
    }

    /** Atomic update — highlights + the caption that explains them arrive
     * together so the two are never a frame out of sync. Pass
     * thinking=true for a lightweight "AI স্ক্রিন দেখছে…" state shown the
     * INSTANT a check starts, before the network reply is back — this is
     * what fixes the "no idea if anything is happening" slow-feeling. */
    fun render(newHighlights: List<Highlight>, newCaption: String?, isThinking: Boolean = false) {
        highlights = newHighlights
        caption = newCaption
        thinking = isThinking
        invalidate()
    }

    fun clear() {
        highlights = emptyList()
        caption = null
        thinking = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        getLocationOnScreen(locationBuf)
        val offsetX = -locationBuf[0]
        val offsetY = -locationBuf[1]
        canvas.save()
        canvas.translate(offsetX.toFloat(), offsetY.toFloat())

        var primaryTarget: Rect? = null
        for (h in highlights) {
            if (h.bbox.width() <= 0 || h.bbox.height() <= 0) continue
            if (primaryTarget == null) primaryTarget = h.bbox

            val rectF = RectF(h.bbox)
            val cornerRadius = 14f

            glowPaint.color = h.color
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, glowPaint)
            strokePaint.color = h.color
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, strokePaint)

            if (h.label.isNotBlank() && h.bbox.top > 60) {
                val textWidth = labelTextPaint.measureText(h.label)
                val chipLeft = h.bbox.left.toFloat()
                val chipTop = (h.bbox.top - 44).toFloat().coerceAtLeast(4f)
                val chipRight = chipLeft + textWidth + 24f
                val chipBottom = chipTop + 36f
                labelBgPaint.color = h.color
                canvas.drawRoundRect(RectF(chipLeft, chipTop, chipRight, chipBottom), 10f, 10f, labelBgPaint)
                canvas.drawText(h.label, chipLeft + 12f, chipBottom - 9f, labelTextPaint)
            }
        }

        val text = if (thinking) "Lenspilot স্ক্রিন দেখছে…" else caption
        if (!text.isNullOrBlank()) {
            drawCaptionCard(canvas, text, primaryTarget)
        }

        canvas.restore()
    }

    private fun drawCaptionCard(canvas: Canvas, text: String, target: Rect?) {
        val screenW = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val screenH = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val margin = 28f
        val maxCardWidth = (screenW - margin * 2).coerceAtMost(720f)
        val paddingH = 28f
        val paddingV = 20f

        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, cardTextPaint, (maxCardWidth - paddingH * 2).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(4f, 1f)
            .build()

        val cardWidth = maxCardWidth
        val cardHeight = layout.height + paddingV * 2

        // Prefer placing the card just above the highlighted target (like a
        // speech bubble pointing down at it); if there's no room above, or
        // no target at all, place it centered near the top of the screen.
        var cardLeft = ((screenW - cardWidth) / 2f)
        var cardTop: Float
        var pointerDown = false
        var pointerUp = false

        if (target != null) {
            cardLeft = (target.centerX() - cardWidth / 2f)
                .coerceIn(margin, screenW - cardWidth - margin)
            val spaceAbove = target.top - margin
            if (spaceAbove > cardHeight + 24f) {
                cardTop = target.top - cardHeight - 20f
                pointerDown = true
            } else {
                cardTop = (target.bottom + 20f).coerceAtMost(screenH - cardHeight - margin)
                pointerUp = true
            }
        } else {
            cardTop = 130f * (resources.displayMetrics.density)
        }

        val cardRect = RectF(cardLeft, cardTop, cardLeft + cardWidth, cardTop + cardHeight)
        canvas.drawRoundRect(cardRect, 20f, 20f, cardBgPaint)
        canvas.drawRoundRect(cardRect, 20f, 20f, cardBorderPaint)

        if (target != null) {
            val px = target.centerX().toFloat().coerceIn(cardLeft + 24f, cardLeft + cardWidth - 24f)
            if (pointerDown) {
                val py = cardTop + cardHeight
                val path = android.graphics.Path().apply {
                    moveTo(px - 12f, py); lineTo(px + 12f, py); lineTo(px, py + 14f); close()
                }
                canvas.drawPath(path, pointerPaint)
            } else if (pointerUp) {
                val py = cardTop
                val path = android.graphics.Path().apply {
                    moveTo(px - 12f, py); lineTo(px + 12f, py); lineTo(px, py - 14f); close()
                }
                canvas.drawPath(path, pointerPaint)
            }
        }

        canvas.save()
        canvas.translate(cardLeft + paddingH, cardTop + paddingV)
        layout.draw(canvas)
        canvas.restore()
    }
}
