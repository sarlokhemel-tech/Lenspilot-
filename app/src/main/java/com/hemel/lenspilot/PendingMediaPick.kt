package com.hemel.lenspilot

/**
 * v81: বাবল-চ্যাটের "+" (ছবি / ভিডিও / ফাইল) — [MediaPickTrampolineActivity] বাছাই শেষে ফল এখানে ফেরত দেয়
 * ([PendingImagePick]-এর মতো, কিন্তু ছবির সাথে লেখা-প্রসঙ্গও বয়ে আনে)।
 *
 * [imageBase64] = AI-কে যাওয়া ছবি (ছবি / ভিডিওর কনট্যাক্ট-শিট / PDF-এর পাতা); ফাইল-টেক্সটে null।
 * [context] = AI-কে বলার নোট (ভিডিও/ফাইলে; ছবিতে null)। [label] = প্রিভিউ-সারির লেখা।
 */
object PendingMediaPick {
    class Picked(val imageBase64: String?, val context: String?, val label: String)

    private var callback: ((Picked?) -> Unit)? = null

    fun awaitNext(cb: (Picked?) -> Unit) { callback = cb }

    fun deliver(p: Picked?) {
        val cb = callback
        callback = null
        cb?.invoke(p)
    }
}
