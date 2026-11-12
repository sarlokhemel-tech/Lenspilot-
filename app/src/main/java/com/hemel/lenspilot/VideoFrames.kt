package com.hemel.lenspilot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * v81: "+" → ভিডিও। AI-র পাইপলাইন একটা ছবি নেয়, তাই ভিডিও থেকে সমান দূরত্বে কয়েকটা ফ্রেম তুলে সময়ের ক্রমে একটা
 * "কনট্যাক্ট-শিট" ছবিতে সাজানো হয় (প্রতিটা ফ্রেমে সময়ের লেবেল)। কোনো সার্ভার-বদল লাগে না; মেইন চ্যাট ও বাবল-চ্যাট দুটোই এটা ব্যবহার করে।
 * ভারী কাজ — ব্যাকগ্রাউন্ড থ্রেডে ডাকতে হবে।
 */
object VideoFrames {

    class Sheet(val name: String, val durationSec: Int, val frames: Int, val bitmap: Bitmap) {
        /** AI-কে বলার নোট: ছবিটা আসলে কী। */
        fun context(): String =
            "[Attached video: $name — ${fmt(durationSec)} long. The image below is a contact sheet of $frames frames taken at " +
                "equal intervals, in time order (left to right, top to bottom); each frame is labelled with its time. " +
                "Treat it as the video: describe or answer about what happens over time, not as a single photo.]"

        fun label(): String = "🎬 $name (${fmt(durationSec)})"

        fun toBase64(): String {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }

    private const val MAX_FRAMES = 9          // ৩×৩
    private const val SHEET_WIDTH = 1280
    private const val MAX_BYTES = 200L * 1024 * 1024

    fun fmt(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

    /** ব্যর্থ হলে (পড়া যায় না/খুব বড়/ফ্রেম নেই) null। */
    fun extract(ctx: Context, uri: Uri): Sheet? {
        val name = AttachmentExtractor.displayName(ctx, uri)
        val size = runCatching {
            ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        if (size > MAX_BYTES) return null
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durMs <= 0L) return null
            val n = when {
                durMs < 4_000 -> 4
                durMs < 15_000 -> 6
                else -> MAX_FRAMES
            }
            val cols = if (n <= 4) 2 else 3
            val cellW = SHEET_WIDTH / cols
            val got = ArrayList<Pair<Bitmap, Long>>()
            for (i in 0 until n) {
                // মাঝখানের বিন্দু নিই — প্রথম/শেষ ফ্রেম প্রায়ই কালো
                val tMs = durMs * (2 * i + 1) / (2 * n)
                val f = r.getFrameAtTime(tMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                got.add(f to tMs)
            }
            if (got.isEmpty()) return null
            val first = got[0].first
            val cellH = (cellW.toFloat() * first.height / first.width).toInt().coerceAtLeast(1)
            val sheetRows = (got.size + cols - 1) / cols
            val sheet = Bitmap.createBitmap(cellW * cols, cellH * sheetRows, Bitmap.Config.ARGB_8888)
            val c = Canvas(sheet)
            c.drawColor(Color.BLACK)
            val bg = Paint().apply { color = 0xAA000000.toInt() }
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = cellW * 0.075f; isFakeBoldText = true
            }
            got.forEachIndexed { i, (bmp, tMs) ->
                val x = (i % cols) * cellW
                val y = (i / cols) * cellH
                val scaled = Bitmap.createScaledBitmap(bmp, cellW, cellH, true)
                c.drawBitmap(scaled, x.toFloat(), y.toFloat(), null)
                val label = fmt((tMs / 1000).toInt())
                val w = tp.measureText(label)
                c.drawRect(x + 6f, y + 6f, x + 6f + w + 16f, y + 6f + tp.textSize + 10f, bg)
                c.drawText(label, x + 14f, y + 6f + tp.textSize, tp)
                if (scaled !== bmp) scaled.recycle()
                bmp.recycle()
            }
            Sheet(name, (durMs / 1000).toInt(), got.size, sheet)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}
