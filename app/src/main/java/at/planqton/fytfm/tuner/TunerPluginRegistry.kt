package at.planqton.fytfm.tuner

import android.util.Log
import at.planqton.fytfm.plugin.api.TunerPlugin

/**
 * Zentrale Registry für alle verfügbaren TunerPlugins.
 */
object TunerPluginRegistry {

    private const val TAG = "TunerPluginRegistry"

    data class RegisteredPlugin(
        val plugin: TunerPlugin,
        val builtIn: Boolean
    )

    private val plugins = mutableMapOf<String, RegisteredPlugin>()

    fun register(plugin: TunerPlugin, builtIn: Boolean = false) {
        val id = plugin.pluginId
        if (plugins.containsKey(id)) {
            Log.w(TAG, "Plugin '$id' already registered, replacing")
        }
        plugins[id] = RegisteredPlugin(plugin, builtIn)
        Log.i(TAG, "Registered plugin: $id (${plugin.displayName}, builtIn=$builtIn)")
    }

    fun unregister(pluginId: String) {
        plugins.remove(pluginId)
        Log.i(TAG, "Unregistered plugin: $pluginId")
    }

    fun getAll(): List<TunerPlugin> = plugins.values.map { it.plugin }

    fun get(pluginId: String): TunerPlugin? = plugins[pluginId]?.plugin

    fun getRegistered(pluginId: String): RegisteredPlugin? = plugins[pluginId]

    fun getAllRegistered(): List<RegisteredPlugin> = plugins.values.toList()

    fun isBuiltIn(pluginId: String): Boolean = plugins[pluginId]?.builtIn ?: false

    fun getPluginIds(): List<String> = plugins.keys.toList()

    fun clear() { plugins.clear() }
}
