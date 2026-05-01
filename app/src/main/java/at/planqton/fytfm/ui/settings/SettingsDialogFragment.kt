package at.planqton.fytfm.ui.settings

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.core.content.ContextCompat
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import at.planqton.fytfm.FrequencyScaleView
import at.planqton.fytfm.R
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.UpdateRepository
import at.planqton.fytfm.viewmodel.SettingsUiState
import at.planqton.fytfm.viewmodel.SettingsViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * DialogFragment für Settings.
 * Verwendet SettingsViewModel für State-Management.
 */
class SettingsDialogFragment : DialogFragment() {

    private lateinit var viewModel: SettingsViewModel

    // Callback interface for actions that need MainActivity
    interface SettingsCallback {
        fun onRadioEditorRequested(mode: FrequencyScaleView.RadioMode)
        fun onLogoTemplateRequested(onDismiss: () -> Unit)
        fun onLanguageDialogRequested(onSelected: (String) -> Unit)
        fun onNowPlayingAnimationRequested(onSelected: (Int) -> Unit)
        fun onCorrectionsViewerRequested()
        fun onRdsRetentionDialogRequested(onSelected: (Int) -> Unit)
        fun onRadioAreaDialogRequested(onSelected: (Int) -> Unit)
        fun onDeezerCacheDialogRequested()
        fun onBugReportRequested()
        fun onCloseAppRequested()
        fun onDarkModeChanged(mode: Int)
        fun onDebugVisibilityChanged(show: Boolean)
        fun onLocalModeChanged(enabled: Boolean)
        fun onMonoModeChanged(enabled: Boolean)
        fun onDabVisualizerToggled(enabled: Boolean)
        fun onDabVisualizerStyleChanged(style: Int)
        fun onRecordingPathRequested()
        fun onRadioModeSpinnerNeedsUpdate()
        fun onStationChangeToastToggled(enabled: Boolean)
        fun onStationListNeedsRefresh()
        fun getActiveLogoTemplateName(): String?
        fun getLogoTemplateCount(name: String): Int
        fun getRadioAreaName(area: Int): String
        fun getCurrentRadioMode(): FrequencyScaleView.RadioMode
        fun onDabCoverDisplayNeedsUpdate()
        fun playTickSound()
        fun isRdsLoggingEnabled(): Boolean
        fun setRdsLoggingEnabled(enabled: Boolean)
        fun getRdsRetentionDays(): Int
        fun clearRdsArchive()
        fun getDeezerCacheCount(): Int
        fun clearDeezerCache()
        fun onRecordingPathCallbackSet(callback: (String?) -> Unit)
        fun getPresetRepository(): PresetRepository
        fun getUpdateRepository(): UpdateRepository
        fun onAccentColorEditorRequested()
        /** Wird gefeuert wenn der FM-Deezer-Master-Toggle geändert wurde,
         *  damit der btnDeezerToggle in der Hauptansicht synchron bleibt. */
        fun onDeezerEnabledFmChanged(enabled: Boolean)
    }

