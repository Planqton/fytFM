package at.planqton.fytfm.scanner

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import at.planqton.fytfm.RdsManager
import at.planqton.fytfm.data.RadioStation
import com.android.fmradio.FmNative
import kotlin.concurrent.thread

/**
 * RadioScanner - Echter Hardware-Scan für FYT Head Units
 * Phase 1: RSSI-Scan (87.5-108.0 MHz) - Alle Sender mit Signal finden
 * Phase 2: RDS-Verifizierung via RdsManager - PS/PI prüfen und filtern
 *
 * Nutzt den gleichen RdsManager wie das Debug-Display für RDS-Daten.
 */
class RadioScanner(private val rdsManager: RdsManager) {

    companion object {
        private const val TAG = "RadioScanner"

        // FM Frequenz-Bereich
        const val FM_MIN = 87.5f
        const val FM_MAX = 108.0f
        const val FM_STEP = 0.1f

        // AM Frequenz-Bereich
        const val AM_MIN = 522f
        const val AM_MAX = 1620f
        const val AM_STEP = 9f

        // Scan Settings
        private const val SCAN_RSSI_THRESHOLD = 15
        private const val SCAN_SETTLE_TIME_MS = 250L
        private const val SCAN_RSSI_SAMPLES = 3
        private const val RDS_COLLECT_TIME_MS = 5000L  // 5 Sekunden pro Sender für RDS

        // fmsyu_jni Command Codes (NavRadio-Stil)
        private const val CMD_GETRSSI = 0x0b      // 11
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private var isCancelled = false
    private var fmNative: FmNative? = null

    init {
        if (FmNative.isLibraryLoaded()) {
            fmNative = FmNative.getInstance()
            Log.i(TAG, "FmNative initialized for scanning")
        } else {
            Log.w(TAG, "FmNative not available - scanning will be simulated")
        }
    }

    /**
     * FM-Scan mit 2 Durchgängen:
     * 1. Alle Frequenzen mit RSSI >= Threshold finden
     * 2. RDS-Daten sammeln und filtern (via RdsManager wie Debug-Display)
     *
     * @param requirePs Sender ohne PS-Name werden gefiltert
     * @param requirePi Sender ohne PI-Code werden gefiltert
     */
    fun scanFM(
        onProgress: (progress: Int, frequency: Float, remainingSeconds: Int, filteredCount: Int, phase: String) -> Unit,
        onStationFound: ((RadioStation) -> Unit)? = null,
        onComplete: (List<RadioStation>) -> Unit,
        requirePs: Boolean = false,
        requirePi: Boolean = false
    ) {
        if (isScanning) {
            Log.w(TAG, "Scan already running")
            return
        }

        isScanning = true
        isCancelled = false

        thread {
            val foundStations = mutableListOf<RadioStation>()
            val scanStartTime = System.currentTimeMillis()
            var filteredCount = 0

            try {
                // ==================== PHASE 1: RSSI SCAN ====================
                Log.i(TAG, "════════════════════════════════════════════════════")
                Log.i(TAG, "         PHASE 1: RSSI SCAN (87.5-108.0 MHz)")
                Log.i(TAG, "════════════════════════════════════════════════════")

                val totalSteps = ((FM_MAX - FM_MIN) / FM_STEP).toInt() + 1
                var currentStep = 0

                var freq = FM_MIN
                while (freq <= FM_MAX && !isCancelled) {
                    currentStep++
                    val progress = ((currentStep.toFloat() / totalSteps) * 50).toInt()
                    val currentFreq = (freq * 10).toInt() / 10.0f

                    // Restzeit berechnen
                    val elapsedMs = System.currentTimeMillis() - scanStartTime
                    val remainingSeconds = if (progress > 0) {
                        val totalEstimatedMs = (elapsedMs * 100) / progress
                        ((totalEstimatedMs - elapsedMs) / 1000).toInt()
                    } else 0

                    mainHandler.post { onProgress(progress, currentFreq, remainingSeconds, filteredCount, "Phase 1: Signal") }

                    // Tune zur Frequenz
                    tuneToFrequency(currentFreq)

                    // Warten bis Signal stabil
                    Thread.sleep(SCAN_SETTLE_TIME_MS)

                    // RSSI messen
                    val rssi = measureRssi(SCAN_RSSI_SAMPLES)

                    if (rssi >= SCAN_RSSI_THRESHOLD) {
                        val station = RadioStation(currentFreq, null, rssi, false)
                        foundStations.add(station)
                        Log.i(TAG, "★ FOUND: %.1f MHz | RSSI: %d".format(currentFreq, rssi))
                        // Phase 1: Sofort anzeigen
                        mainHandler.post { onStationFound?.invoke(station) }
                    }

                    freq += FM_STEP
                }

                if (isCancelled) {
                    Log.i(TAG, "Scan cancelled in Phase 1")
                    mainHandler.post { onComplete(emptyList()) }
                    return@thread
                }

                Log.i(TAG, "Phase 1 complete: ${foundStations.size} stations found")

                // ==================== PHASE 2: RDS VERIFICATION via RdsManager ====================
                if (foundStations.isNotEmpty() && (requirePs || requirePi)) {
                    Log.i(TAG, "════════════════════════════════════════════════════")
                    Log.i(TAG, "         PHASE 2: RDS VERIFICATION (PS=${requirePs}, PI=${requirePi})")
                    Log.i(TAG, "════════════════════════════════════════════════════")

                    val verifiedStations = mutableListOf<RadioStation>()
                    val phase2StartTime = System.currentTimeMillis()

                    foundStations.forEachIndexed { index, station ->
                        if (isCancelled) return@forEachIndexed

                        val progress = 50 + ((index + 1).toFloat() / foundStations.size * 50).toInt()

                        // Restzeit für Phase 2 berechnen
                        val phase2Elapsed = System.currentTimeMillis() - phase2StartTime
                        val remainingSeconds = if (index > 0) {
                            val avgPerStation = phase2Elapsed / index
                            ((foundStations.size - index) * avgPerStation / 1000).toInt()
                        } else {
                            ((foundStations.size - index) * (RDS_COLLECT_TIME_MS / 1000)).toInt()
                        }

                        mainHandler.post { onProgress(progress, station.frequency, remainingSeconds, filteredCount, "Phase 2: RDS") }

                        // RDS-Daten zurücksetzen
                        rdsManager.clearRds()

                        // Tune zur Frequenz
                        tuneToFrequency(station.frequency)

                        // RDS-Daten sammeln (wie Debug-Display) - warten auf Daten
                        val (hasPs, hasPi, psName, piCode) = collectRdsViaManager(RDS_COLLECT_TIME_MS)

                        // Filtern basierend auf Anforderungen
                        val psOk = !requirePs || hasPs
                        val piOk = !requirePi || hasPi

                        if (!psOk || !piOk) {
                            filteredCount++
                            val reason = when {
                                !psOk && !piOk -> "kein PS & kein PI"
                                !psOk -> "kein PS"
                                else -> "kein PI"
                            }
                            Log.i(TAG, "  %.1f MHz → GEFILTERT ($reason) [%d gefiltert]".format(station.frequency, filteredCount))
                            return@forEachIndexed
                        }

                        // Station mit RDS-Daten hinzufügen
                        val verifiedStation = station.copy(name = if (psName.isNotEmpty()) psName else null)
                        verifiedStations.add(verifiedStation)

                        // Live-Callback für UI-Update
                        mainHandler.post { onStationFound?.invoke(verifiedStation) }

                        val piStr = if (piCode != 0) String.format(" [PI=0x%04X]", piCode) else ""
                        if (psName.isNotEmpty()) {
                            Log.i(TAG, "  %.1f MHz → \"%s\"$piStr ✓".format(station.frequency, psName))
                        } else {
                            Log.i(TAG, "  %.1f MHz → (kein PS Name)$piStr ✓".format(station.frequency))
                        }
                    }

                    // Bei Abbruch: leere Liste zurückgeben
                    if (isCancelled) {
                        Log.i(TAG, "Scan cancelled in Phase 2 (with filter)")
                        mainHandler.post { onComplete(emptyList()) }
                        return@thread
                    }

                    // Ersetze foundStations mit verifizierten
                    foundStations.clear()
                    foundStations.addAll(verifiedStations)
                } else if (foundStations.isNotEmpty()) {
                    // Keine Filterung - trotzdem RDS-Namen sammeln
                    Log.i(TAG, "════════════════════════════════════════════════════")
                    Log.i(TAG, "         PHASE 2: RDS NAMES SAMMELN (ohne Filter)")
                    Log.i(TAG, "════════════════════════════════════════════════════")

                    val stationsWithNames = mutableListOf<RadioStation>()
                    val phase2StartTime = System.currentTimeMillis()

                    foundStations.forEachIndexed { index, station ->
                        if (isCancelled) return@forEachIndexed

                        val progress = 50 + ((index + 1).toFloat() / foundStations.size * 50).toInt()
                        val phase2Elapsed = System.currentTimeMillis() - phase2StartTime
                        val remainingSeconds = if (index > 0) {
                            val avgPerStation = phase2Elapsed / index
                            ((foundStations.size - index) * avgPerStation / 1000).toInt()
                        } else {
                            ((foundStations.size - index) * (RDS_COLLECT_TIME_MS / 1000)).toInt()
                        }

                        mainHandler.post { onProgress(progress, station.frequency, remainingSeconds, 0, "Phase 2: RDS") }

                        rdsManager.clearRds()
                        tuneToFrequency(station.frequency)

                        val (_, _, psName, _) = collectRdsViaManager(RDS_COLLECT_TIME_MS)

                        val stationWithName = station.copy(name = if (psName.isNotEmpty()) psName else null)
                        stationsWithNames.add(stationWithName)

                        // Live-Callback für UI-Update
                        mainHandler.post { onStationFound?.invoke(stationWithName) }

                        if (psName.isNotEmpty()) {
                            Log.i(TAG, "  %.1f MHz → \"%s\"".format(station.frequency, psName))
                        } else {
                            Log.i(TAG, "  %.1f MHz → (kein RDS Name)".format(station.frequency))
                        }
                    }

                    // Bei Abbruch: leere Liste zurückgeben
                    if (isCancelled) {
                        Log.i(TAG, "Scan cancelled in Phase 2 (no filter)")
                        mainHandler.post { onComplete(emptyList()) }
                        return@thread
                    }

                    foundStations.clear()
                    foundStations.addAll(stationsWithNames)
                }

                // Log results
                logScanResults(foundStations)

                mainHandler.post { onComplete(foundStations.sortedBy { it.frequency }) }

            } catch (e: Exception) {
                Log.e(TAG, "Scan failed: ${e.message}", e)
                mainHandler.post { onComplete(foundStations.sortedBy { it.frequency }) }
            } finally {
                isScanning = false
            }
        }
    }

    /**
     * Sammelt RDS-Daten via RdsManager (wie Debug-Display)
     * @return Quadruple(hasPs, hasPi, psName, piCode)
     */
    private fun collectRdsViaManager(timeoutMs: Long): RdsResult {
        val startTime = System.currentTimeMillis()
        var foundPs = ""
        var foundPi = 0

        // Warten und regelmäßig RDS-Manager abfragen
        while (System.currentTimeMillis() - startTime < timeoutMs && !isCancelled) {
            Thread.sleep(250)

            // PS vom RdsManager holen
            val ps = rdsManager.ps
            if (!ps.isNullOrEmpty() && ps.length >= 2) {
                foundPs = ps
            }

            // PI vom RdsManager holen
            val pi = rdsManager.pi
            if (pi != 0) {
                foundPi = pi
            }

            // Früh abbrechen wenn beides gefunden
            if (foundPs.isNotEmpty() && foundPi != 0) {
                break
            }
        }

        return RdsResult(
            hasPs = foundPs.isNotEmpty(),
            hasPi = foundPi != 0,
            psName = foundPs,
            piCode = foundPi
        )
    }

    data class RdsResult(
        val hasPs: Boolean,
        val hasPi: Boolean,
        val psName: String,
        val piCode: Int
    )

    fun scanAM(
        onProgress: (progress: Int, frequency: Float, remainingSeconds: Int, filteredCount: Int) -> Unit,
        onComplete: (List<RadioStation>) -> Unit
    ) {
        if (isScanning) return
        isScanning = true
        isCancelled = false

        thread {
            val foundStations = mutableListOf<RadioStation>()
            val totalSteps = ((AM_MAX - AM_MIN) / AM_STEP).toInt()
            var currentStep = 0
            val startTime = System.currentTimeMillis()

            var freq = AM_MIN
            while (freq <= AM_MAX && !isCancelled) {
                currentStep++
                val progress = (currentStep * 100) / totalSteps

                val elapsedMs = System.currentTimeMillis() - startTime
                val remainingSeconds = if (progress > 0) {
                    val totalEstimatedMs = (elapsedMs * 100) / progress
                    ((totalEstimatedMs - elapsedMs) / 1000).toInt()
                } else 0

                mainHandler.post { onProgress(progress, freq, remainingSeconds, 0) }

                // AM scanning - simplified for now
                Thread.sleep(15)

                freq += AM_STEP
            }

            isScanning = false
            mainHandler.post { onComplete(foundStations.sortedBy { it.frequency }) }
        }
    }

    fun stopScan() {
        if (isScanning) {
            isCancelled = true
            Log.i(TAG, "Scan stop requested")
            fmNative?.stopScan()
        }
    }

    private fun tuneToFrequency(frequency: Float): Boolean {
        return try {
            fmNative?.tune(frequency) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Tune failed: ${e.message}")
            false
        }
    }

    private fun measureRssi(samples: Int): Int {
        val native = fmNative ?: return 0

        var totalRssi = 0
        var validSamples = 0

        repeat(samples) { i ->
            var rssi = 0

            // Methode 1: fmsyu_jni
            try {
                val inBundle = Bundle()
                val outBundle = Bundle()
                val result = native.fmsyu_jni(CMD_GETRSSI, inBundle, outBundle)
                if (result == 0) {
                    rssi = outBundle.getInt("rssilevel", 0)
                }
            } catch (e: Throwable) { }

            // Methode 2: getrssi()
            if (rssi == 0) {
                try { rssi = native.getrssi() } catch (e: Throwable) { }
            }

            // Methode 3: sql_getrssi()
            if (rssi == 0) {
                try { rssi = native.sql_getrssi() } catch (e: Throwable) { }
            }

            if (rssi in 1..99) {
                totalRssi += rssi
                validSamples++
            }

            if (i < samples - 1) {
                try { Thread.sleep(50) } catch (e: InterruptedException) { }
            }
        }

        return if (validSamples > 0) totalRssi / validSamples else 0
    }

    private fun logScanResults(stations: List<RadioStation>) {
        val log = StringBuilder()
        log.append("\n════════════════════════════════════════════════════\n")
        log.append("              SCAN COMPLETE: ${stations.size} stations\n")
        log.append("════════════════════════════════════════════════════\n")
        stations.forEach { station ->
            val name = if (station.name.isNullOrEmpty()) "(kein RDS)" else station.name
            log.append("  %5.1f MHz | RSSI: %2d | %s\n".format(station.frequency, station.rssi, name))
        }
        log.append("════════════════════════════════════════════════════")
        Log.i(TAG, log.toString())
    }
}
