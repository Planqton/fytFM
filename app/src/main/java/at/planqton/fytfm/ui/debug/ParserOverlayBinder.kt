package at.planqton.fytfm.ui.debug

import android.content.Context
import android.view.View
import at.planqton.fytfm.R
import at.planqton.fytfm.databinding.ActivityMainBinding
import at.planqton.fytfm.deezer.ParserLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns the parser-log debug overlay (the Dev tool that shows the live
 * RT/DLS-to-track parsing pipeline output). Pulled out of MainActivity to
 * isolate ~150 lines of overlay-specific glue:
 *
 *  - Tab buttons (FM / DAB+) toggling [currentParserTab]
 *  - Live log refresh via [ParserLogger]'s listener API
 *  - Coloured rendering of pass / fail entries into the overlay TextView
 *  - Clear / Export button wiring
 *
 * The activity-result launcher used for the export "Save As" dialog stays in
 * MainActivity (the [registerForActivityResult] contract requires that). The
 * binder delegates to the host via [onExportRequested] when the user taps
 * Export, passing both the filename and the file content; the host stores
 * the content and triggers its launcher.
 *
 * @property onExportRequested invoked when the user taps the Export button.
 *  Filename is `fytfm_parser_<fm|dab>_log_<timestamp>.txt`.
 */
class ParserOverlayBinder(
    private val binding: ActivityMainBinding,
    private val context: Context,
    private val onExportRequested: (filename: String, content: String) -> Unit,
) {

    /** Which tab the user has selected — controls which `ParserLogger` source
     *  the Clear/Export buttons act on, and which entries the overlay shows. */
    var currentParserTab: ParserLogger.Source = ParserLogger.Source.FM
        private set

    /** Single shared listener attached to BOTH FM and DAB sources of
     *  [ParserLogger]. Stored so [release] can detach it. */
    private var parserLogListener: ((ParserLogger.ParserLogEntry) -> Unit)? = null

    /**
     * Wire up the overlay: tab-button click handlers, Clear/Export button
     * handlers, and the [ParserLogger] listeners that trigger live refresh.
     * Idempotent: calling twice will overwrite the listener slot but not
     * leak the previous one (because the same instance is in [parserLogListener]).
     */
    fun setup() {
        val btnFm = binding.btnParserTabFm
        val btnDab = binding.btnParserTabDab

        btnFm?.setOnClickListener {
            currentParserTab = ParserLogger.Source.FM
            updateTabButtons()
            updateLogDisplay()
        }
        btnDab?.setOnClickListener {
            currentParserTab = ParserLogger.Source.DAB
            updateTabButtons()
            updateLogDisplay()
        }

        binding.btnParserClear.setOnClickListener {
            if (currentParserTab == ParserLogger.Source.FM) {
                ParserLogger.clearFm()
            } else {
                ParserLogger.clearDab()
            }
            updateLogDisplay()
        }

        binding.btnParserExport.setOnClickListener {
            export()
        }

        // Live refresh — listener fires on every parse. Skip the work if the
        // overlay isn't currently visible; this is the hot path.
        parserLogListener = { _ ->
            binding.root.post {
                if (binding.debugParserOverlay.visibility == View.VISIBLE) {
                    updateLogDisplay()
                }
            }
        }
        parserLogListener?.let {
            ParserLogger.addFmListener(it)
            ParserLogger.addDabListener(it)
        }
    }

    /** Refresh the tab-button background colours so the active tab reads
     *  green and the inactive one reads gray. Called after every tab switch. */
    fun updateTabButtons() {
        val btnFm = binding.btnParserTabFm
        val btnDab = binding.btnParserTabDab
        if (currentParserTab == ParserLogger.Source.FM) {
            btnFm?.setBackgroundColor(0xFF4CAF50.toInt())  // Green = active
            btnDab?.setBackgroundColor(0xFF555555.toInt()) // Gray = inactive
        } else {
            btnFm?.setBackgroundColor(0xFF555555.toInt())
            btnDab?.setBackgroundColor(0xFF4CAF50.toInt())
        }
    }

    /** Repaint the overlay's log TextView with the current tab's entries.
     *  Most-recent first; failed parses (parsedResult == null) coloured red,
     *  passes coloured green. */
    fun updateLogDisplay() {
        val entries = if (currentParserTab == ParserLogger.Source.FM) {
            ParserLogger.getFmEntries()
        } else {
            ParserLogger.getDabEntries()
        }

        if (entries.isEmpty()) {
            val tabName = if (currentParserTab == ParserLogger.Source.FM) "FM" else "DAB+"
            binding.parserLogText.text = context.getString(R.string.no_log_entries, tabName)
        } else {
            val coloredText = android.text.SpannableStringBuilder()
            val greenColor = android.graphics.Color.parseColor("#4CAF50")
            val redColor = android.graphics.Color.parseColor("#F44336")

            entries.reversed().forEachIndexed { index, entry ->
                if (index > 0) coloredText.append("\n")
                val formatted = entry.format()
                val start = coloredText.length
                coloredText.append(formatted)
                val end = coloredText.length
                val color = if (entry.parsedResult == null) redColor else greenColor
                coloredText.setSpan(
                    android.text.style.ForegroundColorSpan(color),
                    start, end,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            binding.parserLogText.text = coloredText
        }
        binding.parserLogScrollView.post { binding.parserLogScrollView.scrollTo(0, 0) }
    }

    /** Build the export filename + content for the current tab and hand off
     *  to the host. The host owns the activity-result launcher that opens
     *  the system "Save As" dialog. */
    fun export() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val tabSlug = if (currentParserTab == ParserLogger.Source.FM) "fm" else "dab"
        val content = if (currentParserTab == ParserLogger.Source.FM) {
            ParserLogger.exportFm()
        } else {
            ParserLogger.exportDab()
        }
        val filename = "fytfm_parser_${tabSlug}_log_$timestamp.txt"
        onExportRequested(filename, content)
    }

    /** Detach the [ParserLogger] listeners. Call from the host's
     *  `onDestroy` so we don't leak the lambda reference into ParserLogger. */
    fun release() {
        parserLogListener?.let {
            ParserLogger.removeFmListener(it)
            ParserLogger.removeDabListener(it)
        }
        parserLogListener = null
    }
}
