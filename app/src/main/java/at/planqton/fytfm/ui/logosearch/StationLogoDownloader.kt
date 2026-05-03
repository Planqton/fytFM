package at.planqton.fytfm.ui.logosearch

import android.app.ProgressDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import at.planqton.fytfm.R
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.RadioStation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads an image URL, resizes it, saves to `filesDir/station_logos/`,
 * and writes the path directly to the corresponding [RadioStation]
 * (`logoPath` field). Templates are obsolete — logos are now per-station.
 */
class StationLogoDownloader(
    private val activity: AppCompatActivity,
    private val presetRepository: PresetRepository,
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
                val logoFile = saveBitmapAsPng(sized, station)
                writeLogoPathToStation(station, logoFile)

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
        val url = URL(imageUrl)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", UA)
        }
        try {
            connection.connect()
            if (connection.responseCode != 200) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            val raw = connection.inputStream.use { BitmapFactory.decodeStream(it) }
                ?: throw IllegalStateException("Bitmap decode failed")
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

    /**
     * Pro-Sender deterministischer Pfad in `filesDir/station_logos/`.
     * - DAB: `dab_<serviceId>.png`
     * - FM:  `fm_<frequency*10>.png` (z.B. `fm_924` für 92.4 MHz)
     * - AM:  `am_<frequencyKHz>.png`
     */
    private fun saveBitmapAsPng(bitmap: Bitmap, station: RadioStation): File {
        val logosDir = File(activity.filesDir, "station_logos").apply { mkdirs() }
        val key = when {
            station.isDab -> "dab_${station.serviceId}"
            station.isAM -> "am_${station.frequency.toInt()}"
            else -> "fm_${(station.frequency * 10).toInt()}"
        }
        val logoFile = File(logosDir, "$key.png")
        FileOutputStream(logoFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
        }
        return logoFile
    }

    /**
     * Schreibt den `logoPath` in die passende Sender-Liste (FM/AM/DAB/DAB-Dev).
     */
    private fun writeLogoPathToStation(station: RadioStation, logoFile: File) {
        val path = logoFile.absolutePath
        when {
            station.isDab -> {
                // DAB-Sender können in beiden Listen vorkommen (real + dev),
                // wir patchen jeden Match per serviceId.
                val dab = presetRepository.loadDabStations()
                if (dab.any { it.serviceId == station.serviceId }) {
                    presetRepository.saveDabStations(dab.map {
                        if (it.serviceId == station.serviceId) it.copy(logoPath = path) else it
                    })
                }
                val dev = presetRepository.loadDabDevStations()
                if (dev.any { it.serviceId == station.serviceId }) {
                    presetRepository.saveDabDevStations(dev.map {
                        if (it.serviceId == station.serviceId) it.copy(logoPath = path) else it
                    })
                }
            }
            station.isAM -> {
                val am = presetRepository.loadAmStations()
                presetRepository.saveAmStations(am.map {
                    if (Math.abs(it.frequency - station.frequency) < 0.05f) it.copy(logoPath = path) else it
                })
            }
            else -> {
                val fm = presetRepository.loadFmStations()
                presetRepository.saveFmStations(fm.map {
                    if (Math.abs(it.frequency - station.frequency) < 0.05f) it.copy(logoPath = path) else it
                })
            }
        }
        Log.i(TAG, "logo gespeichert: ${station.name} → $path")
    }
}
