package com.hemel.lenspilot.tools.perm

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.hemel.lenspilot.R
import com.hemel.lenspilot.security.Redactor
import com.hemel.lenspilot.tools.ToolsPaths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest

/** TIMEOUT = অস্বীকার (কখনো অনুমোদন ধরা হয় না)। */
enum class Decision { APPROVED, DENIED, TIMEOUT }

/**
 * PermissionGate — ঝুঁকিপূর্ণ কাজের আগে মানুষের অনুমোদন।
 *  * অ্যাপ ফোরগ্রাউন্ডে → গ্লাস কার্ড ([PermissionCard]); নইলে হাই-প্রায়োরিটি নোটিফিকেশন (অনুমোদন/অস্বীকার)।
 *  * অপেক্ষমাণ অনুরোধ সেশন-ফোল্ডারের pending_permissions.json-এ থাকে — প্রসেস মরলে হারায় না।
 *  * টাইমআউট = অস্বীকার। HIGH কখনো মনে রাখা হয় না; MEDIUM শুধু চলতি সেশনে (ইউজার টিক দিলে)।
 *  * title/detail ডিস্কে ও স্ক্রিনে যাওয়ার আগে [Redactor]-এর ভেতর দিয়ে যায়।
 */
object PermissionGate {
    const val ACTION_DECIDE = "com.hemel.lenspilot.tools.perm.DECIDE"
    const val EXTRA_ID = "perm_id"
    const val EXTRA_APPROVE = "perm_approve"

    private const val CHANNEL_ID = "lp_elora_permission"
    private const val FILE_NAME = "pending_permissions.json"
    private const val NOTIF_BASE = 41000
    private const val TEST_NOTIF_ID = 41999

    private class Pending(
        val id: String,
        val key: String,
        val risk: Risk,
        val title: String,
        val detail: String,
        val deadline: Long,
        val sessionId: String?
    ) {
        val result = CompletableDeferred<Decision>()
        @Volatile var remember = false
        var waiters = 0
    }

    private val lock = Any()
    private val byId = HashMap<String, Pending>()
    private val byKey = HashMap<String, Pending>()
    private val sessionAllowed = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var started = 0
    private var resumed: WeakReference<Activity>? = null
    private var dialog: Dialog? = null
    private var dialogFor: String? = null
    @Volatile private var inited = false

    fun isForeground(): Boolean = started > 0

