package at.planqton.fytfm.spotify

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import java.io.File

class CachedTrackAdapter(
    private val onTrackClick: ((TrackInfo) -> Unit)? = null
) : RecyclerView.Adapter<CachedTrackAdapter.TrackViewHolder>() {

    private var tracks: List<TrackInfo> = emptyList()
    private var filteredTracks: List<TrackInfo> = emptyList()

    fun setTracks(newTracks: List<TrackInfo>) {
        tracks = newTracks
        filteredTracks = newTracks
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        filteredTracks = if (query.isBlank()) {
            tracks
        } else {
            val q = query.lowercase()
            tracks.filter {
                it.artist.lowercase().contains(q) ||
                it.title.lowercase().contains(q) ||
                (it.album?.lowercase()?.contains(q) == true)
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cached_track, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        holder.bind(filteredTracks[position])
    }

    override fun getItemCount(): Int = filteredTracks.size

    inner class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivCover: ImageView = itemView.findViewById(R.id.ivTrackCover)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTrackTitle)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvTrackArtist)
        private val tvAlbum: TextView = itemView.findViewById(R.id.tvTrackAlbum)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvTrackDuration)

        fun bind(track: TrackInfo) {
            tvTitle.text = track.title
            tvArtist.text = track.artist
            tvAlbum.text = track.album ?: ""
            tvAlbum.visibility = if (track.album.isNullOrBlank()) View.GONE else View.VISIBLE

            // Duration
            if (track.durationMs > 0) {
                val seconds = (track.durationMs / 1000) % 60
                val minutes = (track.durationMs / 1000) / 60
                tvDuration.text = String.format("%d:%02d", minutes, seconds)
                tvDuration.visibility = View.VISIBLE
            } else {
                tvDuration.visibility = View.GONE
            }

            // Cover image
            val coverPath = track.coverUrl
            if (coverPath != null && coverPath.startsWith("/")) {
                // Local file path
                val file = File(coverPath)
                if (file.exists()) {
                    val bitmap = BitmapFactory.decodeFile(coverPath)
                    ivCover.setImageBitmap(bitmap)
                } else {
                    ivCover.setImageResource(android.R.drawable.ic_menu_gallery)
                }
            } else {
                ivCover.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            itemView.setOnClickListener {
                onTrackClick?.invoke(track)
            }
        }
    }
}
