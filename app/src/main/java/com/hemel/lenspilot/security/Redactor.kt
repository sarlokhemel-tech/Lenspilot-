package com.hemel.lenspilot.security

import android.content.Context
import android.util.Base64
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Redactor — ডিভাইস ছেড়ে বেরোনোর বা ডিস্কে লেখার আগে যেকোনো স্ট্রিং থেকে ভল্টের সব মান ও GitHub টোকেন মুছে ফেলে।
 *
 * কী মাস্ক হয় (প্রতিটি মান, দৈর্ঘ্য ৬ বা বেশি):
 *  * হুবহু মান;
 *  * URL-এনকোডেড রূপ (`+` ও `%20` দুটোই);
 *  * JSON-এস্কেপড রূপ (রিকোয়েস্ট বডি JSON বলে);
 *  * Base64 রূপ (স্ট্যান্ডার্ড ও URL-safe, প্যাডিংসহ ও ছাড়া)।
 *
 * ভল্ট বদলালে ([SecretVault.version]) ক্যাশ নিজে নতুন করে বানায়। ক্যাশে শুধু মাস্ক করার রূপগুলো থাকে।
 * প্লেসহোল্ডার ({{vault:...}}) গোপন নয় — ওগুলো যেমন আছে তেমনই থাকে।
 */
object Redactor {
    private const val MIN_LEN = 6
    private const val MASK = "[REDACTED]"

    @Volatile private var cachedVersion = -1
    @Volatile private var forms: List<String> = emptyList()
    private val lock = Any()

    /** Context না পেলে (init হয়নি) মূল টেক্সটই ফেরত — কিন্তু বাস্তবে Application.onCreate-এ init হয়। */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        val ctx = SecurityHub.context() ?: return text
        return redact(ctx, text)
    }

    fun redact(ctx: Context, text: String): String {
        if (text.isEmpty()) return text
        val list = currentForms(ctx)
        if (list.isEmpty()) return text
        var out = text
        for (f in list) {
            if (out.contains(f)) out = out.replace(f, MASK)
        }
        return out
    }

    /** শুধু জানার জন্য: এই টেক্সটে কোনো গোপন মান আছে কি। */
    fun containsSecret(text: String): Boolean = redact(text) != text

    private fun currentForms(ctx: Context): List<String> {
        if (SecretVault.version == cachedVersion) return forms
        return synchronized(lock) {
            val ver = SecretVault.version
            if (ver == cachedVersion) {
                forms
            } else {
                val set = LinkedHashSet<String>()
                var failed = false
                try {
                    for (value in SecretVault.allValues(ctx)) {
                        if (value.length < MIN_LEN) continue
                        addForms(set, value)
                    }
                } catch (e: Exception) {
                    failed = true   // ডিক্রিপ্ট ব্যর্থ হলে আগের ক্যাশই থাকুক
                }
                if (!failed) {
                    // লম্বা আগে — যাতে ছোট রূপ বড় রূপের ভেতরে আংশিক না কাটে
                    forms = set.sortedByDescending { it.length }
                    cachedVersion = ver
                }
                forms
            }
        }
    }

    private fun addForms(set: MutableSet<String>, value: String) {
        set.add(value)
        try {
            val enc = URLEncoder.encode(value, "UTF-8")
            if (enc.length >= MIN_LEN) {
                set.add(enc)
                set.add(enc.replace("+", "%20"))
            }
        } catch (e: Exception) { }
        try {
            // JSON-এ বসলে যেভাবে দেখায় (ভেতরের " ও \ এস্কেপড); চারপাশের কোটেশন বাদ
            val q = JSONObject.quote(value)
            val inner = q.substring(1, q.length - 1)
            if (inner != value && inner.length >= MIN_LEN) set.add(inner)
        } catch (e: Exception) { }
        try {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val std = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val url = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
            for (b in listOf(std, std.trimEnd('='), url, url.trimEnd('='))) {
                if (b.length >= MIN_LEN) set.add(b)
            }
        } catch (e: Exception) { }
    }

    /**
     * OkHttp ইন্টারসেপ্টর — ApiClient-এর একমাত্র চোক-পয়েন্ট। সার্ভারে যাওয়া প্রতিটি টেক্সট/JSON বডি মাস্ক হয়।
     * মাল্টিপার্ট (অডিও) বাইনারি — ওখানে স্ট্রিং নেই, তাই ছোঁয়া হয় না।
     */
    val interceptor: Interceptor = Interceptor { chain ->
        val req = chain.request()
        val body = req.body
        val type = body?.contentType()
        val textual = type != null && (type.subtype == "json" || type.type == "text" || type.subtype == "x-www-form-urlencoded")
        if (body == null || !textual || body.isOneShot() || body.isDuplex()) {
            chain.proceed(req)
        } else {
            val buf = Buffer()
            body.writeTo(buf)
            val original = buf.readUtf8()
            val masked = redact(original)
            val newReq = if (masked == original) req else req.newBuilder()
                .method(req.method, masked.toRequestBody(type))
                .build()
            chain.proceed(newReq)
        }
    }
}
