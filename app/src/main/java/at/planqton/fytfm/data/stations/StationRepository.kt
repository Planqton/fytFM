package at.planqton.fytfm.data.stations

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import at.planqton.fytfm.data.RadioStation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns the FM/AM/DAB/DAB-Dev station lists: persistence, StateFlows,
 * favourites toggling, and scanned-stations merging (including the per-tuner
 * variants). Extracted from the former god-class PresetRepository so that
 * settings can evolve independently.
 *
 * [isOverwriteFavorites] is injected by the caller because the merge
 * behaviour is configured via app settings, which live outside this repo.
 */
class StationRepository(
    private val context: Context,
    private val isOverwriteFavorites: () -> Boolean,
) {
    companion object {
        private const val TAG = "StationRepository"
        private const val PREFS_FM = "fm_presets"
        private const val PREFS_AM = "am_presets"
        private const val PREFS_DAB = "dab_presets"
        private const val PREFS_DAB_DEV = "dab_dev_presets"
        private const val KEY_STATIONS = "stations"
    }

    private val fmPrefs: SharedPreferences = context.getSharedPreferences(PREFS_FM, Context.MODE_PRIVATE)
    private val amPrefs: SharedPreferences = context.getSharedPreferences(PREFS_AM, Context.MODE_PRIVATE)
    private val dabPrefs: SharedPreferences = context.getSharedPreferences(PREFS_DAB, Context.MODE_PRIVATE)
    private val dabDevPrefs: SharedPreferences = context.getSharedPreferences(PREFS_DAB_DEV, Context.MODE_PRIVATE)

    private val _fmStations = MutableStateFlow(loadStations(fmPrefs, isAM = false))
    val fmStations: StateFlow<List<RadioStation>> = _fmStations.asStateFlow()

    private val _amStations = MutableStateFlow(loadStations(amPrefs, isAM = true))
    val amStations: StateFlow<List<RadioStation>> = _amStations.asStateFlow()

    private val _dabStations = MutableStateFlow(loadStations(dabPrefs, isAM = false, isDab = true))
    val dabStations: StateFlow<List<RadioStation>> = _dabStations.asStateFlow()

    private val _dabDevStations = MutableStateFlow(loadStations(dabDevPrefs, isAM = false, isDab = true))
    val dabDevStations: StateFlow<List<RadioStation>> = _dabDevStations.asStateFlow()

    // Per-tuner (plugin) preset methods removed — they were intended for
    // a tuner-plugin SDK that was never finished. No production callers.
    // The "tuner_presets_$tunerId" prefs files are still readable if any
    // future plugin needs them.

    // ========== FM/AM/DAB/DAB-Dev CRUD ==========

    fun saveFmStations(stations: List<RadioStation>) {
        saveStations(fmPrefs, stations)
        _fmStations.value = loadStations(fmPrefs, isAM = false)
    }

    fun saveAmStations(stations: List<RadioStation>) {
        saveStations(amPrefs, stations)
        _amStations.value = loadStations(amPrefs, isAM = true)
    }

    fun loadFmStations(): List<RadioStation> = loadStations(fmPrefs, isAM = false)
    fun loadAmStations(): List<RadioStation> = loadStations(amPrefs, isAM = true)

    fun saveDabStations(stations: List<RadioStation>) {
        saveStations(dabPrefs, stations)
        _dabStations.value = loadStations(dabPrefs, isAM = false, isDab = true)
    }

    fun loadDabStations(): List<RadioStation> = loadStations(dabPrefs, isAM = false, isDab = true)

    fun clearDabStations() {
        dabPrefs.edit().remove(KEY_STATIONS).apply()
        _dabStations.value = emptyList()
    }

    fun saveDabDevStations(stations: List<RadioStation>) {
        saveStations(dabDevPrefs, stations)
        _dabDevStations.value = loadStations(dabDevPrefs, isAM = false, isDab = true)
    }

    fun loadDabDevStations(): List<RadioStation> = loadStations(dabDevPrefs, isAM = false, isDab = true)

    fun clearDabDevStations() {
        dabDevPrefs.edit().remove(KEY_STATIONS).apply()
        _dabDevStations.value = emptyList()
    }

    fun clearFmStations() {
        fmPrefs.edit().remove(KEY_STATIONS).apply()
        _fmStations.value = emptyList()
    }

    fun clearAmStations() {
        amPrefs.edit().remove(KEY_STATIONS).apply()
        _amStations.value = emptyList()
    }

    // ========== Favorites ==========

    /**
     * Favorisiert einen Sender. Falls der Sender nicht existiert, wird er hinzugefügt.
     * @return true wenn Sender jetzt favorisiert ist, false wenn nicht mehr favorisiert
     */
    fun toggleFavorite(frequency: Float, isAM: Boolean): Boolean {
        val stations = if (isAM) loadAmStations() else loadFmStations()
        val (updatedStations, isFavoriteNow) = toggleFrequencyFavorite(stations, frequency, isAM)
        if (isAM) saveAmStations(updatedStations) else saveFmStations(updatedStations)
        return isFavoriteNow
    }

    fun isFavorite(frequency: Float, isAM: Boolean): Boolean {
        val stations = if (isAM) loadAmStations() else loadFmStations()
        return stations.find { Math.abs(it.frequency - frequency) < 0.05f }?.isFavorite ?: false
    }

    /**
     * Toggle Favorit für DAB-Sender (identifiziert per serviceId).
     */
    fun toggleDabFavorite(serviceId: Int): Boolean {
        val stations = loadDabStations()
        val existing = stations.find { it.serviceId == serviceId } ?: return false
        val isFavoriteNow = !existing.isFavorite
        saveDabStations(stations.map {
            if (it.serviceId == serviceId) it.copy(isFavorite = isFavoriteNow) else it
        })
        return isFavoriteNow
    }

    fun isDabFavorite(serviceId: Int): Boolean =
        loadDabStations().find { it.serviceId == serviceId }?.isFavorite ?: false

    fun toggleDabDevFavorite(serviceId: Int): Boolean {
        val stations = loadDabDevStations()
        val existing = stations.find { it.serviceId == serviceId } ?: return false
        val isFavoriteNow = !existing.isFavorite
        saveDabDevStations(stations.map {
            if (it.serviceId == serviceId) it.copy(isFavorite = isFavoriteNow) else it
        })
        return isFavoriteNow
    }

    fun isDabDevFavorite(serviceId: Int): Boolean =
        loadDabDevStations().find { it.serviceId == serviceId }?.isFavorite ?: false

    // ========== Scanned-station merges ==========

    /**
     * Merged neu gescannte Sender mit existierenden. Liefert das
     * Ergebnis-Tupel `(mergedList, overwrittenFavorites)`, damit der
     * Caller (MainActivity) bei Bedarf zugehörige Logos aufräumen kann.
     */
    fun mergeScannedStations(
        scannedStations: List<RadioStation>,
        isAM: Boolean,
    ): Pair<List<RadioStation>, List<RadioStation>> {
        val (merged, overwritten) = mergeStations(
            existing = if (isAM) loadAmStations() else loadFmStations(),
            scanned = scannedStations,
            keyOf = { (it.frequency * 10).toInt() },
            comparator = compareBy { it.frequency },
            updateNameIfBlank = true,
        )
        if (isAM) saveAmStations(merged) else saveFmStations(merged)
        return Pair(merged, overwritten)
    }

    fun mergeDabScannedStations(scannedStations: List<RadioStation>): List<RadioStation> {
        val (merged, _) = mergeStations(
            existing = loadDabStations(),
            scanned = scannedStations,
            keyOf = { it.serviceId },
            comparator = compareBy { it.name ?: it.ensembleLabel ?: "" },
        )
        saveDabStations(merged)
        return merged
    }

    fun mergeDabDevScannedStations(scannedStations: List<RadioStation>): List<RadioStation> {
        val (merged, _) = mergeStations(
            existing = loadDabDevStations(),
            scanned = scannedStations,
            keyOf = { it.serviceId },
            comparator = compareBy { it.name ?: it.ensembleLabel ?: "" },
        )
        saveDabDevStations(merged)
        return merged
    }

    // ========== Internals ==========

    /**
     * Shared favourite-toggle logic for frequency-keyed (FM/AM) lists — if the
     * station doesn't exist, adds it as a new favourite entry.
     */
    private fun toggleFrequencyFavorite(
        stations: List<RadioStation>,
        frequency: Float,
        isAM: Boolean,
    ): Pair<List<RadioStation>, Boolean> {
        val existing = stations.find { Math.abs(it.frequency - frequency) < 0.05f }
        return if (existing != null) {
            val isFavoriteNow = !existing.isFavorite
            val updated = stations.map {
                if (Math.abs(it.frequency - frequency) < 0.05f) it.copy(isFavorite = isFavoriteNow) else it
            }
            updated to isFavoriteNow
        } else {
            val newStation = RadioStation(
                frequency = frequency,
                name = null,
                rssi = 0,
                isAM = isAM,
                isFavorite = true,
            )
            (stations + newStation).sortedBy { it.frequency } to true
        }
    }

    private fun <K : Any> mergeStations(
        existing: List<RadioStation>,
        scanned: List<RadioStation>,
        keyOf: (RadioStation) -> K,
        comparator: Comparator<RadioStation>,
        updateNameIfBlank: Boolean = false,
    ): Pair<List<RadioStation>, List<RadioStation>> {
        val overwriteFavorites = isOverwriteFavorites()
        val favoritesMap = existing
            .filter { it.isFavorite }
            .associateBy(keyOf)
            .toMutableMap()
        val resultMap = mutableMapOf<K, RadioStation>()
        val overwritten = mutableListOf<RadioStation>()

        for (s in scanned) {
            val key = keyOf(s)
            val existingFavorite = favoritesMap[key]
            resultMap[key] = when {
                existingFavorite == null -> s
                // overwriteFavorites: kompletter Replace — der vorherige
                // Favoriten-Status fällt weg, der neue Eintrag startet
                // wie ein frisch gescannter Sender (isFavorite=false).
                // Der alte Favorit wird in `overwritten` aufgehoben, damit
                // der Caller z.B. das zugehörige Logo löschen kann.
                overwriteFavorites -> {
                    overwritten.add(existingFavorite)
                    s.copy(isFavorite = false)
                }
                updateNameIfBlank &&
                    existingFavorite.name.isNullOrBlank() &&
                    !s.name.isNullOrBlank() ->
                    existingFavorite.copy(name = s.name, rssi = s.rssi)
                else -> existingFavorite
            }
            if (existingFavorite != null) favoritesMap.remove(key)
        }

        resultMap.putAll(favoritesMap)
        return Pair(resultMap.values.sortedWith(comparator), overwritten)
    }

    private fun saveStations(prefs: SharedPreferences, stations: List<RadioStation>) {
        val jsonArray = JSONArray()
        stations.forEach { station ->
            val obj = JSONObject().apply {
                put("frequency", station.frequency.toDouble())
                put("name", station.name ?: "")
                put("rssi", station.rssi)
                put("isFavorite", station.isFavorite)
                put("syncName", station.syncName)
                put("isDab", station.isDab)
                put("serviceId", station.serviceId)
                put("ensembleId", station.ensembleId)
                put("ensembleLabel", station.ensembleLabel ?: "")
                put("pi", station.pi)
                put("logoPath", station.logoPath ?: "")
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_STATIONS, jsonArray.toString()).commit()
    }

    private fun loadStations(
        prefs: SharedPreferences,
        isAM: Boolean,
        isDab: Boolean = false,
    ): List<RadioStation> {
        val json = prefs.getString(KEY_STATIONS, null) ?: return emptyList()
        val stations = mutableListOf<RadioStation>()
        try {
            val jsonArray = JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                stations.add(
                    RadioStation(
                        frequency = obj.getDouble("frequency").toFloat(),
                        name = obj.optString("name").takeIf { it.isNotBlank() },
                        rssi = obj.optInt("rssi", 0),
                        isAM = isAM,
                        isDab = isDab || obj.optBoolean("isDab", false),
                        isFavorite = obj.optBoolean("isFavorite", false),
                        syncName = obj.optBoolean("syncName", true),
                        serviceId = obj.optInt("serviceId", 0),
                        ensembleId = obj.optInt("ensembleId", 0),
                        ensembleLabel = obj.optString("ensembleLabel").takeIf { it.isNotBlank() },
                        pi = obj.optInt("pi", 0),
                        logoPath = obj.optString("logoPath").takeIf { it.isNotBlank() },
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse stations JSON (isAM=$isAM, isDab=$isDab): ${e.message}", e)
        }
        return if (isDab) stations.sortedBy { it.name ?: it.ensembleLabel ?: "" }
        else stations.sortedBy { it.frequency }
    }
}
