package com.hemel.lenspilot.chat

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ScrollView

/**
 * wrap_content উচ্চতার ScrollView, কিন্তু [maxHeightPx]-এর বেশি লম্বা হয় না (তখন স্ক্রল হয়)।
 *
 * v126: চ্যাট-লিস্ট (উল্লম্ব RecyclerView) আর এজেন্ট-কার্ডের পেজার (অনুভূমিক RecyclerView)-এর ভেতরে বসলেও
 * এখন নিজে টেনে স্ক্রল হয় — আগে অভিভাবক RecyclerView টাচ কেড়ে নিত, তাই কার্ডের লম্বা লেখা কেটে যেত, টানলেও বাকিটা আসত না।
 *  - ওপর-নিচে টানলে: কার্ডের লেখা স্ক্রল (শেষ মাথায় পৌঁছালে তবেই চ্যাট-লিস্ট নড়ে)।
 *  - ডানে-বামে টানলে: পেজার অন্য এজেন্টে যায়।
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    var maxHeightPx: Int = 0
        set(v) { field = v; requestLayout() }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = if (maxHeightPx > 0)
            View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST)
        else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, spec)
    }

    private fun scrollable() = canScrollVertically(-1) || canScrollVertically(1)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                parent?.requestDisallowInterceptTouchEvent(scrollable())
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(ev.x - downX)
                val dy = Math.abs(ev.y - downY)
                if (dx > slop && dx > dy * 1.5f) {
                    // পাশে টানা — পেজার পেজ বদলাক
                    parent?.requestDisallowInterceptTouchEvent(false)
                } else if (dy > slop) {
                    // আঙুল ওপরে উঠলে লেখা নিচে যায় (+১), আঙুল নিচে নামলে লেখা ওপরে (−১)
                    val dir = if (ev.y < downY) 1 else -1
                    parent?.requestDisallowInterceptTouchEvent(canScrollVertically(dir))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.dispatchTouchEvent(ev)
    }
}
