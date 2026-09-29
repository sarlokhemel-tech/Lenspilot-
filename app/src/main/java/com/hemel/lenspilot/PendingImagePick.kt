package com.hemel.lenspilot

/**
 * In-process bridge used ONLY to hand a picked image (already downscaled +
 * base64-encoded JPEG) back from [ImagePickTrampolineActivity] to whichever
 * overlay service opened the gallery picker (LenspilotAccessibilityService,
 * FallbackGuideService, or nobody — a plain Activity like AiBrowserActivity
 * doesn't need this, it can register for the result itself).
 *
 * A Service can't call registerForActivityResult/startActivityForResult on
 * its own — only an Activity can — so the service stashes a one-shot
 * callback here, launches the trampoline with FLAG_ACTIVITY_NEW_TASK, and
 * the trampoline calls straight back into this same process instead of a
 * broadcast (everything here always runs in the app's single process).
 * [deliver] clears the callback before invoking it so a stray duplicate
 * result can never fire the same callback twice.
 */
object PendingImagePick {
    private var callback: ((String?) -> Unit)? = null

    fun awaitNext(cb: (String?) -> Unit) {
        callback = cb
    }

    fun deliver(base64: String?) {
        val cb = callback
        callback = null
        cb?.invoke(base64)
    }
}
