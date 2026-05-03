package at.planqton.fytfm.ui.settings

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.View
import androidx.core.content.ContextCompat
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import at.planqton.fytfm.FrequencyScaleView
import at.planqton.fytfm.R
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.UpdateRepository
import at.planqton.fytfm.data.UpdateState
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
        fun setUpdateStateListener(listener: ((UpdateState) -> Unit)?)
        fun getCurrentUpdateState(): UpdateState
        fun installUpdate(localPath: String)
        fun onAccentColorEditorRequested()
        /** Wird gefeuert wenn der FM-Deezer-Master-Toggle geändert wurde,
         *  damit der btnDeezerToggle in der Hauptansicht synchron bleibt. */
        fun onDeezerEnabledFmChanged(enabled: Boolean)
        /** Analog für DAB+. */
        fun onDeezerEnabledDabChanged(enabled: Boolean)
        /** Wird gefeuert wenn ein Signal-Icon-Toggle geändert wurde, damit
         *  die Hauptansicht das Icon sofort ein-/ausblendet. */
        fun onSignalIconToggleChanged(mode: FrequencyScaleView.RadioMode, enabled: Boolean)
        /** Öffnet den read-only-Viewer für die statische PI→Name-Tabelle. */
        fun onPiListRequested()
        /** Wird beim Wechsel der World-Area / Country gefeuert. Erlaubt
         *  der MainActivity, den FM-Chip live auf die neue Region (band,
         *  spacing, de-emphasis) umzustellen, ohne Power-Cycle. */
        fun onRegionChanged()
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
        callback?.setUpdateStateListener(null)
        dismissProgressDialog()
        callback = null
    }

    private var updateInfoDialog: AlertDialog? = null
    private var progressDialog: AlertDialog? = null
    private var progressBar: android.widget.ProgressBar? = null
    private var progressText: TextView? = null
    private var didAutoInstall: Boolean = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)

        val dialog = AlertDialog.Builder(requireContext(), R.style.TransparentDialog)
            .setView(dialogView)
            .create()

        setupCollapsibleSections()
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
        applyAccentToSectionHeaders()
        applyAccentToSeekBars()

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

    private fun currentAccentColor(): Int {
        val ctx = requireContext()
        return at.planqton.fytfm.ui.theme.AccentColors.current(
            ctx,
            callback?.getPresetRepository() ?: return ContextCompat.getColor(ctx, R.color.radio_accent)
        )
    }

    private fun applyAccentToSectionHeaders() {
        val accent = currentAccentColor()
        val textColor = ContextCompat.getColor(requireContext(), R.color.dialog_text)
        val headerIds = listOf(
            R.id.headerSectionGeneral, R.id.headerSectionAppDesign,
            R.id.headerSectionFm, R.id.headerSectionAm, R.id.headerSectionDab,
            R.id.headerSectionRadioLogos, R.id.headerSectionRdsArchive,
            R.id.headerSectionDeezer, R.id.headerSectionApp
        )
        for (id in headerIds) {
            val header = dialogView.findViewById<View>(id) ?: continue
            tintHeaderBackground(header, accent)
            // Header-Text in dialog_text-Farbe (weiß im Night-, dunkel im Day-Mode);
            // Chevron in Akzent-Farbe
            if (header is TextView) {
                header.setTextColor(textColor)
            } else if (header is android.view.ViewGroup) {
                for (i in 0 until header.childCount) {
                    val child = header.getChildAt(i)
                    if (child is TextView) child.setTextColor(textColor)
                    if (child is ImageView) child.setColorFilter(accent)
                }
            }
        }
    }

    private fun tintHeaderBackground(header: View, accent: Int) {
        val bg = header.background ?: return
        val mutated = bg.mutate()
        if (mutated is android.graphics.drawable.LayerDrawable) {
            (mutated.findDrawableByLayerId(R.id.headerAccentBar) as? android.graphics.drawable.GradientDrawable)
                ?.setColor(accent)
            (mutated.findDrawableByLayerId(R.id.headerBgFill) as? android.graphics.drawable.GradientDrawable)
                ?.setColor((accent and 0x00FFFFFF) or 0x0A000000)
            header.background = mutated
        }
    }

    private fun applyAccentToSeekBars() {
        val accent = currentAccentColor()
        val tintList = android.content.res.ColorStateList.valueOf(accent)
        forEachSeekBar(dialogView) { sb ->
            sb.thumbTintList = tintList
            sb.progressTintList = tintList
            sb.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
                (accent and 0x00FFFFFF) or 0x40000000
            )
        }
    }

    private fun forEachSeekBar(view: View, action: (SeekBar) -> Unit) {
        if (view is SeekBar) action(view)
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) forEachSeekBar(view.getChildAt(i), action)
        }
    }

    // ========== COLLAPSIBLE SECTIONS ==========
    private val collapsibleSectionTriples = listOf(
        Triple(R.id.headerSectionGeneral, R.id.contentSectionGeneral, R.id.chevronSectionGeneral),
        Triple(R.id.headerSectionFm, R.id.contentSectionFm, R.id.chevronSectionFm),
        Triple(R.id.headerSectionAm, R.id.contentSectionAm, R.id.chevronSectionAm),
        Triple(R.id.headerSectionDab, R.id.contentSectionDab, R.id.chevronSectionDab),
        Triple(R.id.headerSectionRadioLogos, R.id.contentSectionRadioLogos, R.id.chevronSectionRadioLogos),
        Triple(R.id.headerSectionRdsArchive, R.id.contentSectionRdsArchive, R.id.chevronSectionRdsArchive),
        Triple(R.id.headerSectionDeezer, R.id.contentSectionDeezer, R.id.chevronSectionDeezer)
    )

    // contentId → headerId for ALL sections (including non-collapsible App Design + App)
    private val sectionHeaderForContent = mapOf(
        R.id.contentSectionGeneral to R.id.headerSectionGeneral,
        R.id.contentSectionAppDesign to R.id.headerSectionAppDesign,
        R.id.contentSectionFm to R.id.headerSectionFm,
        R.id.contentSectionAm to R.id.headerSectionAm,
        R.id.contentSectionDab to R.id.headerSectionDab,
        R.id.contentSectionRadioLogos to R.id.headerSectionRadioLogos,
        R.id.contentSectionRdsArchive to R.id.headerSectionRdsArchive,
        R.id.contentSectionDeezer to R.id.headerSectionDeezer,
        R.id.contentSectionApp to R.id.headerSectionApp
    )

    private fun tintEditTextHandles(et: EditText, accent: Int) {
        et.highlightColor = (accent and 0x00FFFFFF) or 0x66000000
        et.textCursorDrawable?.mutate()?.setTint(accent)
        et.textSelectHandle?.mutate()?.let {
            it.setTint(accent)
            et.setTextSelectHandle(it)
        }
        et.textSelectHandleLeft?.mutate()?.let {
            it.setTint(accent)
            et.setTextSelectHandleLeft(it)
        }
        et.textSelectHandleRight?.mutate()?.let {
            it.setTint(accent)
            et.setTextSelectHandleRight(it)
        }
    }

    // Items that stay visible even when their section is collapsed.
    // The set holds View-IDs of any descendant of the row — we walk the row
    // tree and treat the row as "minimal" if any descendant matches.
    private val minimalItemsBySection: Map<Int, Set<Int>> = mapOf(
        R.id.contentSectionGeneral to setOf(
            R.id.switchAutoplayAtStartup,
            R.id.switchAutoBackground,
            R.id.switchStationChangeToast
        ),
        R.id.contentSectionFm to setOf(
            R.id.itemRadioEditorFm,
            R.id.switchDeezerFm
        ),
        R.id.contentSectionAm to setOf(
            R.id.itemRadioEditorAm
        ),
        R.id.contentSectionDab to setOf(
            R.id.itemRadioEditorDab,
            R.id.switchDeezerDab,
            R.id.switchDabVisualizer,
            R.id.itemDabVisualizerStyle,
            R.id.itemDabRecordingPath
        )
    )

    // Sub-containers whose visibility is owned by their parent switch's listener —
    // skipped by section-collapse logic so the existing switch behavior is preserved.
    private val managedSubContainerIds: Set<Int> = setOf(
        R.id.layoutAutoBackgroundOptions,
        R.id.layoutTickSoundVolume
    )

    // Per-section expansion state: true = advanced items visible, false = only minimal
    private val sectionExpansionState = mutableMapOf<Int, Boolean>()

    private fun rowContainsAnyId(row: View, ids: Set<Int>): Boolean {
        if (row.id in ids) return true
        if (row is android.view.ViewGroup) {
            for (i in 0 until row.childCount) {
                if (rowContainsAnyId(row.getChildAt(i), ids)) return true
            }
        }
        return false
    }

    private fun applySectionExpansionState(contentId: Int) {
        val content = dialogView.findViewById<android.view.ViewGroup>(contentId) ?: return
        content.visibility = View.VISIBLE
        // Nicht-klappbare Sektionen (Visual Settings, App): alle Rows immer sichtbar.
        val isCollapsible = collapsibleSectionTriples.any { it.second == contentId }
        if (!isCollapsible) {
            for (i in 0 until content.childCount) {
                val row = content.getChildAt(i)
                if (row.id in managedSubContainerIds) continue
                row.visibility = View.VISIBLE
            }
            return
        }
        val expanded = sectionExpansionState[contentId] == true
        val minimal = minimalItemsBySection[contentId] ?: emptySet()
        for (i in 0 until content.childCount) {
            val row = content.getChildAt(i)
            // Skip switch-managed sub-containers — their visibility is owned by their parent switch listener.
            if (row.id in managedSubContainerIds) continue
            row.visibility = if (expanded || rowContainsAnyId(row, minimal)) View.VISIBLE else View.GONE
        }
    }

    private fun setupCollapsibleSections() {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        for ((headerId, contentId, chevronId) in collapsibleSectionTriples) {
            val header = dialogView.findViewById<android.view.ViewGroup>(headerId) ?: continue
            val chevron = dialogView.findViewById<View>(chevronId) ?: continue
            // Programmatically insert "Show more" / "Show less" label right before the chevron.
            val showMoreLabel = TextView(ctx).apply {
                text = ctx.getString(R.string.show_more)
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.dialog_text))
                alpha = 0.7f
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.marginEnd = (density * 8).toInt()
                layoutParams = params
            }
            val chevronIndex = header.indexOfChild(chevron)
            if (chevronIndex >= 0) header.addView(showMoreLabel, chevronIndex)

            // Initial state: collapsed (only minimal items shown)
            sectionExpansionState[contentId] = false
            applySectionExpansionState(contentId)
            chevron.rotation = 0f
            header.setOnClickListener {
                val newExpanded = sectionExpansionState[contentId] != true
                sectionExpansionState[contentId] = newExpanded
                applySectionExpansionState(contentId)
                chevron.animate().rotation(if (newExpanded) 90f else 0f).setDuration(180).start()
                showMoreLabel.text = ctx.getString(
                    if (newExpanded) R.string.show_less else R.string.show_more
                )
            }
        }
    }

    // ========== SEARCH ==========
    private fun setupSearch() {
        val searchSettings = dialogView.findViewById<EditText>(R.id.searchSettings)
        val btnClearSearch = dialogView.findViewById<ImageView>(R.id.btnClearSearch)
        val textNoSearchResults = dialogView.findViewById<TextView>(R.id.textNoSearchResults)
        val contentContainer = dialogView.findViewById<LinearLayout>(R.id.settingsContentContainer)

        // Cursor + Selection-Handles + Highlight in Akzent-Farbe (statt System-Rot)
        tintEditTextHandles(searchSettings, currentAccentColor())

        val sectionContentIds = sectionHeaderForContent.keys

        // Walk up from a matched view until reaching a "row container" — i.e. a view whose
        // parent is either the main contentContainer or a section content container.
        fun findRow(view: View?): View? {
            if (view == null || contentContainer == null) return null
            var current: View? = view
            while (current != null) {
                val parent = current.parent as? View ?: return null
                if (parent === contentContainer) return current
                if (parent.id in sectionContentIds) return current
                current = parent
            }
            return null
        }

        // Define searchable items with their keywords (German and English)
        data class SearchableItem(val viewId: Int, val keywords: List<String>)
        val searchableItems = listOf(
            // FM Section
            SearchableItem(R.id.itemRadioEditorFm, listOf("fm", "radio", "editor", "sender", "bearbeiten", "edit", "stations")),
            SearchableItem(R.id.switchLocalMode, listOf("loc", "local", "lokal", "signal", "empfang")),
            SearchableItem(R.id.switchMonoMode, listOf("mono", "noise", "rauschen", "stereo", "audio")),
            SearchableItem(R.id.itemRegion, listOf("region", "area", "country", "land", "kontinent", "europa", "usa", "japan", "asia")),
            SearchableItem(R.id.switchAutoScanSensitivity, listOf("scan", "sensitivity", "empfindlichkeit", "auto")),
            SearchableItem(R.id.switchDeezerFm, listOf("deezer", "cover", "fm", "artwork", "album")),
            SearchableItem(R.id.switchSignalIconFm, listOf("signal", "rssi", "balken", "bars", "empfang", "stärke", "fm")),
            // AM Section
            SearchableItem(R.id.itemRadioEditorAm, listOf("am", "radio", "editor", "sender", "bearbeiten", "edit", "stations")),
            SearchableItem(R.id.switchSignalIconAm, listOf("signal", "rssi", "balken", "bars", "empfang", "stärke", "am")),
            // DAB Section
            SearchableItem(R.id.itemRadioEditorDab, listOf("dab", "radio", "editor", "sender", "bearbeiten", "edit", "stations")),
            SearchableItem(R.id.switchSignalIconDab, listOf("signal", "rssi", "balken", "bars", "empfang", "stärke", "dab", "qualität", "quality")),
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

        // Pre-search expansion state per section (to restore on clear)
        var preSearchExpansion: Map<Int, Boolean>? = null

        // Cache of original (non-highlighted) text per TextView, to restore on clear
        val originalTextCache = mutableMapOf<TextView, CharSequence>()
        val highlightColor = (currentAccentColor() and 0x00FFFFFF) or 0x66000000

        fun findTextViewsRecursive(view: View, out: MutableList<TextView>) {
            if (view is TextView) out.add(view)
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) {
                    findTextViewsRecursive(view.getChildAt(i), out)
                }
            }
        }

        fun highlightInRow(row: View, query: String) {
            val tvs = mutableListOf<TextView>()
            findTextViewsRecursive(row, tvs)
            for (tv in tvs) {
                val original = originalTextCache.getOrPut(tv) { tv.text ?: "" }
                val text = original.toString()
                val idx = text.lowercase().indexOf(query)
                if (idx >= 0) {
                    val span = SpannableString(text)
                    span.setSpan(
                        BackgroundColorSpan(highlightColor),
                        idx, idx + query.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    tv.text = span
                } else {
                    tv.text = original
                }
            }
        }

        fun clearAllHighlights() {
            for ((tv, original) in originalTextCache) {
                tv.text = original
            }
            originalTextCache.clear()
        }

        fun filterSettings(query: String) {
            val trimmedQuery = query.trim().lowercase()

            if (trimmedQuery.isEmpty()) {
                clearAllHighlights()
                // Restore visibility of direct contentContainer children (headers, dividers, bottom items)
                contentContainer?.let { container ->
                    for (i in 0 until container.childCount) {
                        val child = container.getChildAt(i)
                        if (child.id !in sectionContentIds) {
                            child.visibility = View.VISIBLE
                        }
                    }
                }
                // Restore per-section expansion state and apply
                preSearchExpansion?.forEach { (contentId, expanded) ->
                    sectionExpansionState[contentId] = expanded
                }
                preSearchExpansion = null
                for (contentId in sectionContentIds) {
                    applySectionExpansionState(contentId)
                }
                textNoSearchResults.visibility = View.GONE
                btnClearSearch.visibility = View.GONE
                return
            }

            btnClearSearch.visibility = View.VISIBLE

            // First search input — capture current expansion state so we can restore later
            if (preSearchExpansion == null) {
                preSearchExpansion = sectionContentIds.associateWith {
                    sectionExpansionState[it] == true
                }
            }

            // Hide everything: direct children of contentContainer + rows inside section content
            contentContainer?.let { container ->
                for (i in 0 until container.childCount) {
                    container.getChildAt(i).visibility = View.GONE
                }
            }
            for (sectionId in sectionContentIds) {
                val section = dialogView.findViewById<android.view.ViewGroup>(sectionId) ?: continue
                for (i in 0 until section.childCount) {
                    section.getChildAt(i).visibility = View.GONE
                }
            }

            // Find matches and reveal: row + section content + section header.
            // Match wins if query is substring of any keyword OR of any visible TextView text in the row.
            var matchCount = 0
            for (item in searchableItems) {
                val view = dialogView.findViewById<View>(item.viewId) ?: continue
                val row = findRow(view) ?: continue
                val keywordMatch = item.keywords.any { it.contains(trimmedQuery) }
                val textMatch = if (!keywordMatch) {
                    val tvs = mutableListOf<TextView>()
                    findTextViewsRecursive(row, tvs)
                    tvs.any {
                        val orig = originalTextCache[it] ?: it.text ?: ""
                        orig.toString().lowercase().contains(trimmedQuery)
                    }
                } else false
                if (keywordMatch || textMatch) {
                    matchCount++
                    row.visibility = View.VISIBLE
                    highlightInRow(row, trimmedQuery)
                    // Reveal containing section if any
                    val parent = row.parent as? View
                    if (parent != null && parent.id in sectionContentIds) {
                        parent.visibility = View.VISIBLE
                        sectionHeaderForContent[parent.id]?.let { headerId ->
                            dialogView.findViewById<View>(headerId)?.visibility = View.VISIBLE
                        }
                    }
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
            callback?.onSignalIconToggleChanged(FrequencyScaleView.RadioMode.FM, isChecked)
        }

        // PI Database — opens the PI→Name lookup dialog (read-only viewer).
        dialogView.findViewById<View>(R.id.itemPiDatabase).setOnClickListener {
            callback?.onPiListRequested()
        }

        // Stationname Parsing Mode — None / PS / PI / PI→PS Fallback
        val itemAutoparse = dialogView.findViewById<View>(R.id.itemFmAutoparseMode)
        val textAutoparseValue = dialogView.findViewById<TextView>(R.id.textFmAutoparseValue)
        val prefsForAutoparse = callback?.getPresetRepository()
        textAutoparseValue.text = autoparseModeLabel(prefsForAutoparse?.getFmAutoparseMode() ?: 1)
        itemAutoparse.setOnClickListener {
            showFmAutoparseModeDialog { newMode ->
                prefsForAutoparse?.setFmAutoparseMode(newMode)
                textAutoparseValue.text = autoparseModeLabel(newMode)
            }
        }
    }

    private fun autoparseModeLabel(modeId: Int): String {
        val resId = when (modeId) {
            0 -> R.string.fm_autoparse_none
            2 -> R.string.fm_autoparse_pi
            3 -> R.string.fm_autoparse_pi_fallback_ps
            else -> R.string.fm_autoparse_ps
        }
        return getString(resId)
    }

    private fun showFmAutoparseModeDialog(onPick: (Int) -> Unit) {
        val ctx = requireContext()
        val labels = arrayOf(
            getString(R.string.fm_autoparse_none),
            getString(R.string.fm_autoparse_ps),
            getString(R.string.fm_autoparse_pi),
            getString(R.string.fm_autoparse_pi_fallback_ps),
        )
        val current = callback?.getPresetRepository()?.getFmAutoparseMode() ?: 1
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.fm_autoparse_pick_title)
            .setSingleChoiceItems(labels, current) { dlg, which ->
                onPick(which)
                dlg.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateRegionText(textView: TextView) {
        val prefs = callback?.getPresetRepository() ?: return
        val area = at.planqton.fytfm.data.region.WorldAreas.byId(prefs.getWorldAreaId())
        val country = prefs.getCountry()
        val flag = at.planqton.fytfm.data.region.CountryFlags.flagFor(country)
        textView.text = getString(
            R.string.region_value_format,
            getString(area.nameRes),
            "$flag $country",
        )
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
            callback?.onSignalIconToggleChanged(FrequencyScaleView.RadioMode.AM, isChecked)
        }

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
            callback?.onDeezerEnabledDabChanged(isChecked)
            val mode = callback?.getCurrentRadioMode()
            if (mode == FrequencyScaleView.RadioMode.DAB ||
                mode == FrequencyScaleView.RadioMode.DAB_DEV) {
                callback?.onDabCoverDisplayNeedsUpdate()
            }
        }

        // Signal-Icon für DAB toggle
        val switchSignalIconDab = dialogView.findViewById<SwitchCompat>(R.id.switchSignalIconDab)
        switchSignalIconDab.isChecked = viewModel.state.value.isSignalIconEnabledDab
        switchSignalIconDab.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setSignalIconEnabledDab(isChecked)
            callback?.onSignalIconToggleChanged(FrequencyScaleView.RadioMode.DAB, isChecked)
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

        // Region (World Area + Country) — Click öffnet WorldRegionDialogFragment.
        val itemRegion = dialogView.findViewById<View>(R.id.itemRegion)
        val textRegionValue = dialogView.findViewById<TextView>(R.id.textRegionValue)
        updateRegionText(textRegionValue)
        itemRegion.setOnClickListener {
            val prefs = callback?.getPresetRepository() ?: return@setOnClickListener
            at.planqton.fytfm.ui.settings.WorldRegionDialogFragment().apply {
                presetRepository = prefs
                onApplied = {
                    updateRegionText(textRegionValue)
                    callback?.onRadioModeSpinnerNeedsUpdate()
                    callback?.onRegionChanged()
                }
            }.show(parentFragmentManager, at.planqton.fytfm.ui.settings.WorldRegionDialogFragment.TAG)
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
        if (!isAdded || !::dialogView.isInitialized) return
        val prefs = callback?.getPresetRepository() ?: return
        val daySwatch = dialogView.findViewById<View>(R.id.accentColorPreviewDay)
        val nightSwatch = dialogView.findViewById<View>(R.id.accentColorPreviewNight)
        val dayColor = prefs.getAccentColorDay().takeIf { it != 0 }
            ?: 0xFFC52322.toInt()  // values/colors.xml radio_accent default
        val nightColor = prefs.getAccentColorNight().takeIf { it != 0 }
            ?: 0xFFFF5252.toInt()  // values-night/colors.xml radio_accent default
        (daySwatch.background as? android.graphics.drawable.GradientDrawable)?.setColor(dayColor)
        (nightSwatch.background as? android.graphics.drawable.GradientDrawable)?.setColor(nightColor)
        // Auch alle anderen akzent-getinteten Elemente live aktualisieren
        applyAccentToSwitches()
        applyAccentToSectionHeaders()
        applyAccentToSeekBars()
        dialogView.findViewById<EditText>(R.id.searchSettings)?.let {
            tintEditTextHandles(it, currentAccentColor())
        }
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
            handleUpdateClick()
        }

        val appVersionBadge = dialogView.findViewById<View>(R.id.updateBadgeAppVersion)
        // Initialer State: wenn beim Öffnen schon ein Update bekannt ist, Punkt zeigen
        appVersionBadge.visibility =
            if (callback?.getCurrentUpdateState() is UpdateState.UpdateAvailable) View.VISIBLE else View.GONE

        // Listener: live State-Änderungen während Dialog offen ist
        callback?.setUpdateStateListener { state ->
            if (!isAdded) return@setUpdateStateListener
            appVersionBadge.visibility =
                if (state is UpdateState.UpdateAvailable) View.VISIBLE else View.GONE
            when (state) {
                is UpdateState.Downloading -> showOrUpdateProgressDialog(state.progress)
                is UpdateState.DownloadComplete -> {
                    dismissProgressDialog()
                    if (!didAutoInstall) {
                        didAutoInstall = true
                        updateInfoDialog?.dismiss()
                        updateInfoDialog = null
                        callback?.installUpdate(state.localPath)
                    }
                }
                is UpdateState.UpdateAvailable -> {
                    if (updateInfoDialog == null) showUpdateAvailableDialog(state)
                }
                is UpdateState.NoUpdate -> {
                    if (updateInfoDialog == null) {
                        Toast.makeText(requireContext(), R.string.update_no_update, Toast.LENGTH_SHORT).show()
                    }
                }
                is UpdateState.Error -> {
                    dismissProgressDialog()
                    Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                }
                else -> { /* Idle, Checking — nichts tun */ }
            }
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

    private fun handleUpdateClick() {
        val state = callback?.getCurrentUpdateState() ?: UpdateState.Idle
        when (state) {
            is UpdateState.UpdateAvailable -> showUpdateAvailableDialog(state)
            is UpdateState.Downloading -> Toast.makeText(requireContext(), R.string.update_downloading, Toast.LENGTH_SHORT).show()
            is UpdateState.DownloadComplete -> callback?.installUpdate(state.localPath)
            is UpdateState.Checking -> Toast.makeText(requireContext(), R.string.update_checking, Toast.LENGTH_SHORT).show()
            else -> {
                Toast.makeText(requireContext(), R.string.update_checking, Toast.LENGTH_SHORT).show()
                viewModel.checkForUpdates()
            }
        }
    }

    private fun showOrUpdateProgressDialog(percent: Int) {
        if (!isAdded) return
        if (progressDialog == null) {
            val ctx = requireContext()
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (resources.displayMetrics.density * 24).toInt()
                setPadding(pad, pad, pad, pad)
            }
            progressText = TextView(ctx).apply {
                text = getString(R.string.update_progress_format, 0)
                textSize = 16f
            }
            progressBar = android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = false
                max = 100
                progress = 0
                val topMargin = (resources.displayMetrics.density * 12).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, topMargin, 0, 0) }
            }
            container.addView(progressText)
            container.addView(progressBar)
            progressDialog = AlertDialog.Builder(ctx)
                .setTitle(R.string.update_progress_title)
                .setView(container)
                .setCancelable(false)
                .create()
                .also { it.show() }
        }
        progressBar?.progress = percent.coerceIn(0, 100)
        progressText?.text = getString(R.string.update_progress_format, percent.coerceIn(0, 100))
    }

    private fun dismissProgressDialog() {
        progressDialog?.dismiss()
        progressDialog = null
        progressBar = null
        progressText = null
    }

    private fun showUpdateAvailableDialog(state: UpdateState.UpdateAvailable) {
        if (!isAdded) return
        val info = state.info
        val notes = info.releaseNotes?.takeIf { it.isNotBlank() } ?: ""
        val title = getString(R.string.update_dialog_title, info.latestVersion)
        val message = getString(
            R.string.update_dialog_message_format,
            info.currentVersion,
            info.latestVersion,
            notes
        )
        updateInfoDialog?.dismiss()
        updateInfoDialog = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.update_download) { _, _ ->
                didAutoInstall = false
                viewModel.downloadUpdate(info.downloadUrl, info.latestVersion)
                Toast.makeText(requireContext(), R.string.update_download_started, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { updateInfoDialog = null }
            .show()
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
