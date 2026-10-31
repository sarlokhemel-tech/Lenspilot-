package com.hemel.lenspilot.tools.browser

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import com.hemel.lenspilot.tools.perm.ToolsSecurityPrefs

/**
 * গুগল সাইন-ইনে সহায়তা (শুধু এটুকুই):
 *  ১. গুগলের অথ-পেজ এলে প্রথমে WebView-মার্কারহীন সাধারণ মোবাইল-ব্রাউজার UA দিয়ে একবার আবার লোড করা।
 *  ২. গুগল তবুও আটকালে (কাঠামোগত সংকেত: HTTP 403 / rejected-পাথ / disallowed_useragent) থেমে needs_you।
 *  ৩. অ্যাকাউন্ট-চুজারে ক্লিক স্বয়ংক্রিয় হতে পারে; পাসওয়ার্ড/২FA/ডিভাইস-যাচাই/ক্যাপচা — কখনো না, ইউজারের হাতে।
 *
 * কোনো শব্দ-তালিকা নেই: পেজের লেখা পড়ে "ব্লক হয়েছে কিনা" অনুমান করা হয় না। অস্পষ্ট পেজে সিদ্ধান্ত
 * নেয় AI (সে observe দেখে blocked/needs_you জানায়); এখানে শুধু URL-হোস্ট ও HTTP-স্ট্যাটাসের মতো কাঠামোগত সংকেত।
 */
class GoogleLoginAssist(private val ctx: Context) {

    companion object {
        private const val AUTH_HOST = "accounts.google.com"

        /** গুগল অথ/সাইন-ইন হোস্ট কিনা (URL-এর হোস্ট দেখে — কাঠামোগত)। */
        fun isAuthUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val host = try { android.net.Uri.parse(url).host?.lowercase() } catch (e: Exception) { null }
            return host == AUTH_HOST
        }

        /** "; wv" ও "Version/x.y" বাদ — সাধারণ মোবাইল Chrome-এর মতো UA। অন্য কিছু বদলানো/নকল করা হয় না। */
        fun plainMobileUa(defaultUa: String): String =
            defaultUa.replace("; wv", "").replace(Regex("\\sVersion/\\d+(\\.\\d+)*"), "")

        /** গুগলের যাচাই-ধাপ (পাসওয়ার্ড/২FA/ডিভাইস/ক্যাপচা): URL-পাথের "challenge" অংশ — কাঠামোগত, ইউজারের হাতে। */
        fun isChallengeUrl(url: String?): Boolean {
            val s = url ?: return false
            if (!isAuthUrl(s)) return false
            return android.net.Uri.parse(s).path.orEmpty().contains("/challenge")
        }

        /** গুগল স্বয়ং আটকানোর কাঠামোগত চিহ্ন: rejected-এন্ডপয়েন্ট বা disallowed_useragent প্যারামিটার। */
        fun isRejectUrl(url: String?): Boolean {
            val s = url ?: return false
            if (!isAuthUrl(s)) return false
            val u = android.net.Uri.parse(s)
            val path = u.path.orEmpty()
            return path.endsWith("/rejected") || path.contains("/signin/rejected") ||
                u.getQueryParameter("error") == "disallowed_useragent"
        }
    }

    /** (companion-এর বাইরে ইনস্ট্যান্স থেকেও ডাকা যায়) */
    fun isChallenge(url: String?): Boolean = isChallengeUrl(url)

    /** এই WebView-তে UA বদলানো আছে কিনা (গুগল-হোস্ট ছাড়লে ডিফল্টে ফেরানো হয়)। */
    private var uaSwitched = false
    private var reloadedOnce = false
    private var raised = false          // একই গুগল-পর্বে একবারই needs_you (বারবার নয়)

    private fun raise(msg: String, cb: (String) -> Unit) {
        if (raised) return
        raised = true
        cb(msg)
    }

    /** সম্মতি-সুইচ (নিরাপত্তা স্ক্রিন, ডিফল্ট বন্ধ): বন্ধ থাকলে গুগল-পেজে কোনো স্বয়ংক্রিয়তা নেই। */
    fun consentGiven(): Boolean = ToolsSecurityPrefs.signedInSessionConsent(ctx)

    /**
     * মূল-ফ্রেমের নেভিগেশনের আগে ডাকা হয়। true = আমরা URL নিজে আবার লোড করেছি (ডাকার জায়গা true ফেরাবে)।
     * [onNeedsYou] এ সরল ব্যাখ্যা যায় (বাংলায়)।
     */
    fun onMainFrameNavigate(view: WebView, url: String, isGet: Boolean, onNeedsYou: (String) -> Unit): Boolean {
        val google = isAuthUrl(url)
        if (!google) {
            if (uaSwitched) {                      // গুগল ছেড়ে এসেছি — ডিফল্ট UA ফেরত (পরের রিকোয়েস্ট থেকে)
                view.settings.userAgentString = WebSettings.getDefaultUserAgent(ctx)
                uaSwitched = false
                reloadedOnce = false
            }
            raised = false
            return false
        }
        if (isRejectUrl(url)) {
            raise(blockedMessage(), onNeedsYou)
            return false
        }
        if (!consentGiven()) {
            raise(noConsentMessage(), onNeedsYou)
            return false
        }
        if (!uaSwitched && isGet) {
            val def = WebSettings.getDefaultUserAgent(ctx)
            view.settings.userAgentString = plainMobileUa(def)
            uaSwitched = true
            if (!reloadedOnce) {                  // একবারই, নতুন UA দিয়ে একই URL
                reloadedOnce = true
                view.loadUrl(url)
                return true
            }
        }
        return false
    }

    /** মূল-ফ্রেমে HTTP ত্রুটি: গুগল-হোস্টে ৪০৩ = আটকানো। */
    fun onMainFrameHttpError(url: String?, status: Int, onNeedsYou: (String) -> Unit) {
        if (isAuthUrl(url) && status == 403) raise(blockedMessage(), onNeedsYou)
    }

    /** পেজ লোড শেষে URL আটকানোর পাতায় গেলে। */
    fun onPageFinished(url: String?, onNeedsYou: (String) -> Unit) {
        if (isRejectUrl(url)) raise(blockedMessage(), onNeedsYou)
    }

    fun blockedMessage(): String =
        "গুগল অ্যাপের ভেতরের ব্রাউজারে সাইন-ইন আটকে দিয়েছে। আমি এটা এড়ানোর চেষ্টা করব না। " +
            "সাইটে ইমেইল/পাসওয়ার্ড বা API কী-র মতো অন্য উপায় থাকলে সেটা বেছে নিন, অথবা এই ধাপটি আপনি নিজে করুন।"

    fun noConsentMessage(): String =
        "গুগল সাইন-ইনে সহায়তা বন্ধ আছে (সাইন-ইন-করা ব্রাউজার সেশন ব্যবহারের সম্মতি দেওয়া নেই)। " +
            "Tools → তালা আইকন → নিরাপত্তা স্ক্রিনে সম্মতি চালু করুন, অথবা সাইন-ইনটা নিজে করুন।"
}
