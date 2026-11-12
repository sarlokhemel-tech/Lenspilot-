package com.hemel.lenspilot.userinfo

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import androidx.recyclerview.widget.RecyclerView
import com.hemel.lenspilot.R

/**
 * Backs dialog_user_info.xml's RecyclerView. Every row is live-editable —
 * there's no separate "edit" step — so each ViewHolder's two EditTexts
 * write straight back into [rows] as the user types, and the delete icon
 * removes that row on the spot. The caller (MainActivity.showUserInfoDialog)
 * reads [rows] back out when the dialog's Save button is tapped.
 *
 * Rows/columns are both fully dynamic per the feature spec: any number of
 * rows (see [addBlankRow]/[removeAt]), and each EditText is wrap_content
 * with textMultiLine so a longer value just grows the row instead of
 * clipping.
 */
class UserInfoAdapter(
    private val rows: MutableList<UserInfoEntry>
) : RecyclerView.Adapter<UserInfoAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_user_info_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        // Clear any watcher from a recycled holder before setting text,
        // so we don't write the previous row's edits into the new one.
        holder.clearWatchers()
        val entry = rows[holder.bindingAdapterPositionOrNull() ?: position]
        holder.label.setText(entry.label)
        holder.value.setText(entry.value)

        holder.labelWatcher = holder.label.watch { text ->
            val pos = holder.bindingAdapterPositionOrNull() ?: return@watch
            if (pos in rows.indices) rows[pos].label = text
        }
        holder.valueWatcher = holder.value.watch { text ->
            val pos = holder.bindingAdapterPositionOrNull() ?: return@watch
            if (pos in rows.indices) rows[pos].value = text
        }

        holder.delete.setOnClickListener {
            val pos = holder.bindingAdapterPositionOrNull() ?: return@setOnClickListener
            if (pos !in rows.indices) return@setOnClickListener
            rows.removeAt(pos)
            notifyItemRemoved(pos)
            notifyItemRangeChanged(pos, rows.size - pos)
        }
    }

    override fun getItemCount(): Int = rows.size

    /** Appends one empty row and scrolls the RecyclerView to it — called
     * by dialog_user_info.xml's "+ সারি যোগ করুন" text button. */
    fun addBlankRow() {
        rows.add(UserInfoEntry())
        notifyItemInserted(rows.size - 1)
    }

    /** Appends rows extracted from a photo (see
     * MainActivity.extractUserInfoFromImage / /api/user-info/extract-image)
     * — used as-is, no dedup, since the user reviews/edits them right
     * after in the same still-open dialog. */
    fun addExtractedRows(newRows: List<UserInfoEntry>) {
        if (newRows.isEmpty()) return
        val start = rows.size
        rows.addAll(newRows)
        notifyItemRangeInserted(start, newRows.size)
    }

    class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val label: EditText = v.findViewById(R.id.userInfoRowLabel)
        val value: EditText = v.findViewById(R.id.userInfoRowValue)
        val delete: ImageButton = v.findViewById(R.id.userInfoRowDelete)
        var labelWatcher: TextWatcher? = null
        var valueWatcher: TextWatcher? = null

        fun clearWatchers() {
            labelWatcher?.let { label.removeTextChangedListener(it) }
            valueWatcher?.let { value.removeTextChangedListener(it) }
            labelWatcher = null
            valueWatcher = null
        }

        fun bindingAdapterPositionOrNull(): Int? =
            bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
    }
}

/** Small helper: adds a TextWatcher that only reports afterTextChanged,
 * and returns it so the caller can remove it again on rebind. */
private fun EditText.watch(onChanged: (String) -> Unit): TextWatcher {
    val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            onChanged(s?.toString() ?: "")
        }
    }
    addTextChangedListener(watcher)
    return watcher
}
