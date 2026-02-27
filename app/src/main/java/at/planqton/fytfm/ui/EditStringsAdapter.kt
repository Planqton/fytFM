package at.planqton.fytfm.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import at.planqton.fytfm.data.rdslog.EditString

class EditStringsAdapter(
    private val onEditClick: (EditString) -> Unit,
    private val onDeleteClick: (EditString) -> Unit,
    private val onToggleEnabled: (EditString, Boolean) -> Unit
) : ListAdapter<EditString, EditStringsAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_edit_string, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textFind: TextView = itemView.findViewById(R.id.textFind)
        private val textReplace: TextView = itemView.findViewById(R.id.textReplace)
        private val textPosition: TextView = itemView.findViewById(R.id.textPosition)
        private val textFallback: TextView = itemView.findViewById(R.id.textFallback)
        private val textCondition: TextView = itemView.findViewById(R.id.textCondition)
        private val textFrequency: TextView = itemView.findViewById(R.id.textFrequency)
        private val textCaseSensitive: TextView = itemView.findViewById(R.id.textCaseSensitive)
        private val switchEnabled: Switch = itemView.findViewById(R.id.switchEnabled)
        private val btnEdit: ImageButton = itemView.findViewById(R.id.btnEdit)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

        fun bind(editString: EditString) {
            textFind.text = "\"${editString.textOriginal}\""

            if (editString.replaceWith.isEmpty()) {
                textReplace.text = "(löschen)"
                textReplace.setTextColor(Color.parseColor("#CC0000"))
            } else {
                textReplace.text = "→ \"${editString.replaceWith}\""
                textReplace.setTextColor(Color.parseColor("#006600"))
            }

            textPosition.text = EditString.getPositionLabel(editString.position)

            if (editString.onlyIfNotFound) {
                textFallback.visibility = View.VISIBLE
                textFallback.text = "Fallback"
            } else {
                textFallback.visibility = View.GONE
            }

            // Show condition if set
            if (!editString.conditionContains.isNullOrEmpty()) {
                textCondition.visibility = View.VISIBLE
                textCondition.text = "wenn \"${editString.conditionContains}\""
            } else {
                textCondition.visibility = View.GONE
            }

            // Show frequency if set
            if (editString.forFrequency != null) {
                textFrequency.visibility = View.VISIBLE
                textFrequency.text = "${editString.forFrequency} MHz"
            } else {
                textFrequency.visibility = View.GONE
            }

            // Show case sensitive indicator (if either find or condition is case sensitive)
            val hasCaseSensitive = editString.caseSensitiveFind || editString.caseSensitiveCondition
            textCaseSensitive.visibility = if (hasCaseSensitive) View.VISIBLE else View.GONE

            // Disable listener while setting checked state
            switchEnabled.setOnCheckedChangeListener(null)
            switchEnabled.isChecked = editString.enabled
            switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggleEnabled(editString, isChecked)
            }

            // Dim the item if disabled
            itemView.alpha = if (editString.enabled) 1.0f else 0.5f

            btnEdit.setOnClickListener {
                onEditClick(editString)
            }

            btnDelete.setOnClickListener {
                onDeleteClick(editString)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<EditString>() {
        override fun areItemsTheSame(oldItem: EditString, newItem: EditString): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: EditString, newItem: EditString): Boolean {
            return oldItem == newItem
        }
    }
}
