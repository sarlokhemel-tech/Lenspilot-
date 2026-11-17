package com.hemel.lenspilot.overlay

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

/**
 * v93: স্ক্রিনের আসল মাপ (নেভিগেশন-বার, নচ সহ) — Accessibility নোডের bounds এই স্থানাঙ্কেই আসে।
 * আগে resources.displayMetrics ব্যবহার হতো; কিছু ফোনে/অ্যান্ড্রয়েড-সংস্করণে সেটার উচ্চতা নেভিগেশন-বার বাদ দিয়ে দেয়,
 * ফলে নিচের বারের (bottom nav) উপাদান ছাঁটা পড়ত বা বাদ যেত, আর সার্ভারে box_2d → পিক্সেল রূপান্তরও সামান্য সরে যেত।
 */
object ScreenMetrics {
    /** [প্রস্থ, উচ্চতা] আসল পিক্সেলে, বর্তমান রোটেশন অনুযায়ী। */
    @Suppress("DEPRECATION")
    fun realSize(ctx: Context): IntArray {
        try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val d = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (d != null) {
                val m = DisplayMetrics()
                d.getRealMetrics(m)
                if (m.widthPixels > 0 && m.heightPixels > 0) return intArrayOf(m.widthPixels, m.heightPixels)
            }
        } catch (_: Exception) { /* নিচের ফলব্যাক */ }
        val m = ctx.resources.displayMetrics
        return intArrayOf(m.widthPixels, m.heightPixels)
    }
}
