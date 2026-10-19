package com.hemel.lenspilot.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * গোপন ভল্ট — AI ব্রাউজারের জন্য ইউজারনেম/ইমেইল/ফোন/পাসওয়ার্ড রাখার জায়গা।
 *
 * নিয়ম (ইউজারের নির্দেশ):
 *  * শিরোনাম ধরে সাজানো: "Facebook" → { ইমেইল, পাসওয়ার্ড, ... }।
 *  * AI শুধু শিরোনাম ও ফিল্ডের নাম দেখে ([promptIndex]) — মান কখনো সার্ভারে/AI-তে যায় না।
 *    মান বসে শুধু এই ফোনের ভেতরে ([get] → WebView-তে সরাসরি পেস্ট)।
 *  * মান Android Keystore-এর AES-256-GCM কী দিয়ে এনক্রিপ্ট করা; কী ডিভাইস ছেড়ে বেরোয় না।
 *    শিরোনাম ও ফিল্ডের নাম প্লেইন থাকে (AI-কে দেখানো হয় বলে), মান নয়।
 */
object SecretVault {
    private const val PREFS = "lenspilot_secret_vault"
    private const val KEY_ALIAS = "lenspilot_vault_key_v1"
    private const val KEY_DATA = "data"          // { title: { label: "iv:cipher" (base64) } }
    private const val KEY_USED = "used"          // { title: lastUsedMillis }
    private const val MAX_TITLES = 60
    private const val MAX_FIELDS = 12

