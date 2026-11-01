package com.hemel.lenspilot.tools.perm

import android.content.Context

/** Tools সিকিউরিটির ছোট সেটিং। সাইন-ইন-করা ব্রাউজার সেশন ব্যবহারের সম্মতি (ধাপ ৫-এ পড়া হবে)। ডিফল্ট বন্ধ। */
object ToolsSecurityPrefs {
    private const val PREFS = "lenspilot_tools_security"
    private const val K_SESSION_CONSENT = "signed_in_session_consent"

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun signedInSessionConsent(c: Context): Boolean = p(c).getBoolean(K_SESSION_CONSENT, false)

    fun setSignedInSessionConsent(c: Context, on: Boolean) {
        p(c).edit().putBoolean(K_SESSION_CONSENT, on).apply()
    }
}
