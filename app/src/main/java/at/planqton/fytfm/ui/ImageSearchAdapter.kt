package at.planqton.fytfm.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import coil.load

data class ImageResult(
    val url: String,
    val name: String
)

class ImageSearchAdapter(
    private val onImageClick: (ImageResult) -> Unit
) : RecyclerView.Adapter<ImageSearchAdapter.ImageViewHolder>() {

    private var images: List<ImageResult> = emptyList()

    fun setImages(newImages: List<ImageResult>) {
        images = newImages
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_image_result, parent, false)
        return ImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val image = images[position]
        holder.bind(image)
        holder.itemView.setOnClickListener {
            onImageClick(image)
        }
    }

    override fun getItemCount(): Int = images.size

    class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imgResult: ImageView = itemView.findViewById(R.id.imgResult)

        fun bind(image: ImageResult) {
            imgResult.load(image.url) {
                crossfade(true)
                placeholder(android.R.color.darker_gray)
                error(android.R.color.holo_red_light)
            }
        }
    }
}
