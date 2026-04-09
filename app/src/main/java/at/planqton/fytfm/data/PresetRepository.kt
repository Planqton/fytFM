package at.planqton.fytfm.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class PresetRepository(private val context: Context) {

    companion object {
        private const val PREFS_FM = "fm_presets"
        private const val PREFS_AM = "am_presets"
        private const val PREFS_DAB = "dab_presets"
        private const val PREFS_SETTINGS = "settings"
        private const val KEY_STATIONS = "stations"
        private const val KEY_POWER_ON_STARTUP = "power_on_startup"
        private const val KEY_POWER_ON_STARTUP_FM = "power_on_startup_fm"
        private const val KEY_POWER_ON_STARTUP_AM = "power_on_startup_am"
        private const val KEY_POWER_ON_STARTUP_DAB = "power_on_startup_dab"
        private const val KEY_DEEZER_ENABLED_FM = "deezer_enabled_fm"
        private const val KEY_DEEZER_ENABLED_DAB = "deezer_enabled_dab"
        private const val KEY_TICK_SOUND_ENABLED = "tick_sound_enabled"
        private const val KEY_TICK_SOUND_VOLUME = "tick_sound_volume"
    }

    private val fmPrefs: SharedPreferences = context.getSharedPreferences(PREFS_FM, Context.MODE_PRIVATE)
    private val amPrefs: SharedPreferences = context.getSharedPreferences(PREFS_AM, Context.MODE_PRIVATE)
    private val dabPrefs: SharedPreferences = context.getSharedPreferences(PREFS_DAB, Context.MODE_PRIVATE)
    private val settingsPrefs: SharedPreferences = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)

    // === Per-Tuner-Instance Storage ===

    fun saveStationsForTuner(tunerId: String, stations: List<RadioStation>) {
        val prefs = context.getSharedPreferences("tuner_presets_$tunerId", Context.MODE_PRIVATE)
        saveStations(prefs, stations)
    }

    fun loadStationsForTuner(tunerId: String, isAM: Boolean = false): List<RadioStation> {
        val prefs = context.getSharedPreferences("tuner_presets_$tunerId", Context.MODE_PRIVATE)
        return loadStations(prefs, isAM)
    }

    fun clearStationsForTuner(tunerId: String) {
        val prefs = context.getSharedPreferences("tuner_presets_$tunerId", Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_STATIONS).apply()
    }

    fun toggleFavoriteForTuner(tunerId: String, frequency: Float, isAM: Boolean): Boolean {
        val stations = loadStationsForTuner(tunerId, isAM)
        val existingStation = stations.find { Math.abs(it.frequency - frequency) < 0.05f }

        val updatedStations: List<RadioStation>
        val isFavoriteNow: Boolean

        if (existingStation != null) {
            isFavoriteNow = !existingStation.isFavorite
            updatedStations = stations.map {
                if (Math.abs(it.frequency - frequency) < 0.05f) {
                    it.copy(isFavorite = isFavoriteNow)
                } else it
            }
        } else {
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

        saveStationsForTuner(tunerId, updatedStations)
        return isFavoriteNow
    }

    fun isFavoriteForTuner(tunerId: String, frequency: Float, isAM: Boolean): Boolean {
        val stations = loadStationsForTuner(tunerId, isAM)
        return stations.find { Math.abs(it.frequency - frequency) < 0.05f }?.isFavorite ?: false
    }

    fun mergeScannedStationsForTuner(tunerId: String, scannedStations: List<RadioStation>, isAM: Boolean): List<RadioStation> {
        val existingStations = loadStationsForTuner(tunerId, isAM)
        val overwriteFavorites = isOverwriteFavorites()

        val favoritesMap = existingStations
            .filter { it.isFavorite }
            .associateBy { (it.frequency * 10).toInt() }
            .toMutableMap()

        val resultMap = mutableMapOf<Int, RadioStation>()

        for (scanned in scannedStations) {
            val freqKey = (scanned.frequency * 10).toInt()
            val existingFavorite = favoritesMap[freqKey]

            if (existingFavorite != null) {
                if (overwriteFavorites) {
                    resultMap[freqKey] = scanned.copy(isFavorite = true)
                } else {
                    if (existingFavorite.name.isNullOrBlank() && !scanned.name.isNullOrBlank()) {
                        resultMap[freqKey] = existingFavorite.copy(name = scanned.name, rssi = scanned.rssi)
                    } else {
                        resultMap[freqKey] = existingFavorite
                    }
                }
                favoritesMap.remove(freqKey)
            } else {
                resultMap[freqKey] = scanned
            }
        }

        for ((freqKey, favorite) in favoritesMap) {
            resultMap[freqKey] = favorite
        }

        val mergedStations = resultMap.values.sortedBy { it.frequency }
        saveStationsForTuner(tunerId, mergedStations)
        return mergedStations
    }

    fun isShowFavoritesOnly(tunerId: String): Boolean {
        return settingsPrefs.getBoolean("show_favorites_only_$tunerId", false)
    }

    fun setShowFavoritesOnly(tunerId: String, enabled: Boolean) {
        settingsPrefs.edit().putBoolean("show_favorites_only_$tunerId", enabled).apply()
    }

    fun savePluginSettings(tunerId: String, settings: Map<String, Any>) {
        val prefs = context.getSharedPreferences("tuner_settings_$tunerId", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.clear()
        settings.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
            }
        }
        editor.apply()
    }

    fun loadPluginSettings(tunerId: String): Map<String, Any> {
        val prefs = context.getSharedPreferences("tuner_settings_$tunerId", Context.MODE_PRIVATE)
        val result = mutableMapOf<String, Any>()
        prefs.all.forEach { (key, value) ->
            if (value != null) result[key] = value
        }
        return result
    }

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

    fun saveDabStations(stations: List<RadioStation>) {
        saveStations(dabPrefs, stations)
    }

    fun loadDabStations(): List<RadioStation> {
        return loadStations(dabPrefs, isAM = false, isDab = true)
    }

    fun clearDabStations() {
        dabPrefs.edit().remove(KEY_STATIONS).apply()
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
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_STATIONS, jsonArray.toString()).commit()
    }

    private fun loadStations(prefs: SharedPreferences, isAM: Boolean, isDab: Boolean = false): List<RadioStation> {
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
                        ensembleLabel = obj.optString("ensembleLabel").takeIf { it.isNotBlank() }
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return if (isDab) stations.sortedBy { it.name ?: it.ensembleLabel ?: "" } else stations.sortedBy { it.frequency }
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
            isFavoriteNow = !existingStation.isFavorite
            updatedStations = stations.map {
                if (Math.abs(it.frequency - frequency) < 0.05f) {
                    it.copy(isFavorite = isFavoriteNow)
                } else it
            }
        } else {
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
     * Toggle Favorit für DAB-Sender (identifiziert per serviceId)
     */
    fun toggleDabFavorite(serviceId: Int): Boolean {
        val stations = loadDabStations()
        val existingStation = stations.find { it.serviceId == serviceId }

        val updatedStations: List<RadioStation>
        val isFavoriteNow: Boolean

        if (existingStation != null) {
            isFavoriteNow = !existingStation.isFavorite
            updatedStations = stations.map {
                if (it.serviceId == serviceId) it.copy(isFavorite = isFavoriteNow) else it
            }
        } else {
            return false
        }

        saveDabStations(updatedStations)
        return isFavoriteNow
    }

    /**
     * Prüft ob ein Sender favorisiert ist
     */
    fun isFavorite(frequency: Float, isAM: Boolean): Boolean {
        val stations = if (isAM) loadAmStations() else loadFmStations()
        return stations.find { Math.abs(it.frequency - frequency) < 0.05f }?.isFavorite ?: false
    }

    fun isDabFavorite(serviceId: Int): Boolean {
        return loadDabStations().find { it.serviceId == serviceId }?.isFavorite ?: false
    }

    // Autoplay at startup (single setting for all modes)
    fun isAutoplayAtStartup(): Boolean {
        return settingsPrefs.getBoolean("autoplay_at_startup", false)
    }

    fun setAutoplayAtStartup(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("autoplay_at_startup", enabled).apply()
    }

    @Deprecated("Use isAutoplayAtStartup() instead")
    fun isPowerOnStartup(): Boolean {
        return settingsPrefs.getBoolean(KEY_POWER_ON_STARTUP, false)
    }

    fun setPowerOnStartup(enabled: Boolean) {
        settingsPrefs.edit().putBoolean(KEY_POWER_ON_STARTUP, enabled).apply()
    }

    // Separate Power-On Settings für jeden Tuner
    fun isPowerOnStartupFm(): Boolean {
        return settingsPrefs.getBoolean(KEY_POWER_ON_STARTUP_FM, false)
    }

    fun setPowerOnStartupFm(enabled: Boolean) {
        settingsPrefs.edit().putBoolean(KEY_POWER_ON_STARTUP_FM, enabled).apply()
    }

    fun isPowerOnStartupAm(): Boolean {
        return settingsPrefs.getBoolean(KEY_POWER_ON_STARTUP_AM, false)
    }

    fun setPowerOnStartupAm(enabled: Boolean) {
        settingsPrefs.edit().putBoolean(KEY_POWER_ON_STARTUP_AM, enabled).apply()
    }

    fun isPowerOnStartupDab(): Boolean {
        return settingsPrefs.getBoolean(KEY_POWER_ON_STARTUP_DAB, false)
    }

    fun setPowerOnStartupDab(enabled: Boolean) {
        settingsPrefs.edit().putBoolean(KEY_POWER_ON_STARTUP_DAB, enabled).apply()
    }

    // Deezer Settings pro Modus
    fun isDeezerEnabledFm(): Boolean {
        return settingsPrefs.getBoolean(KEY_DEEZER_ENABLED_FM, true)  // Default: aktiviert
    }

    fun setDeezerEnabledFm(enabled: Boolean) {
        settingsPrefs.edit().putBoolean(KEY_DEEZER_ENABLED_FM, enabled).apply()
    }

    fun isDeezerEnabledDab(): Boolean {
        return settingsPrefs.getBoolean(KEY_DEEZER_ENABLED_DAB, false)  // Default: deaktiviert (DAB hat eigene Bilder)
    }

    fun setDeezerEnabledDab(enabled: Boolean) {
        settingsPrefs.edit().putBoolean(KEY_DEEZER_ENABLED_DAB, enabled).apply()
    }

    fun isShowDebugInfos(): Boolean {
        return settingsPrefs.getBoolean("show_debug_infos", false)
    }

    fun setShowDebugInfos(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("show_debug_infos", enabled).apply()
    }

    // Autostart Settings
    fun isAutoStartEnabled(): Boolean {
        return settingsPrefs.getBoolean("auto_start_enabled", false)
    }

    fun setAutoStartEnabled(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("auto_start_enabled", enabled).apply()
    }

    // Auto-Background Settings
    fun isAutoBackgroundEnabled(): Boolean {
        return settingsPrefs.getBoolean("auto_background_enabled", false)
    }

    fun setAutoBackgroundEnabled(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("auto_background_enabled", enabled).apply()
    }

    fun getAutoBackgroundDelay(): Int {
        return settingsPrefs.getInt("auto_background_delay", 5)
    }

    fun setAutoBackgroundDelay(seconds: Int) {
        settingsPrefs.edit().putInt("auto_background_delay", seconds).apply()
    }

    fun isAutoBackgroundOnlyOnBoot(): Boolean {
        return settingsPrefs.getBoolean("auto_background_only_on_boot", true)
    }

    fun setAutoBackgroundOnlyOnBoot(onlyOnBoot: Boolean) {
        settingsPrefs.edit().putBoolean("auto_background_only_on_boot", onlyOnBoot).apply()
    }

    // Dark Mode Settings
    // 0 = System, 1 = Light, 2 = Dark
    fun getDarkModePreference(): Int {
        return settingsPrefs.getInt("dark_mode_preference", 0)
    }

    fun setDarkModePreference(mode: Int) {
        settingsPrefs.edit().putInt("dark_mode_preference", mode).apply()
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

    fun isShowFavoritesOnlyDab(): Boolean {
        return settingsPrefs.getBoolean("show_favorites_only_dab", false)
    }

    fun setShowFavoritesOnlyDab(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("show_favorites_only_dab", enabled).apply()
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

    // Deezer Enabled/Disabled Toggle - per station (frequency)
    // Default is enabled, we store frequencies where it's DISABLED
    fun isDeezerEnabledForFrequency(frequency: Float): Boolean {
        val disabledFreqs = settingsPrefs.getStringSet("deezer_disabled_frequencies", emptySet()) ?: emptySet()
        val freqKey = "%.1f".format(frequency)
        return !disabledFreqs.contains(freqKey)
    }

    fun setDeezerEnabledForFrequency(frequency: Float, enabled: Boolean) {
        val disabledFreqs = settingsPrefs.getStringSet("deezer_disabled_frequencies", emptySet())?.toMutableSet() ?: mutableSetOf()
        val freqKey = "%.1f".format(frequency)
        if (enabled) {
            disabledFreqs.remove(freqKey)
        } else {
            disabledFreqs.add(freqKey)
        }
        settingsPrefs.edit().putStringSet("deezer_disabled_frequencies", disabledFreqs).apply()
    }

    // Deezer Local Cache Setting
    fun isDeezerCacheEnabled(): Boolean {
        return settingsPrefs.getBoolean("deezer_cache_enabled", true) // Default: enabled
    }

    fun setDeezerCacheEnabled(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("deezer_cache_enabled", enabled).apply()
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

        // Nur Favoriten aus bestehenden Sendern behalten
        val favoritesMap = existingStations
            .filter { it.isFavorite }
            .associateBy { (it.frequency * 10).toInt() }
            .toMutableMap()

        // Neue Senderliste aufbauen
        val resultMap = mutableMapOf<Int, RadioStation>()

        // Zuerst alle gescannten Sender hinzufügen
        for (scanned in scannedStations) {
            val freqKey = (scanned.frequency * 10).toInt()
            val existingFavorite = favoritesMap[freqKey]

            if (existingFavorite != null) {
                // Sender ist ein Favorit
                if (overwriteFavorites) {
                    // Überschreiben aber Favorit-Status behalten
                    resultMap[freqKey] = scanned.copy(isFavorite = true)
                } else {
                    // Favorit behalten, nur Name updaten wenn leer
                    if (existingFavorite.name.isNullOrBlank() && !scanned.name.isNullOrBlank()) {
                        resultMap[freqKey] = existingFavorite.copy(name = scanned.name, rssi = scanned.rssi)
                    } else {
                        resultMap[freqKey] = existingFavorite
                    }
                }
                // Aus favoritesMap entfernen (wurde verarbeitet)
                favoritesMap.remove(freqKey)
            } else {
                // Kein Favorit - neuen Sender hinzufügen
                resultMap[freqKey] = scanned
            }
        }

        // Übrige Favoriten hinzufügen (die nicht im Scan gefunden wurden)
        for ((freqKey, favorite) in favoritesMap) {
            resultMap[freqKey] = favorite
        }

        val mergedStations = resultMap.values.sortedBy { it.frequency }

        // Speichern
        if (isAM) {
            saveAmStations(mergedStations)
        } else {
            saveFmStations(mergedStations)
        }

        return mergedStations
    }

    /**
     * Fügt gescannte DAB-Sender zur Liste hinzu.
     * Merge key ist serviceId statt Frequenz.
     */
    fun mergeDabScannedStations(scannedStations: List<RadioStation>): List<RadioStation> {
        val existingStations = loadDabStations()
        val overwriteFavorites = isOverwriteFavorites()

        val favoritesMap = existingStations
            .filter { it.isFavorite }
            .associateBy { it.serviceId }
            .toMutableMap()

        val resultMap = mutableMapOf<Int, RadioStation>()

        for (scanned in scannedStations) {
            val existingFavorite = favoritesMap[scanned.serviceId]

            if (existingFavorite != null) {
                if (overwriteFavorites) {
                    resultMap[scanned.serviceId] = scanned.copy(isFavorite = true)
                } else {
                    resultMap[scanned.serviceId] = existingFavorite
                }
                favoritesMap.remove(scanned.serviceId)
            } else {
                resultMap[scanned.serviceId] = scanned
            }
        }

        for ((serviceId, favorite) in favoritesMap) {
            resultMap[serviceId] = favorite
        }

        val mergedStations = resultMap.values.sortedBy { it.name ?: it.ensembleLabel ?: "" }
        saveDabStations(mergedStations)
        return mergedStations
    }

    // Debug Window States
    fun setDebugWindowOpen(windowId: String, isOpen: Boolean) {
        settingsPrefs.edit().putBoolean("debug_window_open_$windowId", isOpen).apply()
    }

    fun isDebugWindowOpen(windowId: String, default: Boolean = false): Boolean {
        return settingsPrefs.getBoolean("debug_window_open_$windowId", default)
    }

    fun setDebugWindowPosition(windowId: String, x: Float, y: Float) {
        settingsPrefs.edit()
            .putFloat("debug_window_x_$windowId", x)
            .putFloat("debug_window_y_$windowId", y)
            .apply()
    }

    fun getDebugWindowPositionX(windowId: String): Float {
        return settingsPrefs.getFloat("debug_window_x_$windowId", -1f)
    }

    fun getDebugWindowPositionY(windowId: String): Float {
        return settingsPrefs.getFloat("debug_window_y_$windowId", -1f)
    }

    // Now Playing Animation Setting
    // 0 = None, 1 = Slide, 2 = Fade
    fun getNowPlayingAnimation(): Int {
        return settingsPrefs.getInt("now_playing_animation", 1) // Default: Slide
    }

    fun setNowPlayingAnimation(type: Int) {
        settingsPrefs.edit().putInt("now_playing_animation", type).apply()
    }

    // Correction Helpers Setting (Trash + Refresh buttons in Now Playing bar)
    fun isCorrectionHelpersEnabled(): Boolean {
        return settingsPrefs.getBoolean("correction_helpers_enabled", false)
    }

    fun setCorrectionHelpersEnabled(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("correction_helpers_enabled", enabled).apply()
    }

    // Show Logos in Favorites List
    fun isShowLogosInFavorites(): Boolean {
        return settingsPrefs.getBoolean("show_logos_in_favorites", true)
    }

    fun setShowLogosInFavorites(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("show_logos_in_favorites", enabled).apply()
    }

    fun isCarouselMode(): Boolean {
        return settingsPrefs.getBoolean("carousel_mode", false)
    }

    fun setCarouselMode(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("carousel_mode", enabled).apply()
    }

    /**
     * Get carousel mode for specific radio mode (FM, AM, DAB)
     */
    fun isCarouselModeForRadioMode(radioMode: String): Boolean {
        return settingsPrefs.getBoolean("carousel_mode_$radioMode", false)
    }

    /**
     * Set carousel mode for specific radio mode (FM, AM, DAB)
     */
    fun setCarouselModeForRadioMode(radioMode: String, enabled: Boolean) {
        settingsPrefs.edit().putBoolean("carousel_mode_$radioMode", enabled).apply()
    }

    // Import Dialog - nur einmal beim ersten Start fragen
    fun hasAskedAboutImport(): Boolean {
        return settingsPrefs.getBoolean("asked_about_import", false)
    }

    fun setAskedAboutImport(asked: Boolean) {
        settingsPrefs.edit().putBoolean("asked_about_import", asked).apply()
    }

    // Station Change Toast (für Lenkradtasten im Hintergrund)
    fun isShowStationChangeToast(): Boolean {
        return settingsPrefs.getBoolean("show_station_change_toast", true)
    }

    fun setShowStationChangeToast(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("show_station_change_toast", enabled).apply()
    }

    // Permanent Station Overlay (Debug - zeigt Overlay permanent an)
    fun isPermanentStationOverlay(): Boolean {
        return settingsPrefs.getBoolean("permanent_station_overlay", false)
    }

    fun setPermanentStationOverlay(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("permanent_station_overlay", enabled).apply()
    }

    // Revert Prev/Next (Lenkradtasten Richtung umkehren)
    fun isRevertPrevNext(): Boolean {
        return settingsPrefs.getBoolean("revert_prev_next", false)
    }

    fun setRevertPrevNext(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("revert_prev_next", enabled).apply()
    }

    // DAB Visualizer Settings
    fun isDabVisualizerEnabled(): Boolean {
        return settingsPrefs.getBoolean("dab_visualizer_enabled", true) // Default: enabled
    }

    fun setDabVisualizerEnabled(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("dab_visualizer_enabled", enabled).apply()
    }

    // 0 = Bars, 1 = Waveform, 2 = Circular
    fun getDabVisualizerStyle(): Int {
        return settingsPrefs.getInt("dab_visualizer_style", 0) // Default: Bars
    }

    fun setDabVisualizerStyle(style: Int) {
        settingsPrefs.edit().putInt("dab_visualizer_style", style).apply()
    }

    // DAB Recording Settings
    fun getDabRecordingPath(): String? {
        return settingsPrefs.getString("dab_recording_path", null)
    }

    fun setDabRecordingPath(path: String?) {
        settingsPrefs.edit().putString("dab_recording_path", path).apply()
    }

    fun isDabRecordingEnabled(): Boolean {
        return getDabRecordingPath() != null
    }

    // Tick Sound Settings (Senderwechsel)
    fun isTickSoundEnabled(): Boolean {
        return settingsPrefs.getBoolean(KEY_TICK_SOUND_ENABLED, false) // Default: aus
    }

    fun setTickSoundEnabled(enabled: Boolean) {
        settingsPrefs.edit().putBoolean(KEY_TICK_SOUND_ENABLED, enabled).apply()
    }

    /**
     * Tick Sound Lautstärke (0-100)
     */
    fun getTickSoundVolume(): Int {
        return settingsPrefs.getInt(KEY_TICK_SOUND_VOLUME, 50) // Default: 50%
    }

    fun setTickSoundVolume(volume: Int) {
        settingsPrefs.edit().putInt(KEY_TICK_SOUND_VOLUME, volume.coerceIn(0, 100)).apply()
    }
}
