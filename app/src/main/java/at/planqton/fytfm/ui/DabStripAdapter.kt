package at.planqton.fytfm.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import at.planqton.fytfm.data.RadioStation
import coil.dispose
import coil.load
import java.io.File

/**
 * Adapter for the horizontal DAB station strip in DAB List Mode.
 */
class DabStripAdapter(
    private val onStationClick: (RadioStation) -> Unit,
    private val getLogoPath: ((name: String?, serviceId: Int) -> String?)? = null
) : RecyclerView.Adapter<DabStripAdapter.ViewHolder>() {

    private var stations: List<RadioStation> = emptyList()
    private var selectedServiceId: Int = 0

    fun setStations(newStations: List<RadioStation>) {
        stations = newStations
        notifyDataSetChanged()
    }

    fun updateFavoriteStatus(serviceId: Int, isFavorite: Boolean) {
        val position = stations.indexOfFirst { it.serviceId == serviceId }
        if (position >= 0) {
            // Update the station in our list
            stations = stations.toMutableList().also {
                it[position] = it[position].copy(isFavorite = isFavorite)
            }
            // Force immediate refresh
            notifyDataSetChanged()
        }
    }

    fun setSelectedStation(serviceId: Int) {
        val oldPosition = stations.indexOfFirst { it.serviceId == selectedServiceId }
        val newPosition = stations.indexOfFirst { it.serviceId == serviceId }
        selectedServiceId = serviceId
        if (oldPosition >= 0) notifyItemChanged(oldPosition)
        if (newPosition >= 0) notifyItemChanged(newPosition)
    }

    fun getPositionForServiceId(serviceId: Int): Int {
        return stations.indexOfFirst { it.serviceId == serviceId }
    }

    fun getStationAtPosition(position: Int): RadioStation? {
        return stations.getOrNull(position)
    }

    override fun getItemCount(): Int = stations.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_dab_strip_station, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val station = stations[position]
        val isSelected = station.serviceId == selectedServiceId

        holder.stationName.text = station.name ?: "Unknown"
        holder.ensemble.text = station.ensembleLabel ?: ""

        // Update card background based on selection
        val cardView = holder.itemView as? CardView
        val bgColor = if (isSelected) {
            ContextCompat.getColor(holder.itemView.context, R.color.radio_accent)
        } else {
            ContextCompat.getColor(holder.itemView.context, R.color.card_background)
        }
        cardView?.setCardBackgroundColor(bgColor)

        // Update text color based on selection
        val textColor = if (isSelected) {
            ContextCompat.getColor(holder.itemView.context, R.color.radio_background)
        } else {
            ContextCompat.getColor(holder.itemView.context, R.color.radio_text_primary)
        }
        val secondaryColor = if (isSelected) {
            ContextCompat.getColor(holder.itemView.context, R.color.radio_background)
        } else {
            ContextCompat.getColor(holder.itemView.context, R.color.radio_text_secondary)
        }
        holder.stationName.setTextColor(textColor)
        holder.ensemble.setTextColor(secondaryColor)

        // Show favorite indicator
        holder.favoriteIcon.visibility = if (station.isFavorite) View.VISIBLE else View.GONE

        // Load station logo if available
        val logoPath = getLogoPath?.invoke(station.name, station.serviceId)
        if (logoPath != null) {
            holder.stationLogo.visibility = View.VISIBLE
            holder.stationLogo.load(File(logoPath)) {
                crossfade(true)
            }
        } else {
            holder.stationLogo.dispose()
            holder.stationLogo.visibility = View.GONE
        }

        holder.itemView.setOnClickListener {
            onStationClick(station)
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val stationName: TextView = view.findViewById(R.id.stripStationName)
        val ensemble: TextView = view.findViewById(R.id.stripEnsemble)
        val favoriteIcon: ImageView = view.findViewById(R.id.stripFavoriteIcon)
        val stationLogo: ImageView = view.findViewById(R.id.stripStationLogo)
    }
}
