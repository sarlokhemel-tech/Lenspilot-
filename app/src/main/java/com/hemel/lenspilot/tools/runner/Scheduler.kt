package com.hemel.lenspilot.tools.runner

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.hemel.lenspilot.tools.StepLog
import com.hemel.lenspilot.tools.ToolsPaths
import com.hemel.lenspilot.tools.perm.Decision
import com.hemel.lenspilot.tools.perm.PermissionGate
import com.hemel.lenspilot.tools.perm.Risk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** type_text-এর আসল টাইপ করা ওয়ার্কস্পেস-স্ক্রিন (ধাপ ৫) এখানে বসাবে। না বসালে শুধু নোটিফিকেশন/ইভেন্ট যায়। */
object ScheduleExecutors {
    @Volatile
    var typeText: ((Context, ScheduledResume, ScheduleAction.TypeText) -> Boolean)? = null
}

/**
 * Scheduler — অ্যালার্মের মতো স্বয়ংক্রিয় সময়-নির্ধারণ।
 * কৌশল: ১) canScheduleExactAlarms() হলে setExactAndAllowWhileIdle; ২) না হলে WorkManager OneTimeWork (এখনই ঢাকা পড়ে)
 * এবং একবারই ব্যাখ্যা-কার্ড দেখিয়ে সঠিক-অ্যালার্ম সেটিংস খোলার প্রস্তাব; অনুমতি পেলে রিসিভার আবার exact-এ বসায়।
 */
object Scheduler {
    enum class Mode { EXACT, WORK }

    const val ACTION_FIRE = "com.hemel.lenspilot.tools.runner.SCHEDULE_FIRE"
    const val EXTRA_ID = "sched_id"
    private const val FILE = "schedules.json"
    private const val PREFS = "lenspilot_tools_scheduler"
    private const val K_ASKED = "exact_asked"
    private const val MAX_SCHEDULES = 50
    private const val MAX_AHEAD_MS = 400L * 24 * 3600 * 1000

    private val lock = Any()
    private val askScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ------------------------------------------------------------------ পাবলিক API
    /** যাচাই + সংরক্ষণ + arm। ভুল হলে [ScheduleSpecException]। */
    fun create(ctx: Context, sessionId: String?, atMillis: Long, action: ScheduleAction, note: String): Pair<ScheduledResume, Mode> {
        val app = ctx.applicationContext
        if (sessionId != null && !ToolsPaths.isValidId(sessionId)) throw ScheduleSpecException("অবৈধ সেশন আইডি")
        val now = System.currentTimeMillis()
        if (atMillis > now + MAX_AHEAD_MS) throw ScheduleSpecException("৪০০ দিনের বেশি দূরের সময় নেওয়া যায় না")
        if (upcoming(app).size >= MAX_SCHEDULES) throw ScheduleSpecException("৫০টির বেশি নির্ধারিত কাজ রাখা যায় না")
        val s = ScheduledResume(UUID.randomUUID().toString().take(12), sessionId, atMillis, action, note.take(200))
        synchronized(lock) {
            val f = file(app)
            val o = read(f)
            o.put(s.id, s.toJson())
            write(f, o)
        }
        val mode = arm(app, s)
        if (mode == Mode.WORK) maybeAskExactPermission(app)
        return s to mode
    }

    fun cancel(ctx: Context, id: String): Boolean {
        val app = ctx.applicationContext
        val removed = synchronized(lock) {
            val f = file(app)
            val o = read(f)
            val had = o.has(id)
            if (had) { o.remove(id); write(f, o) }
            had
        }
        disarm(app, id)
        return removed
    }

    fun upcoming(ctx: Context): List<ScheduledResume> = synchronized(lock) {
        val o = read(file(ctx.applicationContext))
        val out = ArrayList<ScheduledResume>()
        val ids = o.keys().asSequence().toList()
        for (id in ids) {
            o.optJSONObject(id)?.let { ScheduledResume.fromJson(it) }?.let { out.add(it) }
        }
        out.sortedBy { it.atMillis }
    }

    /** রিবুট/অনুমতি-বদলের পর সবকিছু আবার arm। */
    fun rearmAll(ctx: Context) {
        val app = ctx.applicationContext
        for (s in upcoming(app)) arm(app, s)
    }