    data class Item(val title: String, val fields: List<String>)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- Keystore
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String? = try {
        val i = blob.indexOf(':')
        val iv = Base64.decode(blob.substring(0, i), Base64.NO_WRAP)
        val ct = Base64.decode(blob.substring(i + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    } catch (e: Exception) { null }

    // ---------------------------------------------------------------- store
    private fun load(c: Context): JSONObject = try {
        JSONObject(prefs(c).getString(KEY_DATA, "{}") ?: "{}")
    } catch (e: Exception) { JSONObject() }

    private fun save(c: Context, o: JSONObject) { prefs(c).edit().putString(KEY_DATA, o.toString()).apply() }

    private fun usedMap(c: Context): JSONObject = try {
        JSONObject(prefs(c).getString(KEY_USED, "{}") ?: "{}")
    } catch (e: Exception) { JSONObject() }

    /** শিরোনামের তুলনা: ছোট হাতের, ফাঁকা-ছাঁটা। */
    private fun norm(s: String) = s.trim().lowercase()

    private fun findTitleKey(o: JSONObject, title: String): String? {
        val want = norm(title)
        val it = o.keys()
        while (it.hasNext()) { val k = it.next(); if (norm(k) == want) return k }
        return null
    }

    private fun findLabelKey(item: JSONObject, label: String): String? {
        val want = norm(label)
        val it = item.keys()
        while (it.hasNext()) { val k = it.next(); if (norm(k) == want) return k }
        return null
    }

    /** শিরোনাম + ফিল্ডের নাম (মান নয়) — UI-তে তালিকা দেখানোর জন্য। */
    fun list(c: Context): List<Item> {
        val o = load(c)
        val out = ArrayList<Item>()
        val it = o.keys()
        while (it.hasNext()) {
            val t = it.next()
            val f = o.optJSONObject(t) ?: continue
            val labels = ArrayList<String>()
            val fi = f.keys(); while (fi.hasNext()) labels.add(fi.next())
            out.add(Item(t, labels))
        }
        return out.sortedBy { norm(it.title) }
    }

    fun count(c: Context): Int = load(c).length()

    fun hasField(c: Context, title: String, label: String): Boolean {
        val o = load(c)
        val tk = findTitleKey(o, title) ?: return false
        val item = o.optJSONObject(tk) ?: return false
        return findLabelKey(item, label) != null
    }

    /** মান পড়া — শুধু অ্যাপের ভেতরে (WebView-তে বসাতে)। কখনো সার্ভারে/লগে/ইতিহাসে পাঠানো যাবে না। */
    fun get(c: Context, title: String, label: String): String? {
        val o = load(c)
        val tk = findTitleKey(o, title) ?: return null
        val item = o.optJSONObject(tk) ?: return null
        val lk = findLabelKey(item, label) ?: return null
        val v = decrypt(item.optString(lk)) ?: return null
        touch(c, tk)
        return v
    }

    /** নতুন বা বদলানো। শিরোনাম/ফিল্ড আগে থাকলে একই নামে বসে। false = সীমা পেরিয়েছে বা এনক্রিপ্ট ব্যর্থ। */
    fun put(c: Context, title: String, label: String, value: String): Boolean {
        val t = title.trim().take(40); val l = label.trim().take(40)
        if (t.isEmpty() || l.isEmpty() || value.isEmpty()) return false
        return try {
            val o = load(c)
            val tk = findTitleKey(o, t) ?: t
            if (!o.has(tk) && o.length() >= MAX_TITLES) return false
            val item = o.optJSONObject(tk) ?: JSONObject()
            val lk = findLabelKey(item, l) ?: l
            if (!item.has(lk) && item.length() >= MAX_FIELDS) return false
            item.put(lk, encrypt(value))
            o.put(tk, item)
            save(c, o)
            touch(c, tk)
            true
        } catch (e: Exception) { false }
    }

    fun deleteField(c: Context, title: String, label: String) {
        val o = load(c)
        val tk = findTitleKey(o, title) ?: return
        val item = o.optJSONObject(tk) ?: return
        findLabelKey(item, label)?.let { item.remove(it) }
        if (item.length() == 0) o.remove(tk) else o.put(tk, item)
        save(c, o)
    }

    fun deleteTitle(c: Context, title: String) {
        val o = load(c)
        findTitleKey(o, title)?.let { o.remove(it) }
        save(c, o)
    }

    private fun touch(c: Context, title: String) {
        try {
            val u = usedMap(c); u.put(title, System.currentTimeMillis())
            prefs(c).edit().putString(KEY_USED, u.toString()).apply()
        } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------- AI-র জন্য সূচি (RAG-হালকা)
    private fun tokens(s: String): Set<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.toSet()

    /**
     * AI-কে যে সূচি পাঠানো হয়: [{"title":"Facebook","fields":["ইমেইল","পাসওয়ার্ড"]}] — মান নেই।
     * প্রাসঙ্গিকতা: লক্ষ্য + এখনকার সাইটের হোস্ট/শিরোনামের শব্দের সাথে শিরোনামের মিল (যেমন "m.facebook.com" → Facebook)।
     * মিল না থাকলে সবচেয়ে সাম্প্রতিক ব্যবহৃত কয়েকটা। সর্বোচ্চ [limit]টা।
     */
    fun promptIndex(c: Context, goal: String, url: String, pageTitle: String, limit: Int = 8): JSONArray {
        val items = list(c)
        if (items.isEmpty()) return JSONArray()
        val hostTokens = tokens(try { android.net.Uri.parse(url).host.orEmpty().replace('.', ' ') } catch (e: Exception) { "" })
        val ctx = tokens("$goal $pageTitle") + hostTokens
        val used = usedMap(c)
        val scored = items.map { Triple(it, score(it, ctx, hostTokens), used.optLong(it.title, 0L)) }
            .sortedWith(compareByDescending<Triple<Item, Double, Long>> { it.second }
                .thenByDescending { it.third }.thenBy { norm(it.first.title) })
        val arr = JSONArray()
        for ((item, sc, _) in scored.take(limit)) {
            // মিল নেই এমন শিরোনাম শুধু প্রথম ৩টা পর্যন্ত (সাম্প্রতিকতা অনুযায়ী) — অকারণে সব ফাঁস নয়
            if (sc <= 0.0 && arr.length() >= 3) break
            arr.put(JSONObject().put("title", item.title).put("fields", JSONArray(item.fields)))
        }
        return arr
    }

    private fun score(item: Item, ctx: Set<String>, host: Set<String>): Double {
        val tt = tokens(item.title)
        var s = 0.0
        for (t in tt) {
            if (t in host) s += 5.0
            else if (t in ctx) s += 3.0
            else if (ctx.any { it.length >= 4 && (it.contains(t) || t.contains(it)) }) s += 1.5
        }
        return s
    }

    /** ইতিহাস/স্ট্যাটাসে দেখানোর জন্য — মান ছাড়া। */
    fun refText(title: String, label: String) = "$title/$label"
}
