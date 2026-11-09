package com.hemel.lenspilot.learning

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * v31 — রেকর্ড করা ক্লাস (Class Recording)।
 *
 * REC চালু রেখে ক্লাস করলে যা-যা চালানো হয়েছে (ক্লাসের শিরোনাম/ধাপ-পরিকল্পনা + প্রতিটা সেগমেন্ট — বোর্ডের লেখা,
 * অডিও, ছবি/ডায়াগ্রাম, হাইলাইট বক্স সহ) ফোনে সেভ হয়। পরে একই ক্লাস আবার দেখতে সার্ভারে কিছুই জেনারেট করতে হয় না
 * (কোনো টোকেন/নেটওয়ার্ক খরচ নেই) — সেভ-করা সেগমেন্টগুলোই হুবহু আগের মতো আবার চলে।
 *
 * ডিস্ক-গঠন (`filesDir/class_recordings/<id>/`):
 *   meta.json    — শিরোনাম, প্রশ্ন, সময়, সেগমেন্ট-সংখ্যা, আনুমানিক দৈর্ঘ্য, note-force
 *   events.jsonl — প্রতি লাইনে একটা JSON event ("meta" | "plan" | "user_image" | "segment")। লাইন ধরে ধরে পড়া হয়,
 *                  তাই বড় ক্লাসও একসাথে মেমরিতে আসে না।
 *   class.mp4    — (ঐচ্ছিক) স্ক্রিন-রেকর্ডের মতো ভিডিও ফাইল — ডাউনলোড করার জন্য ([ClassVideoRecorder])
 *
 * সব লেখা একটা আলাদা থ্রেডে (single-thread executor) হয়, তাই ক্লাস চলার সময় UI আটকায় না এবং লাইনগুলো ক্রমে থাকে।
 */
