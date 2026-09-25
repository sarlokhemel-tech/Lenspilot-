package com.hemel.lenspilot.freemium

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.updatePadding
import com.hemel.lenspilot.R
import com.hemel.lenspilot.net.ApiClient
import com.hemel.lenspilot.vision.ScreenCaptureService
import com.hemel.lenspilot.vision.TextOcr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * ফ্রিমিয়াম গাইডেড-সেটআপ পাইপলাইনের ক্লায়েন্ট অংশ। এটা কোনো নতুন
 * OCR/overlay সিস্টেম বানায়নি — অ্যাপের বিদ্যমান Tier 2 মেশিনারিই
 * পুনর্ব্যবহার করে (দেখো [ScreenCaptureService], [TextOcr]) যা মূল
 * টাস্ক-অটোমেশন ফিচারের জন্য আগে থেকেই আছে। পার্থক্য শুধু "কার কাছে
 * সিদ্ধান্ত নেওয়া হয়" — সেখানে সিদ্ধান্ত নেয় Gemini vision, এখানে নেয়
 * app.py-র সম্পূর্ণ নিয়মভিত্তিক determine_freemium_step() (কোনো AI কল
 * নেই — /api/freemium/guide-step দেখো), যেমনটা চাওয়া হয়েছিল।
 *
 * লুপ (প্রতি ~１.৫ সেকেন্ডে):
 *   ১. ScreenCaptureService.captureFrame() — একটা স্ক্রিনশট নেয় (Chrome-সহ
 *      যেকোনো অ্যাপের ওপরে, কারণ MediaProjection পুরো ডিসপ্লে দেখে)।
 *   ২. TextOcr.recognize() — অন-ডিভাইস, স্ক্রিনশট কখনো নেটওয়ার্কে যায় না।
 *   ৩. শুধু বের-করা টেক্সট (কোনো ছবি না) /api/freemium/guide-step-এ পাঠানো।
 *   ৪. সার্ভারের রায় অনুযায়ী: instruction bubble আপডেট, ভুল পেজ হলে সঠিক
 *      লিংকে জোর করে পাঠানো, অথবা কী পাওয়া গেলে অটো-অ্যাক্টিভেট।
 */
class FreemiumGuideOverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "freemium_guide"
        private const val NOTIFICATION_ID = 4300
        const val TARGET_URL = "https://aistudio.google.com/app/apikey"
        private const val POLL_INTERVAL_MS = 1500L
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var windowManager: WindowManager? = null
    private var bubbleView: TextView? = null
    private var pollJob: Job? = null
    private var lastForcedUrlAt = 0L

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        showBubble("Chrome খোলা হচ্ছে…")
        pollJob = scope.launch { pollLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private suspend fun pollLoop() {
        while (true) {
            delay(POLL_INTERVAL_MS)
            val bitmap = ScreenCaptureService.instance?.captureFrame() ?: continue
            val blocks = TextOcr.recognize(bitmap)
            val ocrText = blocks.joinToString("\n") { it.text }
            if (ocrText.isBlank()) continue

            val body = JSONObject().put("ocr_text", ocrText).toString()
            val result = ApiClient.callAuthed(
                this@FreemiumGuideOverlayService,
                getString(R.string.space_base_url),
                "/api/freemium/guide-step",
                body
            )
            result.onSuccess { raw ->
                val json = try { JSONObject(raw) } catch (e: Exception) { return@onSuccess }
                handleStep(json)
            }
            // নেটওয়ার্ক হিঁচকি হলে পরের পোলে আবার চেষ্টা হবে — লুপ থামানোর
            // দরকার নেই, ইউজার তো Chrome-এই আছে, ও কিছু বুঝবেও না।
        }
    }

    private fun handleStep(json: JSONObject) {
        val message = json.optString("message", "")
        if (message.isNotBlank()) showBubble(message)

        val detectedKey = json.optString("detected_api_key", "").ifBlank { null }
        if (detectedKey != null) {
            pollJob?.cancel()
            activateDetectedKey(detectedKey)
            return
        }

        val forceUrl = json.optString("force_url", "").ifBlank { null }
        if (forceUrl != null) {
            // একই ভুল পেজে বারবার (প্রতি পোলে) নতুন Intent না ছুঁড়তে —
            // অন্তত ৪ সেকেন্ড ব্যবধান রাখা, নাহলে Chrome-এ ট্যাব-স্প্যাম হবে।
            val now = System.currentTimeMillis()
            if (now - lastForcedUrlAt > 4000L) {
                lastForcedUrlAt = now
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(forceUrl)).apply {
                    setPackage("com.android.chrome")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { startActivity(intent) } catch (e: Exception) {
                    intent.setPackage(null)
                    startActivity(intent)
                }
            }
        }
    }

    private fun activateDetectedKey(key: String) {
        showBubble("🎉 কী পাওয়া গেছে — সেভ করা হচ্ছে…")
        scope.launch {
            val body = JSONObject().put("api_key", key).toString()
            val result = ApiClient.callAuthed(
                this@FreemiumGuideOverlayService,
                getString(R.string.space_base_url),
                "/api/freemium/activate",
                body
            )
            result.onSuccess {
                showBubble("✅ ফ্রিমিয়াম চালু হয়েছে!")
                Toast.makeText(this@FreemiumGuideOverlayService, "ফ্রিমিয়াম চালু হয়েছে — এখন Lenspilot-এ ফিরে যাও", Toast.LENGTH_LONG).show()
                delay(2500)
                stopSelf()
            }.onFailure { e ->
                showBubble("কী দিয়ে অ্যাক্টিভেট করা গেল না, আবার চেষ্টা হচ্ছে…")
                // key_found রেজাল্ট ভুল/আংশিক OCR হতে পারে (একটা ভুল
                // অক্ষর চেনা) — লুপ আবার চালু করে দাও, ঠিকঠাক কী স্ক্রিনে
                // থাকলে পরের ধরাতেই ম্যাচ হবে।
                pollJob = scope.launch { pollLoop() }
            }
        }
    }

    private fun showBubble(text: String) {
        val wm = windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { windowManager = it }
        if (bubbleView == null) {
            val tv = TextView(this).apply {
                setBackgroundColor(Color.parseColor("#E6111827"))
                setTextColor(Color.WHITE)
                textSize = 14f
                updatePadding(28, 20, 28, 20)
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 80
            }
            wm.addView(tv, params)
            bubbleView = tv
        }
        bubbleView?.text = text
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Freemium setup guide", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ফ্রিমিয়াম সেটআপ চলছে")
            .setContentText("Gemini API কী নেওয়ার জন্য গাইড করা হচ্ছে")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        bubbleView?.let { windowManager?.removeView(it) }
        bubbleView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
