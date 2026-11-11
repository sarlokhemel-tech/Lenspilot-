package com.hemel.lenspilot.automation

import android.content.Context
import com.hemel.lenspilot.R
import com.hemel.lenspilot.net.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

/** ব্যাকএন্ডের /api/automation/… এন্ডপয়েন্টের ক্লায়েন্ট (app.py-র "AI Automation of the Real World" ব্লক)। */
object AutomationApi {

    private fun base(ctx: Context) = ctx.getString(R.string.space_base_url)

    suspend fun search(ctx: Context, query: String): Result<List<JSONObject>> =
        ApiClient.getAuthed(ctx, base(ctx), "/api/automation/search?q=" + URLEncoder.encode(query, "UTF-8"))
            .mapCatching { body ->
                val arr = JSONObject(body).getJSONArray("agents")
                (0 until arr.length()).map { arr.getJSONObject(it) }
            }

    /** ডিভাইস-কী + AP SSID/পাসওয়ার্ড/হোস্ট — শুধু লগইন-করা ইউজার পায়, সার্চে এগুলো যায় না। */
    suspend fun deviceKey(ctx: Context, agentId: String): Result<JSONObject> =
        ApiClient.getAuthed(ctx, base(ctx), "/api/automation/device-key/$agentId")
            .mapCatching { JSONObject(it) }

    /** AI-কন্ট্রোলের এক ধাপ: ডিভাইসের ক্ষমতা + অবস্থা + আগের ফলাফল পাঠালে সার্ভারের AI পরের অ্যাকশন ঠিক করে দেয়। */
    suspend fun aiDecide(ctx: Context, body: JSONObject): Result<JSONObject> =
        ApiClient.callAuthed(ctx, base(ctx), "/api/automation/ai/decide", body.toString())
            .mapCatching { JSONObject(it) }

    /** এজেন্টের সব ফ্রেমওয়ার্ক-পার্ট নামিয়ে (sha256 মিলিয়ে) ফোনে রাখে, তারপর এজেন্টটা "ইনস্টল-করা" তালিকায় তোলে।
     * [onProgress] মেইন থ্রেডে ডাকা হয়। */
    suspend fun download(ctx: Context, agent: JSONObject, onProgress: (String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val id = agent.getString("id")
                val parts = agent.optJSONObject("flash")?.optJSONArray("parts")
                val n = parts?.length() ?: 0
                for (i in 0 until n) {
                    val p = parts!!.getJSONObject(i)
                    val url = p.optString("url")
                    if (url.isEmpty()) continue
                    withContext(Dispatchers.Main) { onProgress("Downloading file ${i + 1}/$n…") }
                    val bytes = ApiClient.getBytesAuthed(ctx, base(ctx), url).getOrThrow()
                    val want = p.optString("sha256")
                    if (want.isNotEmpty()) {
                        val got = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                        if (!got.equals(want, true)) throw IllegalStateException("File ${i + 1} is corrupted (checksum mismatch). Please try again.")
                    }
                    AgentStore.partFile(ctx, id, i).writeBytes(bytes)
                }
                AgentStore.save(ctx, agent)
            }
        }
}
