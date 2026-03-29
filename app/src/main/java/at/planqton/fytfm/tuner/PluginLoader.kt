package at.planqton.fytfm.tuner

import android.content.Context
import android.net.Uri
import android.util.Log
import at.planqton.fytfm.plugin.api.TunerHostApi
import at.planqton.fytfm.plugin.api.TunerPlugin
import dalvik.system.DexClassLoader
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import java.util.zip.ZipFile

/**
 * Lädt externe Plugins aus .zip-Dateien im plugins/ Verzeichnis.
 *
 * Plugin-ZIP Format:
 *   plugin.properties    - pluginClass=com.example.MyPlugin
 *                          pluginId=my_plugin
 *                          displayName=My Plugin
 *   classes.dex          - kompilierte Plugin-Klassen (DEX Format)
 */
class PluginLoader(private val context: Context) {

    companion object {
        private const val TAG = "PluginLoader"
        private const val PLUGINS_DIR = "plugins"
        private const val PLUGIN_PROPERTIES = "plugin.properties"
        private const val KEY_PLUGIN_CLASS = "pluginClass"
        private const val KEY_PLUGIN_ID = "pluginId"
        private const val KEY_DISPLAY_NAME = "displayName"
    }

    data class PluginInfo(
        val pluginId: String,
        val className: String,
        val displayName: String,
        val file: File,
        val isBuiltIn: Boolean = false
    )

    private val pluginsDir: File = File(context.filesDir, PLUGINS_DIR)

    fun ensureDirectories() {
        if (!pluginsDir.exists()) pluginsDir.mkdirs()
    }

    /**
     * Scannt das plugins/ Verzeichnis nach .zip-Dateien und liest deren Metadaten.
     */
    fun scanPlugins(): List<PluginInfo> {
        ensureDirectories()
        val infos = mutableListOf<PluginInfo>()

        pluginsDir.listFiles { f -> f.extension == "zip" }?.forEach { file ->
            try {
                val props = readPluginProperties(file)
                if (props != null) {
                    infos.add(PluginInfo(
                        pluginId = props.getProperty(KEY_PLUGIN_ID, ""),
                        className = props.getProperty(KEY_PLUGIN_CLASS, ""),
                        displayName = props.getProperty(KEY_DISPLAY_NAME, file.nameWithoutExtension),
                        file = file
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning plugin ${file.name}: ${e.message}")
            }
        }

        return infos
    }

    /**
     * Lädt ein Plugin aus einer ZIP-Datei via DexClassLoader.
     */
    fun loadPlugin(info: PluginInfo, hostApi: TunerHostApi): TunerPlugin? {
        try {
            val classLoader = DexClassLoader(
                info.file.absolutePath,
                null,  // dexOutputDir ignored on API 29+
                null,  // no native lib path
                context.classLoader  // parent = app classloader (für TunerPlugin interface)
            )
            val clazz = classLoader.loadClass(info.className)
            val plugin = clazz.getDeclaredConstructor().newInstance() as TunerPlugin
            plugin.initialize(hostApi)

            Log.i(TAG, "Loaded plugin: ${info.pluginId} (${info.className})")
            return plugin
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load plugin ${info.pluginId}: ${e.message}", e)
            return null
        }
    }

    /**
     * Lädt alle externen Plugins und gibt sie zurück.
     */
    fun loadAllPlugins(hostApi: TunerHostApi): List<TunerPlugin> {
        return scanPlugins().mapNotNull { loadPlugin(it, hostApi) }
    }

    /**
     * Importiert eine Plugin-ZIP von einer URI (File Picker).
     * Kopiert die Datei ins plugins/ Verzeichnis.
     */
    fun installPlugin(sourceUri: Uri): PluginInfo? {
        ensureDirectories()

        try {
            // Temporär kopieren um Properties zu lesen
            val tempFile = File(pluginsDir, "temp_import.zip")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            // Properties lesen und validieren
            val props = readPluginProperties(tempFile)
            if (props == null) {
                tempFile.delete()
                Log.e(TAG, "Invalid plugin: no plugin.properties found")
                return null
            }

            val pluginId = props.getProperty(KEY_PLUGIN_ID)
            if (pluginId.isNullOrBlank()) {
                tempFile.delete()
                Log.e(TAG, "Invalid plugin: no pluginId in properties")
                return null
            }

            // In finalen Dateinamen umbenennen
            val targetFile = File(pluginsDir, "$pluginId.zip")
            if (targetFile.exists()) targetFile.delete()
            tempFile.renameTo(targetFile)

            val info = PluginInfo(
                pluginId = pluginId,
                className = props.getProperty(KEY_PLUGIN_CLASS, ""),
                displayName = props.getProperty(KEY_DISPLAY_NAME, pluginId),
                file = targetFile
            )

            Log.i(TAG, "Installed plugin: $pluginId from ${sourceUri}")
            return info
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install plugin: ${e.message}", e)
            return null
        }
    }

    /**
     * Löscht ein externes Plugin.
     */
    fun deletePlugin(pluginId: String): Boolean {
        val file = File(pluginsDir, "$pluginId.zip")
        if (file.exists()) {
            val deleted = file.delete()
            Log.i(TAG, "Deleted plugin $pluginId: $deleted")
            return deleted
        }
        return false
    }

    /**
     * Gibt die Datei eines Plugins zurück (für Export/Share).
     */
    fun getPluginFile(pluginId: String): File? {
        val file = File(pluginsDir, "$pluginId.zip")
        return if (file.exists()) file else null
    }

    /**
     * Liste aller installierten Plugin-Infos.
     */
    fun getInstalledPluginInfos(): List<PluginInfo> = scanPlugins()

    private fun readPluginProperties(zipFile: File): Properties? {
        return try {
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry(PLUGIN_PROPERTIES) ?: return null
                val props = Properties()
                zip.getInputStream(entry).use { props.load(it) }
                props
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading plugin.properties from ${zipFile.name}: ${e.message}")
            null
        }
    }
}
