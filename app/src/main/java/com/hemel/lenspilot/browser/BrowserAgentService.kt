package com.hemel.lenspilot.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * AI ব্রাউজারের রান চলাকালীন foreground service (নোটিফিকেশনসহ "বন্ধ করো" বাটন) + partial WakeLock।
 * উদ্দেশ্য: অ্যাপ ব্যাকগ্রাউন্ডে গেলে/স্ক্রিনের আলো কমলে প্রসেস যেন "cached" হয়ে মারা না পড়ে।
 *
 * সীমা (স্পেক B13): Android-এ WebView-র দৃশ্যমান উইন্ডো লাগে। এটা স্ক্রিন-অন (FLAG_KEEP_SCREEN_ON,
 * অ্যাক্টিভিটিতে) + foreground service — স্ক্রিন-বন্ধ অবস্থায় পুরোপুরি হেডলেস চালানো নয়।
 */
class BrowserAgentService : Service() {

    companion object {
        private const val TAG = "BrowserAgentService"
        private const val CHANNEL_ID = "lenspilot_browser_agent"
        private const val NOTIFICATION_ID = 7421
        private const val ACTION_STOP = "com.hemel.lenspilot.browser.STOP_AGENT"
        private const val WAKELOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L   // নিরাপত্তা-সীমা: ২ ঘণ্টা

        /** নোটিফিকেশনের "বন্ধ করো" চাপলে অ্যাক্টিভিটি এখানে শোনে। */
        @Volatile var onStopRequested: (() -> Unit)? = null

        fun start(context: Context) {
            try {
                val i = Intent(context, BrowserAgentService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
            } catch (e: Exception) {
                Log.w(TAG, "start failed (continuing without service)", e)
            }
        }

        fun stop(context: Context) {
            try { context.stopService(Intent(context, BrowserAgentService::class.java)) } catch (e: Exception) { }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            onStopRequested?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, n)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
        }
        if (wakeLock == null) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lenspilot:browser_agent").apply {
                    setReferenceCounted(false); acquire(WAKELOCK_TIMEOUT_MS)
                }
            } catch (e: Exception) { Log.w(TAG, "wakelock failed", e) }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "AI ব্রাউজার", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, BrowserAgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 2, Intent(this, AiBrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(AiBrowserActivity.EXTRA_RESUME, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return b.setContentTitle("AI ব্রাউজার কাজ করছে")
            .setContentText("আপনার কাজ চলছে — ট্যাপ করলে ফিরে যাবেন")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "বন্ধ করো", stopIntent).build())
            .build()
    }

    override fun onDestroy() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        wakeLock = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) { }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
