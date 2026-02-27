package at.planqton.fytfm.data.logo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Repository for managing radio logo templates.
 * Handles template storage, logo downloads, and matching.
 */
class RadioLogoRepository(private val context: Context) {

    companion object {
        private const val TAG = "RadioLogoRepo"
        private const val PREFS_NAME = "radio_logos"
        private const val KEY_TEMPLATES = "templates"
        private const val KEY_ACTIVE_TEMPLATE = "active_template"
        private const val LOGOS_DIR = "logos"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Cached active template for fast lookup
    private var cachedTemplate: RadioLogoTemplate? = null

    /**
     * Get the logos directory
     */
    private fun getLogosDir(): File {
        return File(context.filesDir, LOGOS_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Get directory for a specific template
     */
    private fun getTemplateDir(templateName: String): File {
        val safeName = templateName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(getLogosDir(), safeName).apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Generate a hash for a URL to use as filename
     */
    private fun urlToFilename(url: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(url.toByteArray())
        return hash.joinToString("") { "%02x".format(it) } + ".png"
    }

    /**
     * Get all saved templates
     */
    fun getTemplates(): List<RadioLogoTemplate> {
        val json = prefs.getString(KEY_TEMPLATES, "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            val templates = (0 until array.length()).map {
                RadioLogoTemplate.fromJson(array.getJSONObject(it))
            }
            Log.d(TAG, "getTemplates: Loaded ${templates.size} templates")
            templates.forEach { t ->
                Log.d(TAG, "  - ${t.name}: ${t.stations.size} stations")
            }
            templates
        } catch (e: Exception) {
            Log.e(TAG, "Error loading templates: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get templates for a specific area
     * Area IDs: 0=USA, 1=Latin America, 2=Europe, 3=Russia, 4=Japan
     */
    fun getTemplatesForArea(area: Int): List<RadioLogoTemplate> {
        return getTemplates().filter { it.area == area }
    }

    /**
     * Save all templates
     */
    private fun saveTemplates(templates: List<RadioLogoTemplate>) {
        val array = JSONArray()
        templates.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_TEMPLATES, array.toString()).apply()
    }

    /**
     * Add or update a template
     */
    fun saveTemplate(template: RadioLogoTemplate) {
        val templates = getTemplates().toMutableList()
        val existingIndex = templates.indexOfFirst { it.name == template.name }
        Log.d(TAG, "saveTemplate: name=${template.name}, stations=${template.stations.size}, existingIndex=$existingIndex")
        if (existingIndex >= 0) {
            templates[existingIndex] = template
            Log.d(TAG, "saveTemplate: Updated existing template")
        } else {
            templates.add(template)
            Log.d(TAG, "saveTemplate: Added new template")
        }
        saveTemplates(templates)
        Log.d(TAG, "saveTemplate: Saved ${templates.size} templates total")

        // Update cache if this is the active template
        if (getActiveTemplateName() == template.name) {
            cachedTemplate = template
        }
    }

    /**
     * Delete a template and its logos
     */
    fun deleteTemplate(templateName: String) {
        val templates = getTemplates().toMutableList()
        templates.removeAll { it.name == templateName }
        saveTemplates(templates)

        // Delete logo files
        val dir = getTemplateDir(templateName)
        dir.deleteRecursively()

        // Clear active if this was active
        if (getActiveTemplateName() == templateName) {
            setActiveTemplate(null)
        }
    }

    /**
     * Get the active template name
     */
    fun getActiveTemplateName(): String? {
        return prefs.getString(KEY_ACTIVE_TEMPLATE, null)
    }

    /**
     * Set the active template.
     * Deletes old template's logo files when switching to a different template.
     */
    fun setActiveTemplate(templateName: String?) {
        val oldTemplateName = getActiveTemplateName()

        // Delete old template's logo files if switching to a different template
        if (oldTemplateName != null && oldTemplateName != templateName) {
            deleteTemplateLogos(oldTemplateName)
        }

        prefs.edit().putString(KEY_ACTIVE_TEMPLATE, templateName).apply()
        cachedTemplate = if (templateName != null) {
            getTemplates().find { it.name == templateName }
        } else {
            null
        }
    }

    /**
     * Delete only the logo files for a template (keeps template metadata)
     */
    fun deleteTemplateLogos(templateName: String) {
        val dir = getTemplateDir(templateName)
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
            Log.d(TAG, "Deleted logos for template: $templateName")
        }
    }

    /**
     * Invalidate the cached template to force reload
     */
    fun invalidateCache() {
        cachedTemplate = null
        Log.d(TAG, "Cache invalidated")
    }

    /**
     * Get the active template
     */
    fun getActiveTemplate(): RadioLogoTemplate? {
        if (cachedTemplate != null) return cachedTemplate

        val name = getActiveTemplateName() ?: return null
        cachedTemplate = getTemplates().find { it.name == name }
        return cachedTemplate
    }

    /**
     * Download all logos for a template.
     * Returns list of failed station entries (ps/pi/frequency for identification).
     */
    suspend fun downloadLogos(
        template: RadioLogoTemplate,
        onProgress: (current: Int, total: Int) -> Unit
    ): Pair<RadioLogoTemplate, List<String>> = withContext(Dispatchers.IO) {
        val failed = mutableListOf<String>()
        val updatedStations = mutableListOf<StationLogo>()
        val templateDir = getTemplateDir(template.name)

        template.stations.forEachIndexed { index, station ->
            onProgress(index + 1, template.stations.size)

            val localFile = File(templateDir, urlToFilename(station.logoUrl))

            try {
                // Download logo
                val request = Request.Builder()
                    .url(station.logoUrl)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.byteStream()?.use { input ->
                            FileOutputStream(localFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        updatedStations.add(station.copy(localPath = localFile.absolutePath))
                        Log.d(TAG, "Downloaded: ${station.logoUrl}")
                    } else {
                        throw Exception("HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download ${station.logoUrl}: ${e.message}")
                // Add identifier for failed download
                val identifier = station.ps ?: station.pi ?: station.frequencies?.firstOrNull()?.toString() ?: "Unknown"
                failed.add(identifier)
                // Keep station without local path
                updatedStations.add(station.copy(localPath = null))
            }
        }

        val updatedTemplate = template.copy(stations = updatedStations)
        Pair(updatedTemplate, failed)
    }

    /**
     * Find logo for a station by PI, PS, or frequency.
     * Returns local file path or null.
     */
    fun getLogoForStation(ps: String?, pi: Int?, frequency: Float?): String? {
        val template = getActiveTemplate() ?: return null

        var bestMatch: StationLogo? = null
        var bestPriority = 0

        for (station in template.stations) {
            val priority = station.matchPriority(ps, pi, frequency)
            if (priority > bestPriority) {
                bestPriority = priority
                bestMatch = station
            }
        }

        return bestMatch?.localPath
    }

    /**
     * Import a template from JSON string.
     * Does NOT download logos - call downloadLogos() separately.
     */
    fun importTemplate(jsonString: String): RadioLogoTemplate {
        return RadioLogoTemplate.fromJsonString(jsonString)
    }

    /**
     * Export a template to JSON string
     */
    fun exportTemplate(template: RadioLogoTemplate): String {
        // Export without local paths (they're device-specific)
        val cleanStations = template.stations.map { it.copy(localPath = null) }
        val cleanTemplate = template.copy(stations = cleanStations)
        return cleanTemplate.toJson().toString(2)
    }

    /**
     * Export a template to a file
     */
    fun exportTemplateToFile(template: RadioLogoTemplate, file: File) {
        file.writeText(exportTemplate(template))
    }

    /**
     * Import a template from a file
     */
    fun importTemplateFromFile(file: File): RadioLogoTemplate {
        return importTemplate(file.readText())
    }
}
