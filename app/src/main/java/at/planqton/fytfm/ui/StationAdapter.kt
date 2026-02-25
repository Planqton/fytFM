package at.planqton.fytfm.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import at.planqton.fytfm.data.RadioStation

class StationAdapter(
    private val onStationClick: (RadioStation) -> Unit
) : RecyclerView.Adapter<StationAdapter.StationViewHolder>() {

    private var stations: List<RadioStation> = emptyList()
    private var selectedPosition: Int = -1

    fun setStations(newStations: List<RadioStation>) {
        stations = newStations
        notifyDataSetChanged()
    }

    fun getStations(): List<RadioStation> = stations

    fun setSelectedFrequency(frequency: Float) {
        val newPosition = stations.indexOfFirst {
            Math.abs(it.frequency - frequency) < 0.05f
        }
        if (newPosition != selectedPosition) {
            val oldPosition = selectedPosition
            selectedPosition = newPosition
            if (oldPosition >= 0) notifyItemChanged(oldPosition)
            if (newPosition >= 0) notifyItemChanged(newPosition)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_station, parent, false)
        return StationViewHolder(view)
    }

    override fun onBindViewHolder(holder: StationViewHolder, position: Int) {
        val station = stations[position]
        holder.bind(station, position == selectedPosition)
        holder.itemView.setOnClickListener {
            onStationClick(station)
        }
    }

    override fun getItemCount(): Int = stations.size

    class StationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val container: LinearLayout = itemView.findViewById(R.id.stationContainer)
        private val tvFrequency: TextView = itemView.findViewById(R.id.tvFrequency)
        private val tvStationName: TextView = itemView.findViewById(R.id.tvStationName)

        fun bind(station: RadioStation, isSelected: Boolean) {
            tvFrequency.text = station.getDisplayFrequency()

            val name = station.getDisplayName()
            if (name != null) {
                tvStationName.text = name
                tvStationName.visibility = View.VISIBLE
            } else {
                tvStationName.visibility = View.GONE
            }

            container.isSelected = isSelected
        }
    }
}
