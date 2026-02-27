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
    private val onStationClick: (Float, Boolean) -> Unit
) : RecyclerView.Adapter<StationCarouselAdapter.ViewHolder>() {

    data class StationItem(
        val frequency: Float,
        val name: String?,
        val logoPath: String?,
        val isAM: Boolean = false
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
            // Clear cover when switching stations
            currentCoverUrl = null
            currentLocalCoverPath = null
            if (oldPosition >= 0) notifyItemChanged(oldPosition)
            if (newPosition >= 0) notifyItemChanged(newPosition)
        }
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
        val freqText = if (station.isAM) {
            station.frequency.toInt().toString()
        } else {
            "%.2f".format(station.frequency).replace(".", ",")
        }
        holder.frequencyText.text = freqText
        holder.bandLabel.text = if (station.isAM) "AM" else "FM"

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

        // For selected item, use Spotify cover if available
        if (isSelected) {
            // Cover image: Spotify URL > Local cached > Radio logo
            val coverSource = when {
                !currentCoverUrl.isNullOrBlank() && currentCoverUrl!!.startsWith("http") -> currentCoverUrl
                !currentLocalCoverPath.isNullOrBlank() -> currentLocalCoverPath
                !station.logoPath.isNullOrBlank() -> station.logoPath
                else -> null
            }

            if (!coverSource.isNullOrBlank()) {
                holder.stationLogo.visibility = View.VISIBLE
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
                holder.stationLogo.visibility = View.GONE
            }
        } else {
            // Non-selected items: show only radio logo
            if (!station.logoPath.isNullOrBlank()) {
                holder.stationLogo.visibility = View.VISIBLE
                holder.stationLogo.load(File(station.logoPath)) {
                    crossfade(true)
                }
            } else {
                holder.stationLogo.visibility = View.GONE
            }
        }

        // Click listener
        holder.itemView.setOnClickListener {
            onStationClick(station.frequency, station.isAM)
        }
    }

    override fun getItemCount(): Int = stations.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val frequencyText: TextView = view.findViewById(R.id.frequencyText)
        val bandLabel: TextView = view.findViewById(R.id.bandLabel)
        val stationName: TextView = view.findViewById(R.id.stationName)
        val stationLogo: ImageView = view.findViewById(R.id.stationLogo)
    }
}
