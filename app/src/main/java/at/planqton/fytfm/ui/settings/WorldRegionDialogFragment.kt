package at.planqton.fytfm.ui.settings

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import androidx.fragment.app.DialogFragment
import at.planqton.fytfm.R
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.region.WorldArea
import at.planqton.fytfm.data.region.WorldAreas

/**
 * Zwei-Spinner-Dialog für die Region-Auswahl: World Area + Country.
 * Beim Bestätigen werden beide Werte über den `PresetRepository` persistiert
 * und der Caller-Listener aufgerufen, damit die Settings-UI ihre Anzeige
 * aktualisieren kann.
 */
class WorldRegionDialogFragment : DialogFragment() {

    /** Wird vom umschließenden Code (z.B. SettingsDialogFragment) gesetzt. */
    var presetRepository: PresetRepository? = null
    var onApplied: (() -> Unit)? = null

    private lateinit var spinnerArea: Spinner
    private lateinit var spinnerCountry: Spinner
    private lateinit var etSearch: EditText
    /** Aktuell sichtbare (gefilterte) Country-Liste der gewählten World Area. */
    private var visibleCountries: List<String> = emptyList()
    /** Verhindert Endlos-Loops, wenn die Search-Logik die Area-Auswahl
     *  programmatisch wechselt (Listener würde sonst die Suche resetten). */
    private var suppressAreaListener = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        }
    }

    override fun onStart() {
        super.onStart()
        // Erzwingt einen lesbaren Dialog: ~70 % Bildschirmbreite, Höhe nach Inhalt.
        // Sonst sind die Spinner-Texte abgeschnitten (z.B. „We.." / „Aus..").
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.7f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_world_region, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        spinnerArea = view.findViewById(R.id.spinnerWorldArea)
        spinnerCountry = view.findViewById(R.id.spinnerCountry)
        etSearch = view.findViewById(R.id.etCountrySearch)

        val areaLabels = WorldAreas.ALL.map { getString(it.nameRes) }
        spinnerArea.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            areaLabels,
        )

        val currentAreaId = presetRepository?.getWorldAreaId() ?: 0
        val currentCountry = presetRepository?.getCountry() ?: ""
        val currentAreaIdx = WorldAreas.ALL.indexOfFirst { it.id == currentAreaId }
            .takeIf { it >= 0 } ?: 0
        spinnerArea.setSelection(currentAreaIdx)

        // Initialer Country-Adapter — wird auch beim Area-Wechsel neu gebaut.
        applyCountryAdapter(WorldAreas.ALL[currentAreaIdx], currentCountry, "")

        spinnerArea.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (suppressAreaListener) return
                // User hat manuell die Area gewechselt → Suche leeren und
                // volle Country-Liste der neuen Area zeigen.
                etSearch.setText("")
                applyCountryAdapter(WorldAreas.ALL[pos], null, "")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Live-Such-Logik: globale Suche über alle Areas. Wenn der getippte
        // Text in einer anderen Area einen Treffer hat, wird diese Area
        // automatisch ausgewählt; Country-Liste zeigt nur die Treffer.
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty()
                val currentArea = WorldAreas.ALL[spinnerArea.selectedItemPosition]
                if (q.isBlank()) {
                    applyCountryAdapter(currentArea, null, "")
                    return
                }
                // Trifft die aktuelle Area? Dann dort filtern und Area belassen.
                val ql = q.trim().lowercase()
                if (currentArea.countries.any { it.lowercase().contains(ql) }) {
                    applyCountryAdapter(currentArea, null, q)
                    return
                }
                // Sonst: nach erstem Match in einer anderen Area suchen.
                val match = WorldAreas.ALL.firstOrNull { area ->
                    area.countries.any { it.lowercase().contains(ql) }
                }
                if (match != null) {
                    val targetIdx = WorldAreas.ALL.indexOf(match)
                    suppressAreaListener = true
                    spinnerArea.setSelection(targetIdx)
                    suppressAreaListener = false
                    applyCountryAdapter(match, null, q)
                } else {
                    // Kein Treffer überall → leerer Country-Spinner.
                    applyCountryAdapter(currentArea, null, q)
                }
            }
        })

        view.findViewById<Button>(R.id.btnRegionCancel).setOnClickListener { dismiss() }
        view.findViewById<Button>(R.id.btnRegionOk).setOnClickListener {
            val area = WorldAreas.ALL[spinnerArea.selectedItemPosition]
            val pos = spinnerCountry.selectedItemPosition
            // Aus der gefilterten Liste auflösen, fallback auf erste der Area.
            val country = visibleCountries.getOrNull(pos)
                ?: area.countries.firstOrNull()
                ?: ""
            presetRepository?.setWorldAreaId(area.id)
            presetRepository?.setCountry(country)
            onApplied?.invoke()
            dismiss()
        }
    }

    private fun applyCountryAdapter(area: WorldArea, preselectCountry: String?, query: String) {
        val q = query.trim().lowercase()
        visibleCountries = if (q.isEmpty()) area.countries
        else area.countries.filter { it.lowercase().contains(q) }
        // Anzeige-Liste mit Flaggen-Emoji prefix; visibleCountries bleibt
        // die unverfälschte Daten-Quelle für die Persistierung.
        val displayLabels = visibleCountries.map {
            at.planqton.fytfm.data.region.CountryFlags.labelFor(it)
        }
        spinnerCountry.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            displayLabels,
        )
        val idx = if (preselectCountry != null)
            visibleCountries.indexOf(preselectCountry).takeIf { it >= 0 } ?: 0
        else 0
        if (visibleCountries.isNotEmpty()) spinnerCountry.setSelection(idx)
    }

    companion object {
        const val TAG = "WorldRegionDialogFragment"
    }
}
