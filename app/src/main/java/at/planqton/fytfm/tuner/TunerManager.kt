package at.planqton.fytfm.tuner

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Verwaltet Tuner-Instanzen. Kennt keine konkreten Plugins -
 * greift nur auf die TunerPluginRegistry zu.
 */
class TunerManager(private val context: Context) {

    companion object {
        private const val TAG = "TunerManager"
        private const val PREFS_NAME = "tuner_instances"
        private const val KEY_INSTANCES = "instances"
        private const val KEY_ACTIVE_TUNER_ID = "active_tuner_id"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var instances: MutableList<TunerInstance> = mutableListOf()
    private var activeTunerId: String? = null
    private var activePlugin: TunerPlugin? = null

    init {
        loadInstances()
        activeTunerId = prefs.getString(KEY_ACTIVE_TUNER_ID, null)
    }

    // --- Instance CRUD ---

    fun getInstances(): List<TunerInstance> = instances.toList()

    fun addInstance(name: String, pluginId: String): TunerInstance {
        val plugin = TunerPluginRegistry.get(pluginId)
        val defaultFreq = plugin?.frequencyRange?.start ?: 0f

        val instance = TunerInstance(
            id = UUID.randomUUID().toString(),
            name = name,
            pluginId = pluginId,
            sortOrder = instances.size,
            lastFrequency = defaultFreq
        )
        instances.add(instance)
        saveInstances()
        Log.i(TAG, "Added tuner instance: ${instance.name} (plugin: $pluginId)")
        return instance
    }

    fun updateInstance(id: String, name: String? = null, pluginId: String? = null) {
        val idx = instances.indexOfFirst { it.id == id }
        if (idx >= 0) {
            instances[idx] = instances[idx].copy(
                name = name ?: instances[idx].name,
                pluginId = pluginId ?: instances[idx].pluginId
            )
            saveInstances()
        }
    }

    fun deleteInstance(id: String) {
        instances.removeAll { it.id == id }
        if (activeTunerId == id) {
            activeTunerId = instances.firstOrNull()?.id
            prefs.edit().putString(KEY_ACTIVE_TUNER_ID, activeTunerId).apply()
        }
        saveInstances()
    }

    // --- Plugin access ---

    fun getPluginForInstance(instance: TunerInstance): TunerPlugin? =
        TunerPluginRegistry.get(instance.pluginId)

    fun getAvailablePlugins(): List<TunerPlugin> =
        TunerPluginRegistry.getAll()

    // --- Active tuner ---

    fun getActiveTunerId(): String? = activeTunerId

    fun getActiveInstance(): TunerInstance? =
        instances.find { it.id == activeTunerId }

    fun getActivePlugin(): TunerPlugin? = activePlugin

    /**
     * Wechselt zur angegebenen Tuner-Instanz.
     * Deaktiviert das vorherige Plugin.
     */
    fun switchToTuner(id: String, callback: TunerPluginCallback): TunerPlugin {
        val newInstance = instances.find { it.id == id }
            ?: throw IllegalArgumentException("Tuner $id not found")
        val newPlugin = getPluginForInstance(newInstance)
            ?: throw IllegalArgumentException("Plugin '${newInstance.pluginId}' not registered")

        // Altes Plugin deaktivieren
        val oldInstance = getActiveInstance()
        if (oldInstance != null && oldInstance.id != id) {
            val oldPlugin = getPluginForInstance(oldInstance)
            if (oldPlugin?.isActive == true) {
                oldPlugin.deactivate()
            }
        }

        activeTunerId = id
        activePlugin = newPlugin
        prefs.edit().putString(KEY_ACTIVE_TUNER_ID, id).apply()

        newPlugin.setCallback(callback)
        newPlugin.activate(context, id)

        return newPlugin
    }

    // --- Per-instance state ---

    fun saveLastFrequency(tunerId: String, frequency: Float) {
        val idx = instances.indexOfFirst { it.id == tunerId }
        if (idx >= 0) {
            instances[idx] = instances[idx].copy(lastFrequency = frequency)
            saveInstances()
        }
    }

    fun getLastFrequency(tunerId: String): Float {
        return instances.find { it.id == tunerId }?.lastFrequency ?: 0f
    }

    fun saveLastDabService(tunerId: String, serviceLabel: String?) {
        val idx = instances.indexOfFirst { it.id == tunerId }
        if (idx >= 0) {
            instances[idx] = instances[idx].copy(lastDabServiceLabel = serviceLabel)
            saveInstances()
        }
    }

    /**
     * Löscht alle alten SharedPreferences (fm_presets, am_presets, fytfm_prefs).
     */
    fun clearLegacyCache() {
        Log.i(TAG, "Clearing legacy cache...")
        val legacyNames = listOf("fm_presets", "am_presets", "fytfm_prefs")
        for (name in legacyNames) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply()
        }
        Log.i(TAG, "Legacy cache cleared.")
    }

    // --- Persistence ---

    private fun loadInstances() {
        val json = prefs.getString(KEY_INSTANCES, null) ?: return

        try {
            val jsonArray = JSONArray(json)
            instances.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                instances.add(TunerInstance(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    pluginId = obj.getString("pluginId"),
                    sortOrder = obj.optInt("sortOrder", i),
                    lastFrequency = obj.optDouble("lastFrequency", 0.0).toFloat(),
                    lastDabServiceLabel = obj.optString("lastDabServiceLabel", "").takeIf { it.isNotEmpty() }
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading instances: ${e.message}")
            instances.clear()
        }
    }

    private fun saveInstances() {
        val jsonArray = JSONArray()
        instances.forEach { instance ->
            val obj = JSONObject().apply {
                put("id", instance.id)
                put("name", instance.name)
                put("pluginId", instance.pluginId)
                put("sortOrder", instance.sortOrder)
                put("lastFrequency", instance.lastFrequency.toDouble())
                put("lastDabServiceLabel", instance.lastDabServiceLabel ?: "")
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_INSTANCES, jsonArray.toString()).apply()
    }
}
