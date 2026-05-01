package at.planqton.fytfm.ui.cover

import android.graphics.Bitmap
import android.widget.ImageView
import androidx.annotation.DrawableRes
import at.planqton.fytfm.R
import at.planqton.fytfm.ui.helper.loadCover
import at.planqton.fytfm.ui.helper.loadCoverOrFallback

/**
 * Groups the three cover [ImageView]s that always display the same content
 * (now-playing bar, carousel variant, DAB-list variant) behind a single
 * dispatcher. Replaces the ~24 sites of three-line "mirror to all three"
 * repetition in MainActivity.
 *
 * The DAB-list cover view is optional and resolved lazily because the
 * DAB-list layout is inflated on demand — the provider lambda lets this class
 * stay alive across `setupDabListMode()` rebuilds without re-wiring.
 *
 * When the DAB list wants a different placeholder drawable than the primary
 * cover views (it uses the DAB+ logo instead of a generic radio icon), the
 * `DAB`-specific helpers below split the drawable automatically.
 */
class CoverViewTrio(
    private val nowPlaying: ImageView,
    private val carousel: ImageView,
    private val dabListProvider: () -> ImageView?,
) {
    private val dabList: ImageView?
        get() = dabListProvider()

    /** Push the same bitmap to all three views (e.g. incoming DAB slideshow). */
    fun setBitmap(bitmap: Bitmap) {
        nowPlaying.setImageBitmap(bitmap)
        carousel.setImageBitmap(bitmap)
        dabList?.setImageBitmap(bitmap)
    }

    /**
     * Set the default-FM placeholder on the two cover views and the DAB+-logo
     * on the DAB list (that view has its own contextual placeholder).
     */
    fun setDabIdlePlaceholder() {
        nowPlaying.setImageResource(R.drawable.ic_cover_placeholder)
        carousel.setImageResource(R.drawable.ic_cover_placeholder)
        dabList?.setImageResource(R.drawable.ic_fytfm_dab_plus_light)
    }

    /** Set the same drawable on all three — simple case for uniform fallbacks. */
    fun setPlaceholder(@DrawableRes drawable: Int) {
        nowPlaying.setImageResource(drawable)
        carousel.setImageResource(drawable)
        dabList?.setImageResource(drawable)
    }

    /** Load [path] with crossfade on all three (no fallback). */
    fun loadFromPath(path: String?) {
        nowPlaying.loadCover(path)
        carousel.loadCover(path)
        dabList?.loadCover(path)
    }

    /**
     * Load [path] on all three; when [path] is blank, each view gets its own
     * fallback — cover views use [coversFallback], the DAB list uses
     * [dabListFallback] (defaults to DAB+ logo).
     */
    fun loadFromPathOrFallback(
        path: String?,
        @DrawableRes coversFallback: Int,
        @DrawableRes dabListFallback: Int = R.drawable.ic_fytfm_dab_plus_light,
    ) {
        nowPlaying.loadCoverOrFallback(path, coversFallback)
        carousel.loadCoverOrFallback(path, coversFallback)
        dabList?.loadCoverOrFallback(path, dabListFallback)
    }

    // ---- FM/AM helpers: update the two cover views only, NEVER the DAB list ----
    // The DAB list has its own contextual drawables (DAB+ logo fallback) that would
    // be semantically wrong in an FM/AM context; these methods make the intent
    // explicit instead of relying on "dabList happens to be null right now".

    fun setPlaceholderForCovers(@DrawableRes drawable: Int) {
        nowPlaying.setImageResource(drawable)
        carousel.setImageResource(drawable)
    }

    fun loadForCovers(path: String?, @DrawableRes fallback: Int) {
        nowPlaying.loadCoverOrFallback(path, fallback)
        carousel.loadCoverOrFallback(path, fallback)
    }
}
