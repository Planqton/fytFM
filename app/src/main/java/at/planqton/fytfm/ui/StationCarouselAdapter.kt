package at.planqton.fytfm.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import coil.load
import java.io.File

/**
 * Adapter for the station carousel in image view mode.
 */
class StationCarouselAdapter(
    private val onStationClick: (StationCarouselAdapter.StationItem) -> Unit
) : RecyclerView.Adapter<StationCarouselAdapter.ViewHolder>() {

    data class StationItem(
        val frequency: Float,
        val name: String?,
        val logoPath: String?,
        val isAM: Boolean = false,
        val isDab: Boolean = false,
        val serviceId: Int = 0,
        val ensembleId: Int = 0
    )

    private var stations: List<StationItem> = emptyList()
    private var selectedPosition: Int = -1
    private var currentFrequency: Float = 0f

    // Current cover for the selected station (Spotify/local)
    private var currentCoverUrl: String? = null
    private var currentLocalCoverPath: String? = null

    fun setStations(newStations: List<StationItem>) {
        stations = newStations
        notifyDataSetChanged()
    }

    fun setCurrentFrequency(frequency: Float, isAM: Boolean) {
        currentFrequency = frequency
        val newPosition = stations.indexOfFirst {
            it.frequency == frequency && it.isAM == isAM
        }
        if (newPosition != selectedPosition) {
            val oldPosition = selectedPosition
            selectedPosition = newPosition
            currentCoverUrl = null
            currentLocalCoverPath = null
            if (oldPosition >= 0) notifyItemChanged(oldPosition)
            if (newPosition >= 0) notifyItemChanged(newPosition)
        }
    }

    fun setCurrentDabService(serviceId: Int) {
        val newPosition = stations.indexOfFirst {
            it.isDab && it.serviceId == serviceId
        }
        if (newPosition != selectedPosition) {
            val oldPosition = selectedPosition
            selectedPosition = newPosition
            currentCoverUrl = null
            currentLocalCoverPath = null
            if (oldPosition >= 0) notifyItemChanged(oldPosition)
            if (newPosition >= 0) notifyItemChanged(newPosition)
        }
    }

    fun getPositionForDabService(serviceId: Int): Int {
        return stations.indexOfFirst { it.isDab && it.serviceId == serviceId }
    }

    /**
     * Update cover image for the currently selected station
     */
    fun updateCurrentCover(coverUrl: String?, localCoverPath: String?) {
        currentCoverUrl = coverUrl
        currentLocalCoverPath = localCoverPath
        if (selectedPosition >= 0) {
            notifyItemChanged(selectedPosition)
        }
    }

    fun getPositionForFrequency(frequency: Float, isAM: Boolean): Int {
        return stations.indexOfFirst { it.frequency == frequency && it.isAM == isAM }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_station_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val station = stations[position]
        val isSelected = position == selectedPosition

        // Format frequency
        val freqText = when {
            station.isDab -> station.name ?: "DAB+"
            station.isAM -> station.frequency.toInt().toString()
            else -> "%.2f".format(station.frequency).replace(".", ",")
        }
        holder.frequencyText.text = freqText
        holder.bandLabel.text = when {
            station.isDab -> "DAB+"
            station.isAM -> "AM"
            else -> "FM"
        }

        // Station name inline (next to FM/AM label)
        holder.stationNameInline.text = if (!station.name.isNullOrBlank()) " ${station.name}" else ""

        // Card scaling for selected item
        val scale = if (isSelected) 1.0f else 0.85f
        holder.itemView.scaleX = scale
        holder.itemView.scaleY = scale
        holder.itemView.alpha = if (isSelected) 1.0f else 0.7f

        // Station name (PS)
        if (!station.name.isNullOrBlank()) {
            holder.stationName.text = station.name
            holder.stationName.visibility = View.VISIBLE
        } else {
            holder.stationName.visibility = View.GONE
        }

        // Logo immer sichtbar - mit FM/AM/DAB Platzhalter falls kein Logo
        holder.stationLogo.visibility = View.VISIBLE
        val placeholder = when {
            station.isDab -> R.drawable.placeholder_fm  // TODO: placeholder_dab
            station.isAM -> R.drawable.placeholder_am
            else -> R.drawable.placeholder_fm
        }

        // For selected item, use Spotify cover if available
        if (isSelected) {
            // Cover image: Spotify URL > Local cached > Radio logo > Placeholder
            val coverSource = when {
                !currentCoverUrl.isNullOrBlank() && currentCoverUrl!!.startsWith("http") -> currentCoverUrl
                !currentLocalCoverPath.isNullOrBlank() -> currentLocalCoverPath
                !station.logoPath.isNullOrBlank() -> station.logoPath
                else -> null
            }

            if (!coverSource.isNullOrBlank()) {
                if (coverSource.startsWith("http")) {
                    holder.stationLogo.load(coverSource) {
                        crossfade(true)
                    }
                } else {
                    holder.stationLogo.load(File(coverSource)) {
                        crossfade(true)
                    }
                }
            } else {
                holder.stationLogo.setImageResource(placeholder)
            }
        } else {
            // Non-selected items: show radio logo or placeholder
            if (!station.logoPath.isNullOrBlank()) {
                holder.stationLogo.load(File(station.logoPath)) {
                    crossfade(true)
                }
            } else {
                holder.stationLogo.setImageResource(placeholder)
            }
        }

        // Click listener
        holder.itemView.setOnClickListener {
            onStationClick(station)
        }
    }

    override fun getItemCount(): Int = stations.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val frequencyText: TextView = view.findViewById(R.id.frequencyText)
        val bandLabel: TextView = view.findViewById(R.id.bandLabel)
        val stationName: TextView = view.findViewById(R.id.stationName)
        val stationNameInline: TextView = view.findViewById(R.id.stationNameInline)
        val stationLogo: ImageView = view.findViewById(R.id.stationLogo)
    }
}
