package com.hemel.lenspilot.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build

/**
 * "কপি করো ও ভুলে যাও": ক্লিপবোর্ড/নির্বাচিত লেখা থেকে মান নিয়ে সঙ্গে সঙ্গে এনক্রিপ্ট করে ভল্টে রাখে,
 * তারপর ক্লিপবোর্ড মুছে দেয়। মান কোনো রিটার্ন-ভ্যালুতে বেরোয় না — শুধু [Status]।
 *
 * Android 10+ এ ক্লিপবোর্ড পড়া যায় কেবল যখন অ্যাপ ফোকাসে থাকে।
 */
object SecretCapture {
    enum class Status { OK, EMPTY, SAVE_FAILED }

    private fun cm(ctx: Context): ClipboardManager? =
        ctx.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private fun readClipboard(ctx: Context): String? = try {
        val clip = cm(ctx)?.primaryClip
        if (clip == null || clip.itemCount == 0) null
        else clip.getItemAt(0).coerceToText(ctx)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) { null }

    /** API 28+: clearPrimaryClip; পুরনো ফোনে খালি ক্লিপ বসানো। */
    fun clearClipboard(ctx: Context) {
        try {
            val m = cm(ctx) ?: return
            if (Build.VERSION.SDK_INT >= 28) m.clearPrimaryClip()
            else m.setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (e: Exception) { }
    }

    /** ভল্ট-ডায়ালগের [পেস্ট] বাটন: ক্লিপবোর্ড → ভল্ট → ক্লিপবোর্ড মোছা। */
    fun pasteAndForget(ctx: Context, title: String, label: String): Status =
        captureFrom(ctx, title, label, null, useClipboard = true)

    /**
     * capture_secret অনুমোদনের পর অ্যাপ নিজে পড়ে। আগে ক্লিপবোর্ড, না পেলে [selectedText] (ওয়েবপেজে নির্বাচিত লেখা)।
     * ক্লিপবোর্ড থেকে নিলে তবেই ক্লিপবোর্ড মোছা হয়।
     */
    fun captureAfterApproval(ctx: Context, title: String, label: String, selectedText: String?): Status =
        captureFrom(ctx, title, label, selectedText, useClipboard = true)

    private fun captureFrom(ctx: Context, title: String, label: String, selected: String?, useClipboard: Boolean): Status {
        var value: String? = null
        var fromClip = false
        if (useClipboard) {
            value = readClipboard(ctx)
            fromClip = value != null
        }
        if (value == null) value = selected?.trim()?.takeIf { it.isNotEmpty() }
        if (value == null) return Status.EMPTY
        val ok = SecretVault.put(ctx, title, label, value)
        value = null
        if (!ok) return Status.SAVE_FAILED
        if (fromClip) clearClipboard(ctx)
        return Status.OK
    }
}
