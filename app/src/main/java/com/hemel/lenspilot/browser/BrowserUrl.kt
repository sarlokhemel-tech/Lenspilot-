package com.hemel.lenspilot.browser

import android.net.Uri
import android.util.Patterns

/**
 * v43: অ্যাড্রেস-বারে যা লেখা হয় তাকে লোডযোগ্য URL-এ বদলানো (ঠিক সাধারণ ব্রাউজারের মতো)।
 * এটা কাঠামোগত পার্সিং (স্কিম আছে কি, ডোমেইনের গঠন আছে কি) — শব্দ-তালিকা বা অর্থ-অনুমান নয়;
 * কী খুঁজছে তা বোঝা AI-এর কাজ, যেটা ইউজার আলাদা AI সুইচ/কমান্ড বারে দেয়।
 */
object BrowserUrl {

    const val DEFAULT_ENGINE = "Google"

    val ENGINES: LinkedHashMap<String, String> = linkedMapOf(
        "Google" to "https://www.google.com/search?q=%s",
        "DuckDuckGo" to "https://duckduckgo.com/?q=%s",
        "Bing" to "https://www.bing.com/search?q=%s",
        "Brave" to "https://search.brave.com/search?q=%s"
    )

    fun searchUrl(engine: String, query: String): String {
        val tpl = ENGINES[engine] ?: ENGINES.getValue(DEFAULT_ENGINE)
        return tpl.replace("%s", Uri.encode(query))
    }

    /** খালি হলে "" ফেরত — কিছু লোড হবে না। */
    fun resolve(input: String, engine: String): String {
        val t = input.trim()
        if (t.isEmpty()) return ""
        val lower = t.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return t
        if (lower == "about:blank") return t
        // ফাঁকা থাকলে অবশ্যই সার্চ
        if (t.any { it.isWhitespace() }) return searchUrl(engine, t)
        // localhost / IP / ডোমেইন-গঠন
        val hostPart = t.substringBefore('/').substringBefore('?').substringBefore('#')
        val isLocal = hostPart.startsWith("localhost") || Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?$").matches(hostPart)
        if (isLocal) return "http://$t"
        if (Patterns.WEB_URL.matcher(t).matches()) return "https://$t"
        return searchUrl(engine, t)
    }
}
