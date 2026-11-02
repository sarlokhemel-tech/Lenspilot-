package com.hemel.lenspilot.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Foreground service that owns the MediaProjection session for Tier 2/3
 * (used only when Accessibility is off). One VirtualDisplay + ImageReader
 * is set up once and reused for every capture — the system consent dialog
 * (MediaProjectionManager.createScreenCaptureIntent(), triggered from
 * MainActivity) only needs to be shown once per app process, not once per
 * screenshot.
 *
 * The persistent notification while this is running is an Android
 * platform requirement for any app using MediaProjection, not a design
 * choice — the OS won't allow the capture otherwise, precisely so a
 * screen-reading app can never do this invisibly.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 4200
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile
        var instance: ScreenCaptureService? = null
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    @Volatile private var pendingCapture: ((Bitmap?) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var capW = 0
    private var capH = 0
    private var capDensity = 0

    /** v60: স্ক্রিনে কিছু বদলালেই কেবল নতুন ফ্রেম আসে — স্থির স্ক্রিনে captureFrame() আর ঝুলে না, তাই শেষ ফ্রেমটা রাখা হয়। */
    @Volatile private var lastFrame: Bitmap? = null
    @Volatile private var lastFrameAt = 0L

    /** MediaProjection + VirtualDisplay তৈরি হয়ে গেছে (ক্যাপচার নেওয়া যাবে)। */
    val isReady: Boolean get() = mediaProjection != null && virtualDisplay != null && imageReader != null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (mediaProjection == null && resultData != null) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = manager.getMediaProjection(resultCode, resultData)
            // Android 14+: createVirtualDisplay-এর আগে callback রেজিস্টার না করলে IllegalStateException। ইউজার
            // সিস্টেম থেকে ক্যাপচার থামালে (স্ট্যাটাস-বার চিপ) নিজেকে পরিষ্কার করে বন্ধ হয়।
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post {
                        cleanUpCapture()
                        stopSelf()
                    }
                }
            }, mainHandler)
            runCatching { setUpVirtualDisplay() }.onFailure {
                Log.w(TAG, "Could not start the virtual display", it)
                cleanUpCapture()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun setUpVirtualDisplay() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        capW = width; capH = height; capDensity = density

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val callback = pendingCapture
            try {
                val now = System.currentTimeMillis()
                // কেউ অপেক্ষা করছে, অথবা শেষ ফ্রেম-ক্যাশ ২৫০ms-এর পুরনো — তবেই ফ্রেমটা বিটম্যাপ করি (প্রতিটা ফ্রেমে নয়)
                if (callback != null || now - lastFrameAt > 250) {
                    val plane = image.planes[0]
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    val rowPadding = rowStride - pixelStride * width
                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(plane.buffer)
                    val frame = if (rowPadding == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    lastFrame = frame
                    lastFrameAt = now
                    if (callback != null) {
                        pendingCapture = null
                        callback(frame.copy(Bitmap.Config.ARGB_8888, false))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Frame decode failed", e)
                val cb = pendingCapture
                pendingCapture = null
                cb?.invoke(null)
            } finally {
                image.close()
            }
        }, null)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "LenspilotCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )
    }

    /**
     * বর্তমান স্ক্রিনের ফ্রেম। (১) নতুন ফ্রেমের জন্য ~৪০০ms অপেক্ষা; (২) না এলে (স্ক্রিন স্থির) ডিসপ্লে "নাড়িয়ে" নতুন ফ্রেম
     * জোর করা; (৩) তবুও না এলে শেষ ক্যাশ-করা ফ্রেম। আগে স্থির স্ক্রিনে এটা চিরতরে ঝুলে থাকত।
     * ফেরত বিটম্যাপটা কলারের নিজের কপি — কলার recycle() করতে পারে।
     */
    suspend fun captureFrame(): Bitmap? {
        if (!isReady) return null
        val fresh = waitForFrame(400)
        if (fresh != null) return fresh
        // স্থির স্ক্রিন: ডেনসিটি এক ধাপ বদলে আবার ফেরালে সিস্টেম একটা নতুন ফ্রেম পাঠায়
        runCatching { virtualDisplay?.resize(capW, capH, capDensity + 1) }
        mainHandler.postDelayed({ runCatching { virtualDisplay?.resize(capW, capH, capDensity) } }, 120)
        val nudged = waitForFrame(900)
        if (nudged != null) return nudged
        return lastFrame?.let { runCatching { it.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() }
    }

    private suspend fun waitForFrame(timeoutMs: Long): Bitmap? =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Bitmap?> { cont ->
                pendingCapture = { bitmap -> if (cont.isActive) cont.resume(bitmap) }
                cont.invokeOnCancellation { pendingCapture = null }
            }
        }

    private fun cleanUpCapture() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        lastFrame = null
        pendingCapture = null
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Screen reading", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Lenspilot is viewing the screen")
            .setContentText("Reading the screen to provide guidance")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        val mp = mediaProjection
        cleanUpCapture()
        runCatching { mp?.stop() }
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