    // ------------------------------------------------------------------ ফায়ার
    /** অ্যালার্ম/WorkManager যেই ডাকুক — একবারই চলে (store থেকে সরিয়ে নিয়ে)। */
    fun onFired(ctx: Context, id: String) {
        val app = ctx.applicationContext
        val s = synchronized(lock) {
            val f = file(app)
            val o = read(f)
            val item = o.optJSONObject(id)?.let { ScheduledResume.fromJson(it) }
            if (item != null) { o.remove(id); write(f, o) }
            item
        } ?: return
        disarm(app, id)

        val a = s.action
        val ev = ScheduleFired(
            id = s.id,
            sessionId = s.sessionId,
            action = a.wire,
            stepId = (a as? ScheduleAction.ResumeStep)?.stepId,
            targetWorkspace = (a as? ScheduleAction.TypeText)?.targetWorkspace,
            rawText = (a as? ScheduleAction.TypeText)?.let { it.textRef ?: it.literal },
            rawNote = s.note
        )
        if (s.sessionId != null) {
            StepLog.append(app, s.sessionId, JSONObject()
                .put("type", "schedule_fired").put("action", ev.action)
                .put("step", ev.stepId ?: "").put("note", ev.note))
        }
        ToolsEvents.publish(ev)

        var typed = false
        if (a is ScheduleAction.TypeText) {
            typed = try { ScheduleExecutors.typeText?.invoke(app, s, a) ?: false } catch (e: Exception) { false }
        }
        val title = "নির্ধারিত সময় হয়েছে"
        val text = when (a) {
            is ScheduleAction.ResumeStep -> "ধাপ আবার চালু করার সময়। " + s.note
            is ScheduleAction.TypeText -> (if (typed) "লেখা পাঠানো হয়েছে। " else "লেখা পাঠানোর সময় — ELORA খুলুন। ") + s.note
            ScheduleAction.Notify -> s.note.ifEmpty { "আপনার নির্ধারিত সময় এসেছে।" }
        }
        ToolsNotifier.event(app, title, text.trim(), s.sessionId)
    }

    // ------------------------------------------------------------------ arm / disarm
    private fun canExact(am: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

    private fun alarmIntent(ctx: Context, id: String): PendingIntent {
        val i = Intent(ctx, ScheduleReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(Uri.parse("lenspilot://sched/$id"))
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun arm(ctx: Context, s: ScheduledResume): Mode {
        val now = System.currentTimeMillis()
        val at = if (s.atMillis < now + 1000L) now + 1000L else s.atMillis
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (am != null && canExact(am)) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(ctx, s.id))
                WorkManager.getInstance(ctx).cancelUniqueWork(workName(s.id))   // আগে WORK-এ থাকলে ডাবল ফায়ার ঠেকাতে
                return Mode.EXACT
            } catch (e: SecurityException) {
                // অনুমতি মাঝপথে চলে গেছে — ফলব্যাকে
            }
        }
        val req = OneTimeWorkRequestBuilder<ScheduleWorker>()
            .setInitialDelay(at - now, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(EXTRA_ID to s.id))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(workName(s.id), ExistingWorkPolicy.REPLACE, req)
        return Mode.WORK
    }

    private fun disarm(ctx: Context, id: String) {
        try { ctx.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(ctx, id)) } catch (e: Exception) { }
        try { WorkManager.getInstance(ctx).cancelUniqueWork(workName(id)) } catch (e: Exception) { }
    }

    private fun workName(id: String) = "lp_sched_$id"

    // ------------------------------------------------------------------ সঠিক-অ্যালার্ম অনুমতি (একবারই জিজ্ঞাসা)
    private fun maybeAskExactPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getBoolean(K_ASKED, false)) return
        p.edit().putBoolean(K_ASKED, true).apply()
        askScope.launch {
            val d = PermissionGate.request(
                Risk.MEDIUM,
                "সঠিক সময়ে অ্যালার্মের অনুমতি",
                "নির্ধারিত সময়ে (যেমন কোনো লিমিট রিসেট হওয়ার পর) কাজ ঠিক মিনিটে চালাতে ELORA-র 'অ্যালার্ম ও রিমাইন্ডার' অনুমতি লাগে। " +
                    "'অনুমোদন' চাপলে সেটিংস খুলবে। না দিলেও কাজ চলবে, তবে কয়েক মিনিট দেরি হতে পারে।",
                60
            )
            if (d == Decision.APPROVED && Build.VERSION.SDK_INT >= 31) {
                try {
                    ctx.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) { }
            }
        }
    }

    // ------------------------------------------------------------------ ফাইল
    private fun file(ctx: Context): File = File(ToolsPaths.root(ctx), FILE)

    private fun read(f: File): JSONObject = try {
        if (f.exists()) JSONObject(f.readText()) else JSONObject()
    } catch (e: Exception) { JSONObject() }

    private fun write(f: File, o: JSONObject) {
        try {
            if (o.length() == 0) { f.delete(); return }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) { f.writeText(o.toString()); tmp.delete() }
        } catch (e: Exception) { }
    }
}
