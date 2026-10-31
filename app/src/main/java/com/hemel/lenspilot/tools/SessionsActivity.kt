package com.hemel.lenspilot.tools

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Button
import com.hemel.lenspilot.tools.runner.ScheduleAction
import com.hemel.lenspilot.tools.runner.ScheduledResume
import com.hemel.lenspilot.tools.runner.Scheduler
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hemel.lenspilot.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** কাজের সেশনের তালিকা — স্টেট-চিপ ও শেষ নোটসহ। এখন চাপলে StorageActivity খোলে (পরে লাইভ কাজের স্ক্রিন)। */
class SessionsActivity : AppCompatActivity() {

    private lateinit var listView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: SessionAdapter
    private lateinit var scheduleBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sessions)
        findViewById<ImageButton>(R.id.sessionsBack).setOnClickListener { finish() }
        scheduleBox = findViewById(R.id.sessionsSchedules)
        listView = findViewById(R.id.sessionsList)
        emptyView = findViewById(R.id.sessionsEmpty)
        adapter = SessionAdapter(
            onClick = { s ->
                // সেশন ৭: ট্যাপ → লাইভ কাজের স্ক্রিন
                startActivity(
                    Intent(this, com.hemel.lenspilot.tools.agent.ToolsSessionActivity::class.java)
                        .putExtra(com.hemel.lenspilot.tools.agent.ToolsSessionActivity.EXTRA_SESSION_ID, s.id)
                )
            },
            onLongClick = { s -> confirmDelete(s) },
            onShell = { s ->
                startActivity(
                    Intent(this, com.hemel.lenspilot.tools.shell.ShellActivity::class.java)
                        .putExtra(com.hemel.lenspilot.tools.shell.ShellActivity.EXTRA_SESSION_ID, s.id)
                )
            }
        )
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter
    }

    override fun onStart() {
        super.onStart()
        // লাইভ অবস্থা: দৃশ্যমান থাকা অবধি প্রতি ৩ সেকেন্ডে হালনাগাদ
        liveJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refresh()
                    delay(3000)
                }
            }
        }
    }

    override fun onStop() {
        liveJob?.cancel()
        super.onStop()
    }

    private var liveJob: kotlinx.coroutines.Job? = null

    private fun refresh() {
        val app = applicationContext
        ToolsAsync.io {
            val sessions = SessionStore.list(app)
            val schedules = Scheduler.upcoming(app)
            ToolsAsync.ui {
                if (isFinishing || isDestroyed) return@ui
                renderSchedules(schedules, sessions)
                adapter.submit(sessions)
                emptyView.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /** আসন্ন নির্ধারিত সময়ের তালিকা — প্রতিটির পাশে বাতিল বাটন। */
    private fun renderSchedules(list: List<ScheduledResume>, sessions: List<WorkSession>) {
        scheduleBox.removeAllViews()
        if (list.isEmpty()) { scheduleBox.visibility = View.GONE; return }
        scheduleBox.visibility = View.VISIBLE
        val d = resources.displayMetrics.density
        scheduleBox.addView(TextView(this).apply {
            text = "⏰ নির্ধারিত সময়"
            textSize = 14f
            setTextColor(Color.parseColor("#0F172A"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
        })
        for (s in list) {
            val title = sessions.firstOrNull { it.id == s.sessionId }?.title ?: "সাধারণ"
            val what = when (s.action) {
                is ScheduleAction.ResumeStep -> "ধাপ চালু"
                is ScheduleAction.TypeText -> "লেখা পাঠানো"
                ScheduleAction.Notify -> "নোটিফিকেশন"
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = ToolsFormat.date(s.atMillis) + " — " + what + " · " + title +
                    (if (s.note.isNotBlank()) "\n" + s.note else "")
                textSize = 13f
                setTextColor(Color.parseColor("#475569"))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "বাতিল"
                isAllCaps = false
                setOnClickListener {
                    val app = applicationContext
                    ToolsAsync.io {
                        Scheduler.cancel(app, s.id)
                        ToolsAsync.ui { if (!isFinishing && !isDestroyed) refresh() }
                    }
                }
            })
            scheduleBox.addView(row)
        }
    }

    private fun confirmDelete(s: WorkSession) {
        AlertDialog.Builder(this)
            .setTitle(R.string.tools_session_delete_title)
            .setMessage(getString(R.string.tools_session_delete_msg, s.title))
            .setPositiveButton(R.string.tools_act_delete) { _, _ ->
                val app = applicationContext
                ToolsAsync.io {
                    com.hemel.lenspilot.tools.agent.ToolsAgents.stop(app, s.id)   // চলমান এজেন্ট/নজরদারি আগে বন্ধ
                    SessionStore.delete(app, s.id)
                    ToolsAsync.ui { if (!isFinishing && !isDestroyed) refresh() }
                }
            }
            .setNegativeButton(R.string.tools_cancel, null)
            .show()
    }

    private class SessionAdapter(
        private val onClick: (WorkSession) -> Unit,
        private val onLongClick: (WorkSession) -> Unit,
        private val onShell: (WorkSession) -> Unit
    ) : RecyclerView.Adapter<SessionAdapter.VH>() {

        private var items: List<WorkSession> = emptyList()

        fun submit(list: List<WorkSession>) {
            items = list
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.sessionTitle)
            val chip: TextView = v.findViewById(R.id.sessionChip)
            val note: TextView = v.findViewById(R.id.sessionNote)
            val time: TextView = v.findViewById(R.id.sessionTime)
            val shell: View = v.findViewById(R.id.sessionShell)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = items[position]
            val ctx = holder.itemView.context
            holder.title.text = s.title
            val live = com.hemel.lenspilot.tools.agent.ToolsAgents.isAlive(s.id)
            val liveLabel = if (live) com.hemel.lenspilot.tools.agent.ToolsAgents.uiFlow(ctx, s.id).value.let { u ->
                (if (u.stepLabel.isNotBlank()) u.stepLabel else "") + (if (u.stepTotal > 0) "  ·  " + u.stepIndex + "/" + u.stepTotal else "")
            } else ""
            holder.note.text = if (liveLabel.isNotBlank()) liveLabel else if (s.lastNote.isNotBlank()) s.lastNote else s.goal
            holder.time.text = ToolsFormat.date(s.updatedAt)
            holder.chip.text = ctx.getString(stateLabel(s.state))
            val color = Color.parseColor(stateColor(s.state))
            holder.chip.setTextColor(color)
            holder.chip.background = GradientDrawable().apply {
                cornerRadius = 40f * ctx.resources.displayMetrics.density
                setColor(Color.argb(34, Color.red(color), Color.green(color), Color.blue(color)))
            }
            holder.shell.setOnClickListener { onShell(s) }
            holder.itemView.setOnClickListener { onClick(s) }
            holder.itemView.setOnLongClickListener { onLongClick(s); true }
        }

        private fun stateLabel(st: SessionState): Int = when (st) {
            SessionState.IDLE -> R.string.tools_state_idle
            SessionState.RUNNING -> R.string.tools_state_running
            SessionState.WAITING -> R.string.tools_state_waiting
            SessionState.NEEDS_YOU -> R.string.tools_state_needs_you
            SessionState.DONE -> R.string.tools_state_done
            SessionState.FAILED -> R.string.tools_state_failed
        }

        private fun stateColor(st: SessionState): String = when (st) {
            SessionState.IDLE -> "#64748B"
            SessionState.RUNNING -> "#2563EB"
            SessionState.WAITING -> "#F59E0B"
            SessionState.NEEDS_YOU -> "#8B5CF6"
            SessionState.DONE -> "#10B981"
            SessionState.FAILED -> "#EF4444"
        }
    }
}
