package com.hemel.lenspilot.tools.runner

import android.content.Context
import com.hemel.lenspilot.tools.StepLog
import com.hemel.lenspilot.tools.ToolsPaths
import org.json.JSONObject
import java.util.UUID

/**
 * Runner-এর পাবলিক দরজা। ইভেন্ট-চালিত: arm করার পর অপেক্ষায় LLM-এর টোকেন খরচ শূন্য;
 * শুধু done/failed/stuck/needs_you-তে [ToolsEvents]-এ ঘটনা আসে।
 */
object Runner {
    private const val MAX_WATCHERS = 20

    /**
     * নজরদারি শুরু। [detector] ভ্যালিড না হলে [DetectorSpecException] (Bangla বার্তা)।
     * @return watcher id
     */
    fun arm(ctx: Context, sessionId: String?, stepId: String, detector: JSONObject, stuckAfterS: Int = 1800): String {
        val app = ctx.applicationContext
        if (sessionId != null && !ToolsPaths.isValidId(sessionId)) throw DetectorSpecException("অবৈধ সেশন আইডি")
        DetectorParser.parse(detector)   // শুধু যাচাই; ছুঁড়লে এখানেই থামে
        if (WatcherStore.all(app).size >= MAX_WATCHERS) throw DetectorSpecException("একসাথে ২০টির বেশি নজরদারি চলে না")
        val spec = WatcherSpec(
            id = UUID.randomUUID().toString().take(12),
            sessionId = sessionId,
            stepId = stepId.take(64),
            detectorJson = detector.toString(),
            stuckAfterS = stuckAfterS.coerceIn(30, 86_400),
            createdAt = System.currentTimeMillis()
        )
        WatcherStore.put(app, spec)
        RunnerService.start(app)
        return spec.id
    }

    fun cancel(ctx: Context, watcherId: String): Boolean {
        val app = ctx.applicationContext
        val spec = WatcherStore.all(app).firstOrNull { it.id == watcherId } ?: return false
        WatcherStore.remove(app, spec)
        RunnerService.cancelWatcher(watcherId)
        return true
    }

    fun active(ctx: Context): List<WatcherSpec> = WatcherStore.all(ctx.applicationContext)

    /** রিবুট/প্রসেস-মৃত্যুর পর: সংরক্ষিত watcher থাকলে সার্ভিস চালু। */
    fun rearm(ctx: Context) {
        val app = ctx.applicationContext
        if (WatcherStore.all(app).isNotEmpty()) RunnerService.start(app)
    }

    /** ঘটনা পৌঁছে দেওয়া: steps.jsonl (redacted) + ToolsEvents + ইউজার-নোটিফিকেশন। */
    fun deliver(ctx: Context, e: RunnerEvent) {
        val app = ctx.applicationContext
        val sid = e.sessionId
        if (sid != null) {
            StepLog.append(app, sid, JSONObject()
                .put("type", "runner_event").put("kind", e.kind.wire)
                .put("step", e.stepId).put("evidence", e.evidence))
        }
        ToolsEvents.publish(e)
        val title = when (e.kind) {
            RunnerKind.DONE -> "কাজ শেষ"
            RunnerKind.FAILED -> "কাজ ব্যর্থ"
            RunnerKind.STUCK -> "কাজ আটকে আছে"
            RunnerKind.NEEDS_YOU -> "আপনার দরকার"
        }
        ToolsNotifier.event(app, title, e.evidence.ifEmpty { e.stepId }, sid)
    }
}
