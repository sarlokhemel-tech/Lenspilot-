package com.hemel.lenspilot.browser.multi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ValueCallback
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hemel.lenspilot.browser.BrowserAgentService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v162 — ELA 4N multi-session automation browser.
 * One screen, several live browser sessions side by side (each = its own WebView + its own agent with its own control bar).
 *   • a big task → Gemini 3.5 Flash decides how many sessions and what each does (server: /api/browser-multi-plan)
 *   • "manual" → the person writes every session's task, attaches files and vault items
 *   • master bubble: tap = glass status card with STOP ALL + a line per session (computed by code) · long press = manual setup
 *   • tap a tile = enlarge it (and touch the page yourself); tap again = back to the grid
 */
class MultiSessionActivity : AppCompatActivity(), SessionEnv {

    companion object {
        const val EXTRA_GOAL = "goal"
        const val EXTRA_SYSTEM = "system"
        const val EXTRA_MANUAL = "manual"

        fun intent(ctx: Context, goal: String?, manual: Boolean): Intent =
            Intent(ctx, MultiSessionActivity::class.java)
                .putExtra(EXTRA_GOAL, goal.orEmpty())
                .putExtra(EXTRA_SYSTEM, "ela_4n")
                .putExtra(EXTRA_MANUAL, manual)
    }

    override val activity: Activity get() = this

    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var grid: TileGridLayout
    private lateinit var bubble: MasterBubbleView
    private lateinit var card: StatusCardView
    private lateinit var planOverlay: LinearLayout
    private lateinit var planText: TextView
    private lateinit var manager: SessionManager
    private val tileOf = HashMap<BrowserSession, SessionTileView>()

    private var refreshPosted = false
    private var finishedAnnounced = false
    private var pendingDocsCb: ((List<AttachedDoc>) -> Unit)? = null
    private var pendingFileCb: ValueCallback<Array<Uri>>? = null

