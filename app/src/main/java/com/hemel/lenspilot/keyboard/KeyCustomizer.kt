package com.hemel.lenspilot.keyboard

import android.content.Context
import com.hemel.lenspilot.Prefs
import org.json.JSONObject

/**
 * v120 — কীবোর্ডের প্রতিটা বাটনের আলাদা মাপ (প্রস্থ / উচ্চতা / লেখার সাইজ), ইউজার টেনে ঠিক করে।
 * Prefs-এ JSON: {"<বাটন-id>":[w,h,t]} — সবই গুণক (১.০ = ডিফল্ট)। বাটন-id যেমন "q", "?123", "tb_globe"।
 */
object KeyCustomizer {
    class K(var w: Float = 1f, var h: Float = 1f, var t: Float = 1f)

    private var cache: MutableMap<String, K>? = null

    private fun load(ctx: Context): MutableMap<String, K> {
        cache?.let { return it }
        val m = HashMap<String, K>()
        runCatching {
            val o = JSONObject(Prefs.keyCustomJson(ctx))
            for (key in o.keys()) {
                val a = o.optJSONArray(key) ?: continue
                m[key] = K(a.optDouble(0, 1.0).toFloat(), a.optDouble(1, 1.0).toFloat(), a.optDouble(2, 1.0).toFloat())
            }
        }
        cache = m
        return m
    }

    /** বাটনের মাপ (না থাকলে ডিফল্ট-গুণক ১,১,১ — আর সেটা সেভ হয় না)। */
    fun get(ctx: Context, id: String): K = load(ctx).getOrPut(id) { K() }

    fun save(ctx: Context) {
        val m = load(ctx)
        val o = JSONObject()
        for ((k, v) in m) {
            if (v.w == 1f && v.h == 1f && v.t == 1f) continue
            o.put(k, org.json.JSONArray().put(v.w.toDouble()).put(v.h.toDouble()).put(v.t.toDouble()))
        }
        Prefs.setKeyCustomJson(ctx, o.toString())
    }

    fun reset(ctx: Context) {
        cache = HashMap()
        Prefs.setKeyCustomJson(ctx, "{}")
    }
}
