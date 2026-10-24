package com.hemel.lenspilot.workflow

import com.hemel.lenspilot.userinfo.UserInfoEntry

/**
 * Tiny in-memory bridge so Lenspilot Keyboard (a plain IME, no
 * Accessibility involved — see keyboard/LenspilotInputMethodService)
 * can see "what is the user currently trying to get done" without
 * talking to LenspilotAccessibilityService or FallbackGuideService
 * directly. Whichever tier is actually running a workflow updates this
 * object; the keyboard only ever reads it, same-process so a plain
 * singleton is enough — no IPC needed (mirrors the Prefs.lastAiReply
 * pattern already used for MainActivity <-> keyboard sharing).
 *
 * [version] bumps on every goal start/stop AND every time the user
 * answers a clarifying question via the control bar's voice button or
 * its ✓/× shortcut — the keyboard uses that bump as the "context just
 * changed, try auto-typing again" signal while it's sitting in a
 * "waiting for you to answer" state (see
 * LenspilotInputMethodService.pollForClarificationAnswer).
 */
object WorkflowContext {
    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    var goal: String = ""
        private set

    @Volatile
    var version: Int = 0
        private set

    // ------------------------------------------------------------------
    // v37 — কিবোর্ড ↔ Guide v3 সেতু (একই প্রসেস, তাই সিঙ্গলটনই যথেষ্ট)।
    //  • গাইড প্রতি উত্তরে নিজের say/note এখানে রাখে → কিবোর্ড জানে ঠিক কী লিখতে হবে।
    //  • কিবোর্ড লেখা/ভাবার সময় [keyboardBusy] — গাইড তখন স্ক্রিন-বদলের অটো-কল থামিয়ে রাখে
    //    (নইলে কিবোর্ড খোলা/লেখার প্রতিটা বদলে AI-কে অকারণে ডাকত ও একই ঘর আবার দেখাত)।
    //  • কিবোর্ড লেখা শেষ করলে [notifyKeyboardTyped] — গাইড ইতিহাসে "কিবোর্ড লিখেছে" টুকে পরের ধাপ চায়।
    // ------------------------------------------------------------------
    interface KeyboardListener {
        fun onKeyboardTyped(text: String, field: String, submitted: Boolean)
    }

    @Volatile var guideSay: String = ""
        private set

    @Volatile var guideNote: String = ""
        private set

    /** ইউজারের সর্বশেষ মুখের/লেখা উত্তর (ট্রিগার/মাইক/মেসেজ) — কিবোর্ডের CLARIFY-এর উত্তরও এটাই। */
    @Volatile var lastUserAnswer: String = ""
        private set

    /** চলতি গাইড-সেশনের id (ContextWindowStore-এর ছোট ইতিহাস পড়ার জন্য)। */
    @Volatile var sessionId: String = ""
        private set

    @Volatile private var keyboardBusySince: Long = 0L

    @Volatile var keyboardListener: KeyboardListener? = null

    /** কিবোর্ড আটকে গেলে (ক্র্যাশ/ভুলে ফ্ল্যাগ থেকে গেলে) গাইড যেন চিরকাল না থামে — ২৫ সেকেন্ডের পর ধরা হয় না। */
    val keyboardBusy: Boolean
        get() {
            val since = keyboardBusySince
            return since != 0L && android.os.SystemClock.elapsedRealtime() - since < KEYBOARD_BUSY_MAX_MS
        }

    fun setKeyboardBusy(busy: Boolean) {
        keyboardBusySince = if (busy) android.os.SystemClock.elapsedRealtime() else 0L
    }

    fun setGuideSession(id: String) {
        sessionId = id
    }

    fun setGuideStep(say: String, note: String) {
        guideSay = say.take(300)
        guideNote = note.take(240)
    }

    fun setUserAnswer(text: String) {
        lastUserAnswer = text.take(300)
    }

    fun notifyKeyboardTyped(text: String, field: String, submitted: Boolean) {
        keyboardListener?.onKeyboardTyped(text, field, submitted)
    }

    private const val KEYBOARD_BUSY_MAX_MS = 25_000L

    /** Rows entered via the per-reply "এই কাজের জন্য শুধু" icon (see
     * item_ela_run_button.xml / MainActivity.showOneTimeUserInfoDialog) —
     * scoped to exactly the one run currently active, never written to
     * Prefs. Read by Prefs.userInfoJsonArrayOrNull() so every existing
     * "user_info" call site (analyze-screen, workflow/plan) picks it up
     * automatically without touching each one individually. Cleared the
     * moment this run [stop]s, so it can never bleed into a later,
     * unrelated run. */
    @Volatile
    var oneTimeUserInfo: List<UserInfoEntry> = emptyList()
        private set

    @Synchronized
    fun start(goal: String, oneTimeUserInfo: List<UserInfoEntry> = emptyList()) {
        active = true
        this.goal = goal
        this.oneTimeUserInfo = oneTimeUserInfo
        guideSay = ""
        guideNote = ""
        lastUserAnswer = ""
        keyboardBusySince = 0L
        version++
    }

    /** AI ব্রাউজার রানের জন্য শুধু তথ্য বসায়/মোছে (active/goal ছুঁয়ে না) — ব্রাউজারের অনুরোধ
     * Prefs.userInfoJsonArrayOrNull() দিয়ে এটাই পড়ে। AiBrowserActivity বন্ধ হলে [clearRunInfo]। */
    @Synchronized
    fun setRunInfo(rows: List<UserInfoEntry>) {
        oneTimeUserInfo = rows
    }

    @Synchronized
    fun clearRunInfo() {
        if (!active) oneTimeUserInfo = emptyList()
    }

    @Synchronized
    fun stop() {
        active = false
        goal = ""
        oneTimeUserInfo = emptyList()
        guideSay = ""
        guideNote = ""
        lastUserAnswer = ""
        sessionId = ""
        keyboardBusySince = 0L
        keyboardListener = null
        version++
    }

    /** Bumps [version] without changing [goal] — the goal text itself
     * doesn't change when the user just answers a follow-up question,
     * but anything waiting on "did the context change?" should re-check. */
    @Synchronized
    fun noteUserAnswered() {
        version++
    }
}
