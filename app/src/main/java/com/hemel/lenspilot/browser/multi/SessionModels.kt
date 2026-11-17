package com.hemel.lenspilot.browser.multi

import android.app.Activity
import android.net.Uri
import android.webkit.ValueCallback

/** v162 (ELA 4N multi-session): the life-cycle of one session — decided by code only, never by an LLM. */
enum class SessionState { IDLE, RUNNING, PAUSED, NEEDS_YOU, DONE, STOPPED, FAILED }

/** A file the user attached to a session (any type). [text] = readable preview for text-like files (null for binary). */
data class AttachedDoc(val name: String, val uri: Uri, val mime: String, val text: String?)

/** What one session should do — from the planner (Gemini) or typed by the user in the manual setup. */
data class SessionSpec(
    val title: String,
    val task: String,
    val startUrl: String? = null,
    val docs: List<AttachedDoc> = emptyList(),
    val vaultTitles: List<String> = emptyList(),
)

/** One row of the status card — built from session state only (no LLM). */
data class SessionLine(
    val index: Int,
    val title: String,
    val state: SessionState,
    val host: String,
    val step: Int,
    val status: String,
)

/** What a session needs from the screen that hosts it. */
interface SessionEnv {
    val activity: Activity
    /** A page asked for a file and the session has nothing attached: let the person pick one. */
    fun pickFileFor(session: BrowserSession, callback: ValueCallback<Array<Uri>>)
    /** Any visible state change (status text, state, URL, step) — the UI re-reads the sessions. */
    fun onSessionChanged(session: BrowserSession)
}
