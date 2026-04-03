package at.planqton.fytfm.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import at.planqton.fytfm.data.RadioStation
import coil.dispose
import coil.load
import java.io.File

class StationEditorAdapter(
    private val onEdit: (RadioStation) -> Unit,
    private val onFavorite: (RadioStation) -> Unit,
    private val onDelete: (RadioStation) -> Unit,
    private val getLogoPath: ((ps: String?, pi: Int?, frequency: Float) -> String?)? = null
) : RecyclerView.Adapter<StationEditorAdapter.ViewHolder>() {

    private var stations = listOf<RadioStation>()

    fun setStations(newStations: List<RadioStation>) {
        stations = newStations
        notifyDataSetChanged()
    }

    fun getStations(): List<RadioStation> = stations

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_station_editor, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val station = stations[position]
        holder.bind(station)
    }

    override fun getItemCount() = stations.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivLogo: ImageView = itemView.findViewById(R.id.ivStationLogo)
        private val tvFrequency: TextView = itemView.findViewById(R.id.tvFrequency)
        private val tvName: TextView = itemView.findViewById(R.id.tvStationName)
        private val btnEdit: ImageButton = itemView.findViewById(R.id.btnEdit)
        private val btnFavorite: ImageButton = itemView.findViewById(R.id.btnFavorite)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

        fun bind(station: RadioStation) {
            // Frequency/Ensemble display
            tvFrequency.text = when {
                station.isDab -> station.ensembleLabel ?: "DAB"
                station.isAM -> "AM ${station.frequency.toInt()}"
                else -> "FM %.2f".format(station.frequency)
            }

            // Name display
            tvName.text = if (station.name.isNullOrEmpty()) {
                if (station.isDab) station.ensembleLabel ?: "Unbekannt" else "Uncustomised Name"
            } else {
                station.name
            }

            // Station Logo
            val logoPath = getLogoPath?.invoke(station.name, null, station.frequency)
            if (logoPath != null) {
                ivLogo.visibility = View.VISIBLE
                ivLogo.load(File(logoPath)) {
                    crossfade(true)
                }
            } else {
                // Cancel any pending Coil load to prevent stale images
                ivLogo.dispose()
                ivLogo.visibility = View.GONE
            }

            // Favorite icon
            btnFavorite.setImageResource(
                if (station.isFavorite) R.drawable.ic_favorite_filled
                else R.drawable.ic_favorite_border
            )

            // Click listeners
            btnEdit.setOnClickListener { onEdit(station) }
            btnFavorite.setOnClickListener { onFavorite(station) }
            btnDelete.setOnClickListener { onDelete(station) }
        }
    }
}
