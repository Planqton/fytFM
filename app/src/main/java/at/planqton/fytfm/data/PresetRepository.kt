package at.planqton.fytfm.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class PresetRepository(context: Context) {

    companion object {
        private const val PREFS_FM = "fm_presets"
        private const val PREFS_AM = "am_presets"
        private const val PREFS_SETTINGS = "settings"
        private const val KEY_STATIONS = "stations"
        private const val KEY_POWER_ON_STARTUP = "power_on_startup"
    }

    private val fmPrefs: SharedPreferences = context.getSharedPreferences(PREFS_FM, Context.MODE_PRIVATE)
    private val amPrefs: SharedPreferences = context.getSharedPreferences(PREFS_AM, Context.MODE_PRIVATE)
    private val settingsPrefs: SharedPreferences = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)

    fun saveFmStations(stations: List<RadioStation>) {
        saveStations(fmPrefs, stations)
    }

    fun saveAmStations(stations: List<RadioStation>) {
        saveStations(amPrefs, stations)
    }

    fun loadFmStations(): List<RadioStation> {
        return loadStations(fmPrefs, isAM = false)
    }

    fun loadAmStations(): List<RadioStation> {
        return loadStations(amPrefs, isAM = true)
    }

    private fun saveStations(prefs: SharedPreferences, stations: List<RadioStation>) {
        val jsonArray = JSONArray()
        stations.forEach { station ->
            val obj = JSONObject().apply {
                put("frequency", station.frequency.toDouble())
                put("name", station.name ?: "")
                put("rssi", station.rssi)
                put("isFavorite", station.isFavorite)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_STATIONS, jsonArray.toString()).apply()
    }

    private fun loadStations(prefs: SharedPreferences, isAM: Boolean): List<RadioStation> {
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
                        isFavorite = obj.optBoolean("isFavorite", false)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return stations.sortedBy { it.frequency }
    }

    fun clearFmStations() {
        fmPrefs.edit().remove(KEY_STATIONS).apply()
    }

    fun clearAmStations() {
        amPrefs.edit().remove(KEY_STATIONS).apply()
    }

    /**
     * Favorisiert einen Sender. Falls der Sender nicht existiert, wird er hinzugefügt.
     * @return true wenn Sender jetzt favorisiert ist, false wenn nicht mehr favorisiert
     */
    fun toggleFavorite(frequency: Float, isAM: Boolean): Boolean {
        val stations = if (isAM) loadAmStations() else loadFmStations()
        val existingStation = stations.find { Math.abs(it.frequency - frequency) < 0.05f }

        val updatedStations: List<RadioStation>
        val isFavoriteNow: Boolean

        if (existingStation != null) {
            // Sender existiert - Favorit togglen
            isFavoriteNow = !existingStation.isFavorite
            updatedStations = stations.map {
                if (Math.abs(it.frequency - frequency) < 0.05f) {
                    it.copy(isFavorite = isFavoriteNow)
                } else it
            }
        } else {
            // Sender existiert nicht - hinzufügen und favorisieren
            isFavoriteNow = true
            val newStation = RadioStation(
                frequency = frequency,
                name = null,
                rssi = 0,
                isAM = isAM,
                isFavorite = true
            )
            updatedStations = (stations + newStation).sortedBy { it.frequency }
        }

        if (isAM) {
            saveAmStations(updatedStations)
        } else {
            saveFmStations(updatedStations)
        }

        return isFavoriteNow
    }

    /**
     * Prüft ob ein Sender favorisiert ist
     */
    fun isFavorite(frequency: Float, isAM: Boolean): Boolean {
        val stations = if (isAM) loadAmStations() else loadFmStations()
        return stations.find { Math.abs(it.frequency - frequency) < 0.05f }?.isFavorite ?: false
    }

    fun isPowerOnStartup(): Boolean {
        return settingsPrefs.getBoolean(KEY_POWER_ON_STARTUP, false)
    }

    fun setPowerOnStartup(enabled: Boolean) {
        settingsPrefs.edit().putBoolean(KEY_POWER_ON_STARTUP, enabled).apply()
    }

    fun isShowDebugInfos(): Boolean {
        return settingsPrefs.getBoolean("show_debug_infos", false)
    }

    fun setShowDebugInfos(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("show_debug_infos", enabled).apply()
    }

    // Tuner Settings
    fun isLocalMode(): Boolean {
        return settingsPrefs.getBoolean("local_mode", false)
    }

    fun setLocalMode(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("local_mode", enabled).apply()
    }

    fun isMonoMode(): Boolean {
        return settingsPrefs.getBoolean("mono_mode", false)
    }

    fun setMonoMode(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("mono_mode", enabled).apply()
    }

    fun getRadioArea(): Int {
        return settingsPrefs.getInt("radio_area", 2) // 2 = Europe
    }

    fun setRadioArea(area: Int) {
        settingsPrefs.edit().putInt("radio_area", area).apply()
    }

    // Favoriten-Filter Status (FM und AM getrennt)
    fun isShowFavoritesOnlyFm(): Boolean {
        return settingsPrefs.getBoolean("show_favorites_only_fm", false)
    }

    fun setShowFavoritesOnlyFm(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("show_favorites_only_fm", enabled).apply()
    }

    fun isShowFavoritesOnlyAm(): Boolean {
        return settingsPrefs.getBoolean("show_favorites_only_am", false)
    }

    fun setShowFavoritesOnlyAm(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("show_favorites_only_am", enabled).apply()
    }

    // Überschreibe favorisierte Sender beim Scan
    fun isOverwriteFavorites(): Boolean {
        return settingsPrefs.getBoolean("overwrite_favorites", false)
    }

    fun setOverwriteFavorites(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("overwrite_favorites", enabled).apply()
    }

    // Auto Scan Sensitivity (empfängt auch schwache Sender)
    fun isAutoScanSensitivity(): Boolean {
        return settingsPrefs.getBoolean("auto_scan_sensitivity", false)
    }

    fun setAutoScanSensitivity(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("auto_scan_sensitivity", enabled).apply()
    }

    // Spotify API Credentials
    fun getSpotifyClientId(): String {
        return settingsPrefs.getString("spotify_client_id", "") ?: ""
    }

    fun setSpotifyClientId(clientId: String) {
        settingsPrefs.edit().putString("spotify_client_id", clientId).apply()
    }

    fun getSpotifyClientSecret(): String {
        return settingsPrefs.getString("spotify_client_secret", "") ?: ""
    }

    fun setSpotifyClientSecret(clientSecret: String) {
        settingsPrefs.edit().putString("spotify_client_secret", clientSecret).apply()
    }

    fun hasSpotifyCredentials(): Boolean {
        return getSpotifyClientId().isNotBlank() && getSpotifyClientSecret().isNotBlank()
    }

    // Spotify Local Cache Setting
    fun isSpotifyCacheEnabled(): Boolean {
        return settingsPrefs.getBoolean("spotify_cache_enabled", true) // Default: enabled
    }

    fun setSpotifyCacheEnabled(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("spotify_cache_enabled", enabled).apply()
    }

    /**
     * Fügt gescannte Sender zur Liste hinzu.
     * Favorisierte Sender werden nur überschrieben wenn isOverwriteFavorites() true ist.
     *
     * @param scannedStations Die neu gescannten Sender
     * @param isAM true für AM, false für FM
     * @return Die zusammengeführte Liste
     */
    fun mergeScannedStations(scannedStations: List<RadioStation>, isAM: Boolean): List<RadioStation> {
        val existingStations = if (isAM) loadAmStations() else loadFmStations()
        val overwriteFavorites = isOverwriteFavorites()

        // Map für schnellen Zugriff auf existierende Sender nach Frequenz
        val existingMap = existingStations.associateBy {
            (it.frequency * 10).toInt() // Auf 0.1 MHz Genauigkeit runden
        }.toMutableMap()

        for (scanned in scannedStations) {
            val freqKey = (scanned.frequency * 10).toInt()
            val existing = existingMap[freqKey]

            if (existing != null) {
                // Sender existiert bereits
                if (existing.isFavorite && !overwriteFavorites) {
                    // Favorit behalten, aber evtl. Name updaten wenn leer
                    if (existing.name.isNullOrBlank() && !scanned.name.isNullOrBlank()) {
                        existingMap[freqKey] = existing.copy(name = scanned.name, rssi = scanned.rssi)
                    }
                    // Sonst: Favorit komplett behalten
                } else {
                    // Überschreiben (aber Favorit-Status behalten wenn er einer war)
                    existingMap[freqKey] = scanned.copy(isFavorite = existing.isFavorite)
                }
            } else {
                // Neuer Sender - hinzufügen
                existingMap[freqKey] = scanned
            }
        }

        val mergedStations = existingMap.values.sortedBy { it.frequency }

        // Speichern
        if (isAM) {
            saveAmStations(mergedStations)
        } else {
            saveFmStations(mergedStations)
        }

        return mergedStations
    }
}
