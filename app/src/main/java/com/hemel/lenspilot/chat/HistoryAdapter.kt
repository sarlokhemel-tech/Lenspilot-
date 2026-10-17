package com.hemel.lenspilot.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hemel.lenspilot.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One row in the History list — a past conversation or workflow run,
 * regardless of whether it came from the Space (server) or the on-device
 * fallback cache. [sourceId] is opaque to the adapter; the caller uses it
 * to look back up the full session/entry when a row is tapped. */
data class HistoryRow(
    val sourceId: String,
    val title: String,
    val snippet: String,
    val timestampMillis: Long,
    val isWorkflow: Boolean
)

class HistoryAdapter(
    private val rows: List<HistoryRow>,
    private val onClick: (HistoryRow) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        holder.title.text = row.title.ifBlank { "…" }
        holder.snippet.text = row.snippet
        holder.snippet.visibility = if (row.snippet.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        holder.time.text = formatTimestamp(row.timestampMillis)
        holder.icon.setImageResource(if (row.isWorkflow) R.drawable.ic_history_workflow else R.drawable.ic_history_chat)
        holder.itemView.setOnClickListener { onClick(row) }
    }

    override fun getItemCount(): Int = rows.size

    class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.historyRowIcon)
        val title: TextView = v.findViewById(R.id.historyRowTitle)
        val snippet: TextView = v.findViewById(R.id.historyRowSnippet)
        val time: TextView = v.findViewById(R.id.historyRowTime)
    }

    companion object {
        /** "3:40 PM" for today, "Aug 27" otherwise — same shorthand most
         * chat apps use for a conversation list. */
        fun formatTimestamp(millis: Long): String {
            if (millis <= 0L) return ""
            val zone = ZoneId.systemDefault()
            val then = Instant.ofEpochMilli(millis).atZone(zone)
            val now = Instant.now().atZone(zone)
            return if (then.toLocalDate() == now.toLocalDate()) {
                then.format(DateTimeFormatter.ofPattern("h:mm a"))
            } else if (then.year == now.year) {
                then.format(DateTimeFormatter.ofPattern("MMM d"))
            } else {
                then.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
            }
        }
    }
}
