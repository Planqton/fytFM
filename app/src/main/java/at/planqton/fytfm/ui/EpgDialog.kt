package at.planqton.fytfm.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import at.planqton.fytfm.dab.EpgData
import at.planqton.fytfm.dab.EpgItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dialog für die EPG-Anzeige (Programmvorschau).
 */
class EpgDialog(private val context: Context) {

    private var dialog: AlertDialog? = null
    private var adapter: EpgAdapter? = null

    // Views
    private var tvStationName: TextView? = null
    private var layoutCurrentShow: LinearLayout? = null
    private var tvCurrentTime: TextView? = null
    private var tvCurrentTitle: TextView? = null
    private var tvCurrentDescription: TextView? = null
    private var tvCurrentEndTime: TextView? = null
    private var progressCurrentShow: ProgressBar? = null
    private var tvUpcomingHeader: TextView? = null
    private var rvUpcomingShows: RecyclerView? = null
    private var layoutNoEpg: LinearLayout? = null
    private var tvLastUpdated: TextView? = null

    fun show(epgData: EpgData?, stationName: String = "DAB+") {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_epg, null)

        // View references
        tvStationName = view.findViewById(R.id.tvEpgStationName)
        layoutCurrentShow = view.findViewById(R.id.layoutCurrentShow)
        tvCurrentTime = view.findViewById(R.id.tvCurrentTime)
        tvCurrentTitle = view.findViewById(R.id.tvCurrentTitle)
        tvCurrentDescription = view.findViewById(R.id.tvCurrentDescription)
        tvCurrentEndTime = view.findViewById(R.id.tvCurrentEndTime)
        progressCurrentShow = view.findViewById(R.id.progressCurrentShow)
        tvUpcomingHeader = view.findViewById(R.id.tvUpcomingHeader)
        rvUpcomingShows = view.findViewById(R.id.rvUpcomingShows)
        layoutNoEpg = view.findViewById(R.id.layoutNoEpg)
        tvLastUpdated = view.findViewById(R.id.tvLastUpdated)

        tvStationName?.text = stationName

        // RecyclerView setup
        adapter = EpgAdapter()
        rvUpcomingShows?.layoutManager = LinearLayoutManager(context)
        rvUpcomingShows?.adapter = adapter

        // Dialog erstellen
        dialog = AlertDialog.Builder(context, R.style.FytFM_AlertDialog)
            .setView(view)
            .create()

        // Close Button
        view.findViewById<TextView>(R.id.btnCloseEpg)?.setOnClickListener {
            dialog?.dismiss()
        }

        // EPG-Daten anzeigen
        updateEpgData(epgData)

        dialog?.show()
    }

    fun updateEpgData(epgData: EpgData?) {
        if (epgData == null || !epgData.hasEpgData()) {
            showNoEpgState()
            return
        }

        tvStationName?.text = epgData.serviceName

        // Aktuelle Sendung
        val current = epgData.currentItem
        if (current != null) {
            layoutCurrentShow?.visibility = View.VISIBLE
            layoutNoEpg?.visibility = View.GONE

            tvCurrentTime?.text = current.getFormattedStartTime()
            tvCurrentTitle?.text = current.title

            if (!current.description.isNullOrBlank()) {
                tvCurrentDescription?.text = current.description
                tvCurrentDescription?.visibility = View.VISIBLE
            } else {
                tvCurrentDescription?.visibility = View.GONE
            }

            val endTime = current.getFormattedEndTime()
            if (endTime != null) {
                tvCurrentEndTime?.text = context.getString(R.string.epg_until, endTime)
                tvCurrentEndTime?.visibility = View.VISIBLE
            } else {
                tvCurrentEndTime?.visibility = View.GONE
            }

            val progress = current.getProgress()
            if (progress != null) {
                progressCurrentShow?.visibility = View.VISIBLE
                progressCurrentShow?.progress = progress
            } else {
                progressCurrentShow?.visibility = View.GONE
            }
        } else {
            layoutCurrentShow?.visibility = View.GONE
        }

        // Kommende Sendungen
        if (epgData.upcomingItems.isNotEmpty()) {
            tvUpcomingHeader?.visibility = View.VISIBLE
            rvUpcomingShows?.visibility = View.VISIBLE
            adapter?.updateItems(epgData.upcomingItems)
        } else {
            tvUpcomingHeader?.visibility = View.GONE
            rvUpcomingShows?.visibility = View.GONE
        }

        // Last Updated
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        tvLastUpdated?.text = context.getString(R.string.epg_updated, sdf.format(Date(epgData.lastUpdated)))
    }

    private fun showNoEpgState() {
        layoutCurrentShow?.visibility = View.GONE
        tvUpcomingHeader?.visibility = View.GONE
        rvUpcomingShows?.visibility = View.GONE
        layoutNoEpg?.visibility = View.VISIBLE
    }

    fun dismiss() {
        dialog?.dismiss()
    }

    fun isShowing(): Boolean = dialog?.isShowing == true

    // RecyclerView Adapter
    private inner class EpgAdapter : RecyclerView.Adapter<EpgAdapter.VH>() {
        private val items = mutableListOf<EpgItem>()

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvStartTime: TextView = view.findViewById(R.id.tvStartTime)
            val tvTitle: TextView = view.findViewById(R.id.tvTitle)
            val tvDuration: TextView = view.findViewById(R.id.tvDuration)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_epg_entry, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvStartTime.text = item.getFormattedStartTime()
            holder.tvTitle.text = item.title

            val duration = item.getFormattedDuration()
            if (duration != null) {
                holder.tvDuration.text = duration
                holder.tvDuration.visibility = View.VISIBLE
            } else {
                holder.tvDuration.visibility = View.GONE
            }
        }

        override fun getItemCount(): Int = items.size

        fun updateItems(newItems: List<EpgItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }
    }
}
