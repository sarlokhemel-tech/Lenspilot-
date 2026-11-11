package com.hemel.lenspilot.ads

/** v108: বড় সংখ্যা ছোট করে দেখায় — 1.2K, 3.5M, 2B, 1.1T। ১০০০-এর নিচে যেমন আছে তেমন। শুধু দেখানোর জন্য। */
object CompactNumber {
    fun format(value: Long): String {
        val neg = value < 0
        val n = Math.abs(value.toDouble())
        val sign = if (neg) "-" else ""
        if (n < 1000) return sign + n.toLong().toString()
        val steps = listOf(1e12 to "T", 1e9 to "B", 1e6 to "M", 1e3 to "K")
        val next = mapOf("K" to "1M", "M" to "1B", "B" to "1T")
        for ((div, suf) in steps) {
            if (n >= div) {
                val v = n / div
                if (Math.round(v * 10) / 10.0 >= 1000 && next.containsKey(suf)) return sign + next[suf]   // 999.95K → 1M
                val txt = String.format(java.util.Locale.US, "%.1f", v).removeSuffix(".0")
                return sign + txt + suf
            }
        }
        return sign + n.toLong().toString()
    }
    fun format(value: Int): String = format(value.toLong())
}
