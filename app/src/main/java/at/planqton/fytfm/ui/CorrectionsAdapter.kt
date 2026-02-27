package at.planqton.fytfm.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import at.planqton.fytfm.data.rdslog.RtCorrection

class CorrectionsAdapter(
    private val onDeleteClick: (RtCorrection) -> Unit
) : ListAdapter<RtCorrection, CorrectionsAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_correction, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconType: ImageView = itemView.findViewById(R.id.iconType)
        private val textType: TextView = itemView.findViewById(R.id.textType)
        private val textRt: TextView = itemView.findViewById(R.id.textRt)
        private val textSkippedTrack: TextView = itemView.findViewById(R.id.textSkippedTrack)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

        fun bind(correction: RtCorrection) {
            textRt.text = correction.rtOriginal

            when (correction.type) {
                RtCorrection.TYPE_IGNORED -> {
                    iconType.setImageResource(R.drawable.ic_trash)
                    textType.text = "Ignoriert"
                    textSkippedTrack.visibility = View.GONE
                }
                RtCorrection.TYPE_SKIP_TRACK -> {
                    iconType.setImageResource(R.drawable.ic_refresh)
                    textType.text = "Track übersprungen"
                    if (correction.skipTrackArtist != null && correction.skipTrackTitle != null) {
                        textSkippedTrack.text = "${correction.skipTrackArtist} - ${correction.skipTrackTitle}"
                        textSkippedTrack.visibility = View.VISIBLE
                    } else {
                        textSkippedTrack.visibility = View.GONE
                    }
                }
            }

            btnDelete.setOnClickListener {
                onDeleteClick(correction)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<RtCorrection>() {
        override fun areItemsTheSame(oldItem: RtCorrection, newItem: RtCorrection): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: RtCorrection, newItem: RtCorrection): Boolean {
            return oldItem == newItem
        }
    }
}
