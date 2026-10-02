package com.hemel.lenspilot.security

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Shared "should AI touch this?" check — used by Lenspilot Keyboard's
 * auto-type button so it never fires inside banking/finance apps or on a
 * password field, regardless of whether Accessibility is on. This is a
 * package-name heuristic, not a real security boundary by itself — it's a
 * courtesy guard on top of the real one, which is that this whole feature
 * only ever does what a normal keyboard app can do (commitText into the
 * field the OS says is focused), nothing more.
 */
object SensitiveFieldGuard {

    // Common Bangladeshi + international banking/fintech/wallet package
    // names. Extend this list over time rather than trying to be exhaustive
    // up front — a false negative here just means the AI button is shown
    // somewhere it maybe shouldn't be, not that anything unsafe happens.
    private val BLOCKED_PACKAGES = setOf(
        "com.bKash.customerapp",
        "com.konasl.nagad",
        "com.rocket.dbbl",
        "com.upay.wallet",
        "com.mtb.enrichit",
        "com.brac.mycash",
        "com.citytouch.retail",
        "com.dbbl.nexuspay",
        "com.ibblimited.mcash",
        "com.google.android.apps.walletnfcrel",
        "com.paypal.android.p2pmobile",
        "com.phonepe.app",
        "net.one97.paytm",
        "com.google.android.apps.nbu.paisa.user",
        "com.axis.mobile",
        "com.sbi.SBIFreedomPlus",
    )

    private val BLOCKED_PACKAGE_KEYWORDS = listOf(
        "bank", "bkash", "nagad", "rocket", "wallet", "paypal", "payment",
        "banking", "fincor", "upay", "nexuspay",
    )

    fun isSensitivePackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (packageName in BLOCKED_PACKAGES) return true
        val lower = packageName.lowercase()
        return BLOCKED_PACKAGE_KEYWORDS.any { lower.contains(it) }
    }

    /** True for password/PIN/OTP-style fields, based on the field's own
     * declared inputType — this is the same signal Gboard itself uses to
     * decide whether to disable personalized suggestions. */
    fun isSensitiveField(editorInfo: EditorInfo?): Boolean {
        val inputType = editorInfo?.inputType ?: return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val klass = inputType and InputType.TYPE_MASK_CLASS
        if (klass == InputType.TYPE_CLASS_NUMBER &&
            (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        ) return true
        if (klass == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                )
        ) return true
        return false
    }
}