    // ------------------------------------------------------------------ init
    fun init(app: Application) {
        if (inited) return
        inited = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) { started++ }
            override fun onActivityResumed(activity: Activity) {
                resumed = WeakReference(activity)
                refreshUi()
            }
            override fun onActivityPaused(activity: Activity) {
                if (resumed?.get() === activity) {
                    resumed = null
                    dismissDialog()
                }
            }
            override fun onActivityStopped(activity: Activity) {
                started = if (started > 0) started - 1 else 0
                main.postDelayed({ refreshUi() }, 300L)
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        try { restore(app) } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ request
    /**
     * অনুমোদন চায়। [sessionId] দিলে অনুরোধ ওই সেশনের ফোল্ডারে সংরক্ষিত হয়।
     * একই (সেশন, ঝুঁকি, title, detail) অনুরোধ প্রসেস-মৃত্যুর পরও আগের সিদ্ধান্ত/অপেক্ষা ফিরে পায়।
     */
    suspend fun request(
        risk: Risk,
        title: String,
        detail: String,
        timeoutSec: Int,
        sessionId: String? = null
    ): Decision {
        val ctx = com.hemel.lenspilot.security.SecurityHub.context() ?: return Decision.DENIED
        if (risk == Risk.LOW) return Decision.APPROVED
        val safeTitle = Redactor.redact(ctx, title).take(80)
        val safeDetail = Redactor.redact(ctx, detail).take(500)
        val key = keyOf(sessionId, risk, safeTitle, safeDetail)
        if (risk == Risk.MEDIUM && synchronized(lock) { sessionAllowed.contains(key) }) return Decision.APPROVED

        takeSavedDecision(ctx, sessionId, key)?.let { return it }

        val p = synchronized(lock) {
            val existing = byKey[key]
            if (existing != null) {
                existing.waiters++
                existing
            } else {
                val deadline = System.currentTimeMillis() + maxOf(5, timeoutSec) * 1000L
                val np = Pending(java.util.UUID.randomUUID().toString().take(12), key, risk, safeTitle, safeDetail, deadline, sessionId)
                np.waiters = 1
                byId[np.id] = np
                byKey[key] = np
                np
            }
        }
        persist(ctx, p, "pending")
        main.post { refreshUi() }

        try {
            val remaining = (p.deadline - System.currentTimeMillis()).coerceAtLeast(0L)
            val d = withTimeoutOrNull(remaining) { p.result.await() } ?: Decision.TIMEOUT
            if (d == Decision.APPROVED && risk == Risk.MEDIUM && p.remember) {
                synchronized(lock) { sessionAllowed.add(key) }
            }
            return d
        } finally {
            val last = synchronized(lock) {
                p.waiters--
                if (p.waiters <= 0) { byId.remove(p.id); byKey.remove(p.key); true } else false
            }
            if (last) {
                deleteRecord(ctx, p.sessionId, p.id)
                if (!p.result.isCompleted) p.result.complete(Decision.TIMEOUT)
                cancelNotification(ctx, p.id)
                main.post { refreshUi() }
            }
        }
    }

    /** কার্ড/নোটিফিকেশন বাটন থেকে। কেউ অপেক্ষায় না থাকলে (প্রসেস-মৃত্যুর পর) সিদ্ধান্ত ফাইলে রাখে। */
    fun resolve(id: String, approved: Boolean, remember: Boolean) {
        val ctx = com.hemel.lenspilot.security.SecurityHub.context() ?: return
        val p = synchronized(lock) { byId[id] } ?: return
        val live = synchronized(lock) { p.waiters > 0 }
        p.remember = remember && p.risk == Risk.MEDIUM
        if (!live) {
            updateState(ctx, p, if (approved) "approved" else "denied")
            synchronized(lock) {
                byId.remove(p.id); byKey.remove(p.key)
                if (p.remember) sessionAllowed.add(p.key)
            }
        }
        p.result.complete(if (approved) Decision.APPROVED else Decision.DENIED)
        cancelNotification(ctx, p.id)
        main.post { refreshUi() }
    }

    // ------------------------------------------------------------------ UI (মেইন থ্রেড)
    private fun refreshUi() {
        val ctx = com.hemel.lenspilot.security.SecurityHub.context() ?: return
        pruneExpired(ctx)
        val list = synchronized(lock) { byId.values.filter { !it.result.isCompleted }.sortedBy { it.deadline } }
        if (started > 0) {
            for (p in list) cancelNotification(ctx, p.id)
            val shown = dialogFor
            if (dialog != null && (shown == null || list.none { it.id == shown })) dismissDialog()
            val act = resumed?.get()
            if (dialog == null && list.isNotEmpty() && act != null && !act.isFinishing) showCard(act, list[0])
        } else {
            dismissDialog()
            for (p in list) postNotification(ctx, p)
        }
    }

    private fun showCard(act: Activity, p: Pending) {
        try {
            val d = PermissionCard.show(act, p.title, p.detail, p.risk, p.deadline) { approve, remember ->
                resolve(p.id, approve, remember)
            }
            dialog = d
            dialogFor = p.id
            d.setOnDismissListener { if (dialog === d) { dialog = null; dialogFor = null } }
        } catch (e: Exception) {
            dialog = null; dialogFor = null
        }
    }

    private fun dismissDialog() {
        try { dialog?.dismiss() } catch (e: Exception) { }
        dialog = null; dialogFor = null
    }

    /** অপেক্ষাকারীহীন (পুনরুদ্ধার-করা) অনুরোধের মেয়াদ শেষ হলে অস্বীকার ধরে সরিয়ে দেয়। */
    private fun pruneExpired(ctx: Context) {
        val now = System.currentTimeMillis()
        val dead = synchronized(lock) { byId.values.filter { it.deadline <= now && it.waiters <= 0 } }
        for (p in dead) {
            synchronized(lock) { byId.remove(p.id); byKey.remove(p.key) }
            if (!p.result.isCompleted) p.result.complete(Decision.TIMEOUT)
            deleteRecord(ctx, p.sessionId, p.id)
            cancelNotification(ctx, p.id)
        }
    }

    // ------------------------------------------------------------------ নোটিফিকেশন
    fun canNotify(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
        return nm.areNotificationsEnabled()
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "ELORA অনুমতি", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "ঝুঁকিপূর্ণ কাজের আগে অনুমোদন চাওয়ার নোটিফিকেশন"
        nm.createNotificationChannel(ch)
    }

    private fun notifId(id: String) = NOTIF_BASE + (id.hashCode() and 0x7FF)

    private fun actionIntent(ctx: Context, id: String, approve: Boolean): PendingIntent {
        val i = Intent(ctx, PermissionReceiver::class.java)
            .setAction(ACTION_DECIDE)
            .setData(Uri.parse("lenspilot://perm/$id/" + (if (approve) "y" else "n")))
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_APPROVE, approve)
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun postNotification(ctx: Context, p: Pending) {
        if (!canNotify(ctx)) return
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            ensureChannel(nm)
            val b = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(ctx.getString(R.string.elora_name) + " · " + p.title)
                .setContentText(p.detail.lineSequence().firstOrNull().orEmpty())
                .setStyle(Notification.BigTextStyle().bigText(p.detail))
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setTimeoutAfter((p.deadline - System.currentTimeMillis()).coerceAtLeast(1000L))
                .addAction(0, "অস্বীকার", actionIntent(ctx, p.id, false))
                .addAction(0, "অনুমোদন", actionIntent(ctx, p.id, true))
            val sidLink = p.sessionId
            if (sidLink != null && com.hemel.lenspilot.tools.ToolsPaths.isValidId(sidLink)) {
                // সেশন ৭: নোটিফিকেশন-ট্যাপ সরাসরি ওই সেশনের লাইভ স্ক্রিনে
                val si = Intent(ctx, com.hemel.lenspilot.tools.agent.ToolsSessionActivity::class.java)
                    .putExtra(com.hemel.lenspilot.tools.agent.ToolsSessionActivity.EXTRA_SESSION_ID, sidLink)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                b.setContentIntent(PendingIntent.getActivity(ctx, 100 + (sidLink.hashCode() and 0xFFFF), si, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { li ->
                b.setContentIntent(PendingIntent.getActivity(ctx, 1, li, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            nm.notify(notifId(p.id), b.build())
        } catch (e: Exception) { }
    }

    private fun cancelNotification(ctx: Context, id: String) {
        try { ctx.getSystemService(NotificationManager::class.java)?.cancel(notifId(id)) } catch (e: Exception) { }
    }

    /** সিকিউরিটি স্ক্রিনের টেস্ট বাটন। false = অনুমতি/নোটিফিকেশন বন্ধ। */
    fun sendTestNotification(ctx: Context): Boolean {
        if (!canNotify(ctx)) return false
        return try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
            ensureChannel(nm)
            val n = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(ctx.getString(R.string.elora_name) + " · টেস্ট")
                .setContentText("নোটিফিকেশন ঠিকমতো কাজ করছে। অনুমোদনের অনুরোধ এভাবেই আসবে।")
                .setAutoCancel(true)
                .build()
            nm.notify(TEST_NOTIF_ID, n)
            true
        } catch (e: Exception) { false }
    }

    // ------------------------------------------------------------------ ফাইল-সংরক্ষণ
    private fun dirFor(ctx: Context, sessionId: String?): File =
        if (sessionId != null && ToolsPaths.isValidId(sessionId)) ToolsPaths.sessionDir(ctx, sessionId).apply { mkdirs() }
        else ToolsPaths.root(ctx)

    private fun readStore(f: File): JSONObject = try {
        if (f.exists()) JSONObject(f.readText()) else JSONObject()
    } catch (e: Exception) { JSONObject() }

    private fun writeStore(f: File, o: JSONObject) {
        try {
            if (o.length() == 0) { f.delete(); return }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) { f.writeText(o.toString()); tmp.delete() }
        } catch (e: Exception) { }
    }

    private fun persist(ctx: Context, p: Pending, state: String) {
        try {
            val f = File(dirFor(ctx, p.sessionId), FILE_NAME)
            synchronized(lock) {
                val o = readStore(f)
                o.put(p.id, JSONObject()
                    .put("key", p.key).put("risk", p.risk.name).put("title", p.title)
                    .put("detail", p.detail).put("deadline", p.deadline).put("state", state))
                writeStore(f, o)
            }
        } catch (e: Exception) { }
    }

    private fun updateState(ctx: Context, p: Pending, state: String) {
        try {
            val f = File(dirFor(ctx, p.sessionId), FILE_NAME)
            synchronized(lock) {
                val o = readStore(f)
                o.optJSONObject(p.id)?.put("state", state)
                writeStore(f, o)
            }
        } catch (e: Exception) { }
    }

    private fun deleteRecord(ctx: Context, sessionId: String?, id: String) {
        try {
            val f = File(dirFor(ctx, sessionId), FILE_NAME)
            synchronized(lock) {
                val o = readStore(f)
                if (o.has(id)) { o.remove(id); writeStore(f, o) }
            }
        } catch (e: Exception) { }
    }

    /** প্রসেস-মৃত্যুর আগে/পরে নোটিফিকেশনে দেওয়া সিদ্ধান্ত (মেয়াদ থাকলে) একবারই ফেরে। */
    private fun takeSavedDecision(ctx: Context, sessionId: String?, key: String): Decision? {
        return try {
            val f = File(dirFor(ctx, sessionId), FILE_NAME)
            synchronized(lock) {
                val o = readStore(f)
                var found: Decision? = null
                var foundId: String? = null
                val now = System.currentTimeMillis()
                val ids = o.keys().asSequence().toList()
                for (id in ids) {
                    val r = o.optJSONObject(id) ?: continue
                    if (r.optString("key") != key || r.optLong("deadline") <= now) continue
                    when (r.optString("state")) {
                        "approved" -> { found = Decision.APPROVED; foundId = id }
                        "denied" -> { found = Decision.DENIED; foundId = id }
                    }
                    if (found != null) break
                }
                if (foundId != null) { o.remove(foundId); writeStore(f, o) }
                found
            }
        } catch (e: Exception) { null }
    }

    /** অ্যাপ শুরুতে: মেয়াদ-থাকা পেন্ডিং অনুরোধ মেমরিতে ফেরানো; মেয়াদ-শেষগুলো মোছা। */
    private fun restore(ctx: Context) {
        val dirs = ArrayList<Pair<String?, File>>()
        dirs.add(null to ToolsPaths.root(ctx))
        ToolsPaths.sessionsDir(ctx).listFiles()?.forEach {
            if (it.isDirectory && ToolsPaths.isValidId(it.name)) dirs.add(it.name to it)
        }
        val now = System.currentTimeMillis()
        for ((sid, dir) in dirs) {
            val f = File(dir, FILE_NAME)
            if (!f.exists()) continue
            val o = readStore(f)
            var changed = false
            val ids = o.keys().asSequence().toList()
            for (id in ids) {
                val r = o.optJSONObject(id)
                if (r == null || r.optLong("deadline") <= now) {
                    o.remove(id); changed = true
                    cancelNotification(ctx, id)
                    continue
                }
                if (r.optString("state", "pending") != "pending") continue
                val risk = if (r.optString("risk") == "MEDIUM") Risk.MEDIUM else Risk.HIGH
                val p = Pending(id, r.optString("key"), risk, r.optString("title"), r.optString("detail"), r.optLong("deadline"), sid)
                synchronized(lock) { byId[id] = p; byKey[p.key] = p }
            }
            if (changed) writeStore(f, o)
        }
        main.post { refreshUi() }
    }

    private fun keyOf(sessionId: String?, risk: Risk, title: String, detail: String): String {
        val raw = (sessionId ?: "_") + "|" + risk.name + "|" + title + "|" + detail
        val d = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }.take(16)
    }
}
