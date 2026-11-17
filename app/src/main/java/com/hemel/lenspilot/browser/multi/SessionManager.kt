package com.hemel.lenspilot.browser.multi

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope

/**
 * v162: holds the running sessions of one multi-session screen. Everything here is plain code —
 * counts, states and the status lines for the master card are computed from the sessions, never asked from an LLM.
 */
class SessionManager(
    private val env: SessionEnv,
    private val scope: CoroutineScope,
) {
    companion object {
        /** More than this and the tiles get too small to be useful (the planner is capped by the server to the same range). */
        const val MAX_SESSIONS = 8
        private const val START_STAGGER_MS = 350L   // start the sessions one after another so the server is not hit by all first calls in the same instant
    }

    private val handler = Handler(Looper.getMainLooper())
    val sessions = ArrayList<BrowserSession>()
    private var nextId = 1

    fun add(spec: SessionSpec): BrowserSession? {
        if (sessions.size >= MAX_SESSIONS) return null
        val s = BrowserSession("s${nextId++}", spec, env, scope)
        sessions.add(s)
        return s
    }

    /** Starts every session that has not started yet, a little apart from each other. */
    fun startAll() {
        var delay = 250L   // let the tiles get laid out first
        for (s in sessions.toList()) {
            if (s.state != SessionState.IDLE) continue
            handler.postDelayed({ if (sessions.contains(s)) s.start() }, delay)
            delay += START_STAGGER_MS
        }
    }

    /** MASTER OFF: stops every session that is still working. Finished ones stay as they are. */
    fun masterOff() {
        handler.removeCallbacksAndMessages(null)
        for (s in sessions) {
            when (s.state) {
                SessionState.RUNNING, SessionState.PAUSED, SessionState.NEEDS_YOU, SessionState.IDLE -> s.stop()
                else -> { }
            }
        }
    }

    fun clear() {
        handler.removeCallbacksAndMessages(null)
        for (s in sessions) s.destroy()
        sessions.clear()
    }

    private fun isLive(s: BrowserSession) =
        s.state == SessionState.RUNNING || s.state == SessionState.NEEDS_YOU || s.state == SessionState.IDLE

    fun countRunning() = sessions.count { it.state == SessionState.RUNNING }
    fun countLive() = sessions.count { isLive(it) }
    fun countNeedsYou() = sessions.count { it.state == SessionState.NEEDS_YOU }
    fun countDone() = sessions.count { it.state == SessionState.DONE }
    fun anyLive() = sessions.any { isLive(it) || it.state == SessionState.PAUSED }

    fun lines(): List<SessionLine> = sessions.mapIndexed { i, s -> s.line(i + 1) }

    /** One headline for the master card, e.g. "3 running · 1 needs you · 2 done". */
    fun headline(): String {
        if (sessions.isEmpty()) return "No sessions"
        val parts = ArrayList<String>()
        countRunning().takeIf { it > 0 }?.let { parts.add("$it running") }
        countNeedsYou().takeIf { it > 0 }?.let { parts.add("$it needs you") }
        sessions.count { it.state == SessionState.PAUSED }.takeIf { it > 0 }?.let { parts.add("$it paused") }
        countDone().takeIf { it > 0 }?.let { parts.add("$it done") }
        sessions.count { it.state == SessionState.STOPPED }.takeIf { it > 0 }?.let { parts.add("$it stopped") }
        sessions.count { it.state == SessionState.FAILED }.takeIf { it > 0 }?.let { parts.add("$it failed") }
        return if (parts.isEmpty()) "${sessions.size} sessions" else parts.joinToString(" · ")
    }
}
