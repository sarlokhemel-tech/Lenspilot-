package com.hemel.lenspilot.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import com.hemel.lenspilot.Prefs
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Vision-primary পথের ছবি তৈরি: স্ক্রিনশট → JPG। v29: ডিফল্ট রঙিন + মাঝারি রেজুলেশন (রঙ টোকেন বাড়ায় না, কিন্তু
 * টগল/সক্রিয়-ধূসর/লাল-নীল বাটন চিনতে অনেক সাহায্য করে)। সেটিংস থেকে রঙ বন্ধ করা ও রেজুলেশন কম/বেশি করা যায়।
 * [boost] = AI আগের বার পায়নি/নিশ্চিত হয়নি, বা ধাপটা অফেরতযোগ্য → (অটো-বুস্ট চালু থাকলে) এই এক কলে আসল
 * রেজুলেশন + রঙিন, সেটিংস যা-ই থাক।
 *
 * হাইলাইটের জায়গা মডেল ০–১০০০ স্কেলে বলে, আর সার্ভার সেটা [origW]×[origH] (আসল স্ক্রিন) দিয়ে পিক্সেলে
 * বদলায় — তাই ছবি কতটা ছোট করা হলো তাতে হাইলাইটের অবস্থান বদলায় না।
 */
object ScreenshotProcessor {

    class Result(
        val base64: String,
        /** স্ক্রিনশটের আসল (ছোট করার আগের) মাপ — সার্ভারে screen_width/height হিসেবে যায়। */
        val origW: Int,
        val origH: Int,
        val sentW: Int,
        val sentH: Int
    )

    private fun maxLongSide(context: Context, boosted: Boolean): Int {
        if (boosted) return Int.MAX_VALUE
        return when (Prefs.visionResolution(context)) {
            Prefs.VISION_RES_FULL -> Int.MAX_VALUE
            Prefs.VISION_RES_MID -> 1800
            Prefs.VISION_RES_TINY -> 1000
            else -> 1400
        }
    }

    /** [src] অবশ্যই software bitmap (hardware bitmap-এ Canvas আঁকা যায় না)। src-কে recycle করে না। */
    fun process(context: Context, src: Bitmap, boost: Boolean = false): Result {
        val boosted = boost && Prefs.visionAutoBoost(context)
        val color = boosted || Prefs.visionColor(context)
        val w = src.width
        val h = src.height
        val scale = min(1f, maxLongSide(context, boosted).toFloat() / max(w, h).toFloat())
        val tw = max(1, (w * scale).roundToInt())
        val th = max(1, (h * scale).roundToInt())

        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (!color) {
            // স্কেল + সাদাকালো এক ধাপে
            paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        canvas.drawBitmap(src, Rect(0, 0, w, h), Rect(0, 0, tw, th), paint)

        val stream = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, if (boosted) 92 else if (color) 88 else 68, stream)
        out.recycle()
        return Result(Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP), w, h, tw, th)
    }
}
