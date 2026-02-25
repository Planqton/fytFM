package at.planqton.fytfm.scanner

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import at.planqton.fytfm.data.RadioStation
import com.android.fmradio.FmNative
import kotlin.concurrent.thread

/**
 * RadioScanner - Echter Hardware-Scan für FYT Head Units
 * Phase 1: RSSI-Scan (87.5-108.0 MHz)
 * Phase 2: RDS-Namen sammeln
 *
 * Verwendet direkt fmsyu_jni für alle Abfragen (NavRadio-Stil).
 */
class RadioScanner {

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
        private const val RDS_COLLECT_TIME_MS = 4000
        private const val PI_CODE_WAIT_TIME_MS = 1500

        // fmsyu_jni Command Codes (NavRadio-Stil)
        private const val CMD_GETRSSI = 0x0b      // 11
        private const val CMD_RDSGETPS = 0x1e     // 30 - PS Name
        private const val CMD_RDSGETPI = 0x19    // 25 - PI Code (vermutlich)
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

    fun scanFM(
        onProgress: (progress: Int, frequency: Float, remainingSeconds: Int, filteredCount: Int) -> Unit,
        onStationFound: ((RadioStation) -> Unit)? = null,
        onComplete: (List<RadioStation>) -> Unit,
        verifyWithPiCode: Boolean = false
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

                    mainHandler.post { onProgress(progress, currentFreq, remainingSeconds, filteredCount) }

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
                        mainHandler.post { onStationFound?.invoke(station) }
                    }

                    freq += FM_STEP
                }

                if (isCancelled) {
                    Log.i(TAG, "Scan cancelled")
                    mainHandler.post { onComplete(foundStations) }
                    return@thread
                }

                Log.i(TAG, "Phase 1 complete: ${foundStations.size} stations found")

                // ==================== PHASE 2: RDS/PI VERIFICATION ====================
                if (foundStations.isNotEmpty()) {
                    val phaseTitle = if (verifyWithPiCode) "PHASE 2: PI-CODE VERIFICATION + RDS" else "PHASE 2: COLLECTING RDS NAMES"
                    Log.i(TAG, "════════════════════════════════════════════════════")
                    Log.i(TAG, "         $phaseTitle")
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
                            ((foundStations.size - index) * 5.5).toInt()
                        }

                        mainHandler.post { onProgress(progress, station.frequency, remainingSeconds, filteredCount) }

                        tuneToFrequency(station.frequency)

                        // PI-Code und RDS-Name gleichzeitig sammeln
                        val (hasValidPi, rdsName) = collectPiAndRds(
                            timeoutMs = RDS_COLLECT_TIME_MS,
                            requirePi = verifyWithPiCode
                        )

                        // Bei PI-Verifizierung: Station ohne PI überspringen
                        if (verifyWithPiCode && !hasValidPi) {
                            filteredCount++
                            Log.i(TAG, "  %.1f MHz → KEIN PI-CODE (entfernt) [%d gefiltert]".format(station.frequency, filteredCount))
                            return@forEachIndexed
                        }

                        val verifiedStation = station.copy(name = if (rdsName.isNotEmpty()) rdsName else null)
                        verifiedStations.add(verifiedStation)

                        val piInfo = if (verifyWithPiCode) " [PI OK]" else ""
                        if (rdsName.isNotEmpty()) {
                            Log.i(TAG, "  %.1f MHz → \"%s\"$piInfo".format(station.frequency, rdsName))
                        } else {
                            Log.i(TAG, "  %.1f MHz → (kein RDS Name)$piInfo".format(station.frequency))
                        }
                    }

                    // Ersetze foundStations mit verifizierten
                    foundStations.clear()
                    foundStations.addAll(verifiedStations)
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

    /**
     * Holt PS Name direkt via fmsyu_jni (NavRadio-Stil)
     */
    private fun fetchPs(): String {
        val native = fmNative ?: return ""

        // Methode 1: fmsyu_jni(0x1e)
        try {
            val inBundle = Bundle()
            val outBundle = Bundle()
            val result = native.fmsyu_jni(CMD_RDSGETPS, inBundle, outBundle)
            if (result == 0) {
                val psData = outBundle.getByteArray("PSname")
                if (psData != null && psData.isNotEmpty()) {
                    val ps = String(psData, Charsets.US_ASCII)
                        .replace(Regex("[\\x00-\\x1F]"), "")
                        .trim()
                    if (ps.isNotEmpty()) return ps
                }
            }
        } catch (e: Throwable) { }

        // Methode 2: getPsString()
        try {
            val ps = native.psString
            if (!ps.isNullOrEmpty()) return ps.trim()
        } catch (e: Throwable) { }

        // Methode 3: getPs()
        try {
            val psBytes = native.ps
            if (psBytes != null && psBytes.isNotEmpty()) {
                val ps = String(psBytes, Charsets.US_ASCII)
                    .replace(Regex("[\\x00-\\x1F]"), "")
                    .trim()
                if (ps.isNotEmpty()) return ps
            }
        } catch (e: Throwable) { }

        return ""
    }

    /**
     * Sammelt PI-Code und RDS-Name gleichzeitig
     * @return Pair(hasValidPi, rdsName)
     */
    private fun collectPiAndRds(timeoutMs: Int, requirePi: Boolean): Pair<Boolean, String> {
        val attempts = timeoutMs / 500
        val native = fmNative ?: return Pair(false, "")

        var foundPi = false
        var collectedPs = ""

        repeat(attempts) { attempt ->
            if (isCancelled) return Pair(foundPi, collectedPs)

            try { Thread.sleep(500) } catch (e: InterruptedException) { return Pair(foundPi, collectedPs) }

            // RDS lesen um Hardware zu triggern
            try { native.readRds() } catch (e: Throwable) { }

            // PI-Code prüfen (via readRds return value oder andere Methode)
            if (!foundPi && requirePi) {
                // PI-Code kommt oft über readRds() return value
                try {
                    val rdsResult = native.readRds()
                    // Wenn ein gültiger PI-Code empfangen wurde, ist rdsResult oft > 0
                    if (rdsResult.toInt() != 0) {
                        foundPi = true
                        Log.d(TAG, "  PI indication received after ${(attempt + 1) * 500}ms")
                    }
                } catch (e: Throwable) { }
            }

            // PS Name sammeln
            val ps = fetchPs()
            if (ps.isNotEmpty() && ps != collectedPs) {
                collectedPs = ps
                Log.d(TAG, "  RDS PS: '$collectedPs'")
            }

            // Früh abbrechen wenn beides gefunden (oder PI nicht benötigt)
            val piOk = foundPi || !requirePi
            val nameOk = collectedPs.length >= 2 && !collectedPs.matches(Regex("^[\\s?]+$"))
            if (piOk && nameOk) {
                return Pair(foundPi, collectedPs)
            }
        }

        return Pair(foundPi, collectedPs)
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
