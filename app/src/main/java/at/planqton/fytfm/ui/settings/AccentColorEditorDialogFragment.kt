package at.planqton.fytfm.ui.settings

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.fragment.app.DialogFragment
import at.planqton.fytfm.R
import at.planqton.fytfm.data.PresetRepository

/**
 * App-Design / Akzentfarbe-Editor.
 *
 * Tab-Toggle zwischen Day- und Night-Mode. Pro Tab ein Grid mit Color-
 * Presets (rund, anklickbar). Reset-Button setzt beide Modi auf Default
 * (= 0 in prefs, was den Resource-Default `radio_accent` aus
 * values/values-night nutzt).
 *
 * Aktuell-aktiver Wert wird als äußerer Ring um das Swatch markiert,
 * damit der User auf einen Blick sieht welche Farbe gerade gilt.
 */
class AccentColorEditorDialogFragment : DialogFragment() {

    interface Callback {
        fun getPresetRepository(): PresetRepository
        fun onAccentColorChanged()
    }

    private var callback: Callback? = null
    private var editingNight = false

    /**
     * Preset-Liste — pro Slot ein Pair aus Day-Variante und Night-Variante.
     * Beim Tap auf ein Slot wird je nach `editingNight` der entsprechende
     * Wert gespeichert.
     */
    private data class Preset(val nameRes: Int, val day: Int, val night: Int)

    private val presets = listOf(
        Preset(R.string.accent_color_red,    0xFFC52322.toInt(), 0xFFFF5252.toInt()),
        Preset(R.string.accent_color_blue,   0xFF1976D2.toInt(), 0xFF64B5F6.toInt()),
        Preset(R.string.accent_color_green,  0xFF388E3C.toInt(), 0xFF81C784.toInt()),
        Preset(R.string.accent_color_orange, 0xFFEF6C00.toInt(), 0xFFFFB74D.toInt()),
        Preset(R.string.accent_color_purple, 0xFF7B1FA2.toInt(), 0xFFBA68C8.toInt()),
        Preset(R.string.accent_color_pink,   0xFFC2185B.toInt(), 0xFFF06292.toInt()),
        Preset(R.string.accent_color_teal,   0xFF00796B.toInt(), 0xFF4DB6AC.toInt()),
    )

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callback = context as? Callback
    }

    override fun onDetach() {
        super.onDetach()
        callback = null
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_accent_color_editor, null)
        val dialog = AlertDialog.Builder(requireContext(), R.style.TransparentDialog)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val tabDay = view.findViewById<Button>(R.id.tabDayMode)
        val tabNight = view.findViewById<Button>(R.id.tabNightMode)
        val grid = view.findViewById<LinearLayout>(R.id.colorPresetGrid)
        val btnReset = view.findViewById<Button>(R.id.btnAccentReset)
        val btnClose = view.findViewById<Button>(R.id.btnAccentClose)

        fun rebuildGrid() {
            grid.removeAllViews()
            val prefs = callback?.getPresetRepository() ?: return
            val current = if (editingNight) prefs.getAccentColorNight() else prefs.getAccentColorDay()
            val cols = 4
            val rows = (presets.size + cols - 1) / cols
            val swatchSize = dp(48)
            val swatchMargin = dp(8)
            for (row in 0 until rows) {
                val rowLayout = LinearLayout(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    orientation = LinearLayout.HORIZONTAL
                }
                for (col in 0 until cols) {
                    val idx = row * cols + col
                    if (idx >= presets.size) break
                    val preset = presets[idx]
                    val color = if (editingNight) preset.night else preset.day
                    val view = View(requireContext()).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(color)
                            setStroke(
                                if (current == color) dp(3) else dp(1),
                                if (current == color) 0xFFFFFFFF.toInt() else 0x80000000.toInt()
                            )
                        }
                        contentDescription = getString(preset.nameRes)
                        setOnClickListener {
                            if (editingNight) prefs.setAccentColorNight(color)
                            else prefs.setAccentColorDay(color)
                            callback?.onAccentColorChanged()
                            rebuildGrid()
                        }
                    }
                    val lp = LinearLayout.LayoutParams(swatchSize, swatchSize).apply {
                        setMargins(swatchMargin, swatchMargin, swatchMargin, swatchMargin)
                    }
                    rowLayout.addView(view, lp)
                }
                grid.addView(rowLayout)
            }
        }

        fun selectTab(night: Boolean) {
            editingNight = night
            tabDay.alpha = if (night) 0.5f else 1.0f
            tabNight.alpha = if (night) 1.0f else 0.5f
            rebuildGrid()
        }

        tabDay.setOnClickListener { selectTab(false) }
        tabNight.setOnClickListener { selectTab(true) }

        btnReset.setOnClickListener {
            callback?.getPresetRepository()?.resetAccentColors()
            callback?.onAccentColorChanged()
            rebuildGrid()
        }
        btnClose.setOnClickListener { dismiss() }

        // Default-Tab: Day
        selectTab(false)

        return dialog
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    companion object {
        const val TAG = "AccentColorEditor"
        fun newInstance(): AccentColorEditorDialogFragment = AccentColorEditorDialogFragment()
    }
}
