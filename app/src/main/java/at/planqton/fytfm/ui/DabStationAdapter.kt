package at.planqton.fytfm.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import at.planqton.fytfm.data.RadioStation

class DabStationAdapter(
    private val onStationClick: (RadioStation) -> Unit
) : RecyclerView.Adapter<DabStationAdapter.ViewHolder>() {

    private var stations: List<RadioStation> = emptyList()

    fun setStations(newStations: List<RadioStation>) {
        stations = newStations
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_station_scan, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val station = stations[position]
        holder.bind(station)
        holder.itemView.setOnClickListener {
            onStationClick(station)
        }
    }

    override fun getItemCount(): Int = stations.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvFrequency: TextView = itemView.findViewById(R.id.tvFrequency)
        private val tvStationName: TextView = itemView.findViewById(R.id.tvStationName)
        private val tvRssi: TextView = itemView.findViewById(R.id.tvRssi)

        fun bind(station: RadioStation) {
            // Bei DAB: Name groß anzeigen, Ensemble als Subtitle
            val parts = station.name?.split("\n") ?: listOf("???")
            val name = parts.getOrNull(0) ?: "???"
            val ensemble = parts.getOrNull(1) ?: ""

            tvFrequency.text = ensemble.ifEmpty { "DAB+" }
            tvStationName.text = name
            tvStationName.visibility = View.VISIBLE
            tvRssi.visibility = View.GONE
        }
    }
}
