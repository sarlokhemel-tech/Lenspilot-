package com.hemel.lenspilot.tools

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * "ELORA" ব্র্যান্ড টাইটেল — সাদা থিমে নরম গ্রেডিয়েন্ট (নীল → বেগুনি → টিল) ও হালকা গ্লো।
 * নতুন লাইব্রেরি নেই; শুধু Paint-এর shader ও shadow layer। টেক্সট আসে string resource elora_name থেকে।
 */
class EloraTitleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var shaderLeft = Float.NaN
    private var shaderWidth = -1f

    init {
        val d = resources.displayMetrics.density
        setShadowLayer(14f * d, 0f, 3f * d, Color.parseColor("#552563EB"))
    }

    override fun onDraw(canvas: Canvas) {
        val l = layout
        val w = if (l != null && l.lineCount > 0) l.getLineWidth(0) else 0f
        if (l != null && w > 0f) {
            val left = compoundPaddingLeft + l.getLineLeft(0)
            if (left != shaderLeft || w != shaderWidth) {
                shaderLeft = left
                shaderWidth = w
                paint.shader = LinearGradient(
                    left, 0f, left + w, 0f,
                    intArrayOf(Color.parseColor("#2563EB"), Color.parseColor("#8B5CF6"), Color.parseColor("#14B8A6")),
                    null,
                    Shader.TileMode.CLAMP
                )
            }
        }
        super.onDraw(canvas)
    }
}
