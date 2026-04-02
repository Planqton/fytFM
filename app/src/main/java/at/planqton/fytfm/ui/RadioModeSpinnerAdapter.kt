package at.planqton.fytfm.ui

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import at.planqton.fytfm.R

/**
 * Custom Spinner Adapter für Radio-Modi mit Verfügbarkeitsprüfung.
 * Nicht verfügbare Modi werden ausgegraut und sind nicht auswählbar.
 */
class RadioModeSpinnerAdapter(
    context: Context,
    private val modes: Array<String>
) : ArrayAdapter<String>(context, R.layout.item_radio_mode_spinner, modes) {

    // Verfügbarkeit für jeden Modus (FM=0, AM=1, DAB=2)
    private val availability = booleanArrayOf(true, true, true)

    fun setModeAvailable(position: Int, available: Boolean) {
        if (position in availability.indices) {
            availability[position] = available
        }
    }

    fun isModeAvailable(position: Int): Boolean {
        return position in availability.indices && availability[position]
    }

    /**
     * Findet den ersten verfügbaren Modus.
     * @return Position des ersten verfügbaren Modus, oder 0 (FM) als Fallback
     */
    fun getFirstAvailableMode(): Int {
        for (i in availability.indices) {
            if (availability[i]) return i
        }
        return 0 // Fallback zu FM
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        // Hauptansicht: nutze Standard-Layout, keine Änderungen
        return view
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getDropDownView(position, convertView, parent)

        val textView = view as? TextView ?: view.findViewById(android.R.id.text1)

        val isAvailable = availability[position]
        if (textView != null) {
            if (isAvailable) {
                textView.alpha = 1.0f
                view.isEnabled = true
            } else {
                textView.alpha = 0.4f
                view.isEnabled = false
            }
        }

        return view
    }

    override fun isEnabled(position: Int): Boolean {
        return availability[position]
    }

    override fun areAllItemsEnabled(): Boolean {
        return availability.all { it }
    }
}
