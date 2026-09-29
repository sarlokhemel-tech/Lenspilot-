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
import android.os.IBinder
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
    private var pendingCapture: ((Bitmap?) -> Unit)? = null

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
            setUpVirtualDisplay()
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

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val callback = pendingCapture
            pendingCapture = null
            try {
                if (callback != null) {
                    val plane = image.planes[0]
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    val rowPadding = rowStride - pixelStride * width
                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(plane.buffer)
                    callback(if (rowPadding == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, height))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Frame decode failed", e)
                pendingCapture?.invoke(null)
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

    /** Suspends until the next frame is available (typically <100ms). */
    suspend fun captureFrame(): Bitmap? = suspendCancellableCoroutine { cont ->
        if (mediaProjection == null || imageReader == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        pendingCapture = { bitmap -> if (cont.isActive) cont.resume(bitmap) }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Screen reading", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Lenspilot স্ক্রিন দেখছে")
            .setContentText("গাইডলাইন দেওয়ার জন্য স্ক্রিন পড়া হচ্ছে")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
