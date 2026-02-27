package at.planqton.fytfm.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import at.planqton.fytfm.data.RadioStation
import coil.load
import java.io.File

class StationAdapter(
    private val onStationClick: (RadioStation) -> Unit,
    private val getLogoPath: ((ps: String?, pi: Int?, frequency: Float) -> String?)? = null
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
        val logoPath = getLogoPath?.invoke(station.name, null, station.frequency)
        holder.bind(station, position == selectedPosition, logoPath)
        holder.itemView.setOnClickListener {
            onStationClick(station)
        }
    }

    override fun getItemCount(): Int = stations.size

    class StationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val container: LinearLayout = itemView.findViewById(R.id.stationContainer)
        private val tvFrequency: TextView = itemView.findViewById(R.id.tvFrequency)
        private val tvStationName: TextView = itemView.findViewById(R.id.tvStationName)
        private val ivFavorite: ImageView = itemView.findViewById(R.id.ivFavorite)
        private val ivStationLogo: ImageView = itemView.findViewById(R.id.ivStationLogo)

        fun bind(station: RadioStation, isSelected: Boolean, logoPath: String?) {
            tvFrequency.text = station.getDisplayFrequency()

            val name = station.getDisplayName()
            if (name != null) {
                tvStationName.text = name
                tvStationName.visibility = View.VISIBLE
            } else {
                tvStationName.visibility = View.GONE
            }

            // Favoriten-Stern anzeigen
            ivFavorite.visibility = if (station.isFavorite) View.VISIBLE else View.GONE

            // Station Logo anzeigen
            if (logoPath != null) {
                ivStationLogo.visibility = View.VISIBLE
                ivStationLogo.load(File(logoPath)) {
                    crossfade(true)
                }
            } else {
                ivStationLogo.visibility = View.GONE
            }

            container.isSelected = isSelected
        }
    }
}
