package at.planqton.fytfm.ui.helper

import android.content.res.ColorStateList
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.widget.ImageViewCompat
import coil.load
import java.io.File

/**
 * Klippt eine View auf abgerundete Ecken via Outline-Provider. Gilt für
 * den gesamten View-Inhalt (geladenes Bild + Hintergrund + Tint), nicht nur
 * für die Background-Drawable. Idempotent: einmal pro View aufrufen reicht.
 */
fun View.applyRoundedCorners(radiusDp: Float) {
    val px = radiusDp * resources.displayMetrics.density
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, px)
        }
    }
    clipToOutline = true
}

/**
 * Loads a local cover image into the ImageView with a crossfade.
 * No-op when [path] is null or blank — caller is responsible for rendering
 * a fallback in that case.
 */
fun ImageView.loadCover(
    path: String?,
    @DrawableRes fallbackRes: Int? = null,
) {
    if (path.isNullOrBlank()) return
    load(File(path)) {
        crossfade(true)
        fallbackRes?.let {
            placeholder(it)
            error(it)
        }
    }
}

/**
 * Loads a local cover image or, when [path] is blank, sets [fallbackRes]
 * as a static image.
 *
 * scaleType wird zur Laufzeit umgeschaltet: für echte Cover (square JPG/PNG)
 * `centerCrop` damit das Bild den Rahmen füllt; für die Placeholder-Vektor-
 * Grafiken (3:2-Aspect mit fytFM/FM-Branding) `fitCenter` damit das ganze
 * Logo proportional sichtbar ist und nicht zur Hälfte angeschnitten wird.
 *
 * [placeholderTint] (optional) tintiert den Fallback-Vektor mit der
 * Akzentfarbe. Bei echtem Cover wird der Tint wieder gelöscht.
 */
fun ImageView.loadCoverOrFallback(
    path: String?,
    @DrawableRes fallbackRes: Int,
    @Suppress("UNUSED_PARAMETER")
    @ColorInt placeholderTint: Int? = null,
) {
    if (path.isNullOrBlank()) {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(fallbackRes)
        // Theme-konformer Look: Hintergrund = UI-Background-Color
        // (`radio_background` — hell im Day-Mode, dunkel im Night-Mode);
        // Logo-Pfade = `radio_text_primary` (dunkel im Day, hell im Night).
        // Damit fügt sich die Placeholder-Kachel kontrastarm in den Rest
        // des UI ein und folgt automatisch dem Day/Night-Theme.
        val ctx = context
        setBackgroundColor(
            androidx.core.content.ContextCompat.getColor(ctx, at.planqton.fytfm.R.color.radio_background)
        )
        ImageViewCompat.setImageTintList(
            this,
            ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(ctx, at.planqton.fytfm.R.color.radio_text_primary)
            )
        )
    } else {
        scaleType = ImageView.ScaleType.CENTER_CROP
        // Echtes Cover-Bild — Tint und farbiger Hintergrund weg, sonst
        // würde das JPG eingefärbt bzw. die ungenutzten Ränder gefärbt.
        ImageViewCompat.setImageTintList(this, null)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        load(File(path)) {
            crossfade(true)
            placeholder(fallbackRes)
            error(fallbackRes)
        }
    }
}
