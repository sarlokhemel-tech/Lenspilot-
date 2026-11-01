package com.hemel.lenspilot.audio

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import java.util.Locale

/**
 * v62 — ভয়েস কমান্ডের ভাষা (SpeechRecognizer)।
 *
 * ডিফল্ট এখন AUTO: ইউজার এই চ্যাটে যে ভাষায় (লিখে বা বলে) কথা বলছে, recognizer সেই ভাষায় শোনে।
 *   • চ্যাটে বাংলা অক্ষর → bn-BD, দেবনাগরী → hi-IN, আরবি → ar-SA, ইংরেজি/লাতিন → en-US
 *   • কিছুই না থাকলে (নতুন চ্যাট) আগের জানা ভাষা, তাও না থাকলে বাংলা
 *   • Android 13+ হলে recognizer-কে চারটা ভাষার মধ্যে নিজে চিনে নিতে/মাঝপথে বদলাতে বলা হয়
 *     (সব ডিভাইসে কাজ নাও করতে পারে — তখন চ্যাটের ভাষাই ভরসা)
 * মাইক/ভয়েস বাটনে লং-প্রেস: Auto → বাংলা → English → हिन्दी → العربية → Auto। হাতে বাছলে সেটাই স্থির থাকে।
 */
object VoiceLang {
    private const val PREFS = "lenspilot_voice_lang"
    private const val KEY = "tag"
    private const val KEY_LAST = "last_chat_tag"
    const val AUTO = "auto"

    /** tag → ইউজারকে দেখানোর নাম। ক্রম = লং-প্রেসে ঘোরার ক্রম (শুরুতে Auto)। */
    val OPTIONS: List<Pair<String, String>> = listOf(
        AUTO to "Auto",
        "bn-BD" to "বাংলা",
        "en-US" to "English",
        "hi-IN" to "हिन्दी",
        "ar-SA" to "العربية",
    )
    private val ALL_TAGS = listOf("bn-BD", "en-US", "hi-IN", "ar-SA")

    @Volatile private var appCtx: Context? = null

    fun init(context: Context) { appCtx = context.applicationContext }

    private fun prefs() = appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** ইউজারের বাছাই: AUTO বা নির্দিষ্ট ভাষা। */
    fun mode(): String = prefs()?.getString(KEY, null)?.takeIf { t -> OPTIONS.any { it.first == t } } ?: AUTO

    /** চ্যাটে সর্বশেষ যে ভাষা দেখা গেছে; না থাকলে বাংলা। */
    private fun lastChatTag(): String = prefs()?.getString(KEY_LAST, null)?.takeIf { it in ALL_TAGS } ?: "bn-BD"

    /** এখন recognizer-কে যে ভাষা দেওয়া হবে। */
    fun tag(): String = mode().let { if (it == AUTO) lastChatTag() else it }

    fun label(tag: String = mode()): String =
        if (tag == AUTO) "Auto (" + (OPTIONS.firstOrNull { it.first == lastChatTag() }?.second ?: "") + ")"
        else OPTIONS.firstOrNull { it.first == tag }?.second ?: tag

    fun set(tag: String) { prefs()?.edit()?.putString(KEY, tag)?.apply() }

    /** পরের অপশনে যায় এবং সেটার মান ফেরত দেয়। */
    fun cycle(): String {
        val cur = mode()
        val i = OPTIONS.indexOfFirst { it.first == cur }
        val next = OPTIONS[(i + 1) % OPTIONS.size].first
        set(next)
        return next
    }

    /**
     * চ্যাটে ইউজারের লেখা/বলা কথা থেকে ভাষা মনে রাখে (অক্ষরের লিপি দেখে)। AUTO মোডে পরের ভয়েসে এটাই ধরা হয়।
     * লিপি স্পষ্ট না হলে (সংখ্যা/চিহ্ন) আগেরটাই থাকে।
     */
    fun noteText(text: String?) {
        val t = text ?: return
        var bn = 0; var hi = 0; var ar = 0; var la = 0
        for (c in t) {
            when {
                c in '\u0980'..'\u09FF' -> bn++
                c in '\u0900'..'\u097F' -> hi++
                c in '\u0600'..'\u06FF' -> ar++
                c in 'A'..'Z' || c in 'a'..'z' -> la++
            }
        }
        val best = maxOf(maxOf(bn, hi), maxOf(ar, la))
        if (best < 2) return
        val tag = when (best) {
            bn -> "bn-BD"
            hi -> "hi-IN"
            ar -> "ar-SA"
            else -> "en-US"
        }
        prefs()?.edit()?.putString(KEY_LAST, tag)?.apply()
    }

    /** RecognizerIntent-এ ভাষা + (সমর্থিত হলে) ভাষা-শনাক্তকরণ বসায়। Intent.apply { } ভেতর থেকে ডাকুন। */
    fun applyTo(intent: Intent) {
        val t = tag()
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, t)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, t)
        // পছন্দের ভাষায় আটকে না রেখে অন্য ভাষার ফলাফলও আসতে দেওয়া
        intent.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
        if (mode() == AUTO) {
            // Android 13+ (string key — তাই পুরনো compileSdk-এও কম্পাইল হয়): চার ভাষার মধ্যে নিজে চেনা
            intent.putExtra("android.speech.extra.ENABLE_LANGUAGE_DETECTION", true)
            intent.putStringArrayListExtra("android.speech.extra.LANGUAGE_DETECTION_ALLOWED_LANGUAGES", ArrayList(ALL_TAGS))
            // Android 14+: কথার মাঝখানে ভাষা বদলালেও ধরা
            intent.putExtra("android.speech.extra.ENABLE_LANGUAGE_SWITCH", "quick_response")
            intent.putStringArrayListExtra("android.speech.extra.LANGUAGE_SWITCH_ALLOWED_LANGUAGES", ArrayList(ALL_TAGS))
        }
    }

    @Suppress("unused")
    fun deviceTag(): String = Locale.getDefault().toLanguageTag()
}
