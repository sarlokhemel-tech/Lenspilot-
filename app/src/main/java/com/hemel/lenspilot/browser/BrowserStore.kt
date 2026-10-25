package com.hemel.lenspilot.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * v43: Lenspilot AI Browser-এর নিজস্ব ডেটা — বুকমার্ক, হিস্ট্রি, ডাউনলোড-তালিকা, সার্চ ইঞ্জিন।
 * SharedPreferences-এ ছোট JSON; কোনো সার্ভারে যায় না।
 */
object BrowserStore {

    class Entry(val url: String, val title: String, val time: Long)

    /** id >= 0 হলে DownloadManager-এর আইডি; -1 হলে নিজে সংরক্ষণ (blob/data)। */
    class Dl(val id: Long, val name: String, val url: String, val time: Long, val uri: String, val mime: String)

    private const val PREFS = "lens_browser"
    private const val K_HISTORY = "history"
    private const val K_BOOKMARKS = "bookmarks"
    private const val K_DOWNLOADS = "downloads"
    private const val K_ENGINE = "engine"
    private const val HISTORY_LIMIT = 500      // শুধু জায়গা বাঁচাতে; কোনো সিদ্ধান্তে ব্যবহার হয় না
    private const val DOWNLOAD_LIMIT = 200

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readEntries(c: Context, key: String): MutableList<Entry> {
        val arr = try { JSONArray(sp(c).getString(key, "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optString("u")
            if (u.isNotEmpty()) out.add(Entry(u, o.optString("t"), o.optLong("ts")))
        }
        return out
    }

    private fun writeEntries(c: Context, key: String, list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) arr.put(JSONObject().put("u", e.url).put("t", e.title).put("ts", e.time))
        sp(c).edit().putString(key, arr.toString()).apply()
    }

    // ---- হিস্ট্রি ----
    fun history(c: Context): List<Entry> = readEntries(c, K_HISTORY)

    fun addHistory(c: Context, url: String, title: String) {
        val list = readEntries(c, K_HISTORY)
        list.removeAll { it.url == url }
        list.add(0, Entry(url, title, System.currentTimeMillis()))
        while (list.size > HISTORY_LIMIT) list.removeAt(list.size - 1)
        writeEntries(c, K_HISTORY, list)
    }

    fun removeHistory(c: Context, url: String) {
        val list = readEntries(c, K_HISTORY)
        list.removeAll { it.url == url }
        writeEntries(c, K_HISTORY, list)
    }

    fun clearHistory(c: Context) { sp(c).edit().remove(K_HISTORY).apply() }

    // ---- বুকমার্ক ----
    fun bookmarks(c: Context): List<Entry> = readEntries(c, K_BOOKMARKS)

    fun isBookmarked(c: Context, url: String): Boolean = readEntries(c, K_BOOKMARKS).any { it.url == url }

    fun addBookmark(c: Context, url: String, title: String) {
        val list = readEntries(c, K_BOOKMARKS)
        list.removeAll { it.url == url }
        list.add(0, Entry(url, title, System.currentTimeMillis()))
        writeEntries(c, K_BOOKMARKS, list)
    }

    fun removeBookmark(c: Context, url: String) {
        val list = readEntries(c, K_BOOKMARKS)
        list.removeAll { it.url == url }
        writeEntries(c, K_BOOKMARKS, list)
    }

    // ---- ডাউনলোড ----
    fun downloads(c: Context): List<Dl> {
        val arr = try { JSONArray(sp(c).getString(K_DOWNLOADS, "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Dl>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Dl(o.optLong("id", -1L), o.optString("n"), o.optString("u"), o.optLong("ts"), o.optString("uri"), o.optString("m")))
        }
        return out
    }

    fun addDownload(c: Context, d: Dl) {
        val list = ArrayList(downloads(c))
        list.add(0, d)
        while (list.size > DOWNLOAD_LIMIT) list.removeAt(list.size - 1)
        val arr = JSONArray()
        for (x in list) arr.put(JSONObject().put("id", x.id).put("n", x.name).put("u", x.url).put("ts", x.time).put("uri", x.uri).put("m", x.mime))
        sp(c).edit().putString(K_DOWNLOADS, arr.toString()).apply()
    }

    fun clearDownloads(c: Context) { sp(c).edit().remove(K_DOWNLOADS).apply() }

    // ---- সার্চ ইঞ্জিন ----
    fun searchEngine(c: Context): String {
        val n = sp(c).getString(K_ENGINE, BrowserUrl.DEFAULT_ENGINE) ?: BrowserUrl.DEFAULT_ENGINE
        return if (BrowserUrl.ENGINES.containsKey(n)) n else BrowserUrl.DEFAULT_ENGINE
    }

    fun setSearchEngine(c: Context, name: String) { sp(c).edit().putString(K_ENGINE, name).apply() }
}
