package at.planqton.fytfm.deezer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Logger for RT/DLS parsing - separate logs for FM RDS and DAB+ DLS
 * Persists logs across app restarts
 */
object ParserLogger {

    enum class Source { FM, DAB }

    private val fmEntries = CopyOnWriteArrayList<ParserLogEntry>()
    private val dabEntries = CopyOnWriteArrayList<ParserLogEntry>()
    private val fmListeners = mutableListOf<(ParserLogEntry) -> Unit>()
    private val dabListeners = mutableListOf<(ParserLogEntry) -> Unit>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var prefs: SharedPreferences? = null

    const val MAX_ENTRIES = 200
    private const val PREFS_NAME = "parser_logger"
    private const val KEY_FM_ENTRIES = "fm_entries"
    private const val KEY_DAB_ENTRIES = "dab_entries"

    data class ParserLogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val station: String,
        val rawText: String,
        val parsedResult: String? // null = failed, otherwise "Artist - Title"
    ) {
        fun format(): String {
            val time = dateFormat.format(Date(timestamp))
            val result = parsedResult ?: "X"
            return "[$time] $rawText → $result"
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("timestamp", timestamp)
            put("station", station)
            put("rawText", rawText)
            put("parsedResult", parsedResult ?: "")
        }

        companion object {
            fun fromJson(json: JSONObject): ParserLogEntry? {
                return try {
                    val parsed = json.optString("parsedResult", "")
                    ParserLogEntry(
                        timestamp = json.getLong("timestamp"),
                        station = json.getString("station"),
                        rawText = json.optString("rawText", json.optString("rawDls", "")),
                        parsedResult = if (parsed.isBlank()) null else parsed
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    /**
     * Initialize with context to enable persistence
     */
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadEntries()
        }
    }

    private fun loadEntries() {
        // Load FM entries
        prefs?.getString(KEY_FM_ENTRIES, null)?.let { json ->
            try {
                val array = JSONArray(json)
                fmEntries.clear()
                for (i in 0 until array.length()) {
                    ParserLogEntry.fromJson(array.getJSONObject(i))?.let {
                        fmEntries.add(it)
                    }
                }
            } catch (e: Exception) { }
        }

        // Load DAB entries
        prefs?.getString(KEY_DAB_ENTRIES, null)?.let { json ->
            try {
                val array = JSONArray(json)
                dabEntries.clear()
                for (i in 0 until array.length()) {
                    ParserLogEntry.fromJson(array.getJSONObject(i))?.let {
                        dabEntries.add(it)
                    }
                }
            } catch (e: Exception) { }
        }

        // Migrate old entries (from before FM/DAB split)
        prefs?.getString("entries", null)?.let { json ->
            try {
                val array = JSONArray(json)
                for (i in 0 until array.length()) {
                    ParserLogEntry.fromJson(array.getJSONObject(i))?.let {
                        // Old entries go to DAB (was primarily used for DAB)
                        if (dabEntries.none { e -> e.rawText == it.rawText && e.timestamp == it.timestamp }) {
                            dabEntries.add(it)
                        }
                    }
                }
                // Remove old key after migration
                prefs?.edit()?.remove("entries")?.apply()
                saveEntries()
            } catch (e: Exception) { }
        }
    }

    private fun saveEntries() {
        val fmArray = JSONArray()
        fmEntries.forEach { fmArray.put(it.toJson()) }

        val dabArray = JSONArray()
        dabEntries.forEach { dabArray.put(it.toJson()) }

        prefs?.edit()
            ?.putString(KEY_FM_ENTRIES, fmArray.toString())
            ?.putString(KEY_DAB_ENTRIES, dabArray.toString())
            ?.apply()
    }

    /**
     * Log FM RDS RT parse attempt
     */
    fun logFm(station: String, rawRt: String, artist: String?, title: String?) {
        val parsedResult = if (artist != null && title != null) "$artist - $title" else null
        val entry = ParserLogEntry(
            station = station,
            rawText = rawRt,
            parsedResult = parsedResult
        )
        addEntry(Source.FM, entry)
    }

    /**
     * Log DAB+ DLS parse attempt
     */
    fun logDab(station: String, rawDls: String, artist: String?, title: String?) {
        val parsedResult = if (artist != null && title != null) "$artist - $title" else null
        val entry = ParserLogEntry(
            station = station,
            rawText = rawDls,
            parsedResult = parsedResult
        )
        addEntry(Source.DAB, entry)
    }

    /**
     * Legacy method - logs to DAB by default
     */
    fun log(station: String, rawDls: String, artist: String?, title: String?) {
        logDab(station, rawDls, artist, title)
    }

    private fun addEntry(source: Source, entry: ParserLogEntry) {
        val entries = if (source == Source.FM) fmEntries else dabEntries
        val listeners = if (source == Source.FM) fmListeners else dabListeners

        // Skip if identical to last entry
        val last = entries.lastOrNull()
        if (last != null && last.rawText == entry.rawText && last.parsedResult == entry.parsedResult) {
            return
        }

        entries.add(entry)
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
        saveEntries()
        listeners.forEach { it(entry) }
    }

    fun getFmEntries(): List<ParserLogEntry> = fmEntries.toList()
    fun getDabEntries(): List<ParserLogEntry> = dabEntries.toList()

    fun clearFm() {
        fmEntries.clear()
        saveEntries()
    }

    fun clearDab() {
        dabEntries.clear()
        saveEntries()
    }

    fun addFmListener(listener: (ParserLogEntry) -> Unit) {
        fmListeners.add(listener)
    }

    fun removeFmListener(listener: (ParserLogEntry) -> Unit) {
        fmListeners.remove(listener)
    }

    fun addDabListener(listener: (ParserLogEntry) -> Unit) {
        dabListeners.add(listener)
    }

    fun removeDabListener(listener: (ParserLogEntry) -> Unit) {
        dabListeners.remove(listener)
    }

    fun exportFm(): String {
        val header = buildString {
            appendLine("=== fytFM FM RDS Parser Log ===")
            appendLine("Export: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("Entries: ${fmEntries.size}")
            appendLine("=".repeat(40))
            appendLine()
        }
        return header + fmEntries.joinToString("\n") { it.format() }
    }

    fun exportDab(): String {
        val header = buildString {
            appendLine("=== fytFM DAB+ DLS Parser Log ===")
            appendLine("Export: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("Entries: ${dabEntries.size}")
            appendLine("=".repeat(40))
            appendLine()
        }
        return header + dabEntries.joinToString("\n") { it.format() }
    }

    /**
     * Export both logs combined
     */
    fun export(): String {
        return exportFm() + "\n\n" + exportDab()
    }
}
