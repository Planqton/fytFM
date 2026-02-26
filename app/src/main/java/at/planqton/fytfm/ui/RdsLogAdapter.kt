package at.planqton.fytfm.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import at.planqton.fytfm.RdsManager
import at.planqton.fytfm.data.rdslog.RdsLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RdsLogAdapter : RecyclerView.Adapter<RdsLogAdapter.ViewHolder>() {

    private var entries: List<RdsLogEntry> = emptyList()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.getDefault())

    fun setEntries(newEntries: List<RdsLogEntry>) {
        entries = newEntries
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_archive_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount() = entries.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTime: TextView = itemView.findViewById(R.id.tvEntryTime)
        private val tvFrequency: TextView = itemView.findViewById(R.id.tvEntryFrequency)
        private val tvPs: TextView = itemView.findViewById(R.id.tvEntryPs)
        private val tvRt: TextView = itemView.findViewById(R.id.tvEntryRt)
        private val tvRssi: TextView = itemView.findViewById(R.id.tvEntryRssi)
        private val tvPi: TextView = itemView.findViewById(R.id.tvEntryPi)
        private val tvPty: TextView = itemView.findViewById(R.id.tvEntryPty)
        private val tvEvent: TextView = itemView.findViewById(R.id.tvEntryEvent)

        fun bind(entry: RdsLogEntry) {
            // Time - show date if older than today
            val now = System.currentTimeMillis()
            val entryTime = entry.timestamp
            val oneDayMs = 24 * 60 * 60 * 1000L
            tvTime.text = if (now - entryTime > oneDayMs) {
                dateTimeFormat.format(Date(entryTime))
            } else {
                timeFormat.format(Date(entryTime))
            }

            // Frequency
            tvFrequency.text = if (entry.isAM) {
                "AM ${entry.frequency.toInt()}"
            } else {
                "FM %.1f".format(entry.frequency)
            }

            // PS
            if (!entry.ps.isNullOrBlank()) {
                tvPs.text = entry.ps
                tvPs.visibility = View.VISIBLE
            } else {
                tvPs.visibility = View.GONE
            }

            // RT
            if (!entry.rt.isNullOrBlank()) {
                tvRt.text = entry.rt
                tvRt.setTextColor(itemView.context.getColor(android.R.color.black))
            } else {
                tvRt.text = "(no Radio Text)"
                tvRt.setTextColor(itemView.context.getColor(android.R.color.darker_gray))
            }

            // RSSI
            tvRssi.text = "RSSI: ${entry.rssi}"

            // PI
            if (entry.pi != 0) {
                tvPi.text = "PI: 0x${String.format("%04X", entry.pi and 0xFFFF)}"
                tvPi.visibility = View.VISIBLE
            } else {
                tvPi.visibility = View.GONE
            }

            // PTY
            if (entry.pty > 0) {
                tvPty.text = "PTY: ${RdsManager.getPtyName(entry.pty)}"
                tvPty.visibility = View.VISIBLE
            } else {
                tvPty.visibility = View.GONE
            }

            // Event badge
            if (entry.eventType == "STATION_CHANGE") {
                tvEvent.text = "TUNE"
                tvEvent.visibility = View.VISIBLE
            } else {
                tvEvent.visibility = View.GONE
            }
        }
    }
}
