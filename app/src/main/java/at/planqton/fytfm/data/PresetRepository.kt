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
}
