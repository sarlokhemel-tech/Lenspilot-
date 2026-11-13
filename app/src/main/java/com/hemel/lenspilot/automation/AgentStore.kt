package com.hemel.lenspilot.automation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** ডাউনলোড-করা (ইনস্টল-করা) অটোমেশন এজেন্টের তালিকা + তাদের ফ্রেমওয়ার্ক বাইনারি ফাইল। */
object AgentStore {
    private const val PREFS = "lp_automation"
    private const val KEY = "installed"

    fun installed(ctx: Context): List<JSONObject> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun find(ctx: Context, id: String): JSONObject? = installed(ctx).firstOrNull { it.optString("id") == id }

    fun save(ctx: Context, agent: JSONObject) {
        val id = agent.optString("id")
        val list = installed(ctx).filter { it.optString("id") != id } + agent
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun remove(ctx: Context, id: String) {
        val arr = JSONArray()
        installed(ctx).filter { it.optString("id") != id }.forEach { arr.put(it) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
        File(ctx.filesDir, "automation/$id").deleteRecursively()
    }

    fun dir(ctx: Context, id: String): File = File(ctx.filesDir, "automation/$id").apply { mkdirs() }

    fun partFile(ctx: Context, id: String, index: Int): File = File(dir(ctx, id), "part_$index.bin")
}
