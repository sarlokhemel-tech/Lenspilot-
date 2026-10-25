package com.hemel.lenspilot

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.hemel.lenspilot.accessibility.LenspilotAccessibilityService

/**
 * Invisible one-frame Activity used ONLY as a trampoline for the Quick
 * Settings tile on API < 34, where [android.service.quicksettings.TileService
 * .collapseStatusBar] doesn't exist yet. Launching any real Activity via
 * `startActivityAndCollapse` is the one thing that reliably closes the
 * Quick Settings shade on every Android version — this Activity does
 * nothing visible, waits just long enough for the collapse animation to
 * actually finish, triggers the screen scan on the (separately, always-
 * alive) accessibility service, then finishes itself immediately.
 *
 * Without this trampoline, tapping the tile fired the scan while the shade
 * was still covering the screen, so it ended up "scanning" the Quick
 * Settings panel itself instead of the real app underneath — the bug this
 * whole class exists to fix.
 */
class QuickScanTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v52: শেড বন্ধ হওয়ার অ্যানিমেশন শেষ হলে AI এজেন্টের গ্লাস কার্ড খুলি (Accessibility ছাড়াও চলে)।
        Handler(Looper.getMainLooper()).postDelayed({
            if (com.hemel.lenspilot.agent.AgentOverlayService.canDraw(applicationContext)) {
                com.hemel.lenspilot.agent.AgentOverlayService.start(applicationContext, showPanel = true)
            } else {
                Toast.makeText(applicationContext, "Allow “Display over other apps” for Lenspilot", Toast.LENGTH_LONG).show()
            }
            finish()
        }, 300L)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
