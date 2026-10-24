package com.hemel.lenspilot.learning

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.Window
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * v31 — ক্লাসের "স্ক্রিন রেকর্ড": শুধু ক্লাসের স্ক্রিন (অ্যাপের নিজের উইন্ডো) MP4 ভিডিওতে — সাথে ক্লাসের কথা (অডিও)।
 *
 * কেন সিস্টেম MediaProjection নয়: এতে কোনো অনুমতি-ডায়ালগ/নোটিফিকেশন লাগে না, ফোনের অন্য অ্যাপ/নোটিফিকেশন ভিডিওতে
 * আসে না ("শুধু ক্লাস রেকর্ড"), আর রেকর্ডিং চলাকালীন অন্য কিছুর অডিও মেশে না।
 *
 * কীভাবে কাজ করে:
 *  - ভিডিও: প্রতি ~১০০ms-এ [PixelCopy] দিয়ে উইন্ডোর ছবি নিয়ে H.264 এনকোডারে (ByteBuffer ইনপুট, নিজস্ব সময়-রেখা) পাঠানো হয়।
 *  - অডিও: ক্লাসের প্রতিটা কথার ক্লিপ (সার্ভারের WAV/MP3) যে মুহূর্তে বাজা শুরু হয় সেই সময়-রেখার সময়সহ জমা থাকে; শেষে
 *    সেগুলো ডিকোড করে একটানা ৪৪.১kHz মনো AAC ট্র্যাক বানানো হয় (কথার ফাঁকে নীরবতা) — ফোনের স্পিকার থেকে ধরা নয়, তাই
 *    আওয়াজ পরিষ্কার।
 *  - সময়-রেখা থামানো যায়: ⏸ চাপলে/ডায়ালগ খুললে/অ্যাপ ব্যাকগ্রাউন্ডে গেলে সময় থেমে যায় (ভিডিও ও অডিও একসাথে),
 *    ▶-এ আবার চলে — তাই ভিডিওতে কোনো মরা-সময় থাকে না।
 *  - শেষে ভিডিও-ট্র্যাক ও অডিও-ট্র্যাক একটা MP4-তে মেশানো হয় (re-encode ছাড়া)।
 *
 * সীমাবদ্ধতা: সার্ভার অডিও না দিলে ফোনের নিজস্ব TTS যে সেগমেন্ট বলে, সেটা ভিডিওতে আসে না (শুধু ছবি)।
 */
