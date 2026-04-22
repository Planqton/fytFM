package at.planqton.fytfm.ui.logotemplate

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import at.planqton.fytfm.data.logo.LogoTemplateAdapter
import at.planqton.fytfm.data.logo.RadioLogoRepository
import at.planqton.fytfm.data.logo.RadioLogoTemplate
import at.planqton.fytfm.data.logo.StationLogo
import at.planqton.fytfm.data.logo.StationLogoAdapter
import at.planqton.fytfm.ui.ImageResult
import at.planqton.fytfm.ui.ImageSearchAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

class LogoTemplateDialogFragment : DialogFragment() {

    interface LogoTemplateCallback {
        fun getRadioLogoRepository(): RadioLogoRepository
        fun getRadioAreaName(area: Int): String
        fun getCurrentRadioArea(): Int
        fun onTemplateSelected()
        fun onLogosUpdated()
    }

    private var callback: LogoTemplateCallback? = null
    private var importCallback: ((Boolean) -> Unit)? = null

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                importLogoTemplateFromUri(uri)
            }
        } else {
            importCallback?.invoke(false)
            importCallback = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.TransparentDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_logo_template, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        callback = activity as? LogoTemplateCallback
        if (callback == null) {
            dismiss()
            return
        }

        setupDialog(view)
    }

    private fun setupDialog(view: View) {
        val cb = callback ?: return
        val repository = cb.getRadioLogoRepository()
        val currentArea = cb.getCurrentRadioArea()

        val textCurrentArea = view.findViewById<TextView>(R.id.textCurrentArea)
        textCurrentArea.text = getString(R.string.region_format, cb.getRadioAreaName(currentArea))

        val recyclerTemplates = view.findViewById<RecyclerView>(R.id.recyclerTemplates)
        val textEmptyTemplates = view.findViewById<TextView>(R.id.textEmptyTemplates)

        recyclerTemplates.layoutManager = LinearLayoutManager(requireContext())

        val templates = repository.getTemplatesForArea(currentArea)
        val activeTemplateName = repository.getActiveTemplateName()

        val adapter = LogoTemplateAdapter(
            templates = templates,
            selectedName = activeTemplateName,
            onSelect = { template ->
                repository.setActiveTemplate(template.name)
                dismiss()
                cb.onTemplateSelected()
            },
            onEdit = { template ->
                showTemplateEditorDialog(template) {
                    val newTemplates = repository.getTemplatesForArea(currentArea)
                    (recyclerTemplates.adapter as? LogoTemplateAdapter)
                        ?.updateTemplates(newTemplates, repository.getActiveTemplateName())
                    updateEmptyState(newTemplates, recyclerTemplates, textEmptyTemplates)
                    repository.invalidateCache()
                    cb.onLogosUpdated()
                }
            },
            onExport = { template ->
                exportLogoTemplate(template)
            },
            onDelete = { template ->
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.delete_template_title)
                    .setMessage(getString(R.string.delete_template_message, template.name))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        repository.deleteTemplate(template.name)
                        val newTemplates = repository.getTemplatesForArea(currentArea)
                        (recyclerTemplates.adapter as? LogoTemplateAdapter)
                            ?.updateTemplates(newTemplates, repository.getActiveTemplateName())
                        updateEmptyState(newTemplates, recyclerTemplates, textEmptyTemplates)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        )

        recyclerTemplates.adapter = adapter
        updateEmptyState(templates, recyclerTemplates, textEmptyTemplates)

        // Import button
        view.findViewById<View>(R.id.btnImportTemplate).setOnClickListener {
            importLogoTemplate { imported ->
                if (imported) {
                    val newTemplates = repository.getTemplatesForArea(currentArea)
                    adapter.updateTemplates(newTemplates, repository.getActiveTemplateName())
                    updateEmptyState(newTemplates, recyclerTemplates, textEmptyTemplates)
                }
            }
        }

        // No template button
        view.findViewById<View>(R.id.btnNoTemplate).setOnClickListener {
            repository.setActiveTemplate(null)
            dismiss()
            cb.onTemplateSelected()
        }
    }

    private fun updateEmptyState(
        templates: List<RadioLogoTemplate>,
        recycler: RecyclerView,
        emptyView: TextView
    ) {
        if (templates.isEmpty()) {
            recycler.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recycler.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
        }
    }

    private fun exportLogoTemplate(template: RadioLogoTemplate) {
        val context = requireContext()
        try {
            val exportDir = File(context.cacheDir, "export")
            if (!exportDir.exists()) exportDir.mkdirs()
            val fileName = "${template.name.replace(" ", "_")}.json"
            val file = File(exportDir, fileName)
            callback?.getRadioLogoRepository()?.exportTemplateToFile(template, file)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Logo Template: ${template.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Template teilen"))
        } catch (e: Exception) {
            Toast.makeText(context, getString(R.string.export_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun importLogoTemplate(onComplete: (Boolean) -> Unit) {
        importCallback = onComplete
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "*/*"))
        }
        importLauncher.launch(intent)
    }

    private fun importLogoTemplateFromUri(uri: Uri) {
        val context = requireContext()
        val repository = callback?.getRadioLogoRepository() ?: return

        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val jsonString = inputStream.bufferedReader().readText()
                val template = repository.importTemplate(jsonString)
                repository.saveTemplate(template)
                Toast.makeText(context, getString(R.string.imported_template, template.name), Toast.LENGTH_SHORT).show()
                importCallback?.invoke(true)
            } ?: run {
                Toast.makeText(context, getString(R.string.file_read_error), Toast.LENGTH_SHORT).show()
                importCallback?.invoke(false)
            }
        } catch (e: Exception) {
            Toast.makeText(context, getString(R.string.import_failed, e.message), Toast.LENGTH_SHORT).show()
            importCallback?.invoke(false)
        }
        importCallback = null
    }

    private fun showTemplateEditorDialog(template: RadioLogoTemplate, onSaved: () -> Unit) {
        val context = requireContext()
        val repository = callback?.getRadioLogoRepository() ?: return

        val dialogView = layoutInflater.inflate(R.layout.dialog_template_editor, null)

        val dialog = AlertDialog.Builder(context, R.style.TransparentDialog)
            .setView(dialogView)
            .create()

        val editTemplateName = dialogView.findViewById<EditText>(R.id.editTemplateName)
        val textStationCount = dialogView.findViewById<TextView>(R.id.textStationCount)
        val recyclerStations = dialogView.findViewById<RecyclerView>(R.id.recyclerStations)
        val textEmptyStations = dialogView.findViewById<TextView>(R.id.textEmptyStations)
        val btnAddStation = dialogView.findViewById<View>(R.id.btnAddStation)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancelEditor)
        val btnSave = dialogView.findViewById<View>(R.id.btnSaveTemplate)

        editTemplateName.setText(template.name)

        recyclerStations.layoutManager = LinearLayoutManager(context)

        val stationsList = template.stations.toMutableList()

        fun updateStationCount() {
            textStationCount.text = getString(R.string.stations_count, stationsList.size)
            if (stationsList.isEmpty()) {
                recyclerStations.visibility = View.GONE
                textEmptyStations.visibility = View.VISIBLE
            } else {
                recyclerStations.visibility = View.VISIBLE
                textEmptyStations.visibility = View.GONE
            }
        }

        val stationAdapter = StationLogoAdapter(
            stations = stationsList,
            onEdit = { position, station ->
                val currentTemplateName = editTemplateName.text.toString().trim().ifBlank { template.name }
                showStationEditorDialog(station, currentTemplateName) { updatedStation ->
                    stationsList[position] = updatedStation
                    (recyclerStations.adapter as? StationLogoAdapter)?.updateStation(position, updatedStation)
                }
            },
            onDelete = { position, _ ->
                AlertDialog.Builder(context)
                    .setTitle(R.string.delete_station_title)
                    .setMessage(R.string.delete_entry_message)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        stationsList.removeAt(position)
                        (recyclerStations.adapter as? StationLogoAdapter)?.removeStation(position)
                        updateStationCount()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        )

        recyclerStations.adapter = stationAdapter
        updateStationCount()

        btnAddStation.setOnClickListener {
            val currentTemplateName = editTemplateName.text.toString().trim().ifBlank { template.name }
            showStationEditorDialog(null, currentTemplateName) { newStation ->
                stationsList.add(newStation)
                stationAdapter.addStation(newStation)
                updateStationCount()
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val newName = editTemplateName.text.toString().trim()
            if (newName.isBlank()) {
                Toast.makeText(context, getString(R.string.name_cannot_be_empty), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (newName != template.name && repository.getTemplates().any { it.name == newName }) {
                Toast.makeText(context, getString(R.string.template_name_exists), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (newName != template.name) {
                repository.deleteTemplate(template.name)
            }

            val updatedTemplate = template.copy(
                name = newName,
                stations = stationsList.toList()
            )
            repository.saveTemplate(updatedTemplate)

            if (repository.getActiveTemplateName() == template.name ||
                repository.getActiveTemplateName() == newName) {
                repository.setActiveTemplate(newName)
            }

            Toast.makeText(context, getString(R.string.template_saved), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            onSaved()
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun showStationEditorDialog(
        station: StationLogo?,
        templateName: String,
        onSave: (StationLogo) -> Unit
    ) {
        val context = requireContext()
        val dialogView = layoutInflater.inflate(R.layout.dialog_station_editor, null)

        val dialog = AlertDialog.Builder(context, R.style.TransparentDialog)
            .setView(dialogView)
            .create()

        val editPs = dialogView.findViewById<EditText>(R.id.editStationPs)
        val editPi = dialogView.findViewById<EditText>(R.id.editStationPi)
        val editFrequencies = dialogView.findViewById<EditText>(R.id.editStationFrequencies)
        val editLogoUrl = dialogView.findViewById<EditText>(R.id.editStationLogoUrl)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancelStation)
        val btnSave = dialogView.findViewById<View>(R.id.btnSaveStation)
        val btnSearchLogo = dialogView.findViewById<ImageButton>(R.id.btnSearchLogo)
        val title = dialogView.findViewById<TextView>(R.id.textStationEditorTitle)
        val layoutSaveProgress = dialogView.findViewById<View>(R.id.layoutSaveProgress)

        title.text = if (station == null) "Sender hinzufügen" else "Sender bearbeiten"

        station?.let {
            editPs.setText(it.ps ?: "")
            editPi.setText(it.pi ?: "")
            editFrequencies.setText(it.frequencies?.joinToString(", ") { f -> "%.1f".format(Locale.US, f) } ?: "")
            editLogoUrl.setText(it.logoUrl)
        }

        btnSearchLogo.setOnClickListener {
            val prefilledQuery = editPs.text.toString().trim().takeIf { it.isNotBlank() } ?: ""
            showImageSearchDialog(prefilledQuery) { selectedUrl ->
                editLogoUrl.setText(selectedUrl)
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val ps = editPs.text.toString().trim().takeIf { it.isNotBlank() }
            val pi = editPi.text.toString().trim().takeIf { it.isNotBlank() }
            val frequenciesStr = editFrequencies.text.toString().trim()
            val frequencies = frequenciesStr.split(",", ";", " ")
                .mapNotNull { it.trim().replace(",", ".").toFloatOrNull() }
                .takeIf { it.isNotEmpty() }
            val logoUrl = editLogoUrl.text.toString().trim()

            if (ps == null && pi == null && frequencies == null) {
                Toast.makeText(context, getString(R.string.provide_ps_pi_or_freq), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (logoUrl.isBlank()) {
                Toast.makeText(context, getString(R.string.logo_url_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val urlChanged = station?.logoUrl != logoUrl

            if (urlChanged && logoUrl.startsWith("http")) {
                btnSave.isEnabled = false
                btnCancel.isEnabled = false
                layoutSaveProgress.visibility = View.VISIBLE

                CoroutineScope(Dispatchers.Main).launch {
                    var localPath: String? = station?.localPath

                    try {
                        localPath = withContext(Dispatchers.IO) {
                            downloadLogo(templateName, logoUrl)
                        }

                        if (!dialog.isShowing) return@launch

                        val newStation = StationLogo(
                            ps = ps,
                            pi = pi,
                            frequencies = frequencies,
                            logoUrl = logoUrl,
                            localPath = localPath
                        )

                        dialog.dismiss()
                        onSave(newStation)
                        Toast.makeText(context, getString(R.string.logo_downloaded), Toast.LENGTH_SHORT).show()

                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Failed to download logo: ${e.message}")
                        if (!dialog.isShowing) return@launch

                        layoutSaveProgress.visibility = View.GONE
                        btnSave.isEnabled = true
                        btnCancel.isEnabled = true
                        Toast.makeText(context, getString(R.string.logo_download_failed, e.message), Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                val newStation = StationLogo(
                    ps = ps,
                    pi = pi,
                    frequencies = frequencies,
                    logoUrl = logoUrl,
                    localPath = station?.localPath
                )

                dialog.dismiss()
                onSave(newStation)
            }
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun downloadLogo(templateName: String, logoUrl: String): String {
        val context = requireContext()
        val safeName = templateName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val templateDir = File(context.filesDir, "logos/$safeName").apply {
            if (!exists()) mkdirs()
        }

        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(logoUrl.toByteArray())
        val filename = hash.joinToString("") { "%02x".format(it) } + ".png"
        val localFile = File(templateDir, filename)

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(logoUrl)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(localFile).use { output ->
                        input.copyTo(output)
                    }
                }
                return localFile.absolutePath
            } else {
                throw Exception("HTTP ${response.code}")
            }
        }
    }

    private fun showImageSearchDialog(prefilledQuery: String, onSelect: (String) -> Unit) {
        val context = requireContext()
        val dialogView = layoutInflater.inflate(R.layout.dialog_image_search, null)

        val dialog = AlertDialog.Builder(context, R.style.TransparentDialog)
            .setView(dialogView)
            .create()

        val editSearchQuery = dialogView.findViewById<EditText>(R.id.editSearchQuery)
        val btnSearch = dialogView.findViewById<ImageButton>(R.id.btnSearch)
        val progressLoading = dialogView.findViewById<ProgressBar>(R.id.progressLoading)
        val recyclerImages = dialogView.findViewById<RecyclerView>(R.id.recyclerImages)
        val textEmptyResults = dialogView.findViewById<TextView>(R.id.textEmptyResults)
        val btnClose = dialogView.findViewById<View>(R.id.btnCloseSearch)

        editSearchQuery.setText(prefilledQuery)

        recyclerImages.layoutManager = GridLayoutManager(context, 3)

        val imageAdapter = ImageSearchAdapter { image ->
            dialog.dismiss()
            onSelect(image.url)
        }
        recyclerImages.adapter = imageAdapter

        fun performSearch(query: String) {
            if (query.isBlank()) {
                Toast.makeText(context, getString(R.string.enter_search_term), Toast.LENGTH_SHORT).show()
                return
            }

            progressLoading.visibility = View.VISIBLE
            recyclerImages.visibility = View.GONE
            textEmptyResults.visibility = View.GONE

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val results = withContext(Dispatchers.IO) {
                        searchRadioLogos(query)
                    }

                    if (!dialog.isShowing) return@launch

                    progressLoading.visibility = View.GONE

                    if (results.isEmpty()) {
                        textEmptyResults.text = getString(R.string.no_results_for_query, query)
                        textEmptyResults.visibility = View.VISIBLE
                        recyclerImages.visibility = View.GONE
                    } else {
                        textEmptyResults.visibility = View.GONE
                        recyclerImages.visibility = View.VISIBLE
                        imageAdapter.setImages(results)
                    }
                } catch (e: Exception) {
                    if (!dialog.isShowing) return@launch
                    progressLoading.visibility = View.GONE
                    textEmptyResults.text = getString(R.string.error_prefix, e.message)
                    textEmptyResults.visibility = View.VISIBLE
                    recyclerImages.visibility = View.GONE
                }
            }
        }

        btnSearch.setOnClickListener {
            performSearch(editSearchQuery.text.toString().trim())
        }

        editSearchQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(editSearchQuery.text.toString().trim())
                true
            } else false
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()

        if (prefilledQuery.isNotBlank()) {
            performSearch(prefilledQuery)
        }
    }

    private fun searchRadioLogos(query: String): List<ImageResult> {
        val results = mutableListOf<ImageResult>()
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        // Step 1: Get vqd token from DuckDuckGo
        val tokenUrl = "https://duckduckgo.com/?q=$encodedQuery"
        val tokenRequest = Request.Builder()
            .url(tokenUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        val vqd: String
        client.newCall(tokenRequest).execute().use { response ->
            if (!response.isSuccessful) return results
            val html = response.body?.string() ?: return results

            val vqdMatch = Regex("vqd=([\"'])([^\"']+)\\1").find(html)
                ?: Regex("vqd=([\\d-]+)").find(html)
            vqd = vqdMatch?.groupValues?.lastOrNull() ?: return results
        }

        // Step 2: Search images with token
        val imageUrl = "https://duckduckgo.com/i.js?l=de-de&o=json&q=$encodedQuery&vqd=$vqd&f=,,,&p=1"
        val imageRequest = Request.Builder()
            .url(imageUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", "https://duckduckgo.com/")
            .build()

        client.newCall(imageRequest).execute().use { response ->
            if (!response.isSuccessful) return results
            val body = response.body?.string() ?: return results

            val json = JSONObject(body)
            val resultsArray = json.optJSONArray("results") ?: return results

            val seenUrls = mutableSetOf<String>()
            val maxResults = minOf(resultsArray.length(), 30)

            for (i in 0 until maxResults) {
                val item = resultsArray.getJSONObject(i)
                val imgUrl = item.optString("image", "")
                val title = item.optString("title", "Bild")

                if (imgUrl.isNotBlank() &&
                    imgUrl.startsWith("http") &&
                    !imgUrl.lowercase().endsWith(".svg") &&
                    !imgUrl.lowercase().contains(".svg?") &&
                    !seenUrls.contains(imgUrl)) {
                    seenUrls.add(imgUrl)
                    results.add(ImageResult(imgUrl, title))
                }
            }
        }

        return results
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    companion object {
        const val TAG = "LogoTemplateDialog"

        fun newInstance(): LogoTemplateDialogFragment {
            return LogoTemplateDialogFragment()
        }
    }
}
