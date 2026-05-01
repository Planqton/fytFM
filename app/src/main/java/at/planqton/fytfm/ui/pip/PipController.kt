package at.planqton.fytfm.ui.pip

import at.planqton.fytfm.R
import at.planqton.fytfm.databinding.PipLayoutBinding
import at.planqton.fytfm.deezer.TrackInfo
import at.planqton.fytfm.ui.helper.loadCoverOrFallback

/**
 * Owns the picture-in-picture layout's content: the three control buttons
 * (prev / next / play-pause) and the per-tick content updates (station title,
 * raw RT, artist+title, cover). Does NOT manage PiP-vs-normal mode toggling —
 * that continues to live in MainActivity, because it cascades across ~14
 * non-PiP views (main toolbar, debug overlays, etc.) that are out of scope.
 *
 * All external dependencies come in through lambdas so the controller has no
 * implicit coupling to an Activity / ViewModel / Handler.
 */
class PipController(
    private val pipBinding: PipLayoutBinding,
    private val frequencyStep: () -> Float,
    private val frequencyGetter: () -> Float,
    private val frequencySetter: (Float) -> Unit,
    private val onPlayPauseClicked: () -> Unit,
) {
    /** Wire the prev / next / play-pause click listeners. Call once after setup. */
    fun setupButtons() {
        pipBinding.pipBtnPrev.setOnClickListener {
            frequencySetter(frequencyGetter() - frequencyStep())
        }
        pipBinding.pipBtnNext.setOnClickListener {
            frequencySetter(frequencyGetter() + frequencyStep())
        }
        pipBinding.pipBtnPlayPause.setOnClickListener {
            onPlayPauseClicked()
        }
    }

    /**
     * Refresh the PiP content. Caller should only invoke while actually in PiP mode
     * (to avoid the small overhead when the layout isn't visible).
     */
    fun update(ps: String?, frequency: Float, rawRt: String?, trackInfo: TrackInfo?) {
        pipBinding.pipTitle.text = if (!ps.isNullOrBlank()) {
            ps
        } else {
            "FM ${String.format("%.2f", frequency)}"
        }
        pipBinding.pipRawRt.text = rawRt ?: ""

        if (trackInfo != null) {
            pipBinding.pipArtist.text = "${trackInfo.artist} - ${trackInfo.title}"
            // Only local paths — HTTP URLs aren't displayed in PiP.
            val localCover = trackInfo.coverUrl?.takeIf { it.startsWith("/") }
            pipBinding.pipCoverImage.loadCoverOrFallback(localCover, R.drawable.ic_cover_placeholder)
        } else {
            pipBinding.pipArtist.text = ""
            pipBinding.pipCoverImage.setImageResource(R.drawable.ic_cover_placeholder)
        }
    }
}
