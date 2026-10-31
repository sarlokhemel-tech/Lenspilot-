package com.hemel.lenspilot.tools.runner

import android.content.Context
import com.hemel.lenspilot.security.VaultRefs
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.TimeUnit

/**
 * Runner-এর নিজস্ব ছোট HTTP ক্লায়েন্ট (ApiClient নয় — ওটা শুধু নিজেদের সার্ভারের জন্য)।
 * হেডারের {{vault:…}} প্রতিটি কলে ডিভাইসে রিজলভ হয়; রিজলভ-করা মান কোথাও লগ হয় না।
 */
object RunnerHttp {
    class Res(val code: Int, val body: String)

    /** হেডার-রিজলভে ভল্টে না পেলে। */
    class SecretMissing(val ref: String) : Exception("ভল্টে নেই: $ref")

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** ব্লকিং — Dispatchers.IO থেকে ডাকতে হবে। IOException ছুঁড়তে পারে। */
    fun call(
        ctx: Context,
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        maxBytes: Long
    ): Res {
        val b = Request.Builder().url(url)
        var hasSecret = false
        for ((k, v) in headers) {
            val r = VaultRefs.resolve(ctx, v)
            if (!r.ok) throw SecretMissing(r.missing ?: "?")
            if (r.text != null && r.text != v) hasSecret = true
            b.header(k, r.text ?: v)
        }
        // সেশন ৮: গোপন মানসহ হেডার শুধু https-এ যায়, আর রিডাইরেক্ট ফলো হয় না (কাস্টম হেডার অন্য হোস্টে ফাঁস ঠেকাতে)
        if (hasSecret && !url.trim().lowercase().startsWith("https://")) {
            throw java.io.IOException("গোপন মানসহ অনুরোধ শুধু https-এ চলে")
        }
        val client = if (hasSecret) client.newBuilder().followRedirects(false).followSslRedirects(false).build() else client
        if (method == "POST") {
            val mt = "application/json".toMediaTypeOrNull()
            b.post((body ?: "").toRequestBody(mt))
        } else {
            b.get()
        }
        return client.newCall(b.build()).execute().use { resp ->
            val text = try { resp.peekBody(maxBytes).string() } catch (e: Exception) { "" }
            Res(resp.code, text)
        }
    }

    // ---------------------------------------------------------------- JSON পাথ (সরল: a.b[0].c, শুরুতে $ বা $. চলে)
    fun jsonPath(rootText: String, path: String): Any? {
        val root: Any? = try { JSONTokener(rootText).nextValue() } catch (e: Exception) { return null }
        var cur: Any? = root
        val p = path.trim().removePrefix("$").removePrefix(".")
        if (p.isEmpty()) return cur
        val rx = Regex("([^.\\[\\]]+)|\\[(\\d+)\\]")
        for (m in rx.findAll(p)) {
            val key = m.groups[1]?.value
            val idx = m.groups[2]?.value
            cur = when {
                key != null -> (cur as? JSONObject)?.let { if (it.has(key)) it.opt(key) else return null } ?: return null
                idx != null -> (cur as? JSONArray)?.let { a -> idx.toIntOrNull()?.let { i -> if (i in 0 until a.length()) a.opt(i) else null } } ?: return null
                else -> return null
            }
        }
        return cur
    }

    fun compare(actual: Any?, op: String, expected: String): Boolean {
        val a = if (actual == null || actual == JSONObject.NULL) "null" else actual.toString()
        return when (op) {
            "eq" -> a == expected || (a.toDoubleOrNull() != null && a.toDoubleOrNull() == expected.toDoubleOrNull())
            "ne" -> !(a == expected || (a.toDoubleOrNull() != null && a.toDoubleOrNull() == expected.toDoubleOrNull()))
            "contains" -> a.contains(expected)
            "gt" -> { val x = a.toDoubleOrNull(); val y = expected.toDoubleOrNull(); x != null && y != null && x > y }
            "lt" -> { val x = a.toDoubleOrNull(); val y = expected.toDoubleOrNull(); x != null && y != null && x < y }
            else -> false
        }
    }
}
