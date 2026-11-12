package com.hemel.lenspilot.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.hemel.lenspilot.R
import com.hemel.lenspilot.net.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * এজেন্ট কার্ডের থাম্বনেইল (অ্যাডমিন প্যানেলে আপলোড-করা কভার ছবি) — লগইন-করা সেশন দিয়ে নামে,
 * মেমরি (LruCache) + ডিস্ক-ক্যাশে থাকে। URL-এর `?v=` বদলালে (অ্যাডমিন নতুন ছবি দিলে) নতুন করে নামে।
 * ডাউনলোড-করা এজেন্টের ছবিও ক্যাশে থাকে, তাই অফলাইনেও কার্ড ছবিসহ দেখায়।
 */
internal object ThumbLoader {
    private val mem = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt().coerceAtLeast(4 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    private fun key(url: String): String =
        MessageDigest.getInstance("MD5").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun diskFile(ctx: Context, url: String): File =
        File(File(ctx.cacheDir, "automation_thumbs").apply { mkdirs() }, key(url))

    /** ছবি পাওয়া না গেলে (url ফাঁকা / ছবি নেই / নেটওয়ার্ক) null — কলার ফলব্যাক গ্রেডিয়েন্ট দেখাবে। */
    suspend fun load(ctx: Context, thumbUrl: String): Bitmap? {
        if (thumbUrl.isBlank()) return null
        val k = key(thumbUrl)
        mem.get(k)?.let { return it }
        return withContext(Dispatchers.IO) {
            val f = diskFile(ctx, thumbUrl)
            var bmp = if (f.exists()) decode(f.readBytes()) else null
            if (bmp == null) {
                val bytes = ApiClient.getBytesAuthed(ctx, ctx.getString(R.string.space_base_url), thumbUrl).getOrNull()
                if (bytes != null && bytes.isNotEmpty()) {
                    bmp = decode(bytes)
                    if (bmp != null) runCatching { f.writeBytes(bytes) }
                }
            }
            if (bmp != null) mem.put(k, bmp)
            bmp
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth / sample > 1100) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}
