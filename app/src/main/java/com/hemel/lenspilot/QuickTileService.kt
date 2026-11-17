package com.hemel.lenspilot

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.hemel.lenspilot.accessibility.LenspilotAccessibilityService

/**
 * "ফোনের উপরের বারে সর্টকাট" — the tile that appears in the pull-down
 * Quick Settings shade next to Wi-Fi/data. Tapping it:
 *   - if Accessibility isn't on yet -> opens the app straight to the
 *     accessibility-permission prompt (can't scan anything without it);
 *   - otherwise -> triggers [LenspilotAccessibilityService.quickScanCurrentScreen]
 *     directly, with NO app UI opened at all — it reads whatever screen
 *     is currently in front (the running accessibility service persists
 *     regardless of what app is in the foreground) and either starts
 *     guiding a fix it found, or asks the user what they want.
 */
@RequiresApi(Build.VERSION_CODES.N)
class QuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        // v52: টাইল চাপলে এখন AI এজেন্টের গ্লাস কার্ড নিজে খোলে (Accessibility লাগে না — শুধু Overlay পারমিশন)।
        // কার্ড বন্ধ করলে বাবল হয়ে থাকে। পুরনো স্ক্রিন-স্ক্যান কার্ডের "🔍 Scan" চিপে (Accessibility চালু থাকলে) আছে।
        if (!com.hemel.lenspilot.agent.AgentOverlayService.canDraw(this)) {
            Toast.makeText(this, "Allow “Display over other apps” for Lenspilot", Toast.LENGTH_LONG).show()
            val intent = com.hemel.lenspilot.agent.AgentOverlayService.overlayPermissionIntent(this)
            if (Build.VERSION.SDK_INT >= 34) {
                val pending = PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }
        // Must collapse the Quick Settings shade BEFORE reading the
        // screen — onClick() firing quickScanCurrentScreen() directly
        // (the old code) left the panel open on top of everything, so
        // the scan ended up reading the QS panel itself instead of the
        // real app underneath. Launching a real Activity via
        // startActivityAndCollapse is the one thing that reliably closes
        // the shade on every Android version, so route through the
        // invisible trampoline (same version branching as the
        // "not enabled yet" case above) instead of calling
        // quickScanCurrentScreen() directly from here.
        val trampoline = Intent(this, QuickScanTrampolineActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(
                this, 1, trampoline, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(trampoline)
        }
    }

    private fun updateTileState() {
        val enabled = com.hemel.lenspilot.agent.AgentOverlayService.canDraw(this)
        qsTile?.apply {
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.app_name)
            updateTile()
        }
    }
}
