package com.hemel.lenspilot.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.hemel.lenspilot.intent.IntentLauncher
import org.json.JSONObject

/**
 * OTP সহায়তার যোগসূত্র — ব্রাউজার-এজেন্ট (অ্যাক্টিভিটি) ↔ গাইডলাইন ওভারলে (Accessibility সার্ভিস)।
 *
 * প্রবাহ: AI "otp" বলে থামে → [OtpSupport.openSourceApp] দিয়ে ইমেইল/SMS/WhatsApp অ্যাপ intent-এ খোলে →
 * গাইডলাইন-সিস্টেম কোড কপির জায়গা হাইলাইট করে, কন্ট্রোল বারে বিশেষ "কপি হয়েছে" বাটন আসে →
 * বাটন চাপলে [OtpBridge.copiedFlag] সেট হয়ে ব্রাউজার অ্যাক্টিভিটি সামনে আসে (সেশন একই) →
 * এজেন্ট ক্লিপবোর্ড থেকে কোড নিয়ে OTP-বক্সে বসায় (AI কোড দেখে না) → কাজ চলে।
 */
object OtpBridge {
    /** OTP-কপি গাইড চলছে কি না (ওভারলে বারে "কপি হয়েছে" বাটন দেখানোর জন্য)। */
    @Volatile var assistActive = false

    /** কন্ট্রোল বারে "কপি হয়েছে" চাপা হয়েছে — অ্যাক্টিভিটি ফিরলে একবার খরচ হয়। */
    @Volatile var copiedFlag = false

    fun consumeCopied(): Boolean { val v = copiedFlag; copiedFlag = false; return v }
}

object OtpSupport {

    /** AI-র দেওয়া source: sms | email | whatsapp | telegram | messenger | other */
    fun openSourceApp(ctx: Context, source: String, appName: String): Boolean {
        val src = source.trim().lowercase()
        return try {
            when (src) {
                "sms", "message", "messages" -> openCategory(ctx, Intent.CATEGORY_APP_MESSAGING)
                    || openByName(ctx, appName.ifBlank { "Messages" })
                "email", "mail", "gmail" -> openByName(ctx, appName.ifBlank { "Gmail" })
                    || openCategory(ctx, Intent.CATEGORY_APP_EMAIL)
                "whatsapp" -> openByName(ctx, "WhatsApp")
                "telegram" -> openByName(ctx, "Telegram")
                "messenger" -> openByName(ctx, "Messenger")
                else -> if (appName.isNotBlank()) openByName(ctx, appName) else false
            }
        } catch (e: Exception) { false }
    }

    private fun openCategory(ctx: Context, category: String): Boolean = try {
        val i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category)
        if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i); true
    } catch (e: Exception) { false }

    private fun openByName(ctx: Context, name: String): Boolean {
        val r = IntentLauncher.execute(ctx, "open_app", JSONObject().put("app_name", name))
        return r is IntentLauncher.Result.Opened || r is IntentLauncher.Result.Fallback
    }

    fun sourceLabel(source: String, appName: String): String = when (source.trim().lowercase()) {
        "sms", "message", "messages" -> "Messages (SMS)"
        "email", "mail", "gmail" -> appName.ifBlank { "Email" }
        "whatsapp" -> "WhatsApp"
        "telegram" -> "Telegram"
        "messenger" -> "Messenger"
        else -> appName.ifBlank { "Messages app" }
    }

    /** গাইডলাইন-সিস্টেমকে দেওয়া লক্ষ্য — কোড মুখে/লিখে বলবে না, শুধু কপির জায়গা দেখাবে। */
    fun guideGoal(source: String, appName: String): String =
        "On this phone, show the way to find and open the OTP / verification code message that just arrived in ${sourceLabel(source, appName)}. " +
            "Then highlight where to copy the code (press and hold the code, or the copy button). " +
            "Never say or write the digits of the code yourself. Only show where to tap or copy. " +
            "Once it is copied, the user will tap the 'Copied' button on the control bar. You do nothing more."

    // ---------------------------------------------------------------- ক্লিপবোর্ড
    private fun cm(ctx: Context) = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun clipboardText(ctx: Context): String? = try {
        val clip = cm(ctx).primaryClip
        if (clip == null || clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(ctx)?.toString()
    } catch (e: Exception) { null }

    /** কপি-হওয়া লেখা থেকে OTP বের করা: "Your code is 482913." → 482913; "123 456" → 123456। */
    fun extractCode(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val t = text.trim().replace(Regex("(?<=\\d)[ \\-](?=\\d)"), "")
        // একাধিক সংখ্যা (বছর, দাম, ফোন) থাকলে কোড-সদৃশটা বাছি: ৬ অঙ্ক + "code/otp/verify" কাছে থাকা আগে; একা বছর-ধাঁচের ৪ অঙ্ক পিছে।
        val rx = Regex("(?<!\\d)(?<!\\d[.,])(\\d{4,8})(?!\\d|[.,]\\d|\\s?(?:BDT|USD|TK|taka|%))", RegexOption.IGNORE_CASE)
        val near = Regex("code|otp|verif|pin|কোড|ওটিপি", RegexOption.IGNORE_CASE)
        var best: String? = null; var bestScore = Int.MIN_VALUE; var bestPos = 0
        for (m in rx.findAll(t)) {
            val v = m.groupValues[1]; val pos = m.range.first
            var sc = 0
            if (v.length == 6) sc += 3 else sc += 1
            val from = maxOf(0, pos - 40); val to = minOf(t.length, pos + v.length + 40)
            if (near.containsMatchIn(t.substring(from, to))) sc += 4
            if (v.length == 4 && Regex("(19|20)\\d\\d").matches(v)) sc -= 3
            if (best == null || sc > bestScore || (sc == bestScore && pos < bestPos)) { best = v; bestScore = sc; bestPos = pos }
        }
        if (best != null) return best
        // অঙ্কহীন শব্দ ("Messages", "Copied") কখনো কোড নয়; অক্ষর+অঙ্ক মেশানো (যেমন G-123456 নয়, "A1B2C3") হলে চলে।
        if (t.length in 4..10 && t.all { it.isLetterOrDigit() } && t.any { it.isDigit() }) return t
        return null
    }

    /** শুরুতে ক্লিপবোর্ডের ছাপ — ফেরার পর বদলেছে কি না দেখতে (কন্ট্রোল-বাটন ছাড়া ফলব্যাক)। */
    fun clipFingerprint(ctx: Context): Int = (clipboardText(ctx) ?: "").hashCode()

    fun clearClipboard(ctx: Context) {
        try {
            val m = cm(ctx)
            if (android.os.Build.VERSION.SDK_INT >= 28) m.clearPrimaryClip()
            else m.setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (e: Exception) { }
    }
}
