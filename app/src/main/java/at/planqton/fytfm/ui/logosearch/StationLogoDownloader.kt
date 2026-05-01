package at.planqton.fytfm.ui.logosearch

import android.app.ProgressDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import at.planqton.fytfm.R
import at.planqton.fytfm.data.RadioStation
import at.planqton.fytfm.data.logo.RadioLogoRepository
import at.planqton.fytfm.data.logo.RadioLogoTemplate
import at.planqton.fytfm.data.logo.StationLogo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads an image URL, resizes it to a reasonable max size, and stores it
 * as the station logo inside the active template (or the default
 * "Manual-Search" template when nothing is active). Extracted from the
 * previous inline implementation in MainActivity; uses [lifecycleScope] so
 * the background job gets cancelled on Activity destruction instead of
 * outliving it (old code used a free-floating `Thread`).
 */
class StationLogoDownloader(
    private val activity: AppCompatActivity,
    private val radioLogoRepository: RadioLogoRepository,
    /** Invoked on the main thread after a successful save — the Activity
     *  should refresh station-list / cover-source UI. */
    private val onLogoSaved: (stationName: String) -> Unit,
) {
    companion object {
        private const val TAG = "StationLogoDownloader"
        private const val MAX_DIMENSION = 512
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }

    fun downloadAndSave(
        imageUrl: String,
        station: RadioStation,
        mode: at.planqton.fytfm.FrequencyScaleView.RadioMode,
        onComplete: () -> Unit,
    ) {
        @Suppress("DEPRECATION") // matches the rest of the codebase
        val progress = ProgressDialog(activity).apply {
            setMessage(activity.getString(R.string.logo_downloading_progress))
            setCancelable(false)
            show()
        }

        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stationName = station.name ?: station.ensembleLabel ?: "Unknown"
                val bitmap = downloadAndDecode(imageUrl)
                val sized = resize(bitmap, MAX_DIMENSION)
                val logoFile = saveBitmapAsPng(sized, stationName)
                upsertStationLogoTemplate(station, stationName, logoFile)

                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    Toast.makeText(
                        activity,
                        activity.getString(at.planqton.fytfm.R.string.logo_saved_for_format, stationName),
                        Toast.LENGTH_SHORT,
                    ).show()
                    onLogoSaved(stationName)
                    onComplete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download/save logo: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    Toast.makeText(activity, activity.getString(at.planqton.fytfm.R.string.error_prefix, e.message ?: ""), Toast.LENGTH_SHORT).show()
                    onComplete()
                }
            }
        }
    }

    private fun downloadAndDecode(imageUrl: String): Bitmap {
        val connection = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            val raw = connection.inputStream.use { BitmapFactory.decodeStream(it) }
                ?: throw IllegalStateException("Bild konnte nicht dekodiert werden")
            // Hardware bitmaps can't be processed further — copy to software.
            return if (raw.config == Bitmap.Config.HARDWARE) {
                raw.copy(Bitmap.Config.ARGB_8888, false)
            } else raw
        } finally {
            connection.disconnect()
        }
    }

    private fun resize(bitmap: Bitmap, maxDimension: Int): Bitmap {
        if (bitmap.width <= maxDimension && bitmap.height <= maxDimension) return bitmap
        val scale = minOf(maxDimension.toFloat() / bitmap.width, maxDimension.toFloat() / bitmap.height)
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true,
        )
    }

    private fun saveBitmapAsPng(bitmap: Bitmap, stationName: String): File {
        val templateName = radioLogoRepository.getActiveTemplateName() ?: "Manual-Search"
        val logosDir = File(activity.filesDir, "logos/$templateName").apply { mkdirs() }
        val hash = MessageDigest.getInstance("MD5")
            .digest(stationName.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val logoFile = File(logosDir, "$hash.png")
        FileOutputStream(logoFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
        }
        return logoFile
    }

    private fun upsertStationLogoTemplate(
        station: RadioStation,
        stationName: String,
        logoFile: File,
    ) {
        val templateName = radioLogoRepository.getActiveTemplateName() ?: "Manual-Search"
        val existingTemplate = radioLogoRepository.getTemplates().find { it.name == templateName }
        val stations = existingTemplate?.stations?.toMutableList() ?: mutableListOf()

        // Drop any prior logo for the same station name (case-insensitive).
        stations.removeAll { it.ps.equals(stationName, ignoreCase = true) }
        stations += StationLogo(
            ps = stationName,
            logoUrl = "local://${logoFile.name}",
            localPath = logoFile.absolutePath,
        )

        val newTemplate = RadioLogoTemplate(
            name = templateName,
            area = existingTemplate?.area ?: 2,
            stations = stations,
        )
        radioLogoRepository.saveTemplate(newTemplate)

        if (radioLogoRepository.getActiveTemplateName() == null) {
            radioLogoRepository.setActiveTemplate(templateName)
        }
    }
}
