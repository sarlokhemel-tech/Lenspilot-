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
        "sms", "message", "messages" -> "মেসেজ (SMS)"
        "email", "mail", "gmail" -> appName.ifBlank { "ইমেইল" }
        "whatsapp" -> "WhatsApp"
        "telegram" -> "Telegram"
        "messenger" -> "Messenger"
        else -> appName.ifBlank { "মেসেজ অ্যাপ" }
    }

    /** গাইডলাইন-সিস্টেমকে দেওয়া লক্ষ্য — কোড মুখে/লিখে বলবে না, শুধু কপির জায়গা দেখাবে। */
    fun guideGoal(source: String, appName: String): String =
        "এই ফোনে ${sourceLabel(source, appName)}-এ এইমাত্র আসা OTP/ভেরিফিকেশন কোডের মেসেজটা খুঁজে খোলার পথ দেখাও, " +
            "তারপর কোডটা কপি করার জায়গা (কোড চেপে ধরা বা কপি বাটন) হাইলাইট করে দেখাও। " +
            "কোডের অঙ্ক কখনো নিজে উচ্চারণ বা লিখে বোলো না — শুধু কোথায় ট্যাপ/কপি করতে হবে দেখাও। " +
            "কপি হয়ে গেলে ইউজার কন্ট্রোল বারের 'কপি হয়েছে' বাটন চাপবে; তুমি আর কিছু করবে না।"

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
        Regex("(?<!\\d)(\\d{4,8})(?!\\d)").find(t)?.let { return it.groupValues[1] }
        if (t.length in 4..10 && t.all { it.isLetterOrDigit() }) return t
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
