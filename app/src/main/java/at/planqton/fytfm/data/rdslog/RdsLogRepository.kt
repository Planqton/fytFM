package at.planqton.fytfm.data.rdslog

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class RdsLogRepository(context: Context) {

    private val database = RdsDatabase.getInstance(context)
    private val dao = database.rdsLogDao()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val prefs = context.getSharedPreferences("rds_log_settings", Context.MODE_PRIVATE)

    // State tracking for change detection
    private var lastRt: String? = null
    private var currentFrequency: Float = 0f
    private var isAM: Boolean = false

    // Current RDS state (for logging when RT changes)
    private var currentPs: String? = null
    private var currentPi: Int = 0
    private var currentPty: Int = 0
    private var currentTp: Int = 0
    private var currentTa: Int = 0
    private var currentRssi: Int = 0
    private var currentAfList: String? = null

    // Settings
    var retentionDays: Int
        get() = prefs.getInt("retention_days", 7)
        set(value) = prefs.edit().putInt("retention_days", value).apply()

    var loggingEnabled: Boolean
        get() = prefs.getBoolean("logging_enabled", true)
        set(value) = prefs.edit().putBoolean("logging_enabled", value).apply()

    /**
     * Called on frequency change (station change).
     * Logs a STATION_CHANGE event with current RDS data.
     */
    fun onStationChange(frequency: Float, isAM: Boolean) {
        if (!loggingEnabled) return

        val oldFrequency = currentFrequency
        currentFrequency = frequency
        this.isAM = isAM

        // Only log if we actually changed frequency (not initial)
        if (oldFrequency > 0 && oldFrequency != frequency) {
            scope.launch {
                val entry = RdsLogEntry(
                    timestamp = System.currentTimeMillis(),
                    frequency = frequency,
                    isAM = isAM,
                    ps = currentPs,
                    rt = lastRt,
                    pi = currentPi,
                    pty = currentPty,
                    tp = currentTp,
                    ta = currentTa,
                    rssi = currentRssi,
                    afList = currentAfList,
                    eventType = "STATION_CHANGE"
                )
                dao.insert(entry)
            }
        }

        // Reset RT state for new station
        lastRt = null
    }

    /**
     * Called from RdsManager callback. Only logs if RT changes.
     */
    fun onRdsUpdate(
        ps: String?,
        rt: String?,
        pi: Int,
        pty: Int,
        tp: Int,
        ta: Int,
        rssi: Int,
        afList: ShortArray?
    ) {
        if (!loggingEnabled) return

        // Update current state
        currentPs = ps?.takeIf { it.isNotBlank() }
        currentPi = pi
        currentPty = pty
        currentTp = tp
        currentTa = ta
        currentRssi = rssi
        currentAfList = afList?.joinToString(",")

        // Check if RT changed
        val newRt = rt?.takeIf { it.isNotBlank() }
        if (newRt != lastRt && newRt != null) {
            lastRt = newRt

            scope.launch {
                val entry = RdsLogEntry(
                    timestamp = System.currentTimeMillis(),
                    frequency = currentFrequency,
                    isAM = isAM,
                    ps = currentPs,
                    rt = newRt,
                    pi = currentPi,
                    pty = currentPty,
                    tp = currentTp,
                    ta = currentTa,
                    rssi = currentRssi,
                    afList = currentAfList,
                    eventType = null // RT change
                )
                dao.insert(entry)
            }
        }
    }

    /**
     * Set initial frequency without logging.
     */
    fun setInitialFrequency(frequency: Float, isAM: Boolean) {
        currentFrequency = frequency
        this.isAM = isAM
        lastRt = null
    }

    // Query methods
    fun getAllEntries(): Flow<List<RdsLogEntry>> = dao.getAllEntriesFlow()

    fun getEntriesForFrequency(frequency: Float): Flow<List<RdsLogEntry>> =
        dao.getEntriesByFrequency(frequency - 0.05f, frequency + 0.05f)

    fun searchRt(query: String): Flow<List<RdsLogEntry>> = dao.searchRt(query)

    fun getDistinctFrequencies(): Flow<List<FrequencyStats>> = dao.getDistinctFrequencies()

    // Cleanup
    fun performCleanup() {
        scope.launch {
            val cutoff = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)
            dao.deleteOlderThan(cutoff)
        }
    }

    fun clearAll() {
        scope.launch {
            dao.deleteAll()
            lastRt = null
        }
    }

    suspend fun getEntryCount(): Int = dao.getEntryCount()

    fun destroy() {
        scope.cancel()
    }
}
