package at.planqton.fytfm.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import at.planqton.fytfm.data.RadioStation
import coil.dispose
import coil.load
import java.io.File

class StationAdapter(
    private val onStationClick: (RadioStation) -> Unit,
    private val onStationLongClick: ((RadioStation) -> Unit)? = null,
    private val getLogoPath: ((ps: String?, pi: Int?, frequency: Float) -> String?)? = null
) : RecyclerView.Adapter<StationAdapter.StationViewHolder>() {

    private var stations: List<RadioStation> = emptyList()
    private var selectedPosition: Int = -1
    private var overrideClickListener: ((RadioStation) -> Unit)? = null
    /** 0 = use the static `station_tile_background` drawable (legacy red).
     *  Otherwise: build the selected-state stroke in code with this colour. */
    private var accentColorOverride: Int = 0

    fun setOnStationClickListener(listener: ((RadioStation) -> Unit)?) {
        overrideClickListener = listener
    }

    fun setAccentColor(color: Int) {
        if (accentColorOverride == color) return
        accentColorOverride = color
        notifyDataSetChanged()
    }

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

    fun setSelectedDabService(serviceId: Int) {
        val newPosition = stations.indexOfFirst {
            it.isDab && it.serviceId == serviceId
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
        val isSelected = position == selectedPosition
        holder.bind(station, isSelected, logoPath)

        // Override the state-list selector's red stroke with the dynamic
        // accent colour. station_tile_background.xml hardcodes
        // @color/radio_accent (the legacy red); rebuilding the drawable in
        // code lets the user-picked accent flow through.
        val container = holder.itemView.findViewById<LinearLayout>(R.id.stationContainer)
        if (accentColorOverride != 0) {
            container.background = buildTileBackground(holder.itemView.context, isSelected)
        }

        holder.itemView.setOnClickListener {
            (overrideClickListener ?: onStationClick)(station)
        }
        holder.itemView.setOnLongClickListener {
            onStationLongClick?.invoke(station)
            true
        }
    }

    private fun buildTileBackground(context: android.content.Context, selected: Boolean): GradientDrawable {
        val density = context.resources.displayMetrics.density
        val cornerRadius = 12f * density
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
        }
        if (selected) {
            drawable.setStroke((2 * density).toInt(), accentColorOverride)
            drawable.setColor(ContextCompat.getColor(context, R.color.card_background))
        } else {
            drawable.setStroke(
                (1 * density).toInt(),
                ContextCompat.getColor(context, R.color.radio_scale_line)
            )
            drawable.setColor(android.graphics.Color.TRANSPARENT)
        }
        return drawable
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
                // Cancel any pending Coil load to prevent stale images
                ivStationLogo.dispose()
                ivStationLogo.visibility = View.GONE
            }

            container.isSelected = isSelected
        }
    }
}
