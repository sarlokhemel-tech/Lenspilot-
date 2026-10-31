package com.hemel.lenspilot.tools.perm

/**
 * ঝুঁকি-শ্রেণিবিন্যাস — AI নয়, একটা ঘোষণামূলক নিয়ম-তালিকা ([RULES])। নতুন নিয়ম = তালিকায় এক লাইন।
 * উপরের নিয়ম আগে; একাধিক মিললে সবচেয়ে বেশি ঝুঁকিটাই জেতে। কিছু না মিললে LOW (অনুমোদন লাগে না)।
 *
 * HIGH: কখনো মনে রাখা হয় না, প্রতিবার অনুমোদন। MEDIUM: ইউজার চাইলে চলতি সেশনের জন্য অনুমোদিত থাকতে পারে।
 */
enum class Risk { LOW, MEDIUM, HIGH }

/** কী চালানো হচ্ছে। workspace: shell | browser | storage | ...; action: ধাপের অ্যাকশনের নাম; text: কমান্ড/লেবেল/ইউআরএল। */
data class RiskInput(val workspace: String, val action: String, val text: String)

class Verdict(val risk: Risk, val ruleId: String, val reason: String)

class RiskRule(
    val id: String,
    val risk: Risk,
    /** null = যেকোনো workspace। */
    val workspace: String?,
    /** null = যেকোনো অ্যাকশন। */
    val action: Regex?,
    /** null = টেক্সট দেখা হয় না। */
    val text: Regex?,
    /** জটিল শর্ত (যেমন "scratch-এর বাইরে মোছা") — null হলে শুধু রেজেক্স। */
    val guard: ((RiskInput) -> Boolean)? = null,
    val reason: String
)

object RiskClassifier {
    private val I = RegexOption.IGNORE_CASE

    /** scratch এলাকা = ওয়ার্কস্পেসের `scratch/` ফোল্ডার; এর ভেতরের মোছা কম ঝুঁকির। */
    const val SCRATCH_DIR = "scratch"

    /** কমান্ডের আর্গুমেন্টগুলোর সবই scratch/-এর ভেতরে? (flag বাদ)। কোনো টার্গেট না থাকলে false = বাইরে ধরা হয়। */
    internal fun deletesOutsideScratch(input: RiskInput): Boolean {
        val words = input.text.trim().split(Regex("\\s+")).drop(2)   // "git rm ..." → আর্গুমেন্ট
        if (words.any { it == "-n" || it == "--dry-run" }) return false   // শুধু দেখা, মোছা নয়
        val targets = words.filter { !it.startsWith("-") }
        if (targets.isEmpty()) return true
        return !targets.all { it.removePrefix("./").startsWith("$SCRATCH_DIR/") }
    }

    val RULES: List<RiskRule> = listOf(
        // ---- শেল / git ----
        RiskRule("git-push", Risk.HIGH, "shell", null, Regex("""(^|\s)git\s+push\b""", I),
            reason = "রিমোটে কোড পাঠানো (push)"),
        RiskRule("git-force", Risk.HIGH, "shell", null,
            Regex("""(^|\s)git\s+\S.*(\s--force(-with-lease)?\b|\s-[a-zA-Z]*f[a-zA-Z]*(\s|$)|\s\+\S)""", I),
            guard = { it.text.contains("push") },
            reason = "ফোর্স-পুশ: রিমোটের ইতিহাস বদলে যেতে পারে"),
        RiskRule("git-delete", Risk.HIGH, "shell", null, Regex("""(^|\s)git\s+(rm|clean)\b""", I),
            guard = { deletesOutsideScratch(it) },
            reason = "scratch এলাকার বাইরে ফাইল মোছা"),
        RiskRule("git-discard", Risk.MEDIUM, "shell", null,
            Regex("""(^|\s)git\s+(reset\s+--hard|branch\s+(-D|--delete\s+--force)|stash\s+(drop|clear))""", I),
            reason = "স্থানীয় কাজ ফেলে দেওয়া হতে পারে"),

        // ---- স্টোরেজ ----
        RiskRule("storage-delete", Risk.HIGH, "storage", Regex("""^(delete|remove|rm)$""", I), null,
            guard = { deletesOutsideScratch(RiskInput(it.workspace, it.action, "x x " + it.text)) },
            reason = "scratch এলাকার বাইরে ফাইল মোছা"),
        RiskRule("storage-share", Risk.HIGH, "storage", Regex("""^(share|publish)$""", I), null,
            reason = "ফাইল অন্যের সাথে শেয়ার"),

        // ---- যেকোনো workspace: গোপন তথ্য ----
        RiskRule("capture-secret", Risk.HIGH, null, Regex("""^capture_secret$""", I), null,
            reason = "ক্লিপবোর্ড/নির্বাচিত লেখা থেকে গোপন তথ্য ভল্টে নেওয়া"),

        // ---- ব্রাউজার: লেবেল/ইউআরএল/টেক্সট দেখে ----
        RiskRule("browser-payment", Risk.HIGH, "browser", null,
            Regex("""checkout|payment|\bpay\b|pay now|place (your )?order|buy now|purchase|billing|credit card|debit card|\bcvv\b|subscribe|চেকআউট|পেমেন্ট|কিনুন|অর্ডার করুন|টাকা পরিশোধ""", I),
            reason = "পেমেন্ট/চেকআউট মনে হচ্ছে"),
        RiskRule("browser-publish", Risk.HIGH, "browser", null,
            Regex("""publish|make (it )?public|set .{0,20}public|go live|share (publicly|link)|visibility|প্রকাশ|পাবলিক করুন|শেয়ার""", I),
            reason = "প্রকাশ বা শেয়ার"),
        RiskRule("browser-account-security", Risk.HIGH, "browser", null,
            Regex("""change password|reset password|two.?factor|\b2fa\b|security settings|delete (my )?account|deactivate|close account|recovery (email|phone)|revoke|connected apps|পাসওয়ার্ড (বদল|পরিবর্তন)|নিরাপত্তা সেটিং|অ্যাকাউন্ট (মুছ|বন্ধ)""", I),
            reason = "অ্যাকাউন্ট বা নিরাপত্তা সেটিং")
    )

    fun classify(input: RiskInput): Verdict {
        var best: Verdict? = null
        for (r in RULES) {
            if (r.workspace != null && !r.workspace.equals(input.workspace, ignoreCase = true)) continue
            if (r.action != null && !r.action.containsMatchIn(input.action)) continue
            if (r.text != null && !r.text.containsMatchIn(input.text)) continue
            if (r.guard != null && !r.guard.invoke(input)) continue
            if (best == null || r.risk.ordinal >= best.risk.ordinal) best = Verdict(r.risk, r.id, r.reason)
        }
        return best ?: Verdict(Risk.LOW, "none", "")
    }
}
