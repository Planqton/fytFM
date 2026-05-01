package at.planqton.fytfm.ui.editor

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.FrequencyScaleView
import at.planqton.fytfm.R
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.RadioStation
import at.planqton.fytfm.data.logo.RadioLogoRepository
import at.planqton.fytfm.ui.StationEditorAdapter
import coil.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * DialogFragment für den Radio-Editor.
 * Ermöglicht das Bearbeiten, Löschen und Verwalten von Radiosendern.
 */
class RadioEditorDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "RadioEditorDialog"
        private const val ARG_MODE = "mode"

        fun newInstance(mode: FrequencyScaleView.RadioMode): RadioEditorDialogFragment {
            return RadioEditorDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, mode.name)
                }
            }
        }
    }

    interface RadioEditorCallback {
        fun getPresetRepository(): PresetRepository
        fun getRadioLogoRepository(): RadioLogoRepository
        fun getLogoForStation(ps: String?, pi: Int?, freq: Float): String?
        fun getLogoForDabStation(name: String?, serviceId: Int): String?
        fun onStationsUpdated()
        fun showManualLogoSearchDialog(station: RadioStation, mode: FrequencyScaleView.RadioMode, onComplete: () -> Unit)
        fun showEditStationDialog(station: RadioStation, onSave: (String, Boolean) -> Unit)
        fun showEditDabStationDialog(station: RadioStation, onSave: (String) -> Unit)
        fun clearLastSyncedPs(freqKey: Int)
    }

    private var callback: RadioEditorCallback? = null
    private lateinit var targetMode: FrequencyScaleView.RadioMode
    private lateinit var presetRepository: PresetRepository
    private lateinit var radioLogoRepository: RadioLogoRepository

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callback = context as? RadioEditorCallback
            ?: throw IllegalStateException("Activity must implement RadioEditorCallback")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.TransparentDialog)

        val modeName = arguments?.getString(ARG_MODE) ?: FrequencyScaleView.RadioMode.FM.name
        targetMode = FrequencyScaleView.RadioMode.valueOf(modeName)

        callback?.let {
            presetRepository = it.getPresetRepository()
            radioLogoRepository = it.getRadioLogoRepository()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_radio_editor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView(view)
        setupLogoSearchButton(view)
    }

    private fun setupRecyclerView(view: View) {
        val rvStations = view.findViewById<RecyclerView>(R.id.rvStations)
        rvStations.layoutManager = LinearLayoutManager(requireContext())

        val stations = loadStations()

        val adapter = StationEditorAdapter(
            getLogoPath = { ps, pi, freq ->
                callback?.getLogoForStation(ps, pi, freq)
            },
            getLogoPathForDab = { name, serviceId ->
                callback?.getLogoForDabStation(name, serviceId)
            },
            onSearchLogo = { station ->
                callback?.showManualLogoSearchDialog(station, targetMode) {
                    // Refresh dialog after logo selection
                    refreshDialog()
                }
            },
            onEdit = { station ->
                handleEditStation(station, stations)
            },
            onFavorite = { station ->
                handleToggleFavorite(station, stations)
            },
            onDelete = { station ->
                handleDeleteStation(station, stations)
            }
        )
        adapter.setStations(stations)
        rvStations.adapter = adapter
    }

    private fun setupLogoSearchButton(view: View) {
        val btnLogoSearch = view.findViewById<android.widget.Button>(R.id.btnLogoSearch)
        btnLogoSearch?.setOnClickListener {
            dismiss()
            val stations = loadStations()
            showLogoSearchDialog(stations)
        }
    }

    private fun loadStations(): List<RadioStation> {
        return when (targetMode) {
            FrequencyScaleView.RadioMode.FM -> presetRepository.loadFmStations()
            FrequencyScaleView.RadioMode.AM -> presetRepository.loadAmStations()
            FrequencyScaleView.RadioMode.DAB -> presetRepository.loadDabStations()
            FrequencyScaleView.RadioMode.DAB_DEV -> presetRepository.loadDabDevStations()
        }
    }

    private fun saveStations(stations: List<RadioStation>) {
        when (targetMode) {
            FrequencyScaleView.RadioMode.FM -> presetRepository.saveFmStations(stations)
            FrequencyScaleView.RadioMode.AM -> presetRepository.saveAmStations(stations)
            FrequencyScaleView.RadioMode.DAB -> presetRepository.saveDabStations(stations)
            FrequencyScaleView.RadioMode.DAB_DEV -> presetRepository.saveDabDevStations(stations)
        }
        callback?.onStationsUpdated()
    }

    private fun handleEditStation(station: RadioStation, stations: List<RadioStation>) {
        // Beide DAB-Modi gehen über den DAB-Editor — die Persistenz wählt
        // dann je nach targetMode den richtigen SharedPreferences-Slot,
        // damit Demo-Sender niemals in den realen DAB-Speicher fallen.
        if (targetMode == FrequencyScaleView.RadioMode.DAB ||
            targetMode == FrequencyScaleView.RadioMode.DAB_DEV) {
            callback?.showEditDabStationDialog(station) { newName ->
                val updatedStations = stations.map {
                    if (it.serviceId == station.serviceId) it.copy(name = newName) else it
                }
                saveStations(updatedStations)
                refreshDialog()
            }
        } else {
            callback?.showEditStationDialog(station) { newName, syncName ->
                val updatedStations = stations.map {
                    if (it.frequency == station.frequency) it.copy(name = newName, syncName = syncName) else it
                }
                saveStations(updatedStations)

                if (syncName) {
                    val freqKey = (station.frequency * 10).toInt()
                    callback?.clearLastSyncedPs(freqKey)
                }
                refreshDialog()
            }
        }
    }

    private fun handleToggleFavorite(station: RadioStation, stations: List<RadioStation>) {
        val updatedStations = if (targetMode == FrequencyScaleView.RadioMode.DAB) {
            stations.map {
                if (it.serviceId == station.serviceId) it.copy(isFavorite = !it.isFavorite) else it
            }
        } else {
            stations.map {
                if (it.frequency == station.frequency) it.copy(isFavorite = !it.isFavorite) else it
            }
        }
        saveStations(updatedStations)
        refreshDialog()
    }

    private fun handleDeleteStation(station: RadioStation, stations: List<RadioStation>) {
        val stationName = if (targetMode == FrequencyScaleView.RadioMode.DAB) {
            station.name ?: station.ensembleLabel ?: "Unbekannt"
        } else {
            station.getDisplayFrequency()
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_station_title)
            .setMessage(getString(R.string.delete_station_message, stationName))
            .setPositiveButton(R.string.yes) { _, _ ->
                val updatedStations = if (targetMode == FrequencyScaleView.RadioMode.DAB) {
                    stations.filter { it.serviceId != station.serviceId }
                } else {
                    stations.filter { it.frequency != station.frequency }
                }
                saveStations(updatedStations)
                refreshDialog()
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun refreshDialog() {
        dismiss()
        newInstance(targetMode).show(parentFragmentManager, TAG)
    }

    private fun showLogoSearchDialog(stations: List<RadioStation>) {
        val progressDialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.logo_search)
            .setMessage(getString(R.string.searching_logos_for_stations, stations.size))
            .setCancelable(false)
            .create()
        progressDialog.show()

        val logoSearchService = at.planqton.fytfm.data.logo.LogoSearchService()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val results = logoSearchService.searchLogos(stations) { current, total, stationName ->
                    activity?.runOnUiThread {
                        progressDialog.setMessage(getString(R.string.template_progress_format, current, total, stationName))
                    }
                }

                progressDialog.dismiss()

                val foundLogos = results.filter { it.logoUrl != null }

                if (foundLogos.isEmpty()) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.no_logos_found_title)
                        .setMessage(getString(R.string.no_logos_found_for_stations, stations.size))
                        .setPositiveButton(R.string.ok) { _, _ ->
                            refreshDialog()
                        }
                        .show()
                    return@launch
                }

                showLogoSearchResultsDialog(foundLogos, results.size)

            } catch (e: Exception) {
                progressDialog.dismiss()
                android.util.Log.e("RadioEditor", "Logo search failed: ${e.message}", e)
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.error_title)
                    .setMessage(getString(R.string.logo_search_failed, e.message))
                    .setPositiveButton(R.string.ok) { _, _ ->
                        refreshDialog()
                    }
                    .show()
            }
        }
    }

    private fun showLogoSearchResultsDialog(
        foundLogos: List<at.planqton.fytfm.data.logo.LogoSearchService.LogoSearchResult>,
        totalSearched: Int
    ) {
        val remainingLogos = foundLogos.toMutableList()
        var savedCount = 0

        fun updateAndShowDialog() {
            if (remainingLogos.isEmpty()) {
                if (savedCount > 0) {
                    Toast.makeText(requireContext(), resources.getQuantityString(R.plurals.logos_saved_count, savedCount, savedCount), Toast.LENGTH_SHORT).show()
                }
                refreshDialog()
                return
            }

            val container = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(32, 16, 32, 16)
            }

            val summaryText = android.widget.TextView(requireContext()).apply {
                text = getString(R.string.tap_to_apply_remaining, remainingLogos.size, savedCount)
                textSize = 13f
                setTextColor(0xFF666666.toInt())
                setPadding(0, 0, 0, 16)
            }
            container.addView(summaryText)

            val scrollView = android.widget.ScrollView(requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    (400 * resources.displayMetrics.density).toInt()
                )
            }

            val resultsList = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }

            for (result in remainingLogos.toList()) {
                val itemLayout = createResultItemView(result)
                resultsList.addView(itemLayout)

                val divider = View(requireContext()).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(0xFFEEEEEE.toInt())
                }
                resultsList.addView(divider)
            }

            scrollView.addView(resultsList)
            container.addView(scrollView)

            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(R.string.logo_search_results)
                .setView(container)
                .setNegativeButton(R.string.done) { dlg, _ ->
                    dlg.dismiss()
                    if (savedCount > 0) {
                        Toast.makeText(requireContext(), resources.getQuantityString(R.plurals.logos_saved_count, savedCount, savedCount), Toast.LENGTH_SHORT).show()
                    }
                    refreshDialog()
                }
                .setOnCancelListener {
                    if (savedCount > 0) {
                        Toast.makeText(requireContext(), resources.getQuantityString(R.plurals.logos_saved_count, savedCount, savedCount), Toast.LENGTH_SHORT).show()
                    }
                    refreshDialog()
                }
                .create()

            var itemIndex = 0
            for (i in 0 until resultsList.childCount step 2) {
                val itemLayout = resultsList.getChildAt(i)
                val resultIndex = itemIndex
                if (resultIndex < remainingLogos.size) {
                    val result = remainingLogos[resultIndex]
                    itemLayout.setOnClickListener {
                        saveSingleLogo(result)
                        savedCount++
                        remainingLogos.remove(result)
                        dialog.dismiss()
                        updateAndShowDialog()
                    }
                }
                itemIndex++
            }

            dialog.show()
        }

        updateAndShowDialog()
    }

    private fun createResultItemView(result: at.planqton.fytfm.data.logo.LogoSearchService.LogoSearchResult): android.widget.LinearLayout {
        val itemLayout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(16, 12, 16, 12)
            gravity = android.view.Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = requireContext().getDrawable(android.R.drawable.list_selector_background)
        }

        val density = resources.displayMetrics.density
        val imageView = android.widget.ImageView(requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (56 * density).toInt(),
                (56 * density).toInt()
            ).apply {
                marginEnd = (16 * density).toInt()
            }
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFEEEEEE.toInt())
        }
        imageView.load(result.logoUrl) {
            crossfade(true)
            placeholder(R.drawable.ic_cover_placeholder)
            error(R.drawable.ic_cover_placeholder)
        }
        itemLayout.addView(imageView)

        val infoLayout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameText = android.widget.TextView(requireContext()).apply {
            text = result.stationName
            textSize = 16f
            setTextColor(0xFF333333.toInt())
        }
        infoLayout.addView(nameText)

        val sourceText = android.widget.TextView(requireContext()).apply {
            text = result.source
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }
        infoLayout.addView(sourceText)

        itemLayout.addView(infoLayout)

        val arrowIcon = android.widget.ImageView(requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (24 * density).toInt(),
                (24 * density).toInt()
            )
            setImageResource(android.R.drawable.ic_input_add)
            setColorFilter(0xFF4CAF50.toInt())
        }
        itemLayout.addView(arrowIcon)

        return itemLayout
    }

    private fun saveSingleLogo(result: at.planqton.fytfm.data.logo.LogoSearchService.LogoSearchResult) {
        val templateName = "Auto-Search-${targetMode.name}"
        val logoUrl = result.logoUrl ?: return
        val station = result.station

        var template = radioLogoRepository.getTemplates().find { it.name == templateName }
        val existingStations = template?.stations?.toMutableList() ?: mutableListOf()

        val stationLogo = if (station.isDab) {
            at.planqton.fytfm.data.logo.StationLogo(
                ps = station.name,
                logoUrl = logoUrl
            )
        } else {
            at.planqton.fytfm.data.logo.StationLogo(
                ps = station.name,
                frequencies = listOf(station.frequency),
                logoUrl = logoUrl
            )
        }

        existingStations.removeAll { existing ->
            if (station.isDab) {
                existing.ps == station.name
            } else {
                existing.frequencies?.any { Math.abs(it - station.frequency) < 0.05f } == true
            }
        }

        existingStations.add(stationLogo)

        val newTemplate = at.planqton.fytfm.data.logo.RadioLogoTemplate(
            name = templateName,
            area = 2,
            stations = existingStations
        )
        radioLogoRepository.saveTemplate(newTemplate)

        if (radioLogoRepository.getActiveTemplateName() == null) {
            radioLogoRepository.setActiveTemplate(templateName)
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val (updatedTemplate, _) = radioLogoRepository.downloadLogos(newTemplate) { _, _ -> }
                radioLogoRepository.saveTemplate(updatedTemplate)
            } catch (e: Exception) {
                android.util.Log.e("RadioEditor", "Failed to download logo: ${e.message}", e)
            }
        }
    }

    override fun onDetach() {
        super.onDetach()
        callback = null
    }
}
