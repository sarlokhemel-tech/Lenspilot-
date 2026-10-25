package com.hemel.lenspilot.chat

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView

/** wrap_content উচ্চতার ScrollView, কিন্তু [maxHeightPx]-এর বেশি লম্বা হয় না (তখন স্ক্রল হয়)। */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    var maxHeightPx: Int = 0
        set(v) { field = v; requestLayout() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = if (maxHeightPx > 0)
            View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST)
        else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, spec)
    }
}
