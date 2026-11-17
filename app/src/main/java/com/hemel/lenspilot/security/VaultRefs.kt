package com.hemel.lenspilot.security

import android.content.Context
import org.json.JSONObject

/**
 * রেফারেন্স রিজলভার: `{{vault:Title.field}}` — শেষ মুহূর্তে, শুধু এই ডিভাইসে, মানে বদলে যায়।
 *
 * নিয়ম: রিজলভ-করা স্ট্রিং শুধু WebView-তে টাইপ বা শেল-আর্গুমেন্টে যায়। লগ, সেশন-ফাইল, ContextWindowStore বা
 * সার্ভারের রিকোয়েস্টে সবসময় মূল প্লেসহোল্ডারটাই থাকে (কলার মূল স্ট্রিংটাই লগ করে, রিজলভ-করাটা নয়)।
 * শিরোনামে '.' থাকলে শেষ '.'-টা শিরোনাম ও ফিল্ড আলাদা করে।
 */
object VaultRefs {
    private val REF = Regex("\\{\\{vault:([^}]+?)\\.([^.}]+)\\}\\}")

    class Resolved(val text: String?, val missing: String?) {
        val ok: Boolean get() = text != null
    }

    fun hasRef(s: String): Boolean = REF.containsMatchIn(s)

    /** সব রেফারেন্স মিললে text; না মিললে text = null ও missing = "Title/field"। */
    fun resolve(ctx: Context, s: String): Resolved {
        if (!hasRef(s)) return Resolved(s, null)
        var missing: String? = null
        val out = REF.replace(s) { m ->
            val title = m.groupValues[1].trim()
            val field = m.groupValues[2].trim()
            val v = SecretVault.get(ctx, title, field)
            if (v == null) {
                if (missing == null) missing = SecretVault.refText(title, field)
                ""
            } else v
        }
        return if (missing != null) Resolved(null, missing) else Resolved(out, null)
    }

    /** JSON-অবজেক্টের একটা স্ট্রিং-ফিল্ডে রিজলভ-করা কপি (মূলটা অপরিবর্তিত)। কিছু বদলাতে না হলে মূলটাই ফেরে। */
    fun resolveJsonField(ctx: Context, src: JSONObject, field: String): Pair<JSONObject, String?> {
        val raw = src.optString(field, "")
        if (raw.isEmpty() || !hasRef(raw)) return src to null
        val r = resolve(ctx, raw)
        if (!r.ok) return src to (r.missing ?: "?")
        val copy = JSONObject(src.toString())
        copy.put(field, r.text)
        return copy to null
    }
}
