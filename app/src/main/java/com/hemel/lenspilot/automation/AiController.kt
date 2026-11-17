package com.hemel.lenspilot.automation

import android.content.Context
import com.hemel.lenspilot.net.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * AI দিয়ে ESP32 নিয়ন্ত্রণ। ফোন এখানে শুধু সেতু — সিদ্ধান্ত নেয় আপনার সার্ভারের AI (সার্ভারের API key, ইউজারের টোকেন-ওয়ালেট):
 *
 *   ESP32  ──/ai/capabilities, /ai/state──►  অ্যাপ  ──/api/automation/ai/decide──►  সার্ভারের AI
 *   ESP32  ◄──/ai/act {action, params}─────  অ্যাপ  ◄── {say, actions[], wait_ms, done, notes, ask_user} ──
 *
 * ফার্মওয়্যার নিজে ঘোষণা করে সে কী কী করতে পারে (capabilities) — তাই ফ্রেমওয়ার্ক যেমনই হোক, এই লুপ বদলাতে হয় না।
 * AI-র স্মৃতি (notes) প্রতি ধাপে সার্ভার-AI নিজেই লিখে, অ্যাপ শুধু ফিরিয়ে পাঠায়। এখানে কোনো শব্দ-মেলানো বা সংখ্যার সীমা নেই;
 * একমাত্র সীমা ডিভাইসের নিজের ঘোষণা করা (ফার্মওয়্যারও যা নিজে যাচাই করে)।
 */
