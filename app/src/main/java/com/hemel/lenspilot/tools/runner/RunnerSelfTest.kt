package com.hemel.lenspilot.tools.runner

import android.content.Context
import com.hemel.lenspilot.tools.ToolsPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * ফোনে AI ছাড়াই যাচাইয়ের ডিবাগ-পরীক্ষা (নিরাপত্তা স্ক্রিনের বাটন থেকে)।
 * শুধু বিল্ড-যাচাইয়ের জন্য — সাধারণ ব্যবহারে এগুলো ডাকা হয় না।
 */
object RunnerSelfTest {
    const val TEST_STEP = "debug_test"
    const val TEST_NOTE = "ELORA টেস্ট:"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * ২০ সেকেন্ড পর গ্লোবাল workspace-এ একটা ফাইল তৈরি হবে; file_appears detector সেটা ধরবে (ইভেন্ট ToolsEvents-এ ও নোটিফিকেশনে)।
     * ফাইলটা ৪০ সেকেন্ডে নিজেই মুছে যায়। ব্যর্থ হলে বার্তা, সফল হলে null।
     */
    fun startWatcherTest(ctx: Context): String? {
        val app = ctx.applicationContext
        return try {
            val ws = ToolsPaths.globalWorkspace(app)
            ws.listFiles()?.forEach { if (it.name.startsWith("elora_test_")) it.delete() }   // আগের অবশেষ থাকলে ভুল-সফল হয়
            val name = "elora_test_" + System.currentTimeMillis() + ".txt"
            val det = JSONObject()
                .put("type", "file_appears").put("glob", "elora_test_*.txt")
                .put("stable_for_s", 2).put("interval_s", 2).put("max_duration_s", 120)
            Runner.arm(app, null, TEST_STEP, det, 60)
            scope.launch {
                delay(20_000L)
                try { File(ws, name).writeText("ELORA runner test") } catch (e: Exception) { }
                delay(20_000L)
                try { File(ws, name).delete() } catch (e: Exception) { }
            }
            null
        } catch (e: DetectorSpecException) {
            e.message ?: "detector ত্রুটি"
        } catch (e: Exception) {
            "শুরু করা যায়নি: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    /** ১ মিনিট পরের নোটিফিকেশন। ফেরত: সফল হলে "EXACT"/"WORK", ব্যর্থ হলে "ERR:বার্তা"। */
    fun startScheduleTest(ctx: Context): String = try {
        val (_, mode) = Scheduler.create(
            ctx.applicationContext, null, System.currentTimeMillis() + 60_000L,
            ScheduleAction.Notify, "$TEST_NOTE ১ মিনিট পার হয়েছে।"
        )
        mode.name
    } catch (e: ScheduleSpecException) {
        "ERR:" + (e.message ?: "")
    } catch (e: Exception) {
        "ERR:" + (e.message ?: e.javaClass.simpleName)
    }
}
