package at.planqton.fytfm.data.logo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import coil.load
import java.io.File

class StationLogoAdapter(
    private var stations: MutableList<StationLogo>,
    private val onEdit: (Int, StationLogo) -> Unit,
    private val onDelete: (Int, StationLogo) -> Unit
) : RecyclerView.Adapter<StationLogoAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgLogoPreview: ImageView = view.findViewById(R.id.imgLogoPreview)
        val textStationIdentifier: TextView = view.findViewById(R.id.textStationIdentifier)
        val textStationDetails: TextView = view.findViewById(R.id.textStationDetails)
        val btnEditStation: ImageButton = view.findViewById(R.id.btnEditStation)
        val btnDeleteStation: ImageButton = view.findViewById(R.id.btnDeleteStation)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_station_logo, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val station = stations[position]

        // Format frequencies list (use Locale.US to ensure decimal point)
        fun formatFrequencies(freqs: List<Float>?): String? {
            if (freqs.isNullOrEmpty()) return null
            return freqs.joinToString(", ") { "%.1f".format(java.util.Locale.US, it) } + " MHz"
        }

        // Build identifier string (PS > PI > Frequency)
        val identifier = when {
            !station.ps.isNullOrBlank() -> station.ps
            !station.pi.isNullOrBlank() -> "PI: ${station.pi}"
            !station.frequencies.isNullOrEmpty() -> formatFrequencies(station.frequencies)
            else -> "Unbekannt"
        }
        holder.textStationIdentifier.text = identifier

        // Build details string
        val details = mutableListOf<String>()
        if (!station.ps.isNullOrBlank() && !station.frequencies.isNullOrEmpty()) {
            details.add(formatFrequencies(station.frequencies)!!)
        }
        if (!station.pi.isNullOrBlank() && station.ps.isNullOrBlank()) {
            // PI already shown as identifier
        } else if (!station.pi.isNullOrBlank()) {
            details.add("PI: ${station.pi}")
        }
        holder.textStationDetails.text = if (details.isEmpty()) station.logoUrl else details.joinToString(" | ")

        // Load logo preview
        if (!station.localPath.isNullOrBlank() && File(station.localPath!!).exists()) {
            holder.imgLogoPreview.load(File(station.localPath!!)) {
                crossfade(true)
            }
        } else {
            holder.imgLogoPreview.load(station.logoUrl) {
                crossfade(true)
            }
        }

        holder.btnEditStation.setOnClickListener {
            onEdit(holder.adapterPosition, station)
        }

        holder.btnDeleteStation.setOnClickListener {
            onDelete(holder.adapterPosition, station)
        }

        holder.itemView.setOnClickListener {
            onEdit(holder.adapterPosition, station)
        }
    }

    override fun getItemCount() = stations.size

    fun getStations(): List<StationLogo> = stations.toList()

    fun addStation(station: StationLogo) {
        stations.add(station)
        notifyItemInserted(stations.size - 1)
    }

    fun updateStation(position: Int, station: StationLogo) {
        if (position in stations.indices) {
            stations[position] = station
            notifyItemChanged(position)
        }
    }

    fun removeStation(position: Int) {
        if (position in stations.indices) {
            stations.removeAt(position)
            notifyItemRemoved(position)
        }
    }
}