class AiController(
    private val ctx: Context,
    private val agentId: String,
    private val session: DeviceSession,
    private val host: Host
) {
    /** সব মেথড ব্যাকগ্রাউন্ড থ্রেড থেকে ডাকা হয় — ইমপ্লিমেন্টেশন নিজে মেইন থ্রেডে নেবে। */
    interface Host {
        fun log(line: String)
        fun onRunning(running: Boolean)
        /** ডিভাইস যে অ্যাকশনে "confirm": true দিয়েছে (গরম/বিপজ্জনক) তার আগে ইউজারের অনুমতি। */
        suspend fun confirm(text: String): Boolean
        /** AI ইউজারকে প্রশ্ন করলে — ইউজারের উত্তর। */
        suspend fun ask(question: String): String
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    /** লুপ + তার শেষের safe_stop পর্যন্ত true (cancel করলেও safe_stop শেষ না হওয়া অবধি)। */
    @Volatile private var active = false
    val running: Boolean get() = active

    fun start(goal: String) {
        if (running || goal.isBlank()) return
        active = true
        host.onRunning(true)
        job = scope.launch {
            var finishedNormally = false
            var caps = JSONObject()
            try {
                caps = readCaps()
                finishedNormally = loop(goal.trim(), caps)
                host.log("✔ Task complete")
            } catch (e: CancellationException) {
                host.log("■ Stopped")
                throw e
            } catch (e: Exception) {
                host.log("✗ ${e.message ?: e.toString()}")
            } finally {
                withContext(NonCancellable) {
                    if (!finishedNormally) safeStop(caps)
                    active = false
                    host.onRunning(false)
                }
            }
        }
    }

    fun stop() { job?.cancel() }

    fun release() {
        job?.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    // ---------- ডিভাইসের সাথে কথা ----------

    private fun deviceJson(method: String, path: String, body: JSONObject? = null): JSONObject {
        val headers = if (body != null) mapOf("Content-Type" to "application/json") else emptyMap()
        val resp = session.forward(
            DeviceRequest(method, path, headers, body?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0))
        )
        val text = String(resp.body, Charsets.UTF_8)
        if (resp.status !in 200..299) {
            val m = try { JSONObject(text).optString("error") } catch (e: Exception) { "" }
            throw IOException(m.ifBlank { "HTTP ${resp.status} on device $path" })
        }
        return try { JSONObject(text) } catch (e: Exception) { throw IOException("The device's $path response is not JSON") }
    }

    private fun readCaps(): JSONObject {
        host.log("Reading device capabilities…")
        return try {
            deviceJson("GET", "/ai/capabilities")
        } catch (e: IOException) {
            throw IOException("This firmware has no AI control (/ai/capabilities not found): ${e.message}")
        }
    }

    private fun actionMeta(caps: JSONObject, name: String): JSONObject? {
        val arr = caps.optJSONArray("actions") ?: return null
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            if (a.optString("name") == name) return a
        }
        return null
    }

    private fun safeStop(caps: JSONObject) {
        val name = caps.optString("safe_stop")
        if (name.isBlank()) return
        try {
            deviceJson("POST", "/ai/act", JSONObject().put("action", name).put("params", JSONObject()))
            host.log("⛑ Safe stop ($name) sent")
        } catch (e: Exception) {
            host.log("⚠ Could not send the safe stop: ${e.message}")
        }
    }

    // ---------- মূল লুপ ----------

    /** true = AI নিজে বলেছে কাজ শেষ। */
    private suspend fun loop(goal: String, caps: JSONObject): Boolean {
        val t0 = System.currentTimeMillis()
        var notes = ""
        var results = JSONArray()
        var feedback = ""
        var step = 0
        var deviceFails = 0
        while (true) {
            step++
            val state = try {
                deviceJson("GET", "/ai/state")
            } catch (e: IOException) {
                JSONObject().put("_error", e.message ?: "Could not read the state")
            }
            if (state.has("_error")) deviceFails++ else deviceFails = 0
            if (deviceFails >= 3) throw IOException("Communication with the device keeps failing. Please check the connection.")

            val body = JSONObject()
                .put("agent_id", agentId)
                .put("goal", goal)
                .put("capabilities", caps)
                .put("state", state)
                .put("last_results", results)
                .put("notes", notes)
                .put("user_feedback", feedback)
                .put("elapsed_s", (System.currentTimeMillis() - t0) / 1000)
                .put("step", step)
            val reply = AutomationApi.aiDecide(ctx, body).getOrElse { e ->
                val msg = if (e is ApiException) {
                    try { JSONObject(e.body).optString("error") } catch (x: Exception) { "" }
                } else ""
                throw IOException(msg.ifBlank { "Could not reach the server AI: ${e.message}" })
            }

            val say = reply.optString("say")
            if (say.isNotBlank()) host.log("🤖 $say")
            if (reply.has("notes")) notes = reply.optString("notes")
            reply.optJSONArray("rejected")?.let { rj ->
                for (i in 0 until rj.length()) host.log("⚠ Skipped: ${rj.optString(i)}")
            }

            results = JSONArray()
            feedback = ""
            val actions = reply.optJSONArray("actions") ?: JSONArray()
            for (i in 0 until actions.length()) {
                val a = actions.optJSONObject(i) ?: continue
                val name = a.optString("action")
                val params = a.optJSONObject("params") ?: JSONObject()
                val rec = JSONObject().put("action", name).put("params", params)
                host.log("▶ $name $params")
                val needConfirm = actionMeta(caps, name)?.optBoolean("confirm", false) == true
                if (needConfirm && !host.confirm("The AI wants to run this:\n$name $params\n\nRun it?")) {
                    results.put(rec.put("ok", false).put("error", "The user did not grant permission"))
                    host.log("✗ The user did not grant permission")
                    continue
                }
                try {
                    val res = deviceJson("POST", "/ai/act", JSONObject().put("action", name).put("params", params))
                    val ok = res.optBoolean("ok", true)
                    results.put(rec.put("ok", ok).put("result", res))
                    host.log(if (ok) "✓ $name" else "✗ $name: ${res.optString("error")}")
                } catch (e: IOException) {
                    results.put(rec.put("ok", false).put("error", e.message ?: "Could not send to the device"))
                    host.log("✗ $name: ${e.message}")
                }
            }

            if (reply.optBoolean("done", false)) return true

            val q = reply.optString("ask_user")
            if (q.isNotBlank()) {
                host.log("❓ $q")
                feedback = host.ask(q)
                host.log("🙂 $feedback")
            } else {
                delay(reply.optLong("wait_ms", 2_000L).coerceAtLeast(0L))
            }
        }
    }
}