    private val docsLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val cb = pendingDocsCb
        pendingDocsCb = null
        if (cb != null && uris.isNotEmpty()) {
            lifecycleScope.launch {
                val docs = withContext(Dispatchers.IO) {
                    uris.map { u ->
                        try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
                        DocReader.read(this@MultiSessionActivity, u)
                    }
                }
                cb(docs)
            }
        }
    }

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val cb = pendingFileCb
        pendingFileCb = null
        cb?.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    // ------------------------------------------------------------------ SessionEnv
    override fun pickFileFor(session: BrowserSession, callback: ValueCallback<Array<Uri>>) {
        pendingFileCb?.onReceiveValue(null)      // an older chooser that was never answered
        pendingFileCb = callback
        Toast.makeText(this, "${session.spec.title}: choose a file to upload", Toast.LENGTH_SHORT).show()
        fileLauncher.launch(arrayOf("*/*"))
    }

    override fun onSessionChanged(session: BrowserSession) {
        if (refreshPosted) return
        refreshPosted = true
        ui.postDelayed({ refreshPosted = false; refreshUi() }, 250)
    }

    // ------------------------------------------------------------------ life-cycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = 0xFF0F1115.toInt()
        root = FrameLayout(this).apply { setBackgroundColor(0xFF0F1115.toInt()) }
        setContentView(root)

        manager = SessionManager(this, lifecycleScope)
        grid = TileGridLayout(this)
        root.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        card = StatusCardView(
            this,
            onStopAll = { manager.masterOff(); refreshUi() },
            onClose = { hideCard() },
            onRowClick = { idx -> hideCard(); manager.sessions.getOrNull(idx)?.let { s -> tileOf[s]?.let { grid.maximize(it) } } },
        )
        card.visibility = View.GONE
        root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(dp(10), dp(8), dp(10), 0)
        })

        bubble = MasterBubbleView(this, onBubbleTap = { toggleCard() }, onBubbleLongPress = { openManual() })
        root.addView(bubble, FrameLayout.LayoutParams(dp(54), dp(54)))
        root.post { bubble.placeAtBottomEnd() }

        planOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xE60F1115.toInt())
            isClickable = true
            visibility = View.GONE
        }
        planOverlay.addView(ProgressBar(this))
        planText = TextView(this).apply { setTextColor(0xFFE8EAF0.toInt()); textSize = 15f; gravity = Gravity.CENTER; setPadding(dp(32), dp(16), dp(32), 0) }
        planOverlay.addView(planText)
        root.addView(planOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        BrowserAgentService.onStopRequested = { runOnUiThread { manager.masterOff(); refreshUi() } }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    card.visibility == View.VISIBLE -> hideCard()
                    grid.maximized != null -> grid.maximize(null)
                    manager.anyLive() -> AlertDialog.Builder(this@MultiSessionActivity)
                        .setTitle("Leave multi-session?")
                        .setMessage("The sessions that are still working will be stopped.")
                        .setPositiveButton("Stop all & leave") { _, _ -> manager.masterOff(); finish() }
                        .setNegativeButton("Stay", null)
                        .show()
                    else -> finish()
                }
            }
        })

        val goal = intent.getStringExtra(EXTRA_GOAL).orEmpty()
        val manual = intent.getBooleanExtra(EXTRA_MANUAL, false)
        if (!manual && goal.isNotBlank()) planAndRun(goal) else openManual()
    }

    override fun onDestroy() {
        BrowserAgentService.onStopRequested = null
        ui.removeCallbacksAndMessages(null)
        try { BrowserAgentService.stop(this) } catch (e: Exception) { }
        grid.setTiles(emptyList())
        manager.clear()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ run
    private fun planAndRun(goal: String) {
        planText.text = "🧠 Deciding how many sessions this task needs…"
        planOverlay.visibility = View.VISIBLE
        lifecycleScope.launch {
            val plan = MultiPlanner.plan(this@MultiSessionActivity, goal)
            planOverlay.visibility = View.GONE
            plan.error?.let { Toast.makeText(this@MultiSessionActivity, it, Toast.LENGTH_LONG).show() }
            if (plan.error == null) {
                val n = plan.sessions.size
                val msg = (if (n == 1) "1 session" else "$n sessions") + (if (plan.why.isNotBlank()) " — ${plan.why}" else "")
                Toast.makeText(this@MultiSessionActivity, msg, Toast.LENGTH_LONG).show()
            }
            runSpecs(plan.sessions)
        }
    }

    private fun runSpecs(specs: List<SessionSpec>) {
        if (specs.isEmpty()) return
        grid.maximize(null)
        grid.setTiles(emptyList())
        manager.clear()
        tileOf.clear()
        finishedAnnounced = false
        for (s in specs.take(SessionManager.MAX_SESSIONS)) manager.add(s)
        val tiles = manager.sessions.map { s ->
            SessionTileView(this, s, { grid.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels }) { t ->
                grid.maximize(if (grid.maximized === t) null else t)
            }.also { tileOf[s] = it }
        }
        grid.setTiles(tiles)
        try { BrowserAgentService.start(this) } catch (e: Exception) { }
        manager.startAll()
        refreshUi()
    }

    private fun openManual() {
        val dlg = ManualSetupDialog(
            this,
            manager.sessions.size,
            pickDocs = { cb -> pendingDocsCb = cb; docsLauncher.launch(arrayOf("*/*")) },
            onRun = { specs -> runSpecs(specs) },
        )
        dlg.onDismissed = { if (manager.sessions.isEmpty() && planOverlay.visibility != View.VISIBLE) finish() }
        dlg.show()
    }

    // ------------------------------------------------------------------ UI refresh (code only — no LLM)
    private fun refreshUi() {
        for (t in tileOf.values) t.refresh()
        bubble.setCounts(manager.countRunning(), manager.countNeedsYou())
        if (card.visibility == View.VISIBLE) renderCard()
        if (manager.sessions.isNotEmpty() && !manager.anyLive() && !finishedAnnounced) {
            finishedAnnounced = true
            try { BrowserAgentService.stop(this) } catch (e: Exception) { }
            Toast.makeText(this, manager.headline(), Toast.LENGTH_LONG).show()
        }
    }

    private fun renderCard() = card.render(manager.headline(), manager.lines(), manager.anyLive())

    private fun toggleCard() { if (card.visibility == View.VISIBLE) hideCard() else showCard() }

    private fun showCard() {
        renderCard()
        card.alpha = 0f
        card.translationY = -dp(16).toFloat()
        card.visibility = View.VISIBLE
        card.animate().alpha(1f).translationY(0f).setDuration(160).start()
        card.bringToFront()
        bubble.bringToFront()
    }

    private fun hideCard() {
        card.animate().alpha(0f).translationY(-dp(16).toFloat()).setDuration(120).withEndAction { card.visibility = View.GONE }.start()
    }
}
