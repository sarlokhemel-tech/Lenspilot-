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
        version++
    }

    @Synchronized
    fun stop() {
        active = false
        goal = ""
        oneTimeUserInfo = emptyList()
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
