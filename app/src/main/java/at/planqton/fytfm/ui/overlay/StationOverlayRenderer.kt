package at.planqton.fytfm.ui.overlay

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import at.planqton.fytfm.StationChangeOverlayService
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.RadioStation
import org.json.JSONArray
import org.json.JSONObject

/**
 * Starts / stops the system overlay shown during station changes and in
 * permanent-bar mode. Replaces three near-identical helpers that used to live
 * in MainActivity and share the same Intent construction + error handling.
 */
class StationOverlayRenderer(
    private val context: Context,
    private val presetRepository: PresetRepository,
) {
    companion object {
        private const val TAG = "StationOverlayRenderer"
    }

    fun showFmAmChange(
        frequency: Float,
        oldFrequency: Float,
        isAM: Boolean,
        isAppInForeground: Boolean,
        stations: List<RadioStation>,
        logoFor: (name: String?, frequency: Float) -> String?,
    ) {
        if (!presetRepository.isShowStationChangeToast()) return

        val stationsJson = buildFmAmStationsJson(stations, logoFor)
        val intent = baseIntent(StationChangeOverlayService.ACTION_SHOW_OVERLAY).apply {
            putExtra(StationChangeOverlayService.EXTRA_FREQUENCY, frequency)
            putExtra(StationChangeOverlayService.EXTRA_OLD_FREQUENCY, oldFrequency)
            putExtra(StationChangeOverlayService.EXTRA_IS_AM, isAM)
            putExtra(StationChangeOverlayService.EXTRA_APP_IN_FOREGROUND, isAppInForeground)
            stationsJson?.let { putExtra(StationChangeOverlayService.EXTRA_STATIONS, it) }
        }
        startOverlayService(intent)
    }

    fun showDabChange(
        serviceId: Int,
        oldServiceId: Int,
        isAppInForeground: Boolean,
        stations: List<RadioStation>,
        logoFor: (name: String?, serviceId: Int) -> String?,
    ) {
        if (!presetRepository.isShowStationChangeToast()) return

        val stationsJson = buildDabStationsJson(stations, logoFor)
        val intent = baseIntent(StationChangeOverlayService.ACTION_SHOW_OVERLAY).apply {
            putExtra(StationChangeOverlayService.EXTRA_IS_DAB, true)
            putExtra(StationChangeOverlayService.EXTRA_DAB_SERVICE_ID, serviceId)
            putExtra(StationChangeOverlayService.EXTRA_DAB_OLD_SERVICE_ID, oldServiceId)
            putExtra(StationChangeOverlayService.EXTRA_APP_IN_FOREGROUND, isAppInForeground)
            stationsJson?.let { putExtra(StationChangeOverlayService.EXTRA_STATIONS, it) }
        }
        startOverlayService(intent)
    }

    fun showPermanent(
        frequency: Float,
        isAM: Boolean,
        stations: List<RadioStation>,
        logoFor: (name: String?, frequency: Float) -> String?,
    ) {
        val stationsJson = buildFmAmStationsJson(stations, logoFor)
        val intent = baseIntent(StationChangeOverlayService.ACTION_SHOW_PERMANENT).apply {
            putExtra(StationChangeOverlayService.EXTRA_FREQUENCY, frequency)
            putExtra(StationChangeOverlayService.EXTRA_IS_AM, isAM)
            stationsJson?.let { putExtra(StationChangeOverlayService.EXTRA_STATIONS, it) }
        }
        startOverlayService(intent)
    }

    fun hidePermanent() {
        val intent = baseIntent(StationChangeOverlayService.ACTION_HIDE_OVERLAY)
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide overlay: ${e.message}", e)
        }
    }

    private fun baseIntent(action: String): Intent =
        Intent(context, StationChangeOverlayService::class.java).apply { this.action = action }

    private fun startOverlayService(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start overlay service: ${e.message}", e)
        }
    }

    private fun buildFmAmStationsJson(
        stations: List<RadioStation>,
        logoFor: (name: String?, frequency: Float) -> String?,
    ): String? = tryBuildJson { arr ->
        stations.forEach { s ->
            arr.put(JSONObject().apply {
                put("frequency", s.frequency.toDouble())
                put("name", s.name ?: "")
                put("logoPath", logoFor(s.name, s.frequency) ?: "")
                put("isAM", s.isAM)
            })
        }
    }

    private fun buildDabStationsJson(
        stations: List<RadioStation>,
        logoFor: (name: String?, serviceId: Int) -> String?,
    ): String? = tryBuildJson { arr ->
        stations.forEach { s ->
            arr.put(JSONObject().apply {
                put("frequency", 0.0)
                put("name", s.name ?: "")
                put("logoPath", logoFor(s.name, s.serviceId) ?: "")
                put("isAM", false)
                put("isDab", true)
                put("serviceId", s.serviceId)
            })
        }
    }

    private inline fun tryBuildJson(fill: (JSONArray) -> Unit): String? = try {
        val arr = JSONArray()
        fill(arr)
        arr.toString()
    } catch (e: Exception) {
        Log.e(TAG, "Error building stations JSON: ${e.message}", e)
        null
    }
}
