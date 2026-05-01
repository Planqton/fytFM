package at.planqton.fytfm.scanner

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import at.planqton.fytfm.RdsManager
import at.planqton.fytfm.controller.FmNativeAdapter
import at.planqton.fytfm.controller.FmNativeApi
import at.planqton.fytfm.data.RadioStation
import com.android.fmradio.FmNative
import kotlin.concurrent.thread

/**
 * RadioScanner - Echter Hardware-Scan für FYT Head Units
 * Phase 1: RSSI-Scan (87.5-108.0 MHz) - Alle Sender mit Signal finden
 * Phase 2: RDS-Verifizierung via RdsManager - PS/PI prüfen und filtern
 *
 * Nutzt den gleichen RdsManager wie das Debug-Display für RDS-Daten.
 *
 * The [fmNative] backend is injected through the [FmNativeApi] facade so
 * the JNI hardware singleton can be substituted with a MockK mock in tests
 * — the previous direct `FmNative.getInstance()` call made the scanner
 * un-testable on the JVM.
 */
class RadioScanner(
    private val rdsManager: RdsManager,
    private val fmNative: FmNativeApi? = defaultFmNative(),
) {

    /**
     * Filter-Modi für RDS-Verifizierung
     */
    enum class FilterMode {
        NONE,            // Keine Filter, nur RDS-Namen sammeln
        REQUIRE_PS,      // Muss PS (Sendername) haben
        REQUIRE_PI,      // Muss PI (Program Identification) haben
        REQUIRE_PS_AND_PI, // Muss beides haben (PS UND PI)
        REQUIRE_PS_OR_PI // Muss PS ODER PI haben
    }

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
        private const val SCAN_RSSI_OFFSET_NORMAL = 15  // Threshold = NoiseFloor + Offset (normal mode)
        private const val SCAN_RSSI_OFFSET_SENSITIVE = 5  // Lower offset for sensitive mode (more weak stations)
        private const val SCAN_SETTLE_TIME_MS = 250L
        private const val SCAN_RSSI_SAMPLES = 3
        private const val NOISE_FLOOR_SAMPLES = 10  // Anzahl Frequenzen für Noise Floor Messung
        private const val RDS_COLLECT_TIME_MS = 8000L  // 8 Sekunden pro Sender für RDS (optimiert für langsame Sender)

        // fmsyu_jni Command Codes (NavRadio-Stil)
        private const val CMD_GETRSSI = 0x0b      // 11

        /** Resolves the default production [FmNativeApi] from the JNI singleton.
         *  Returns null when the native library failed to load — the scanner
         *  guards against that and bails early on every scan entry point. */
        private fun defaultFmNative(): FmNativeApi? =
            if (FmNative.isLibraryLoaded()) FmNativeAdapter(FmNative.getInstance()) else null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    // Control flags are written from the main thread (stopScan/skipScan) and
    // read from the scan worker thread — without @Volatile the cancel/skip
    // request may never reach the scan loop.
    @Volatile private var isScanning = false
    @Volatile private var isCancelled = false
    @Volatile private var isSkipped = false  // Skip behält gefundene Sender, Cancel nicht

    init {
        if (fmNative != null) {
            Log.i(TAG, "FmNative initialized for scanning")
        } else {
            Log.w(TAG, "FmNative not available - scanning will be simulated")
        }
    }

    /**
     * Snapshot the scan-thread-mutable [stations] list, then post the
     * onComplete callback to main with the immutable, frequency-sorted
     * snapshot. Snapshotting on the scan thread (where mutation happens)
     * eliminates the ConcurrentModificationException risk that exists if
     * the main-thread sort touches the live list while the scan loop is
     * still running (cancel/skip races).
     */
    internal fun postSortedComplete(
        stations: List<RadioStation>,
        onComplete: (List<RadioStation>) -> Unit,
    ) {
        val snapshot = stations.sortedBy { it.frequency }
        mainHandler.post { onComplete(snapshot) }
    }

    /**
     * FM-Scan mit 2 Durchgängen:
     * 1. Tune+RDS-Check: Jede Frequenz prüfen ob Sender vorhanden
     * 2. RDS-Verifizierung: PS/PI prüfen und nach Checkboxen filtern
     *
     * @param requirePs Sender ohne PS-Name werden gefiltert
     * @param requirePi Sender ohne PI-Code werden gefiltert
     * @param highSensitivity Wenn true, werden auch schwächere Signale erkannt
     */
    fun scanFM(
        onProgress: (progress: Int, frequency: Float, remainingSeconds: Int, filteredCount: Int, phase: String) -> Unit,
        onStationFound: ((RadioStation) -> Unit)? = null,
        onComplete: (List<RadioStation>) -> Unit,
        requirePs: Boolean = false,
        requirePi: Boolean = false,
        highSensitivity: Boolean = false
    ) {
        if (isScanning) {
            Log.w(TAG, "Scan already running")
            return
        }

        isScanning = true
        isCancelled = false
        isSkipped = false

        thread {
            val foundStations = mutableListOf<RadioStation>()
            val scanStartTime = System.currentTimeMillis()
            var filteredCount = 0

            try {
                // ==================== PHASE 1: SIGNAL SCAN ====================
                Log.i(TAG, "════════════════════════════════════════════════════")
                Log.i(TAG, "         PHASE 1: SIGNAL SCAN (87.5-108.0 MHz)")
                Log.i(TAG, "════════════════════════════════════════════════════")

                val native = fmNative
                if (native == null) {
                    Log.e(TAG, "FmNative not available!")
                    mainHandler.post { onComplete(emptyList()) }
                    return@thread
                }

                // Radio muss eingeschaltet sein für RSSI-Messung!
                // (Wird normalerweise von MainActivity gemacht, aber sicherheitshalber prüfen)
                try {
                    native.openDev()
                    native.powerUp(FM_MIN)
                    native.setRds(true)
                    Log.i(TAG, "Radio initialized for scanning")
                } catch (e: Exception) {
                    Log.w(TAG, "Radio init failed (may already be on): ${e.message}")
                }

                // ========== NOISE FLOOR MESSUNG ==========
                Log.i(TAG, "Measuring noise floor...")
                mainHandler.post { onProgress(0, FM_MIN, 120, 0, "Noise Floor") }

                val noiseFloor = measureNoiseFloor(native)
                val rssiOffset = if (highSensitivity) SCAN_RSSI_OFFSET_SENSITIVE else SCAN_RSSI_OFFSET_NORMAL
                val dynamicThreshold = noiseFloor + rssiOffset

                Log.i(TAG, "╔════════════════════════════════════════════════")
                Log.i(TAG, "║ NOISE FLOOR: $noiseFloor dBm")
                Log.i(TAG, "║ SENSITIVITY: ${if (highSensitivity) "HIGH" else "NORMAL"}")
                Log.i(TAG, "║ THRESHOLD:   $dynamicThreshold dBm (floor + $rssiOffset)")
                Log.i(TAG, "╚════════════════════════════════════════════════")

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
                        val totalEstimatedMs = (elapsedMs * 50) / progress
                        ((totalEstimatedMs - elapsedMs) / 1000).toInt()
                    } else 60

                    mainHandler.post { onProgress(progress, currentFreq, remainingSeconds, 0, "Phase 1: Signal") }

                    // Tune zur Frequenz
                    try {
                        native.tune(currentFreq)
                    } catch (e: Exception) {
                        Log.w(TAG, "Tune failed at %.1f".format(currentFreq))
                        freq += FM_STEP
                        continue
                    }

                    // Kurz warten bis Signal stabil
                    Thread.sleep(SCAN_SETTLE_TIME_MS)

                    // RSSI messen
                    val rssi = measureRssi(SCAN_RSSI_SAMPLES)

                    if (rssi >= dynamicThreshold) {
                        val station = RadioStation(currentFreq, null, rssi, false)
                        foundStations.add(station)
                        Log.i(TAG, "★ FOUND: %.1f MHz | RSSI: %d".format(currentFreq, rssi))
                        mainHandler.post { onStationFound?.invoke(station) }
                    } else if (rssi > 0) {
                        Log.d(TAG, "  %.1f MHz: RSSI=%d (below threshold)".format(currentFreq, rssi))
                    }

                    freq += FM_STEP
                }

                if (isCancelled) {
                    if (isSkipped && foundStations.isNotEmpty()) {
                        Log.i(TAG, "Scan skipped in Phase 1 - keeping ${foundStations.size} stations")
                        postSortedComplete(foundStations, onComplete)
                    } else {
                        Log.i(TAG, "Scan cancelled in Phase 1")
                        mainHandler.post { onComplete(emptyList()) }
                    }
                    return@thread
                }

                Log.i(TAG, "Phase 1 complete: ${foundStations.size} stations found")

                // ==================== PHASE 2: RDS VERIFICATION via RdsManager ====================
                if (foundStations.isNotEmpty() && (requirePs || requirePi)) {
                    Log.i(TAG, "════════════════════════════════════════════════════")
                    Log.i(TAG, "         PHASE 2: RDS VERIFICATION (PS=${requirePs}, PI=${requirePi})")
                    Log.i(TAG, "════════════════════════════════════════════════════")

                    // Sicherstellen dass RdsManager Polling aktiv ist
                    if (!rdsManager.isPolling) {
                        Log.w(TAG, "RdsManager polling not active - starting...")
                        rdsManager.startPolling(null)
                    }

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

                    // Bei Abbruch: bei Skip gefundene Sender behalten
                    if (isCancelled) {
                        if (isSkipped && verifiedStations.isNotEmpty()) {
                            Log.i(TAG, "Scan skipped in Phase 2 (with filter) - keeping ${verifiedStations.size} verified stations")
                            postSortedComplete(verifiedStations, onComplete)
                        } else {
                            Log.i(TAG, "Scan cancelled in Phase 2 (with filter)")
                            mainHandler.post { onComplete(emptyList()) }
                        }
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

                    // Sicherstellen dass RdsManager Polling aktiv ist
                    if (!rdsManager.isPolling) {
                        Log.w(TAG, "RdsManager polling not active - starting...")
                        rdsManager.startPolling(null)
                    }

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

                    // Bei Abbruch: bei Skip gefundene Sender behalten
                    if (isCancelled) {
                        if (isSkipped && stationsWithNames.isNotEmpty()) {
                            Log.i(TAG, "Scan skipped in Phase 2 (no filter) - keeping ${stationsWithNames.size} stations")
                            postSortedComplete(stationsWithNames, onComplete)
                        } else {
                            Log.i(TAG, "Scan cancelled in Phase 2 (no filter)")
                            mainHandler.post { onComplete(emptyList()) }
                        }
                        return@thread
                    }

                    foundStations.clear()
                    foundStations.addAll(stationsWithNames)
                }

                // Log results
                logScanResults(foundStations)

                postSortedComplete(foundStations, onComplete)

            } catch (e: Exception) {
                Log.e(TAG, "Scan failed: ${e.message}", e)
                postSortedComplete(foundStations, onComplete)
            } finally {
                isScanning = false
            }
        }
    }

    /**
     * FM Signal-Scan (nur Phase 1, ohne RDS)
     * Findet alle Sender mit Signal, aber ohne RDS-Namen.
     * Für anschließende manuelle RDS-Filterung.
     */
    fun scanFMSignalOnly(
        onProgress: (progress: Int, frequency: Float, remainingSeconds: Int, phase: String) -> Unit,
        onStationFound: ((RadioStation) -> Unit)? = null,
        onComplete: (List<RadioStation>) -> Unit,
        highSensitivity: Boolean = false
    ) {
        if (isScanning) {
            Log.w(TAG, "Scan already running")
            return
        }

        isScanning = true
        isCancelled = false
        isSkipped = false

        thread {
            val foundStations = mutableListOf<RadioStation>()
            val scanStartTime = System.currentTimeMillis()

            try {
                Log.i(TAG, "════════════════════════════════════════════════════")
                Log.i(TAG, "         SIGNAL-ONLY SCAN (87.5-108.0 MHz)")
                Log.i(TAG, "════════════════════════════════════════════════════")

                val native = fmNative
                if (native == null) {
                    Log.e(TAG, "FmNative not available!")
                    mainHandler.post { onComplete(emptyList()) }
                    return@thread
                }

                // Radio initialisieren
                try {
                    native.openDev()
                    native.powerUp(FM_MIN)
                    native.setRds(true)
                    Log.i(TAG, "Radio initialized for scanning")
                } catch (e: Exception) {
                    Log.w(TAG, "Radio init failed (may already be on): ${e.message}")
                }

                // Noise Floor messen
                Log.i(TAG, "Measuring noise floor...")
                mainHandler.post { onProgress(0, FM_MIN, 60, "Noise Floor") }

                val noiseFloor = measureNoiseFloor(native)
                val rssiOffset = if (highSensitivity) SCAN_RSSI_OFFSET_SENSITIVE else SCAN_RSSI_OFFSET_NORMAL
                val dynamicThreshold = noiseFloor + rssiOffset

                Log.i(TAG, "╔════════════════════════════════════════════════")
                Log.i(TAG, "║ NOISE FLOOR: $noiseFloor dBm")
                Log.i(TAG, "║ SENSITIVITY: ${if (highSensitivity) "HIGH" else "NORMAL"}")
                Log.i(TAG, "║ THRESHOLD:   $dynamicThreshold dBm (floor + $rssiOffset)")
                Log.i(TAG, "╚════════════════════════════════════════════════")

                val totalSteps = ((FM_MAX - FM_MIN) / FM_STEP).toInt() + 1
                var currentStep = 0

                var freq = FM_MIN
                while (freq <= FM_MAX && !isCancelled) {
                    currentStep++
                    val progress = ((currentStep.toFloat() / totalSteps) * 100).toInt()
                    val currentFreq = (freq * 10).toInt() / 10.0f

                    val elapsedMs = System.currentTimeMillis() - scanStartTime
                    val remainingSeconds = if (progress > 0) {
                        val totalEstimatedMs = (elapsedMs * 100) / progress
                        ((totalEstimatedMs - elapsedMs) / 1000).toInt()
                    } else 60

                    mainHandler.post { onProgress(progress, currentFreq, remainingSeconds, "Signal-Scan") }

                    try {
                        native.tune(currentFreq)
                    } catch (e: Exception) {
                        Log.w(TAG, "Tune failed at %.1f".format(currentFreq))
                        freq += FM_STEP
                        continue
                    }

                    Thread.sleep(SCAN_SETTLE_TIME_MS)

                    val rssi = measureRssi(SCAN_RSSI_SAMPLES)

                    if (rssi >= dynamicThreshold) {
                        val station = RadioStation(currentFreq, null, rssi, false)
                        foundStations.add(station)
                        Log.i(TAG, "★ FOUND: %.1f MHz | RSSI: %d".format(currentFreq, rssi))
                        mainHandler.post { onStationFound?.invoke(station) }
                    }

                    freq += FM_STEP
                }

                if (isCancelled) {
                    if (isSkipped && foundStations.isNotEmpty()) {
                        Log.i(TAG, "Scan skipped - keeping ${foundStations.size} stations")
                        postSortedComplete(foundStations, onComplete)
                    } else {
                        Log.i(TAG, "Scan cancelled")
                        mainHandler.post { onComplete(emptyList()) }
                    }
                    return@thread
                }

                Log.i(TAG, "Signal scan complete: ${foundStations.size} stations found")
                logScanResults(foundStations)
                postSortedComplete(foundStations, onComplete)

            } catch (e: Exception) {
                Log.e(TAG, "Signal scan failed: ${e.message}", e)
                postSortedComplete(foundStations, onComplete)
            } finally {
                isScanning = false
            }
        }
    }

    /**
     * RDS-Verifizierung für bereits gefundene Sender (Phase 2, separat aufrufbar)
     * Sammelt RDS-Daten und filtert nach gewähltem Modus.
     * @param rdsTimeoutMs Wartezeit pro Sender in Millisekunden (Standard: 8000ms)
     */
    fun collectRdsAndFilter(
        stations: List<RadioStation>,
        filterMode: FilterMode,
        rdsTimeoutMs: Long = RDS_COLLECT_TIME_MS,
        onProgress: (progress: Int, frequency: Float, remainingSeconds: Int, filteredCount: Int) -> Unit,
        onStationVerified: ((RadioStation) -> Unit)? = null,
        onComplete: (List<RadioStation>) -> Unit
    ) {
        if (isScanning) {
            Log.w(TAG, "Scan already running")
            return
        }

        if (stations.isEmpty()) {
            mainHandler.post { onComplete(emptyList()) }
            return
        }

        isScanning = true
        isCancelled = false
        isSkipped = false

        thread {
            val verifiedStations = mutableListOf<RadioStation>()
            var filteredCount = 0
            val startTime = System.currentTimeMillis()

            try {
                Log.i(TAG, "════════════════════════════════════════════════════")
                Log.i(TAG, "         RDS FILTER (${stations.size} Sender, Mode: $filterMode)")
                Log.i(TAG, "════════════════════════════════════════════════════")

                // RdsManager Polling starten wenn nicht aktiv
                if (!rdsManager.isPolling) {
                    Log.w(TAG, "RdsManager polling not active - starting...")
                    rdsManager.startPolling(null)
                }

                stations.forEachIndexed { index, station ->
                    if (isCancelled) return@forEachIndexed

                    val progress = ((index + 1).toFloat() / stations.size * 100).toInt()
                    val elapsed = System.currentTimeMillis() - startTime
                    val remainingSeconds = if (index > 0) {
                        val avgPerStation = elapsed / index
                        ((stations.size - index) * avgPerStation / 1000).toInt()
                    } else {
                        ((stations.size - index) * (rdsTimeoutMs / 1000)).toInt()
                    }

                    mainHandler.post { onProgress(progress, station.frequency, remainingSeconds, filteredCount) }

                    // RDS zurücksetzen und zur Frequenz tunen
                    rdsManager.clearRds()
                    tuneToFrequency(station.frequency)

                    // RDS-Daten sammeln
                    val (hasPs, hasPi, psName, piCode) = collectRdsViaManager(rdsTimeoutMs)

                    // Filtern basierend auf Modus
                    val passesFilter = when (filterMode) {
                        FilterMode.NONE -> true
                        FilterMode.REQUIRE_PS -> hasPs
                        FilterMode.REQUIRE_PI -> hasPi
                        FilterMode.REQUIRE_PS_AND_PI -> hasPs && hasPi
                        FilterMode.REQUIRE_PS_OR_PI -> hasPs || hasPi
                    }

                    if (!passesFilter) {
                        filteredCount++
                        val reason = when (filterMode) {
                            FilterMode.REQUIRE_PS -> "kein PS"
                            FilterMode.REQUIRE_PI -> "kein PI"
                            FilterMode.REQUIRE_PS_AND_PI -> if (!hasPs && !hasPi) "weder PS noch PI" else if (!hasPs) "kein PS" else "kein PI"
                            FilterMode.REQUIRE_PS_OR_PI -> "weder PS noch PI"
                            else -> "unknown"
                        }
                        Log.i(TAG, "  %.1f MHz → GEFILTERT ($reason)".format(station.frequency))
                        return@forEachIndexed
                    }

                    // Station mit RDS-Daten hinzufügen
                    val verifiedStation = station.copy(name = if (psName.isNotEmpty()) psName else null)
                    verifiedStations.add(verifiedStation)

                    mainHandler.post { onStationVerified?.invoke(verifiedStation) }

                    val piStr = if (piCode != 0) " [PI=0x%04X]".format(piCode) else ""
                    if (psName.isNotEmpty()) {
                        Log.i(TAG, "  %.1f MHz → \"%s\"$piStr ✓".format(station.frequency, psName))
                    } else {
                        Log.i(TAG, "  %.1f MHz → (kein Name)$piStr ✓".format(station.frequency))
                    }
                }

                if (isCancelled) {
                    if (isSkipped && verifiedStations.isNotEmpty()) {
                        Log.i(TAG, "RDS filter skipped - keeping ${verifiedStations.size} verified stations")
                        postSortedComplete(verifiedStations, onComplete)
                    } else {
                        Log.i(TAG, "RDS filter cancelled")
                        mainHandler.post { onComplete(emptyList()) }
                    }
                    return@thread
                }

                Log.i(TAG, "RDS filter complete: ${verifiedStations.size} verified, $filteredCount filtered")
                logScanResults(verifiedStations)
                postSortedComplete(verifiedStations, onComplete)

            } catch (e: Exception) {
                Log.e(TAG, "RDS filter failed: ${e.message}", e)
                postSortedComplete(verifiedStations, onComplete)
            } finally {
                isScanning = false
            }
        }
    }

    /**
     * Sammelt RDS-Daten über den RdsManager (nutzt dessen Polling mit allen Fallbacks)
     * Der RdsManager hat 12x readRds() + multiple Fallback-Methoden für PI
     * @return RdsResult mit PS und PI
     */
    private fun collectRdsViaManager(timeoutMs: Long): RdsResult {
        val startTime = System.currentTimeMillis()
        var foundPs = ""
        var foundPi = 0

        // RdsManager polling läuft bereits - wir warten einfach auf Daten
        // RdsManager macht: 12x readRds() + fetchPs() + GETRDSSTATE für PI + Fallbacks
        while (System.currentTimeMillis() - startTime < timeoutMs && !isCancelled) {
            Thread.sleep(200)  // Etwas schneller als Polling-Intervall (150ms)

            // PS vom RdsManager lesen (hat alle Fallback-Methoden)
            if (foundPs.isEmpty()) {
                val ps = rdsManager.ps
                if (!ps.isNullOrEmpty() && ps.length >= 2 && ps.any { it.isLetter() }) {
                    foundPs = ps
                    Log.d(TAG, "RDS PS from RdsManager: '$ps'")
                }
            }

            // PI vom RdsManager lesen (hat FmService Fallback + PS-Lookup)
            if (foundPi == 0) {
                val pi = rdsManager.pi
                if (pi != 0) {
                    foundPi = pi
                    Log.d(TAG, "RDS PI from RdsManager: 0x${pi.toString(16)}")
                }
            }

            // Früh abbrechen wenn beides gefunden
            if (foundPs.isNotEmpty() && foundPi != 0) {
                Log.i(TAG, "RDS complete: PS='$foundPs' PI=0x${foundPi.toString(16)}")
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
            postSortedComplete(foundStations, onComplete)
        }
    }

    fun stopScan() {
        if (isScanning) {
            isCancelled = true
            Log.i(TAG, "Scan stop requested")
            fmNative?.stopScan()
        }
    }

    /**
     * Skip scan - behält bereits gefundene Sender
     */
    fun skipScan() {
        if (isScanning) {
            isSkipped = true
            isCancelled = true
            Log.i(TAG, "Scan skip requested - keeping found stations")
            fmNative?.stopScan()
        }
    }

    /**
     * Native FM-Scan (NavRadio-Methode)
     * Verwendet fmsyu_jni Command 0x01 (AUTOSCAN)
     * Schneller als manueller Scan, aber ohne RDS-Verifizierung
     */
    fun scanFMNative(
        onProgress: (progress: Int, frequency: Float, remainingSeconds: Int, filteredCount: Int, phase: String) -> Unit,
        onStationFound: ((RadioStation) -> Unit)? = null,
        onComplete: (List<RadioStation>) -> Unit,
        highSensitivity: Boolean = false
    ) {
        if (isScanning) {
            Log.w(TAG, "Scan already running")
            return
        }

        isScanning = true
        isCancelled = false
        isSkipped = false

        thread {
            val foundStations = mutableListOf<RadioStation>()

            try {
                Log.i(TAG, "════════════════════════════════════════════════════")
                Log.i(TAG, "         NATIVE SCAN (fmsyu_jni 0x01)")
                Log.i(TAG, "════════════════════════════════════════════════════")

                val native = fmNative
                if (native == null) {
                    Log.e(TAG, "FmNative not available!")
                    mainHandler.post { onComplete(emptyList()) }
                    return@thread
                }

                // Radio initialisieren
                try {
                    native.openDev()
                    native.powerUp(FM_MIN)
                    Log.i(TAG, "Radio initialized for native scanning")
                } catch (e: Exception) {
                    Log.w(TAG, "Radio init failed (may already be on): ${e.message}")
                }

                mainHandler.post { onProgress(10, FM_MIN, 30, 0, "Native Scan...") }

                // Sensitivity (NavRadio-Stil: 0-3, wobei 0 = höchste Empfindlichkeit)
                val sensitivity = if (highSensitivity) 0 else 2

                // Native AutoScan aufrufen
                val inBundle = Bundle()
                val outBundle = Bundle()
                inBundle.putInt("param0", (FM_MIN * 10).toInt())  // Startfrequenz * 10
                inBundle.putInt("sensitivity", sensitivity)
                inBundle.putInt("isuseam", 0)  // FM Mode

                Log.i(TAG, "Calling fmsyu_jni(0x01) with param0=${(FM_MIN * 10).toInt()}, sensitivity=$sensitivity")

                mainHandler.post { onProgress(20, FM_MIN, 25, 0, "Hardware-Scan...") }

                val result = native.fmsyu_jni(FmNative.CMD_AUTOSCAN, inBundle, outBundle)

                Log.i(TAG, "fmsyu_jni(0x01) returned: $result")

                if (isCancelled) {
                    Log.i(TAG, "Native scan cancelled")
                    mainHandler.post { onComplete(emptyList()) }
                    return@thread
                }

                mainHandler.post { onProgress(80, FM_MAX, 5, 0, "Ergebnisse...") }

                if (result == 0 || result == -1) {
                    // Ergebnisse auslesen
                    val frequencies = outBundle.getShortArray("param0")
                    val strengths = outBundle.getShortArray("param1")

                    if (frequencies != null && frequencies.isNotEmpty()) {
                        Log.i(TAG, "Native scan found ${frequencies.size} stations")

                        frequencies.forEachIndexed { index, freqShort ->
                            // Frequenz ist * 10 gespeichert (z.B. 875 = 87.5 MHz)
                            val freq = freqShort.toFloat() / 10f
                            val rssi = strengths?.getOrNull(index)?.toInt() ?: 0

                            if (freq >= FM_MIN && freq <= FM_MAX) {
                                val station = RadioStation(freq, null, rssi, false)
                                foundStations.add(station)
                                Log.i(TAG, "★ FOUND: %.1f MHz | RSSI: %d".format(freq, rssi))
                                mainHandler.post { onStationFound?.invoke(station) }
                            }
                        }
                    } else {
                        Log.w(TAG, "Native scan returned no frequencies")
                        // Versuche alternative Keys
                        val allKeys = outBundle.keySet()
                        Log.d(TAG, "OutBundle keys: $allKeys")
                        allKeys.forEach { key ->
                            Log.d(TAG, "  $key = ${outBundle.get(key)}")
                        }
                    }
                } else {
                    Log.e(TAG, "Native scan failed with result: $result")
                }

                mainHandler.post { onProgress(100, FM_MAX, 0, 0, "Fertig") }

                Log.i(TAG, "Native scan complete: ${foundStations.size} stations found")
                postSortedComplete(foundStations, onComplete)

            } catch (e: Exception) {
                Log.e(TAG, "Native scan failed: ${e.message}", e)
                postSortedComplete(foundStations, onComplete)
            } finally {
                isScanning = false
            }
        }
    }

    private fun tuneToFrequency(frequency: Float): Boolean {
        return try {
            fmNative?.tune(frequency) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Tune failed: ${e.message}", e)
            false
        }
    }

    /**
     * Misst den Noise Floor durch Sampling zufälliger Frequenzen.
     * Nimmt den Durchschnitt der niedrigsten Werte als Baseline.
     */
    private fun measureNoiseFloor(native: FmNativeApi): Int {
        val measurements = mutableListOf<Int>()

        for (freq in ScanCalibration.sampleFrequencies(FM_MIN, FM_MAX, NOISE_FLOOR_SAMPLES)) {
            try {
                native.tune(freq)
                Thread.sleep(150)
                val rssi = measureRssi(2)
                if (rssi in 1..99) {
                    measurements.add(rssi)
                    Log.d(TAG, "Noise sample %.1f MHz: RSSI=%d".format(freq, rssi))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Noise sample failed at %.1f".format(freq))
            }
        }

        val noiseFloor = ScanCalibration.calculateNoiseFloor(measurements)
        Log.i(TAG, "Noise measurements: ${measurements.sorted()} → Noise Floor: $noiseFloor")
        return noiseFloor
    }

    private fun measureRssi(samples: Int): Int {
        val native = fmNative ?: return 0

        var totalRssi = 0
        var validSamples = 0

        repeat(samples) { i ->
            var rssi = 0
            var method = ""

            // Methode 1: fmsyu_jni
            try {
                val inBundle = Bundle()
                val outBundle = Bundle()
                val result = native.fmsyu_jni(CMD_GETRSSI, inBundle, outBundle)
                if (result == 0) {
                    val level = outBundle.getInt("rssilevel", 0)
                    if (level > 0) {
                        rssi = level
                        method = "fmsyu_jni"
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "fmsyu_jni RSSI failed: ${e.message}")
            }

            // Methode 2: getrssi()
            if (rssi == 0) {
                try {
                    val level = native.getrssi()
                    if (level > 0) {
                        rssi = level
                        method = "getrssi"
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "getrssi failed: ${e.message}")
                }
            }

            // Methode 3: sql_getrssi() - hat binder errors, als letztes versuchen
            if (rssi == 0) {
                try {
                    val level = native.sql_getrssi()
                    if (level > 0) {
                        rssi = level
                        method = "sql_getrssi"
                    }
                } catch (e: Throwable) {
                    // Ignorieren - binder errors erwartet
                }
            }

            // Log nur beim ersten Sample
            if (i == 0 && rssi > 0) {
                Log.d(TAG, "RSSI via $method: $rssi")
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
