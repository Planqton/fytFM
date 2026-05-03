package at.planqton.fytfm.ui.pi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import at.planqton.fytfm.data.pi.PiEntry

class PiListAdapter : RecyclerView.Adapter<PiListAdapter.ViewHolder>() {

    private var entries: List<PiEntry> = emptyList()

    fun setEntries(newEntries: List<PiEntry>) {
        entries = newEntries
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pi_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvHex: TextView = itemView.findViewById(R.id.tvPiHex)
        private val tvName: TextView = itemView.findViewById(R.id.tvPiName)
        private val tvCountry: TextView = itemView.findViewById(R.id.tvPiCountry)

        fun bind(entry: PiEntry) {
            tvHex.text = "0x%04X".format(entry.pi)
            tvName.text = entry.name
            tvCountry.text = entry.country
        }
    }
}
