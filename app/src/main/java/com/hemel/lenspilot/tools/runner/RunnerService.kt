package com.hemel.lenspilot.tools.runner

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.hemel.lenspilot.tools.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * ফোরগ্রাউন্ড সার্ভিস (dataSync): সক্রিয় watcher-গুলো এখানে চলে, স্থায়ী নোটিফিকেশনে তালিকা দেখায়,
 * কোনোটাই না থাকলে নিজেই বন্ধ হয়। watcher-সংজ্ঞা ডিস্কে থাকে ([WatcherStore]) — সার্ভিস শুরুতে সব ফেরায়।
 */
class RunnerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ৫ সেকেন্ডের মধ্যে startForeground বাধ্যতামূলক — আগে এটা, তারপর কাজ
        goForeground(emptyList())
        scope.launch { sync() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        jobs.clear()
        super.onDestroy()
    }

    private fun goForeground(lines: List<String>) {
        val n = ToolsNotifier.watching(this, if (lines.isEmpty()) listOf("প্রস্তুত হচ্ছে…") else lines)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            // ব্যাকগ্রাউন্ড থেকে FGS শুরু নিষিদ্ধ হলে — সার্ভিস বন্ধ; পরের অ্যাপ-খোলায় আবার চেষ্টা
            stopSelf()
        }
    }

    private fun sync() {
        val app = applicationContext
        val specs = WatcherStore.all(app)
        for ((id, j) in jobs) if (specs.none { it.id == id }) j.cancel()
        for (spec in specs) {
            if (jobs.containsKey(spec.id)) continue
            launchWatcher(spec)
        }
        main.post { afterChange() }
    }

    private fun launchWatcher(spec: WatcherSpec) {
        val app = applicationContext
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val detector = try {
                DetectorParser.parse(spec.detectorJson)
            } catch (e: Exception) {
                WatcherStore.remove(app, spec)
                Runner.deliver(app, RunnerEvent(RunnerKind.FAILED, spec.sessionId, spec.stepId, "detector অবৈধ: ${e.message}"))
                return@launch
            }
            // CancellationException এখানে ছড়িয়ে কোরুটিন বাতিল করে (cancel = চুপচাপ বন্ধ, কোনো ইভেন্ট নয়)
            val ev = DetectorRunner.watch(app, spec, detector) { stuck -> Runner.deliver(app, stuck) }
            WatcherStore.remove(app, spec)
            Runner.deliver(app, ev)
        }
        jobs[spec.id] = job
        job.invokeOnCompletion {
            jobs.remove(spec.id)
            main.post { afterChange() }
        }
        job.start()
    }

    /** তালিকা বদলালে নোটিফিকেশন হালনাগাদ; কিছু না থাকলে সার্ভিস বন্ধ। */
    private fun afterChange() {
        // ডিস্কের তালিকাই সত্য — সদ্য-arm হওয়া কিন্তু এখনো job-না-হওয়া watcher-এর জন্য ভুলে বন্ধ হওয়া ঠেকায়
        val specs = WatcherStore.all(applicationContext)
        val agentSids = com.hemel.lenspilot.tools.agent.ToolsAgents.keepAliveSessions()   // সেশন ৭: অপেক্ষমাণ এজেন্টও সার্ভিস ধরে রাখে
        if (specs.isEmpty() && agentSids.isEmpty()) {
            stopForeground(true)
            stopSelf()
            return
        }
        val lines = specs.map { s ->
            val title = s.sessionId?.let { SessionStore.get(applicationContext, it)?.title } ?: "সাধারণ"
            "• " + title + " — " + s.stepId.ifEmpty { "ধাপ" }
        } + agentSids.filter { id -> specs.none { it.sessionId == id } }.map { id ->
            "• " + (SessionStore.get(applicationContext, id)?.title ?: "কাজ") + " — চলছে"
        }
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, ToolsNotifier.watching(this, lines))
        } catch (e: Exception) { }
    }

    companion object {
        private const val NOTIF_ID = 42001
        private val jobs = ConcurrentHashMap<String, Job>()

        fun start(ctx: Context) {
            try {
                ctx.applicationContext.startForegroundService(Intent(ctx.applicationContext, RunnerService::class.java))
            } catch (e: Exception) {
                // শুরু করা যায়নি (ব্যাকগ্রাউন্ড-নিষেধ ইত্যাদি) — সংজ্ঞা ডিস্কে আছে, পরে ফেরানো হবে
            }
        }

        fun cancelWatcher(id: String) {
            jobs[id]?.cancel()
        }
    }
}