object ClassRecordingStore {
    private const val DIR = "class_recordings"
    const val EVENTS = "events.jsonl"
    const val META = "meta.json"
    const val VIDEO = "class.mp4"

    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "class-rec-writer").apply { isDaemon = true }
    }

    data class Meta(
        val id: String,
        val title: String,
        val topic: String,
        val createdAt: Long,
        val durationMs: Long,
        val segments: Int,
        val sizeBytes: Long,
        val hasVideo: Boolean,
        val noteForce: Int
    )

    fun root(context: Context): File = File(context.applicationContext.filesDir, DIR).apply { mkdirs() }
    fun dir(context: Context, id: String): File = File(root(context), safeId(id))
    fun eventsFile(context: Context, id: String): File = File(dir(context, id), EVENTS)
    fun videoFile(context: Context, id: String): File = File(dir(context, id), VIDEO)

    /** পথের জন্য নিরাপদ id (শুধু অক্ষর/সংখ্যা/-/_) — ফাইল-পাথ ছেড়ে বেরোনোর সুযোগ নেই। */
    private fun safeId(id: String): String = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "x" }

    fun hasVideo(context: Context, id: String): Boolean =
        videoFile(context, id).let { it.exists() && it.length() > 1024 }

    /** একটা চলমান রেকর্ডিং। ক্লাস চলার সময় এতে event লেখা হয়; শেষে [finish]। */
    class Session(context: Context, val id: String, title: String, topic: String, private val noteForce: Int) {
        private val appCtx = context.applicationContext
        private val createdAt = System.currentTimeMillis()
        @Volatile var title: String = title
        @Volatile var topic: String = topic
        @Volatile private var segments = 0
        @Volatile private var durationMs = 0L
        @Volatile private var finished = false

        init {
            dir(appCtx, id).mkdirs()
            writeMeta()
        }

        /** একটা event লাইন যোগ করে (ডিস্ক-লেখা ব্যাকগ্রাউন্ডে)। */
        fun appendEvent(event: JSONObject) {
            if (finished) return
            val line = try { event.toString() } catch (_: Throwable) { return }
            if (event.optString("type") == "segment") {
                segments++
                durationMs += event.optJSONObject("segment")?.optLong("duration_ms", 0L) ?: 0L
                writeMeta()   // প্রতি সেগমেন্টে ছোট মেটা-ফাইল হালনাগাদ — অ্যাপ মাঝপথে বন্ধ হলেও এ পর্যন্ত রেকর্ড টিকে থাকে
            }
            val f = eventsFile(appCtx, id)
            writer.execute {
                try {
                    java.io.FileOutputStream(f, true).use { out ->
                        out.write(line.toByteArray(Charsets.UTF_8))
                        out.write('\n'.code)
                    }
                } catch (_: Throwable) { /* ডিস্ক ভরা ইত্যাদি — ক্লাস চলা বন্ধ করা হয় না */ }
            }
        }

        fun appendSegment(segment: JSONObject) {
            appendEvent(JSONObject().put("type", "segment").put("segment", segment))
        }

        private fun writeMeta() {
            val snapshot = JSONObject()
                .put("id", id)
                .put("title", title)
                .put("topic", topic)
                .put("created_at", createdAt)
                .put("duration_ms", durationMs)
                .put("segments", segments)
                .put("note_force", noteForce)
                .toString()
            val f = File(dir(appCtx, id), META)
            writer.execute { try { f.writeText(snapshot, Charsets.UTF_8) } catch (_: Throwable) {} }
        }

        /** রেকর্ডিং শেষ: মেটা সেভ। কোনো সেগমেন্ট না থাকলে (কিছুই চালানো হয়নি) ফোল্ডারটাই মুছে ফেলা হয়
         * — ভিডিও পরে আসার সম্ভাবনা থাকলে [keepIfEmpty] = true। */
        fun finish(keepIfEmpty: Boolean = false) {
            if (finished) return
            finished = true
            if (segments == 0 && !keepIfEmpty) {
                val d = dir(appCtx, id)
                writer.execute { try { d.deleteRecursively() } catch (_: Throwable) {} }
            } else {
                writeMeta()
            }
        }

        val segmentCount: Int get() = segments
    }

    /** পরের কাজ (যেমন ফাইল পড়া) যেন আগের সব লেখা শেষ হওয়ার পরে হয় — writer-queue-তে নিজেকে ঢুকিয়ে অপেক্ষা। */
    fun flush() {
        try { writer.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Throwable) {}
    }

    fun list(context: Context): List<Meta> {
        flush()
        val out = mutableListOf<Meta>()
        val dirs = root(context).listFiles() ?: return emptyList()
        for (d in dirs) {
            if (!d.isDirectory) continue
            val m = readMeta(context, d) ?: continue
            if (m.segments > 0) out.add(m)
        }
        return out.sortedByDescending { it.createdAt }
    }

    fun get(context: Context, id: String): Meta? {
        flush()
        return readMeta(context, dir(context, id))
    }

    private fun readMeta(context: Context, d: File): Meta? {
        val mf = File(d, META)
        if (!mf.exists()) return null
        return try {
            val o = JSONObject(mf.readText(Charsets.UTF_8))
            val id = o.optString("id").ifBlank { d.name }
            val video = File(d, VIDEO)
            val events = File(d, EVENTS)
            Meta(
                id = id,
                title = o.optString("title"),
                topic = o.optString("topic"),
                createdAt = o.optLong("created_at"),
                durationMs = o.optLong("duration_ms"),
                segments = o.optInt("segments"),
                sizeBytes = (if (events.exists()) events.length() else 0L) + (if (video.exists()) video.length() else 0L),
                hasVideo = video.exists() && video.length() > 1024,
                noteForce = o.optInt("note_force")
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun delete(context: Context, id: String) {
        flush()
        try { dir(context, id).deleteRecursively() } catch (_: Throwable) {}
    }

    /** events.jsonl-এর প্রতিটা লাইন ধরে [onEvent] ডাকে। [onEvent] false ফেরত দিলে পড়া থামে। ব্লকিং — IO থ্রেডে ডাকো। */
    fun forEachEvent(context: Context, id: String, onEvent: (JSONObject) -> Boolean) {
        flush()
        val f = eventsFile(context, id)
        if (!f.exists()) return
        BufferedReader(InputStreamReader(FileInputStream(f), Charsets.UTF_8), 64 * 1024).use { br ->
            while (true) {
                val line = br.readLine() ?: break
                if (line.isBlank()) continue
                val ev = try { JSONObject(line) } catch (_: Throwable) { continue }
                if (!onEvent(ev)) break
            }
        }
    }

    /** ভিডিও ফাইল বদলে রাখার জন্য নতুন ফাইলের জায়গা (আগেরটা মুছে)। */
    fun newVideoTarget(context: Context, id: String): File {
        val f = videoFile(context, id)
        dir(context, id).mkdirs()
        try { if (f.exists()) f.delete() } catch (_: Throwable) {}
        return f
    }

    // ------------------------------------------------------------------------------------------
    // PDF নোটের জন্য: রেকর্ড থেকে ব্লাকবোর্ডের লেখা (ও ছবি) ক্রমানুসারে বের করা
    // ------------------------------------------------------------------------------------------

    /** ক্লাসের ব্লাকবোর্ডে যা উঠেছিল (লেখা + ছবি) চালানোর ক্রমে। ব্লকিং — IO থ্রেডে ডাকো। */
    fun loadNoteItems(context: Context, id: String): List<ClassNotesPdf.NoteItem> {
        val items = mutableListOf<ClassNotesPdf.NoteItem>()
        forEachEvent(context, id) { ev ->
            when (ev.optString("type")) {
                "user_image" -> {
                    val b64 = ev.optString("image_base64")
                    if (b64.isNotBlank() && !ev.isNull("image_base64")) {
                        compressForNotes(b64)?.let { items.add(ClassNotesPdf.NoteItem(jpeg = it)) }
                    }
                }
                "segment" -> {
                    val seg = ev.optJSONObject("segment")
                    if (seg != null) {
                        when (seg.optString("type")) {
                            "write", "write_silent" -> {
                                val t = seg.optString("text").trim()
                                if (t.isNotEmpty()) items.add(ClassNotesPdf.NoteItem(text = t))
                            }
                            "diagram" -> {
                                if (!seg.isNull("diagram_base64")) {
                                    compressForNotes(seg.optString("diagram_base64"))?.let { items.add(ClassNotesPdf.NoteItem(jpeg = it)) }
                                }
                            }
                            "image" -> {
                                if (!seg.isNull("image_base64")) {
                                    compressForNotes(seg.optString("image_base64"))?.let { items.add(ClassNotesPdf.NoteItem(jpeg = it)) }
                                }
                            }
                        }
                    }
                }
            }
            true
        }
        return items
    }

    /** base64 ছবি → PDF-এর জন্য ছোট (লম্বা দিক ≤ ১০০০px) সাদা-ব্যাকগ্রাউন্ড JPEG bytes। */
    fun compressForNotes(b64: String): ByteArray? = try {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        compressBytesForNotes(bytes)
    } catch (_: Throwable) {
        null
    }

    fun compressBytesForNotes(bytes: ByteArray, maxSide: Int = 1000): ByteArray? {
        var bmp: Bitmap? = null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2
            bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val longest = maxOf(bmp.width, bmp.height)
            if (longest > maxSide) {
                val s = maxSide.toFloat() / longest
                val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
                if (scaled !== bmp) { bmp.recycle(); bmp = scaled }
            }
            // স্বচ্ছ PNG (ডায়াগ্রাম) যেন কালো না দেখায় — সাদা ব্যাকগ্রাউন্ডে বসানো
            val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(flat)
            c.drawColor(android.graphics.Color.WHITE)
            c.drawBitmap(bmp, 0f, 0f, null)
            val out = ByteArrayOutputStream()
            flat.compress(Bitmap.CompressFormat.JPEG, 85, out)
            flat.recycle()
            out.toByteArray()
        } catch (_: Throwable) {
            null
        } finally {
            try { bmp?.recycle() } catch (_: Throwable) {}
        }
    }
}