class ClassVideoRecorder(
    activity: Activity,
    private val outFile: File,
    private val workDir: File
) {
    companion object {
        private const val FPS = 10
        private const val FRAME_INTERVAL_MS = 1000L / FPS
        private const val MAX_SIDE = 1280
        private const val VIDEO_BITRATE = 1_000_000
        private const val AUDIO_RATE = 44100
        private const val AUDIO_BITRATE = 64_000
    }

    private val windowRef = WeakReference<Window>(activity.window)
    private val thread = HandlerThread("class-video").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- সময়-রেখা (থামানো যায়) ----
    private val lock = Any()
    private var startNs = 0L
    private var pausedTotalNs = 0L
    private var pausedSinceNs = 0L
    private var paused = false
    @Volatile private var running = false
    @Volatile private var stopping = false

    // ---- ভিডিও ----
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var vTrack = -1
    private var muxerStarted = false
    private var tw = 0
    private var th = 0
    private var srcBmp: Bitmap? = null
    private var frameBmp: Bitmap? = null
    private var frameCanvas: Canvas? = null
    private var pixels: IntArray? = null
    private val bufInfo = MediaCodec.BufferInfo()
    private var lastPtsUs = -1L
    private var framesWritten = 0
    private val videoOnly = File(workDir, "video_only.mp4")
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    // ---- অডিও ক্লিপ ----
    private class Clip(val file: File, val startUs: Long)
    private val clips = java.util.Collections.synchronizedList(mutableListOf<Clip>())
    private var clipCounter = 0

    /** ক্লাসের এখন পর্যন্ত রেকর্ড-হওয়া সময় (থামানো সময় বাদে), মিলিসেকেন্ডে। */
    fun elapsedMs(): Long = nowUs() / 1000L

    private fun nowUs(): Long = synchronized(lock) {
        val ref = if (paused) pausedSinceNs else System.nanoTime()
        ((ref - startNs - pausedTotalNs) / 1000L).coerceAtLeast(0L)
    }

    /** UI থ্রেড থেকে ডাকো (উইন্ডোর মাপ লাগে)। এনকোডার তৈরি না হলে false — তখন ভিডিও হবে না, বাকি রেকর্ডিং (রিপ্লে) অক্ষত। */
    fun start(): Boolean {
        val win = windowRef.get() ?: return false
        val decor = win.peekDecorView() ?: return false
        val w = decor.width
        val h = decor.height
        if (w <= 0 || h <= 0) return false
        try { workDir.mkdirs() } catch (_: Throwable) {}
        if (!setupEncoder(w, h)) {
            releaseVideo()
            return false
        }
        synchronized(lock) {
            startNs = System.nanoTime()
            pausedTotalNs = 0L
            paused = false
        }
        running = true
        handler.post(tick)
        return true
    }

    /** ⏸/▶ — সময়-রেখা থামায়/চালায় (ক্লাসের pause-এর সাথে একসাথে)। */
    fun setPaused(p: Boolean) {
        synchronized(lock) {
            if (p && !paused) {
                paused = true
                pausedSinceNs = System.nanoTime()
            } else if (!p && paused) {
                paused = false
                pausedTotalNs += System.nanoTime() - pausedSinceNs
            }
        }
    }

    /** একটা কথার ক্লিপ ঠিক এই মুহূর্তে বাজা শুরু হয়েছে। ফাইলটা কপি করে রাখা হয় (বাজানোর পর মূলটা মুছে যায়)। UI থ্রেডে ডাকো। */
    fun onAudioClip(file: File, mime: String) {
        if (!running) return
        val startUs = nowUs()
        try {
            val ext = if (mime.contains("mpeg") || mime.contains("mp3")) ".mp3" else ".wav"
            val dest = File(workDir, "clip_${clipCounter++}$ext")
            file.copyTo(dest, overwrite = true)
            clips.add(Clip(dest, startUs))
        } catch (_: Throwable) {
            // একটা ক্লিপ কপি না হলে সেই অংশ নীরব থাকবে — রেকর্ডিং চলবে
        }
    }

    /** রেকর্ডিং শেষ: ভিডিও/অডিও চূড়ান্ত করে [outFile]-এ MP4 লেখে। [onDone] UI থ্রেডে ডাকা হয় (সফল হলে true)। */
    fun stop(onDone: (Boolean) -> Unit) {
        if (stopping) return
        stopping = true
        running = false
        handler.post {
            var ok = false
            try {
                ok = finalizeAll()
            } catch (_: Throwable) {
                ok = false
            }
            cleanup()
            mainHandler.post { onDone(ok) }
            thread.quitSafely()
        }
    }

    /** আউটপুট না বানিয়ে সব ছেড়ে দেয় (start() ব্যর্থ হলে/বাতিল করলে)। */
    fun abort() {
        if (stopping) return
        stopping = true
        running = false
        handler.post {
            cleanup()
            thread.quitSafely()
        }
    }

    // =================================================================================
    // ফ্রেম ধরা
    // =================================================================================
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val t0 = SystemClock.elapsedRealtime()
            captureFrame {
                if (running) {
                    val dt = SystemClock.elapsedRealtime() - t0
                    handler.postDelayed(this, max(10L, FRAME_INTERVAL_MS - dt))
                }
            }
        }
    }

    private fun isPausedNow(): Boolean = synchronized(lock) { paused }

    private fun captureFrame(done: () -> Unit) {
        val win = windowRef.get()
        if (win == null || isPausedNow() || encoder == null) {
            done()
            return
        }
        val decor = win.peekDecorView()
        if (decor == null || decor.width <= 0 || decor.height <= 0) {
            done()
            return
        }
        val w = decor.width
        val h = decor.height
        var src = srcBmp
        if (src == null || src.width != w || src.height != h) {
            try { src?.recycle() } catch (_: Throwable) {}
            src = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { null }
            srcBmp = src
        }
        if (src == null) {
            done()
            return
        }
        val ptsUs = nowUs()
        try {
            PixelCopy.request(win, src, { result ->
                if (result == PixelCopy.SUCCESS && encoder != null) {
                    try { encodeFrame(ptsUs) } catch (_: Throwable) {}
                }
                done()
            }, handler)
        } catch (_: Throwable) {
            done()
        }
    }

    private fun encodeFrame(ptsUs: Long) {
        val enc = encoder ?: return
        val src = srcBmp ?: return
        val fb = frameBmp ?: return
        val canvas = frameCanvas ?: return
        val px = pixels ?: return
        canvas.drawBitmap(src, null, Rect(0, 0, tw, th), scalePaint)
        fb.getPixels(px, 0, tw, 0, 0, tw, th)

        val idx = enc.dequeueInputBuffer(20_000)
        if (idx < 0) {
            drain(false)
            return
        }
        var p = ptsUs
        if (p <= lastPtsUs) p = lastPtsUs + 1000L
        lastPtsUs = p
        val image: Image? = try { enc.getInputImage(idx) } catch (_: Throwable) { null }
        val size: Int
        if (image != null) {
            fillImage(image, px, tw, th)   // Image বন্ধ করতে হয় না — queueInputBuffer-এর পর নিজেই অবৈধ হয়ে যায়
            size = tw * th * 3 / 2
        } else {
            val buf = enc.getInputBuffer(idx)
            if (buf == null) {
                enc.queueInputBuffer(idx, 0, 0, p, 0)
                return
            }
            buf.clear()
            fillNv12(buf, px, tw, th)
            size = tw * th * 3 / 2
        }
        enc.queueInputBuffer(idx, 0, size, p, 0)
        drain(false)
    }

    /** ARGB → YUV420 (BT.601, limited range), Image-এর প্লেন/স্ট্রাইড মেনে। */
    private fun fillImage(image: Image, px: IntArray, w: Int, h: Int) {
        val planes = image.planes
        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val yRs = planes[0].rowStride
        val yPs = planes[0].pixelStride
        val uRs = planes[1].rowStride
        val uPs = planes[1].pixelStride
        val vRs = planes[2].rowStride
        val vPs = planes[2].pixelStride
        var j = 0
        while (j < h) {
            val rowBase = j * w
            val yRow = j * yRs
            var i = 0
            while (i < w) {
                val c = px[rowBase + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val yy = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuf.put(yRow + i * yPs, yy.toByte())
                i++
            }
            j++
        }
        j = 0
        while (j < h) {
            var i = 0
            while (i < w) {
                val c00 = px[j * w + i]
                val c01 = px[j * w + i + 1]
                val c10 = px[(j + 1) * w + i]
                val c11 = px[(j + 1) * w + i + 1]
                val r = (((c00 shr 16) and 0xFF) + ((c01 shr 16) and 0xFF) + ((c10 shr 16) and 0xFF) + ((c11 shr 16) and 0xFF)) shr 2
                val g = (((c00 shr 8) and 0xFF) + ((c01 shr 8) and 0xFF) + ((c10 shr 8) and 0xFF) + ((c11 shr 8) and 0xFF)) shr 2
                val b = ((c00 and 0xFF) + (c01 and 0xFF) + (c10 and 0xFF) + (c11 and 0xFF)) shr 2
                val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                uBuf.put((j shr 1) * uRs + (i shr 1) * uPs, u.toByte())
                vBuf.put((j shr 1) * vRs + (i shr 1) * vPs, v.toByte())
                i += 2
            }
            j += 2
        }
    }

    /** getInputImage() না দিলে: সাধারণ NV12 (Y প্লেন + ইন্টারলিভড UV) হিসেবে ভরা। */
    private fun fillNv12(buf: ByteBuffer, px: IntArray, w: Int, h: Int) {
        val ySize = w * h
        var j = 0
        while (j < h) {
            var i = 0
            while (i < w) {
                val c = px[j * w + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                buf.put(j * w + i, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
                i++
            }
            j++
        }
        j = 0
        while (j < h) {
            var i = 0
            while (i < w) {
                val c = px[j * w + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val base = ySize + (j shr 1) * w + i
                buf.put(base, ((((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)).toByte())
                buf.put(base + 1, ((((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)).toByte())
                i += 2
            }
            j += 2
        }
    }

    // =================================================================================
    // ভিডিও এনকোডার
    // =================================================================================
    private fun setupEncoder(w: Int, h: Int): Boolean {
        var scale = min(1f, MAX_SIDE.toFloat() / max(w, h))
        for (attempt in 0 until 2) {
            val tW = max(64, ((w * scale).toInt() / 16) * 16)
            val tH = max(64, ((h * scale).toInt() / 16) * 16)
            var enc: MediaCodec? = null
            try {
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, tW, tH).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                    setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                }
                enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                enc.start()
                try { if (videoOnly.exists()) videoOnly.delete() } catch (_: Throwable) {}
                muxer = MediaMuxer(videoOnly.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                encoder = enc
                tw = tW
                th = tH
                val fb = Bitmap.createBitmap(tW, tH, Bitmap.Config.ARGB_8888)
                frameBmp = fb
                frameCanvas = Canvas(fb)
                pixels = IntArray(tW * tH)
                return true
            } catch (_: Throwable) {
                try { enc?.release() } catch (_: Throwable) {}
                try { muxer?.release() } catch (_: Throwable) {}
                muxer = null
                encoder = null
                scale *= 0.75f
            }
        }
        return false
    }

    private fun drain(endOfStream: Boolean) {
        val enc = encoder ?: return
        val mux = muxer ?: return
        var tries = 0
        while (true) {
            val oi = enc.dequeueOutputBuffer(bufInfo, if (endOfStream) 10_000 else 0)
            when {
                oi == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    tries++
                    if (tries > 300) return
                }
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        vTrack = mux.addTrack(enc.outputFormat)
                        mux.start()
                        muxerStarted = true
                    }
                }
                oi >= 0 -> {
                    val ob = enc.getOutputBuffer(oi)
                    if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bufInfo.size = 0
                    if (ob != null && bufInfo.size > 0 && muxerStarted) {
                        ob.position(bufInfo.offset)
                        ob.limit(bufInfo.offset + bufInfo.size)
                        mux.writeSampleData(vTrack, ob, bufInfo)
                        framesWritten++
                    }
                    enc.releaseOutputBuffer(oi, false)
                    if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    // =================================================================================
    // শেষ ধাপ: ভিডিও বন্ধ → অডিও ট্র্যাক → মেশানো
    // =================================================================================
    private fun finalizeAll(): Boolean {
        val enc = encoder
        val mux = muxer
        if (enc != null && mux != null) {
            try {
                val idx = enc.dequeueInputBuffer(100_000)
                if (idx >= 0) {
                    val p = max(nowUs(), lastPtsUs + 100_000L)
                    enc.queueInputBuffer(idx, 0, 0, p, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    drain(true)
                }
            } catch (_: Throwable) {}
            try { enc.stop() } catch (_: Throwable) {}
            try { enc.release() } catch (_: Throwable) {}
            encoder = null
            try { if (muxerStarted) mux.stop() } catch (_: Throwable) {}
            try { mux.release() } catch (_: Throwable) {}
            muxer = null
        }
        if (framesWritten == 0 || !videoOnly.exists() || videoOnly.length() < 512) return false

        val audioFile = File(workDir, "audio_only.m4a")
        val hasAudio = try { encodeAudioTrack(audioFile) } catch (_: Throwable) { false }
        try { if (outFile.exists()) outFile.delete() } catch (_: Throwable) {}
        outFile.parentFile?.mkdirs()
        if (hasAudio) {
            val merged = try { mergeAv(videoOnly, audioFile, outFile) } catch (_: Throwable) { false }
            if (!merged) {
                try { if (outFile.exists()) outFile.delete() } catch (_: Throwable) {}
                videoOnly.copyTo(outFile, overwrite = true)
            }
        } else {
            videoOnly.copyTo(outFile, overwrite = true)
        }
        return outFile.exists() && outFile.length() > 1024
    }

    private fun cleanup() {
        try { encoder?.release() } catch (_: Throwable) {}
        encoder = null
        try { muxer?.release() } catch (_: Throwable) {}
        muxer = null
        releaseVideo()
        try { workDir.deleteRecursively() } catch (_: Throwable) {}
    }

    private fun releaseVideo() {
        try { srcBmp?.recycle() } catch (_: Throwable) {}
        try { frameBmp?.recycle() } catch (_: Throwable) {}
        srcBmp = null
        frameBmp = null
        frameCanvas = null
        pixels = null
    }

    // ---- অডিও ট্র্যাক: ক্লিপগুলো সময়-রেখায় বসিয়ে AAC ----
    private fun encodeAudioTrack(out: File): Boolean {
        val sorted = synchronized(clips) { clips.sortedBy { it.startUs } }
        if (sorted.isEmpty()) return false
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var mux: MediaMuxer? = null
        var started = false
        try {
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            try { if (out.exists()) out.delete() } catch (_: Throwable) {}
            mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val source = PcmTimeline(sorted, AUDIO_RATE)
            val tmp = ByteArray(8192)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var inputEos = false
            var outEos = false
            var samplesFed = 0L
            val deadline = SystemClock.elapsedRealtime() + 15 * 60_000L
            while (!outEos) {
                if (SystemClock.elapsedRealtime() > deadline) break
                if (!inputEos) {
                    val ii = enc.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = enc.getInputBuffer(ii)
                        if (ib == null) {
                            enc.queueInputBuffer(ii, 0, 0, samplesFed * 1_000_000L / AUDIO_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            ib.clear()
                            val want = min(tmp.size, ib.capacity()) and 1.inv()
                            val n = source.read(tmp, want)
                            if (n <= 0) {
                                enc.queueInputBuffer(ii, 0, 0, samplesFed * 1_000_000L / AUDIO_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            } else {
                                ib.put(tmp, 0, n)
                                enc.queueInputBuffer(ii, 0, n, samplesFed * 1_000_000L / AUDIO_RATE, 0)
                                samplesFed += (n / 2).toLong()
                            }
                        }
                    }
                }
                val oi = enc.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!started) {
                        track = mux.addTrack(enc.outputFormat)
                        mux.start()
                        started = true
                    }
                } else if (oi >= 0) {
                    val ob = enc.getOutputBuffer(oi)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                    if (ob != null && info.size > 0 && started) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        mux.writeSampleData(track, ob, info)
                    }
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true
                    enc.releaseOutputBuffer(oi, false)
                }
            }
            return started
        } finally {
            try { enc.stop() } catch (_: Throwable) {}
            try { enc.release() } catch (_: Throwable) {}
            try { if (started) mux?.stop() } catch (_: Throwable) {}
            try { mux?.release() } catch (_: Throwable) {}
        }
    }

    /** ক্লিপগুলো একে একে ডিকোড করে, নিজ নিজ শুরুর সময়ে বসিয়ে, মাঝের ফাঁক নীরবতায় ভরে PCM16 মনো স্ট্রিম দেয়। */
    private class PcmTimeline(private val clips: List<Clip>, private val rate: Int) {
        private var idx = 0
        private var cursor = 0L
        private var cur: ShortArray? = null
        private var curPos = 0
        private var curStart = 0L

        /** [dst]-তে সর্বোচ্চ [maxBytes] (জোড়) বাইট লেখে; শেষ হলে -1। */
        fun read(dst: ByteArray, maxBytes: Int): Int {
            val maxSamples = maxBytes / 2
            var n = 0
            while (n < maxSamples) {
                if (cur == null) {
                    if (idx >= clips.size) break
                    val c = clips[idx++]
                    val pcm = decodeClip(c.file, rate) ?: continue
                    cur = pcm
                    curPos = 0
                    curStart = max(cursor, c.startUs * rate / 1_000_000L)
                }
                val data = cur ?: continue
                if (cursor < curStart) {
                    val gap = min((maxSamples - n).toLong(), curStart - cursor).toInt()
                    for (k in 0 until gap) {
                        dst[(n + k) * 2] = 0
                        dst[(n + k) * 2 + 1] = 0
                    }
                    n += gap
                    cursor += gap
                } else {
                    val take = min(maxSamples - n, data.size - curPos)
                    for (k in 0 until take) {
                        val s = data[curPos + k].toInt()
                        dst[(n + k) * 2] = (s and 0xFF).toByte()
                        dst[(n + k) * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                    }
                    curPos += take
                    n += take
                    cursor += take
                    if (curPos >= data.size) cur = null
                }
            }
            return if (n == 0) -1 else n * 2
        }
    }
}

// =====================================================================================
// অডিও ডিকোড (ফাইল-লেভেল হেল্পার)
// =====================================================================================

/** একটা ক্লিপ (WAV বা MP3/AAC...) → [rate] Hz মনো PCM16। না পারলে null। */
private fun decodeClip(file: File, rate: Int): ShortArray? {
    return try {
        val bytes = file.readBytes()
        val wav = parseWavPcm16(bytes)
        if (wav != null) {
            resampleMono(wav.first, wav.second, rate)
        } else {
            val dec = decodeWithCodec(file) ?: return null
            resampleMono(dec.first, dec.second, rate)
        }
    } catch (_: Throwable) {
        null
    }
}

/** PCM16 WAV হলে (মনো-করা samples, sampleRate); অন্য ফরম্যাট হলে null। */
private fun parseWavPcm16(b: ByteArray): Pair<ShortArray, Int>? {
    if (b.size < 44) return null
    fun u16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    fun u32(o: Int) = u16(o).toLong() or (u16(o + 2).toLong() shl 16)
    if (String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE") return null
    var pos = 12
    var channels = 1
    var rate = 24000
    var bits = 16
    var fmtTag = 1
    var dataOff = -1
    var dataLen = 0
    while (pos + 8 <= b.size) {
        val id = String(b, pos, 4, Charsets.US_ASCII)
        val sz = u32(pos + 4)
        val body = pos + 8
        if (id == "fmt ") {
            fmtTag = u16(body)
            channels = u16(body + 2)
            rate = u32(body + 4).toInt()
            bits = u16(body + 14)
        } else if (id == "data") {
            dataOff = body
            val remain = (b.size - body).toLong()
            dataLen = (if (sz == 0L || sz > remain) remain else sz).toInt()
            break
        }
        pos = (body.toLong() + sz + (sz and 1L)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    if (dataOff < 0 || fmtTag != 1 || bits != 16 || channels < 1 || rate <= 0) return null
    val frames = dataLen / (2 * channels)
    val out = ShortArray(frames)
    var o = dataOff
    for (i in 0 until frames) {
        var sum = 0
        for (c in 0 until channels) {
            sum += ((b[o].toInt() and 0xFF) or (b[o + 1].toInt() shl 8)).toShort().toInt()
            o += 2
        }
        out[i] = (sum / channels).toShort()
    }
    return out to rate
}

/** MediaExtractor + MediaCodec দিয়ে ডিকোড (MP3 ইত্যাদি) → (মনো samples, sampleRate)। */
private fun decodeWithCodec(file: File): Pair<ShortArray, Int>? {
    val ex = MediaExtractor()
    var dec: MediaCodec? = null
    try {
        ex.setDataSource(file.absolutePath)
        var track = -1
        var mime = ""
        for (i in 0 until ex.trackCount) {
            val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (m.startsWith("audio/")) {
                track = i
                mime = m
                break
            }
        }
        if (track < 0) return null
        ex.selectTrack(track)
        val inFmt = ex.getTrackFormat(track)
        var sr = if (inFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 24000
        var ch = if (inFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        val d = MediaCodec.createDecoderByType(mime)
        dec = d
        d.configure(inFmt, null, null, 0)
        d.start()
        val pcm = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val deadline = SystemClock.elapsedRealtime() + 60_000L
        while (!outputDone && SystemClock.elapsedRealtime() < deadline) {
            if (!inputDone) {
                val ii = d.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val ib = d.getInputBuffer(ii)
                    val n = if (ib != null) ex.readSampleData(ib, 0) else -1
                    if (n < 0) {
                        d.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        d.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val oi = d.dequeueOutputBuffer(info, 10_000)
            if (oi >= 0) {
                val ob = d.getOutputBuffer(oi)
                if (ob != null && info.size > 0) {
                    val chunk = ByteArray(info.size)
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    ob.get(chunk)
                    pcm.write(chunk)
                }
                d.releaseOutputBuffer(oi, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = d.outputFormat
                if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
        }
        val raw = pcm.toByteArray()
        if (ch < 1) ch = 1
        val frames = raw.size / (2 * ch)
        if (frames <= 0) return null
        val out = ShortArray(frames)
        var o = 0
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until ch) {
                sum += ((raw[o].toInt() and 0xFF) or (raw[o + 1].toInt() shl 8)).toShort().toInt()
                o += 2
            }
            out[i] = (sum / ch).toShort()
        }
        return out to sr
    } catch (_: Throwable) {
        return null
    } finally {
        try { dec?.stop() } catch (_: Throwable) {}
        try { dec?.release() } catch (_: Throwable) {}
        try { ex.release() } catch (_: Throwable) {}
    }
}

/** সরল লিনিয়ার রিস্যাম্পল (কথার জন্য যথেষ্ট)। */
private fun resampleMono(src: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
    if (srcRate == dstRate || src.isEmpty()) return src
    val outLen = (src.size.toLong() * dstRate / srcRate).toInt().coerceAtLeast(1)
    val out = ShortArray(outLen)
    val step = srcRate.toDouble() / dstRate
    for (i in 0 until outLen) {
        val pos = i * step
        val i0 = pos.toInt()
        val i1 = min(i0 + 1, src.size - 1)
        val frac = pos - i0
        val s = src[min(i0, src.size - 1)] * (1.0 - frac) + src[i1] * frac
        out[i] = s.toInt().coerceIn(-32768, 32767).toShort()
    }
    return out
}

/** দুটো MP4/M4A (ভিডিও-ট্র্যাক + অডিও-ট্র্যাক) সময়ের ক্রমে মিশিয়ে একটা MP4 বানায় — re-encode ছাড়া। */
private fun mergeAv(videoFile: File, audioFile: File, out: File): Boolean {
    val vEx = MediaExtractor()
    val aEx = MediaExtractor()
    var mux: MediaMuxer? = null
    var started = false
    try {
        vEx.setDataSource(videoFile.absolutePath)
        aEx.setDataSource(audioFile.absolutePath)
        var vi = -1
        var ai = -1
        for (i in 0 until vEx.trackCount) {
            if ((vEx.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) { vi = i; break }
        }
        for (i in 0 until aEx.trackCount) {
            if ((aEx.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { ai = i; break }
        }
        if (vi < 0 || ai < 0) return false
        vEx.selectTrack(vi)
        aEx.selectTrack(ai)
        mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vOut = mux.addTrack(vEx.getTrackFormat(vi))
        val aOut = mux.addTrack(aEx.getTrackFormat(ai))
        mux.start()
        started = true
        val buf = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val vt = vEx.sampleTime
            val at = aEx.sampleTime
            if (vt < 0 && at < 0) break
            val useVideo = vt >= 0 && (at < 0 || vt <= at)
            val ex = if (useVideo) vEx else aEx
            val track = if (useVideo) vOut else aOut
            buf.clear()
            val size = ex.readSampleData(buf, 0)
            if (size < 0) {
                ex.advance()
                continue
            }
            info.set(0, size, ex.sampleTime, if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            mux.writeSampleData(track, buf, info)
            ex.advance()
        }
        return true
    } catch (_: Throwable) {
        return false
    } finally {
        try { if (started) mux?.stop() } catch (_: Throwable) {}
        try { mux?.release() } catch (_: Throwable) {}
        try { vEx.release() } catch (_: Throwable) {}
        try { aEx.release() } catch (_: Throwable) {}
    }
}
