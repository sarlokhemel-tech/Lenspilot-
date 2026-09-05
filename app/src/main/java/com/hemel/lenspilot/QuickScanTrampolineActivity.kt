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
        val service = LenspilotAccessibilityService.instance
        if (service == null) {
            finish()
            return
        }
        // Give the shade-collapse animation (~250-300ms on stock Android)
        // time to actually finish before capturing the screen underneath.
        Handler(Looper.getMainLooper()).postDelayed({
            Toast.makeText(applicationContext, "স্ক্রিন স্ক্যান করছি...", Toast.LENGTH_SHORT).show()
            service.quickScanCurrentScreen()
            finish()
        }, 300L)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
