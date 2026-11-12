package com.hemel.lenspilot.tools

import android.content.Context
import com.hemel.lenspilot.security.Redactor
import org.json.JSONObject
import java.io.File

/**
 * steps.jsonl লেখার একমাত্র পথ (প্রতি লাইন একটা JSON)। প্রতিটি লাইন ডিস্কে যাওয়ার আগে [Redactor]-এর ভেতর দিয়ে যায়।
 * প্লেসহোল্ডার ({{vault:…}}) যেমন আছে তেমনই লেখা হয় — রিজলভ-করা মান এখানে কখনো আসে না।
 */
object StepLog {
    private val lock = Any()
    private const val MAX_BYTES = 2_000_000L

    fun append(ctx: Context, sessionId: String, entry: JSONObject) {
        if (!ToolsPaths.isValidId(sessionId)) return
        try {
            if (!entry.has("ts")) entry.put("ts", System.currentTimeMillis())
            val line = Redactor.redact(ctx, entry.toString()).replace('\n', ' ')
            val f: File = ToolsPaths.stepsLog(ctx, sessionId)
            synchronized(lock) {
                f.parentFile?.mkdirs()
                if (f.exists() && f.length() > MAX_BYTES) {
                    // পুরনো অর্ধেক ফেলে দিয়ে ছোট রাখা
                    val lines = f.readLines()
                    f.writeText(lines.drop(lines.size / 2).joinToString("\n") + "\n")
                }
                f.appendText(line + "\n")
            }
        } catch (e: Exception) { }
    }
}
