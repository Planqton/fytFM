package at.planqton.fytfm.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.ContextCompat
import at.planqton.fytfm.R
import at.planqton.fytfm.data.PresetRepository

/**
 * Liefert die aktuell gültige Akzentfarbe — User-konfiguriert (per
 * App-Design-Settings) ODER, falls keine vorhanden, die Default-Resource
 * `R.color.radio_accent` (die per values/values-night automatisch zur
 * Tag-/Nacht-Variante auflöst).
 *
 * Wird von Code-Stellen aufgerufen, die die Akzentfarbe dynamisch setzen
 * müssen (Banner-Background, Indikatoren) — anstelle von
 * `getColor(R.color.radio_accent)`.
 */
object AccentColors {

    /** True wenn aktuell Night-/Dark-Mode aktiv ist. */
    fun isNightMode(context: Context): Boolean {
        val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMask == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Aktuelle Akzentfarbe als ARGB-Int. Nutzt User-Wert wenn gesetzt,
     * sonst die Standard-Resource `radio_accent` (ist selbst day/night-
     * sensitiv).
     */
    fun current(context: Context, prefs: PresetRepository): Int {
        val customForCurrentTheme = if (isNightMode(context)) {
            prefs.getAccentColorNight()
        } else {
            prefs.getAccentColorDay()
        }
        return if (customForCurrentTheme != 0) customForCurrentTheme
        else ContextCompat.getColor(context, R.color.radio_accent)
    }
}
