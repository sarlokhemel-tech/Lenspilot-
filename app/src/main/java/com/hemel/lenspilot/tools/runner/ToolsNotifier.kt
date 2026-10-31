package com.hemel.lenspilot.tools.runner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.hemel.lenspilot.R
import com.hemel.lenspilot.tools.ToolsPaths
import com.hemel.lenspilot.tools.agent.ToolsSessionActivity
import com.hemel.lenspilot.tools.perm.PermissionGate
import java.util.concurrent.atomic.AtomicInteger

/** Runner/Scheduler-এর ইউজার-নোটিফিকেশন (অনুমোদনের চ্যানেল থেকে আলাদা)। */
object ToolsNotifier {
    const val CHANNEL_WATCH = "lp_elora_runner"
    const val CHANNEL_EVENTS = "lp_elora_events"
    private val counter = AtomicInteger(0)

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_WATCH) == null) {
            val ch = NotificationChannel(CHANNEL_WATCH, "ELORA নজরদারি", NotificationManager.IMPORTANCE_LOW)
            ch.description = "চলমান কাজের শেষ হওয়ার অপেক্ষা"
            nm.createNotificationChannel(ch)
        }
        if (nm.getNotificationChannel(CHANNEL_EVENTS) == null) {
            val ch = NotificationChannel(CHANNEL_EVENTS, "ELORA ঘটনা", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "কাজ শেষ/ব্যর্থ/আটকে গেলে এবং নির্ধারিত সময় এলে"
            nm.createNotificationChannel(ch)
        }
    }

    /** [sessionId] দিলে নোটিফিকেশন-ট্যাপ সরাসরি ওই সেশনের লাইভ স্ক্রিনে যায় (সেশন ৭)। */
    private fun openApp(ctx: Context, sessionId: String? = null): PendingIntent? {
        if (sessionId != null && ToolsPaths.isValidId(sessionId)) {
            val si = Intent(ctx, ToolsSessionActivity::class.java)
                .putExtra(ToolsSessionActivity.EXTRA_SESSION_ID, sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(ctx, 100 + (sessionId.hashCode() and 0xFFFF), si, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val li = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        return PendingIntent.getActivity(ctx, 2, li, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** ফোরগ্রাউন্ড-সার্ভিসের স্থায়ী নোটিফিকেশন (অনুমতি না থাকলেও সার্ভিস চলে, শুধু নোটিফিকেশন দেখা যায় না)। */
    fun watching(ctx: Context, lines: List<String>): Notification {
        ensureChannels(ctx)
        val title = ctx.getString(R.string.elora_name) + " · " + lines.size + "টি কাজ দেখছে"
        val b = Notification.Builder(ctx, CHANNEL_WATCH)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        openApp(ctx)?.let { b.setContentIntent(it) }
        return b.build()
    }

    /** একবারের ঘটনা-নোটিফিকেশন। অনুমতি না থাকলে চুপচাপ বাদ (ইভেন্ট ToolsEvents-এ ও steps.jsonl-এ থাকে)। */
    fun event(ctx: Context, title: String, text: String, sessionId: String? = null) {
        if (!PermissionGate.canNotify(ctx)) return
        try {
            ensureChannels(ctx)
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            val b = Notification.Builder(ctx, CHANNEL_EVENTS)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(ctx.getString(R.string.elora_name) + " · " + title)
                .setContentText(text.lineSequence().firstOrNull().orEmpty().take(160))
                .setStyle(Notification.BigTextStyle().bigText(text.take(1500)))
                .setAutoCancel(true)
            openApp(ctx, sessionId)?.let { b.setContentIntent(it) }
            nm.notify(42100 + (counter.incrementAndGet() and 0xFF), b.build())
        } catch (e: Exception) { }
    }
}
