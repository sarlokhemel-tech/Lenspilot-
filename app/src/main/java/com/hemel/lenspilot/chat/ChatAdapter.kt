package com.hemel.lenspilot.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hemel.lenspilot.R

private const val TYPE_USER = 0
private const val TYPE_AI = 1
private const val TYPE_WORKFLOW = 2

/**
 * Simple three-view-type chat list. [onRunWorkflow] fires when the user
 * taps "Run" on a workflow card, passing the item's position so the caller
 * can update that specific card's status text as the run progresses (see
 * [setWorkflowStatus]).
 */
class ChatAdapter(
    private val messages: MutableList<ChatMessage>,
    private val onRunWorkflow: (position: Int, workflow: WorkflowPreview) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val runningStatus = mutableMapOf<Int, String>()

    override fun getItemViewType(position: Int): Int {
        val m = messages[position]
        return when {
            m.workflow != null -> TYPE_WORKFLOW
            m.role == "user" -> TYPE_USER
            else -> TYPE_AI
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserVH(inflater.inflate(R.layout.item_message_user, parent, false))
            TYPE_WORKFLOW -> WorkflowVH(inflater.inflate(R.layout.item_workflow_card, parent, false))
            else -> AiVH(inflater.inflate(R.layout.item_message_ai, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val m = messages[position]
        when (holder) {
            is UserVH -> holder.text.text = m.text
            is AiVH -> holder.text.text = m.text
            is WorkflowVH -> {
                val wf = m.workflow ?: return
                holder.title.text = wf.title
                holder.steps.text = wf.steps.joinToString("\n") { "${it.stepNumber}. ${it.goal}" }
                val status = runningStatus[position]
                if (status != null) {
                    holder.status.visibility = View.VISIBLE
                    holder.status.text = status
                } else {
                    holder.status.visibility = View.GONE
                }
                holder.runButton.setOnClickListener { onRunWorkflow(holder.bindingAdapterPosition, wf) }
            }
        }
    }

    override fun getItemCount(): Int = messages.size

    /** Called by the host Activity while a workflow is executing, so the
     * card shows live progress ("২/৪ ধাপ চলছে…") without rebuilding the
     * whole list. */
    fun setWorkflowStatus(position: Int, status: String?) {
        if (position < 0 || position >= messages.size) return
        if (status == null) runningStatus.remove(position) else runningStatus[position] = status
        notifyItemChanged(position)
    }

    class UserVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.messageText)
    }

    class AiVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.messageText)
    }

    class WorkflowVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.workflowTitle)
        val steps: TextView = v.findViewById(R.id.workflowStepsText)
        val status: TextView = v.findViewById(R.id.workflowStatusText)
        val runButton: android.widget.Button = v.findViewById(R.id.workflowRunButton)
    }
}