    private var callback: SettingsCallback? = null
    private lateinit var dialogView: View

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callback = context as? SettingsCallback
        // Initialize ViewModel with repositories from callback
        callback?.let {
            viewModel = SettingsViewModel(it.getPresetRepository(), it.getUpdateRepository())
        }
    }

    override fun onDetach() {
        super.onDetach()
        callback = null
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)

        val dialog = AlertDialog.Builder(requireContext(), R.style.TransparentDialog)
            .setView(dialogView)
            .create()

        setupSearch()
        setupFmSettings()
        setupAmSettings()
        setupDabSettings()
        setupGeneralSettings()
        setupAppDesignSettings()
        setupRadioLogosSettings()
        setupRdsArchiveSettings()
        setupDeezerCacheSettings()
        setupAppSettings()
        setupOtherSettings()
        applyAccentToSwitches()

        observeViewModel()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialog.setOnShowListener {
            // Dialog breiter machen (70% der Bildschirmbreite)
            val targetWidth = (resources.displayMetrics.widthPixels * 0.7).toInt()
            dialog.window?.let { window ->
                val params = window.attributes
                params.width = targetWidth
                params.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
                window.attributes = params
            }
        }

        return dialog
    }

    private fun observeViewModel() {
        // DialogFragment has no inflated view in onCreateDialog(), so
        // viewLifecycleOwner is unavailable. Use the Fragment lifecycle.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest { state ->
                    updateUiFromState(state)
                }
            }
        }
    }

    private fun updateUiFromState(state: SettingsUiState) {
        // Note: Most UI elements are bound in setup methods with listeners
        // This method is for reactive updates if needed
    }

    /**
     * Walks the dialog view tree and tints every [SwitchCompat]'s thumb +
     * track with the user's current accent colour, replacing the legacy red
     * `colorAccent` baked into the AppCompat theme. Called once after all
     * setup methods so it covers freshly-inflated switches.
     */
    private fun applyAccentToSwitches() {
        val ctx = requireContext()
        val accent = at.planqton.fytfm.ui.theme.AccentColors.current(
            ctx,
            callback?.getPresetRepository() ?: return
        )
        // Material switches expose two drawables: thumb (the round knob) and
        // track (the pill behind it). Both default to the theme's
        // colorAccent when checked. We tint with a state-aware list so the
        // unchecked state stays grey.
        val thumbTint = android.content.res.ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked),
            ),
            intArrayOf(
                accent,
                ContextCompat.getColor(ctx, R.color.radio_text_secondary),
            )
        )
        val trackTint = android.content.res.ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked),
            ),
            intArrayOf(
                (accent and 0x00FFFFFF) or 0x66000000,
                ContextCompat.getColor(ctx, R.color.radio_scale_line),
            )
        )
        forEachSwitch(dialogView) { sw ->
            sw.thumbTintList = thumbTint
            sw.trackTintList = trackTint
        }
    }

    private fun forEachSwitch(view: View, action: (SwitchCompat) -> Unit) {
        if (view is SwitchCompat) action(view)
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) forEachSwitch(view.getChildAt(i), action)
        }
    }

    // ========== SEARCH ==========
    private fun setupSearch() {
        val searchSettings = dialogView.findViewById<EditText>(R.id.searchSettings)
        val btnClearSearch = dialogView.findViewById<ImageView>(R.id.btnClearSearch)
        val textNoSearchResults = dialogView.findViewById<TextView>(R.id.textNoSearchResults)
        val contentContainer = dialogView.findViewById<LinearLayout>(R.id.settingsContentContainer)

        // Helper function to find the row container (direct child of contentContainer)
        fun findRowContainer(view: View?): View? {
            if (view == null || contentContainer == null) return null
            var current: View? = view
            while (current != null && current.parent != contentContainer) {
                current = current.parent as? View
            }
            return current
        }

        // Define searchable items with their keywords (German and English)
        data class SearchableItem(val viewId: Int, val keywords: List<String>)
        val searchableItems = listOf(
            // FM Section
            SearchableItem(R.id.itemRadioEditorFm, listOf("fm", "radio", "editor", "sender", "bearbeiten", "edit", "stations")),
            SearchableItem(R.id.switchLocalMode, listOf("loc", "local", "lokal", "signal", "empfang")),
            SearchableItem(R.id.switchMonoMode, listOf("mono", "noise", "rauschen", "stereo", "audio")),
            SearchableItem(R.id.itemRadioArea, listOf("area", "region", "gebiet", "europa", "usa", "japan")),
            SearchableItem(R.id.switchAutoScanSensitivity, listOf("scan", "sensitivity", "empfindlichkeit", "auto")),
            SearchableItem(R.id.switchDeezerFm, listOf("deezer", "cover", "fm", "artwork", "album")),
            SearchableItem(R.id.switchSignalIconFm, listOf("signal", "rssi", "balken", "bars", "empfang", "stärke", "fm")),
            // AM Section
            SearchableItem(R.id.itemRadioEditorAm, listOf("am", "radio", "editor", "sender", "bearbeiten", "edit", "stations")),
            SearchableItem(R.id.switchSignalIconAm, listOf("signal", "rssi", "balken", "bars", "empfang", "stärke", "am")),
            // DAB Section
            SearchableItem(R.id.itemRadioEditorDab, listOf("dab", "radio", "editor", "sender", "bearbeiten", "edit", "stations")),
            SearchableItem(R.id.switchDeezerDab, listOf("deezer", "cover", "dab", "artwork", "album")),
            SearchableItem(R.id.switchDabVisualizer, listOf("visualizer", "audio", "spektrum", "anzeige", "dab")),
            SearchableItem(R.id.itemDabVisualizerStyle, listOf("visualizer", "style", "stil", "bars", "wave", "dab")),
            SearchableItem(R.id.itemDabRecordingPath, listOf("recording", "aufnahme", "pfad", "path", "speichern", "dab")),
            // General Section
            SearchableItem(R.id.switchAutoplayAtStartup, listOf("autoplay", "startup", "start", "automatisch", "einschalten")),
            SearchableItem(R.id.switchShowDebug, listOf("debug", "info", "entwickler", "developer", "rds")),
            SearchableItem(R.id.switchAllowRootFallback, listOf("root", "su", "fallback", "uis7870", "dudu7", "sqlfmservice", "binder")),
            SearchableItem(R.id.darkModeRow, listOf("dark", "mode", "dunkel", "hell", "theme", "design", "nacht")),
            SearchableItem(R.id.switchAutoStart, listOf("autostart", "boot", "system", "start")),
            SearchableItem(R.id.switchAutoBackground, listOf("background", "hintergrund", "auto", "minimieren")),
            SearchableItem(R.id.switchStationChangeToast, listOf("toast", "overlay", "popup", "sender", "wechsel", "anzeige")),
            SearchableItem(R.id.switchTickSound, listOf("tick", "sound", "ton", "klick", "audio")),
            SearchableItem(R.id.switchRevertPrevNext, listOf("revert", "swap", "tauschen", "prev", "next", "vor", "zurück")),
            // Radio Logos Section
            SearchableItem(R.id.itemLogoTemplate, listOf("logo", "template", "vorlage", "bild", "sender")),
            SearchableItem(R.id.switchShowLogosInFavorites, listOf("logo", "favoriten", "favorites", "anzeigen", "show")),
            // App Section
            SearchableItem(R.id.itemAppVersion, listOf("version", "update", "app", "aktualisieren")),
            SearchableItem(R.id.itemLanguage, listOf("language", "sprache", "deutsch", "english")),
            SearchableItem(R.id.itemNowPlayingAnimation, listOf("animation", "now playing", "aktuell", "slide", "fade")),
            SearchableItem(R.id.switchCorrectionHelpers, listOf("correction", "korrektur", "helper", "hilfe", "rt", "radiotext")),
            SearchableItem(R.id.itemViewCorrections, listOf("correction", "korrektur", "rules", "regeln", "anzeigen")),
            // RDS Archive Section
            SearchableItem(R.id.switchRdsLogging, listOf("rds", "logging", "archiv", "speichern", "log")),
            SearchableItem(R.id.itemRdsRetention, listOf("retention", "aufbewahrung", "tage", "days", "rds")),
            SearchableItem(R.id.itemClearArchive, listOf("clear", "löschen", "archiv", "delete", "rds")),
            // Deezer Section
            SearchableItem(R.id.switchDeezerCache, listOf("deezer", "cache", "speicher", "lokal")),
            SearchableItem(R.id.btnViewDeezerCache, listOf("deezer", "cache", "anzeigen", "view")),
            SearchableItem(R.id.btnClearDeezerCache, listOf("deezer", "cache", "löschen", "clear", "delete")),
            // Other
            SearchableItem(R.id.itemBugReport, listOf("bug", "report", "fehler", "melden", "log")),
            SearchableItem(R.id.btnCloseApp, listOf("close", "beenden", "schließen", "app", "exit"))
        )

        // Store original visibility states
        val originalVisibility = mutableMapOf<View, Int>()
        contentContainer?.let { container ->
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                originalVisibility[child] = child.visibility
            }
        }

        fun filterSettings(query: String) {
            val trimmedQuery = query.trim().lowercase()

            if (trimmedQuery.isEmpty()) {
                // Restore all items to original state
                originalVisibility.forEach { (view, visibility) ->
                    view.visibility = visibility
                }
                textNoSearchResults.visibility = View.GONE
                btnClearSearch.visibility = View.GONE
                return
            }

            btnClearSearch.visibility = View.VISIBLE

            // First: hide all items in content container
            contentContainer?.let { container ->
                for (i in 0 until container.childCount) {
                    container.getChildAt(i).visibility = View.GONE
                }
            }

            // Find and show matching items
            var matchCount = 0
            for (item in searchableItems) {
                val view = dialogView.findViewById<View>(item.viewId)
                val matches = item.keywords.any { it.contains(trimmedQuery) }
                if (matches) {
                    matchCount++
                    // Find and show the row container
                    val rowContainer = findRowContainer(view)
                    rowContainer?.visibility = View.VISIBLE
                }
            }

            textNoSearchResults.visibility = if (matchCount == 0) View.VISIBLE else View.GONE
        }

        searchSettings.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterSettings(s?.toString() ?: "")
            }
        })

        btnClearSearch.setOnClickListener {
            searchSettings.text.clear()
        }
    }

    // ========== FM SETTINGS ==========
    private fun setupFmSettings() {
        // FM Radio Editor click
        dialogView.findViewById<View>(R.id.itemRadioEditorFm).setOnClickListener {
            dismiss()
            callback?.onRadioEditorRequested(FrequencyScaleView.RadioMode.FM)
        }

        // LOC Local Mode toggle
        val switchLocalMode = dialogView.findViewById<SwitchCompat>(R.id.switchLocalMode)
        switchLocalMode.isChecked = viewModel.state.value.isLocalMode
        switchLocalMode.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setLocalMode(isChecked)
            callback?.onLocalModeChanged(isChecked)
        }

        // Mono Mode toggle
        val switchMonoMode = dialogView.findViewById<SwitchCompat>(R.id.switchMonoMode)
        switchMonoMode.isChecked = viewModel.state.value.isMonoMode
        switchMonoMode.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setMonoMode(isChecked)
            callback?.onMonoModeChanged(isChecked)
        }

        // Radio Area item
        val textRadioAreaValue = dialogView.findViewById<TextView>(R.id.textRadioAreaValue)
        updateRadioAreaText(textRadioAreaValue)
        dialogView.findViewById<View>(R.id.itemRadioArea).setOnClickListener {
            callback?.onRadioAreaDialogRequested { area ->
                viewModel.setRadioArea(area)
                updateRadioAreaText(textRadioAreaValue)
            }
        }

        // Auto Scan Sensitivity toggle
        val switchAutoScanSensitivity = dialogView.findViewById<SwitchCompat>(R.id.switchAutoScanSensitivity)
        switchAutoScanSensitivity.isChecked = viewModel.state.value.isAutoScanSensitivity
        switchAutoScanSensitivity.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoScanSensitivity(isChecked)
        }

        // Deezer für FM toggle
        val switchDeezerFm = dialogView.findViewById<SwitchCompat>(R.id.switchDeezerFm)
        switchDeezerFm.isChecked = viewModel.state.value.isDeezerEnabledFm
        switchDeezerFm.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setDeezerEnabledFm(isChecked)
            callback?.onDeezerEnabledFmChanged(isChecked)
        }

        val switchSignalIconFm = dialogView.findViewById<SwitchCompat>(R.id.switchSignalIconFm)
        switchSignalIconFm.isChecked = viewModel.state.value.isSignalIconEnabledFm
        switchSignalIconFm.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setSignalIconEnabledFm(isChecked)
        }

        // FM Step Size SeekBar — progress 0..9 → 0.05..0.50 MHz in 0.05-Schritten.
        val seekBarFmStep = dialogView.findViewById<SeekBar>(R.id.seekBarFmStep)
        val textFmStepValue = dialogView.findViewById<TextView>(R.id.textFmStepValue)
        val prefs = callback?.getPresetRepository()
        val initialFmStep = prefs?.getFmFrequencyStep() ?: 0.1f
        seekBarFmStep.progress = ((initialFmStep / 0.05f).toInt() - 1).coerceIn(0, 9)
        textFmStepValue.text = "%.2f MHz".format(initialFmStep)
        seekBarFmStep.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val step = (progress + 1) * 0.05f
                textFmStepValue.text = "%.2f MHz".format(step)
                if (fromUser) prefs?.setFmFrequencyStep(step)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateRadioAreaText(textView: TextView) {
        val areaName = callback?.getRadioAreaName(viewModel.state.value.radioArea) ?: ""
        val templateName = callback?.getActiveLogoTemplateName()
        textView.text = if (templateName != null) {
            "$areaName / $templateName"
        } else {
            areaName
        }
    }

    // ========== AM SETTINGS ==========
    private fun setupAmSettings() {
        dialogView.findViewById<View>(R.id.itemRadioEditorAm).setOnClickListener {
            dismiss()
            callback?.onRadioEditorRequested(FrequencyScaleView.RadioMode.AM)
        }

        val switchSignalIconAm = dialogView.findViewById<SwitchCompat>(R.id.switchSignalIconAm)
        switchSignalIconAm.isChecked = viewModel.state.value.isSignalIconEnabledAm
        switchSignalIconAm.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setSignalIconEnabledAm(isChecked)
        }

        // AM Step Size SeekBar — progress 0..99 → 1..100 kHz in 1-kHz-Schritten.
        val seekBarAmStep = dialogView.findViewById<SeekBar>(R.id.seekBarAmStep)
        val textAmStepValue = dialogView.findViewById<TextView>(R.id.textAmStepValue)
        val prefs = callback?.getPresetRepository()
        val initialAmStep = prefs?.getAmFrequencyStep() ?: 9f
        seekBarAmStep.progress = (initialAmStep.toInt() - 1).coerceIn(0, 99)
        textAmStepValue.text = "%d kHz".format(initialAmStep.toInt())
        seekBarAmStep.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val step = (progress + 1).toFloat()
                textAmStepValue.text = "%d kHz".format(step.toInt())
                if (fromUser) prefs?.setAmFrequencyStep(step)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ========== DAB SETTINGS ==========
    private fun setupDabSettings() {
        // DAB Radio Editor click
        dialogView.findViewById<View>(R.id.itemRadioEditorDab).setOnClickListener {
            dismiss()
            callback?.onRadioEditorRequested(FrequencyScaleView.RadioMode.DAB)
        }

        // Deezer für DAB toggle
        val switchDeezerDab = dialogView.findViewById<SwitchCompat>(R.id.switchDeezerDab)
        switchDeezerDab.isChecked = viewModel.state.value.isDeezerEnabledDab
        switchDeezerDab.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setDeezerEnabledDab(isChecked)
            val mode = callback?.getCurrentRadioMode()
            if (mode == FrequencyScaleView.RadioMode.DAB ||
                mode == FrequencyScaleView.RadioMode.DAB_DEV) {
                callback?.onDabCoverDisplayNeedsUpdate()
            }
        }

        // DAB Visualizer toggle
        val switchDabVisualizer = dialogView.findViewById<SwitchCompat>(R.id.switchDabVisualizer)
        switchDabVisualizer.isChecked = viewModel.state.value.isDabVisualizerEnabled
        switchDabVisualizer.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setDabVisualizerEnabled(isChecked)
            callback?.onDabVisualizerToggled(isChecked)
        }

        // DAB Visualizer Style selector
        val visualizerStyleOptions = resources.getStringArray(R.array.visualizer_styles)
        val textVisualizerStyleValue = dialogView.findViewById<TextView>(R.id.textDabVisualizerStyleValue)
        val currentStyleIndex = viewModel.state.value.dabVisualizerStyle.coerceIn(0, visualizerStyleOptions.size - 1)
        textVisualizerStyleValue.text = visualizerStyleOptions[currentStyleIndex]

        dialogView.findViewById<View>(R.id.itemDabVisualizerStyle).setOnClickListener {
            val currentSelection = viewModel.state.value.dabVisualizerStyle.coerceIn(0, visualizerStyleOptions.size - 1)
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.visualizer_style_title)
                .setSingleChoiceItems(visualizerStyleOptions, currentSelection) { styleDialog, which ->
                    viewModel.setDabVisualizerStyle(which)
                    textVisualizerStyleValue.text = visualizerStyleOptions[which]
                    callback?.onDabVisualizerStyleChanged(which)
                    styleDialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // DAB Recording Path selector
        val textRecordingPathValue = dialogView.findViewById<TextView>(R.id.textDabRecordingPathValue)
        updateRecordingPathText(textRecordingPathValue)

        dialogView.findViewById<View>(R.id.itemDabRecordingPath).setOnClickListener {
            callback?.onRecordingPathCallbackSet { path ->
                viewModel.setDabRecordingPath(path)
                updateRecordingPathText(textRecordingPathValue)
            }
            callback?.onRecordingPathRequested()
        }

        // DAB Dev Mode toggle
        val switchDabDevMode = dialogView.findViewById<SwitchCompat>(R.id.switchDabDevMode)
        switchDabDevMode?.isChecked = viewModel.state.value.isDabDevModeEnabled
        switchDabDevMode?.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setDabDevModeEnabled(isChecked)
            callback?.onRadioModeSpinnerNeedsUpdate()
        }
    }

    private fun updateRecordingPathText(textView: TextView) {
        val currentRecordingPath = viewModel.state.value.dabRecordingPath
        textView.text = if (currentRecordingPath != null) {
            try {
                val uri = android.net.Uri.parse(currentRecordingPath)
                uri.lastPathSegment?.substringAfterLast(':') ?: getString(R.string.configured)
            } catch (e: Exception) {
                getString(R.string.configured)
            }
        } else {
            getString(R.string.not_configured)
        }
    }

    // ========== GENERAL SETTINGS ==========
    private fun setupGeneralSettings() {
        // Autoplay at startup toggle
        val switchAutoplay = dialogView.findViewById<SwitchCompat>(R.id.switchAutoplayAtStartup)
        switchAutoplay.isChecked = viewModel.state.value.isAutoplayAtStartup
        switchAutoplay.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoplayAtStartup(isChecked)
        }

        // Show debug infos toggle
        val switchDebug = dialogView.findViewById<SwitchCompat>(R.id.switchShowDebug)
        switchDebug.isChecked = viewModel.state.value.isShowDebugInfos
        switchDebug.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setShowDebugInfos(isChecked)
            callback?.onDebugVisibilityChanged(isChecked)
        }

        // Allow Root-Fallback toggle (default off; only relevant for headunits
        // where the binder to sqlfmservice is blocked, e.g. UIS7870/DUDU7).
        val switchAllowRootFallback = dialogView.findViewById<SwitchCompat>(R.id.switchAllowRootFallback)
        switchAllowRootFallback.isChecked = viewModel.state.value.isAllowRootFallback
        switchAllowRootFallback.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAllowRootFallback(isChecked)
        }

        // Dark Mode setting
        val darkModeOptions = resources.getStringArray(R.array.dark_mode_options)
        val tvDarkModeValue = dialogView.findViewById<TextView>(R.id.tvDarkModeValue)
        tvDarkModeValue.text = darkModeOptions[viewModel.state.value.darkModePreference]

        val darkModeRow = dialogView.findViewById<LinearLayout>(R.id.darkModeRow)
        darkModeRow.setOnClickListener {
            val currentSelection = viewModel.state.value.darkModePreference
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.dark_mode)
                .setSingleChoiceItems(darkModeOptions, currentSelection) { dlg, which ->
                    viewModel.setDarkModePreference(which)
                    tvDarkModeValue.text = darkModeOptions[which]
                    callback?.onDarkModeChanged(which)
                    dlg.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // Autostart bei Boot toggle
        val switchAutoStart = dialogView.findViewById<SwitchCompat>(R.id.switchAutoStart)
        switchAutoStart.isChecked = viewModel.state.value.isAutoStartEnabled
        switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoStartEnabled(isChecked)
        }

        // Auto-Background toggle
        val switchAutoBackground = dialogView.findViewById<SwitchCompat>(R.id.switchAutoBackground)
        val layoutAutoBackgroundOptions = dialogView.findViewById<LinearLayout>(R.id.layoutAutoBackgroundOptions)
        val seekBarDelay = dialogView.findViewById<SeekBar>(R.id.seekBarAutoBackgroundDelay)
        val textDelayValue = dialogView.findViewById<TextView>(R.id.textAutoBackgroundDelayValue)
        val switchOnlyOnBoot = dialogView.findViewById<SwitchCompat>(R.id.switchAutoBackgroundOnlyOnBoot)

        // Initial state
        switchAutoBackground.isChecked = viewModel.state.value.isAutoBackgroundEnabled
        layoutAutoBackgroundOptions.visibility = if (switchAutoBackground.isChecked) View.VISIBLE else View.GONE
        val currentDelay = viewModel.state.value.autoBackgroundDelay
        seekBarDelay.progress = currentDelay
        textDelayValue.text = getString(R.string.seconds_format_short, currentDelay)
        switchOnlyOnBoot.isChecked = viewModel.state.value.isAutoBackgroundOnlyOnBoot

        // Listeners
        switchAutoBackground.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoBackgroundEnabled(isChecked)
            layoutAutoBackgroundOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        seekBarDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = if (progress < 1) 1 else progress
                textDelayValue.text = getString(R.string.seconds_format_short, value)
                if (fromUser) {
                    viewModel.setAutoBackgroundDelay(value)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        switchOnlyOnBoot.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoBackgroundOnlyOnBoot(isChecked)
        }

        // Station Change Toast toggle
        val switchStationChangeToast = dialogView.findViewById<SwitchCompat>(R.id.switchStationChangeToast)
        switchStationChangeToast.isChecked = viewModel.state.value.isShowStationChangeToast
        switchStationChangeToast.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setShowStationChangeToast(isChecked)
            callback?.onStationChangeToastToggled(isChecked)
        }

        // Tick Sound toggle
        val switchTickSound = dialogView.findViewById<SwitchCompat>(R.id.switchTickSound)
        val layoutTickSoundVolume = dialogView.findViewById<LinearLayout>(R.id.layoutTickSoundVolume)
        val seekBarTickSoundVolume = dialogView.findViewById<SeekBar>(R.id.seekBarTickSoundVolume)
        val textTickSoundVolumeValue = dialogView.findViewById<TextView>(R.id.textTickSoundVolumeValue)

        switchTickSound.isChecked = viewModel.state.value.isTickSoundEnabled
        layoutTickSoundVolume.visibility = if (viewModel.state.value.isTickSoundEnabled) View.VISIBLE else View.GONE
        seekBarTickSoundVolume.progress = viewModel.state.value.tickSoundVolume
        textTickSoundVolumeValue.text = getString(R.string.percent_format, viewModel.state.value.tickSoundVolume)

        switchTickSound.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setTickSoundEnabled(isChecked)
            layoutTickSoundVolume.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                callback?.playTickSound()
            }
        }

        seekBarTickSoundVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                textTickSoundVolumeValue.text = getString(R.string.percent_format, progress)
                if (fromUser) {
                    viewModel.setTickSoundVolume(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                callback?.playTickSound()
            }
        })

        // Revert Prev/Next toggle
        val switchRevertPrevNext = dialogView.findViewById<SwitchCompat>(R.id.switchRevertPrevNext)
        switchRevertPrevNext.isChecked = viewModel.state.value.isRevertPrevNext
        switchRevertPrevNext.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setRevertPrevNext(isChecked)
        }
    }

    // ========== APP DESIGN SETTINGS ==========
    private fun setupAppDesignSettings() {
        // Accent-Colors-Item öffnet den separaten Editor.
        dialogView.findViewById<View>(R.id.itemAccentColors).setOnClickListener {
            callback?.onAccentColorEditorRequested()
        }
        refreshAccentPreviewSwatches()
    }

    /**
     * Setzt die zwei kleinen Preview-Swatches (Tag + Nacht) in der
     * Accent-Colors-Row passend zum aktuell gewählten Wert. Wird bei jeder
     * Änderung im Editor aufgerufen.
     */
    fun refreshAccentPreviewSwatches() {
        val prefs = callback?.getPresetRepository() ?: return
        val daySwatch = dialogView.findViewById<View>(R.id.accentColorPreviewDay)
        val nightSwatch = dialogView.findViewById<View>(R.id.accentColorPreviewNight)
        val dayColor = prefs.getAccentColorDay().takeIf { it != 0 }
            ?: 0xFFC52322.toInt()  // values/colors.xml radio_accent default
        val nightColor = prefs.getAccentColorNight().takeIf { it != 0 }
            ?: 0xFFFF5252.toInt()  // values-night/colors.xml radio_accent default
        (daySwatch.background as? android.graphics.drawable.GradientDrawable)?.setColor(dayColor)
        (nightSwatch.background as? android.graphics.drawable.GradientDrawable)?.setColor(nightColor)
    }

    // ========== RADIO LOGOS SETTINGS ==========
    private fun setupRadioLogosSettings() {
        // Logo Template item
        val textLogoTemplateValue = dialogView.findViewById<TextView>(R.id.textLogoTemplateValue)
        updateLogoTemplateText(textLogoTemplateValue)
        dialogView.findViewById<View>(R.id.itemLogoTemplate).setOnClickListener {
            callback?.onLogoTemplateRequested { updateLogoTemplateText(textLogoTemplateValue) }
        }

        // Show Logos in Favorites toggle
        val switchShowLogosInFavorites = dialogView.findViewById<SwitchCompat>(R.id.switchShowLogosInFavorites)
        switchShowLogosInFavorites.isChecked = viewModel.state.value.isShowLogosInFavorites
        switchShowLogosInFavorites.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setShowLogosInFavorites(isChecked)
            callback?.onStationListNeedsRefresh()
        }
    }

    private fun updateLogoTemplateText(textView: TextView) {
        val activeTemplate = callback?.getActiveLogoTemplateName()
        textView.text = if (activeTemplate != null) {
            val count = callback?.getLogoTemplateCount(activeTemplate) ?: 0
            "$activeTemplate (${getString(R.string.template_station_count, count)})"
        } else {
            getString(R.string.no_template)
        }
    }

    // ========== RDS ARCHIVE SETTINGS ==========
    private fun setupRdsArchiveSettings() {
        // RDS Logging toggle
        val switchRdsLogging = dialogView.findViewById<SwitchCompat>(R.id.switchRdsLogging)
        switchRdsLogging.isChecked = callback?.isRdsLoggingEnabled() ?: false
        switchRdsLogging.setOnCheckedChangeListener { _, isChecked ->
            callback?.setRdsLoggingEnabled(isChecked)
        }

        // RDS Retention item
        val textRdsRetentionValue = dialogView.findViewById<TextView>(R.id.textRdsRetentionValue)
        val retentionDays = callback?.getRdsRetentionDays() ?: 30
        textRdsRetentionValue.text = getString(R.string.days_format, retentionDays)
        dialogView.findViewById<View>(R.id.itemRdsRetention).setOnClickListener {
            callback?.onRdsRetentionDialogRequested { days ->
                textRdsRetentionValue.text = getString(R.string.days_format, days)
            }
        }

        // Clear Archive button
        dialogView.findViewById<View>(R.id.itemClearArchive).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.clear_archive_title)
                .setMessage(R.string.clear_archive_message)
                .setPositiveButton(R.string.delete) { _, _ ->
                    callback?.clearRdsArchive()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // ========== DEEZER CACHE SETTINGS ==========
    private fun setupDeezerCacheSettings() {
        // Deezer Cache toggle
        val switchDeezerCache = dialogView.findViewById<SwitchCompat>(R.id.switchDeezerCache)
        switchDeezerCache.isChecked = viewModel.state.value.isDeezerCacheEnabled
        switchDeezerCache.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setDeezerCacheEnabled(isChecked)
        }

        // View Deezer Cache button
        dialogView.findViewById<View>(R.id.btnViewDeezerCache).setOnClickListener {
            callback?.onDeezerCacheDialogRequested()
        }

        // Clear Deezer Cache button
        dialogView.findViewById<View>(R.id.btnClearDeezerCache).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.cache_delete_title)
                .setMessage(R.string.cache_delete_message)
                .setPositiveButton(R.string.delete) { _, _ ->
                    callback?.clearDeezerCache()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // ========== APP SETTINGS ==========
    private fun setupAppSettings() {
        // App Version item
        val textVersionValue = dialogView.findViewById<TextView>(R.id.textVersionValue)
        textVersionValue?.text = viewModel.state.value.currentVersion
        dialogView.findViewById<View>(R.id.itemAppVersion).setOnClickListener {
            viewModel.checkForUpdates()
        }

        // Language item
        val textLanguageValue = dialogView.findViewById<TextView>(R.id.textLanguageValue)
        // TODO: Update language text based on current setting
        dialogView.findViewById<View>(R.id.itemLanguage).setOnClickListener {
            callback?.onLanguageDialogRequested { language ->
                // Update UI if needed
            }
        }

        // Now Playing Animation item
        val animationOptions = resources.getStringArray(R.array.now_playing_animation_options)
        val textNowPlayingAnimationValue = dialogView.findViewById<TextView>(R.id.textNowPlayingAnimationValue)
        val currentAnimationIndex = viewModel.state.value.nowPlayingAnimation.coerceIn(0, animationOptions.size - 1)
        textNowPlayingAnimationValue.text = animationOptions[currentAnimationIndex]

        dialogView.findViewById<View>(R.id.itemNowPlayingAnimation).setOnClickListener {
            callback?.onNowPlayingAnimationRequested { type ->
                viewModel.setNowPlayingAnimation(type)
                textNowPlayingAnimationValue.text = animationOptions[type.coerceIn(0, animationOptions.size - 1)]
            }
        }

        // Correction Helpers toggle
        val switchCorrectionHelpers = dialogView.findViewById<SwitchCompat>(R.id.switchCorrectionHelpers)
        switchCorrectionHelpers.isChecked = viewModel.state.value.isCorrectionHelpersEnabled
        switchCorrectionHelpers.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setCorrectionHelpersEnabled(isChecked)
        }

        // View Corrections item
        dialogView.findViewById<View>(R.id.itemViewCorrections).setOnClickListener {
            callback?.onCorrectionsViewerRequested()
        }
    }

    // ========== OTHER SETTINGS ==========
    private fun setupOtherSettings() {
        // Bug Report item
        dialogView.findViewById<View>(R.id.itemBugReport).setOnClickListener {
            dismiss()
            callback?.onBugReportRequested()
        }

        // Close App button
        dialogView.findViewById<TextView>(R.id.btnCloseApp).setOnClickListener {
            dismiss()
            callback?.onCloseAppRequested()
        }
    }

    companion object {
        const val TAG = "SettingsDialogFragment"

        fun newInstance(): SettingsDialogFragment {
            return SettingsDialogFragment()
        }
    }
}
