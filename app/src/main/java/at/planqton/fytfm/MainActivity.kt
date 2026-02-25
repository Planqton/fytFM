package at.planqton.fytfm

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.ui.StationAdapter
import com.android.fmradio.FmNative

class MainActivity : AppCompatActivity() {

    private lateinit var tvFrequency: TextView
    private lateinit var frequencyScale: FrequencyScaleView
    private lateinit var btnPrevStation: ImageButton
    private lateinit var btnNextStation: ImageButton
    private lateinit var btnFavorite: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnFM: ImageButton
    private lateinit var btnPower: ImageButton
    private lateinit var controlBar: LinearLayout
    private lateinit var stationRecycler: RecyclerView
    private lateinit var tvAllList: TextView
    private var debugOverlay: View? = null
    private var debugChecklist: View? = null
    private var debugPs: TextView? = null
    private var debugPi: TextView? = null
    private var debugPty: TextView? = null
    private var debugRt: TextView? = null
    private var debugRssi: TextView? = null
    private var debugFreq: TextView? = null
    private var debugAf: TextView? = null
    private var debugTpTa: TextView? = null
    private var checkRdsInfo: CheckBox? = null
    private var checkLayoutInfo: CheckBox? = null
    private var debugLayoutOverlay: View? = null
    private var debugScreenInfo: TextView? = null
    private var debugDensityInfo: TextView? = null

    private lateinit var presetRepository: PresetRepository
    private lateinit var stationAdapter: StationAdapter
    private lateinit var fmNative: FmNative
    private lateinit var rdsManager: RdsManager
    private var twUtil: TWUtilHelper? = null

    private var isPlaying = true
    private var isRadioOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        presetRepository = PresetRepository(this)
        fmNative = FmNative.getInstance()
        rdsManager = RdsManager(fmNative)

        // Initialize TWUtil for MCU communication - critical for RDS!
        twUtil = TWUtilHelper()
        if (twUtil?.isAvailable == true) {
            android.util.Log.i("fytFM", "TWUtil available, opening...")
            if (twUtil?.open() == true) {
                android.util.Log.i("fytFM", "TWUtil opened successfully")
            }
        }

        // Pass TWUtil to DebugReceiver for ADB debugging
        DebugReceiver.setTwUtil(twUtil)

        initViews()
        setupStationList()
        setupListeners()

        // Load last frequency from SharedPreferences
        val lastFreq = loadLastFrequency()
        frequencyScale.setFrequency(lastFreq)
        updateFrequencyDisplay(lastFreq)
        updateModeButton()
        loadStationsForCurrentMode()
        updatePowerButton()

        // Auto power on if enabled in settings
        if (presetRepository.isPowerOnStartup() && !isRadioOn) {
            toggleRadioPower()
        }
    }

    /**
     * Startet das RDS-Polling mit Callback für PS, RT, RSSI, PI, PTY, TP, TA, AF.
     */
    private fun startRdsPolling() {
        rdsManager.startPolling(object : RdsManager.RdsCallback {
            override fun onRdsUpdate(ps: String?, rt: String?, rssi: Int, pi: Int, pty: Int, tp: Int, ta: Int, afList: ShortArray?) {
                runOnUiThread {
                    // PTY mit Name
                    val ptyStr = if (pty > 0) "$pty (${RdsManager.getPtyName(pty)})" else ""

                    // TP/TA
                    val tpTaStr = "TP=$tp TA=$ta"

                    // PI als Hex
                    val piStr = if (pi != 0) String.format("0x%04X", pi and 0xFFFF) else ""

                    // AF als Frequenz-Liste
                    val afStr = if (afList != null && afList.isNotEmpty()) {
                        afList.map { freq ->
                            // AF-Frequenzen sind oft in 100 kHz Einheiten oder direkt als Dezimalwert
                            val freqMhz = if (freq > 875) freq / 10.0f else (87.5f + freq * 0.1f)
                            String.format("%.1f", freqMhz)
                        }.joinToString(", ")
                    } else ""

                    updateDebugInfo(
                        ps = ps ?: "",
                        rt = rt ?: "",
                        rssi = rssi,
                        pi = piStr,
                        pty = ptyStr,
                        tpTa = tpTaStr,
                        af = afStr
                    )
                }
            }
        })
    }

    private fun stopRdsPolling() {
        rdsManager.stopPolling()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        setPipLayout(!hasFocus)
    }

    private fun setPipLayout(isPip: Boolean) {
        if (isPip) {
            btnFavorite.visibility = View.GONE
            btnPrevStation.visibility = View.GONE
            btnNextStation.visibility = View.GONE
            controlBar.visibility = View.GONE
            findViewById<LinearLayout>(R.id.stationBar)?.visibility = View.GONE
            tvFrequency.textSize = 36f
        } else {
            btnFavorite.visibility = View.VISIBLE
            btnPrevStation.visibility = View.VISIBLE
            btnNextStation.visibility = View.VISIBLE
            controlBar.visibility = View.VISIBLE
            findViewById<LinearLayout>(R.id.stationBar)?.visibility = View.VISIBLE
            tvFrequency.textSize = 99f
        }
    }

    private fun initViews() {
        tvFrequency = findViewById(R.id.tvFrequency)
        frequencyScale = findViewById(R.id.frequencyScale)
        btnPrevStation = findViewById(R.id.btnPrevStation)
        btnNextStation = findViewById(R.id.btnNextStation)
        btnFavorite = findViewById(R.id.btnFavorite)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnFM = findViewById(R.id.btnFM)
        btnPower = findViewById(R.id.btnPower)
        controlBar = findViewById(R.id.controlBar)
        stationRecycler = findViewById(R.id.stationRecycler)
        tvAllList = findViewById(R.id.tvAllList)

        // Debug overlays
        debugOverlay = findViewById(R.id.debugOverlay)
        debugChecklist = findViewById(R.id.debugChecklistOverlay)
        debugPs = findViewById(R.id.debugPs)
        debugPi = findViewById(R.id.debugPi)
        debugPty = findViewById(R.id.debugPty)
        debugRt = findViewById(R.id.debugRt)
        debugRssi = findViewById(R.id.debugRssi)
        debugFreq = findViewById(R.id.debugFreq)
        debugAf = findViewById(R.id.debugAf)
        debugTpTa = findViewById(R.id.debugTpTa)
        checkRdsInfo = findViewById(R.id.checkRdsInfo)
        checkLayoutInfo = findViewById(R.id.checkLayoutInfo)
        debugLayoutOverlay = findViewById(R.id.debugLayoutOverlay)
        debugScreenInfo = findViewById(R.id.debugScreenInfo)
        debugDensityInfo = findViewById(R.id.debugDensityInfo)

        setupDebugOverlayDrag()
        setupDebugChecklistDrag()
        setupDebugChecklistListeners()
        updateDebugOverlayVisibility()
    }

    private fun setupDebugChecklistDrag() {
        val checklist = debugChecklist ?: return
        var dX = 0f
        var dY = 0f

        checklist.setOnTouchListener { view, event ->
            // Don't intercept clicks on checkboxes
            if (event.action == MotionEvent.ACTION_DOWN) {
                val checkbox = checkRdsInfo ?: return@setOnTouchListener false
                val location = IntArray(2)
                checkbox.getLocationOnScreen(location)
                val checkboxRect = android.graphics.Rect(
                    location[0], location[1],
                    location[0] + checkbox.width,
                    location[1] + checkbox.height
                )
                val screenX = event.rawX.toInt()
                val screenY = event.rawY.toInt()
                if (checkboxRect.contains(screenX, screenY)) {
                    return@setOnTouchListener false
                }
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newX = (event.rawX + dX).coerceIn(0f, (view.parent as View).width - view.width.toFloat())
                    val newY = (event.rawY + dY).coerceIn(0f, (view.parent as View).height - view.height.toFloat())
                    view.x = newX
                    view.y = newY
                    true
                }
                else -> false
            }
        }
    }

    private fun setupDebugChecklistListeners() {
        checkRdsInfo?.setOnCheckedChangeListener { _, isChecked ->
            debugOverlay?.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        checkLayoutInfo?.setOnCheckedChangeListener { _, isChecked ->
            debugLayoutOverlay?.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                updateLayoutDebugInfo()
            }
        }
    }

    private fun updateLayoutDebugInfo() {
        val metrics = resources.displayMetrics
        debugScreenInfo?.text = "Screen: ${metrics.widthPixels}x${metrics.heightPixels}"
        debugDensityInfo?.text = "Density: ${metrics.density} (${metrics.densityDpi}dpi)"
    }

    private fun setupDebugOverlayDrag() {
        val overlay = debugOverlay ?: return
        var dX = 0f
        var dY = 0f

        overlay.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newX = (event.rawX + dX).coerceIn(0f, (view.parent as View).width - view.width.toFloat())
                    val newY = (event.rawY + dY).coerceIn(0f, (view.parent as View).height - view.height.toFloat())
                    view.x = newX
                    view.y = newY
                    true
                }
                else -> false
            }
        }
    }

    private fun updateDebugOverlayVisibility() {
        val showDebug = presetRepository.isShowDebugInfos()
        debugChecklist?.visibility = if (showDebug) View.VISIBLE else View.GONE
        // RDS overlay visibility depends on both the setting AND the checkbox
        debugOverlay?.visibility = if (showDebug && checkRdsInfo?.isChecked == true) View.VISIBLE else View.GONE
    }

    fun updateDebugInfo(ps: String? = null, pi: String? = null, pty: String? = null,
                        rt: String? = null, rssi: Int? = null, freq: Float? = null, af: String? = null, tpTa: String? = null) {
        if (debugOverlay?.visibility != View.VISIBLE) return

        ps?.let { debugPs?.text = it.ifEmpty { "--------" } }
        pi?.let { debugPi?.text = if (it.isNotEmpty()) it else "----" }
        pty?.let { debugPty?.text = if (it.isNotEmpty()) it else "--" }
        rt?.let { debugRt?.text = it.ifEmpty { "--------------------------------" } }
        rssi?.let { debugRssi?.text = "$it dBm" }
        freq?.let { debugFreq?.text = String.format("%.1f MHz", it) }
        af?.let { debugAf?.text = it.ifEmpty { "----" } }
        tpTa?.let { debugTpTa?.text = it }
    }

    private fun setupStationList() {
        stationAdapter = StationAdapter { station ->
            // Tune to selected station
            if (station.isAM) {
                frequencyScale.setMode(FrequencyScaleView.RadioMode.AM)
            } else {
                frequencyScale.setMode(FrequencyScaleView.RadioMode.FM)
            }
            frequencyScale.setFrequency(station.frequency)
            stationAdapter.setSelectedFrequency(station.frequency)
        }

        stationRecycler.layoutManager = LinearLayoutManager(
            this, LinearLayoutManager.HORIZONTAL, false
        )
        stationRecycler.adapter = stationAdapter
    }

    private fun setupListeners() {
        frequencyScale.setOnFrequencyChangeListener { frequency ->
            updateFrequencyDisplay(frequency)
            stationAdapter.setSelectedFrequency(frequency)
            // Clear RDS data on frequency change for fresh data
            rdsManager.clearRds()
            // Actually tune the radio hardware!
            tuneToFrequency(frequency)
            // Save frequency to SharedPreferences
            saveLastFrequency(frequency)
        }

        frequencyScale.setOnModeChangeListener { mode ->
            updateModeButton()
            loadStationsForCurrentMode()
        }

        btnPrevStation.setOnClickListener {
            val step = if (frequencyScale.getMode() == FrequencyScaleView.RadioMode.FM) {
                FrequencyScaleView.FM_FREQUENCY_STEP
            } else {
                FrequencyScaleView.AM_FREQUENCY_STEP
            }
            val newFreq = frequencyScale.getFrequency() - step
            frequencyScale.setFrequency(newFreq)
        }

        btnNextStation.setOnClickListener {
            val step = if (frequencyScale.getMode() == FrequencyScaleView.RadioMode.FM) {
                FrequencyScaleView.FM_FREQUENCY_STEP
            } else {
                FrequencyScaleView.AM_FREQUENCY_STEP
            }
            val newFreq = frequencyScale.getFrequency() + step
            frequencyScale.setFrequency(newFreq)
        }

        btnFavorite.setOnClickListener {
            // Favorite functionality - to be implemented
        }

        btnPlayPause.setOnClickListener {
            isPlaying = !isPlaying
            updatePlayPauseButton()
        }

        // FM/AM toggle button
        btnFM.setOnClickListener {
            frequencyScale.toggleMode()
        }

        // Power button - toggle radio on/off
        btnPower.setOnClickListener {
            toggleRadioPower()
        }

        // Search button - DEAKTIVIERT
        findViewById<ImageButton>(R.id.btnSearch).setOnClickListener {
            // Scanner deaktiviert
            android.widget.Toast.makeText(this, "Scanner deaktiviert", android.widget.Toast.LENGTH_SHORT).show()
        }

        // All list button
        tvAllList.setOnClickListener {
            // TODO: Show full station list dialog
        }

        // Other bottom control buttons
        findViewById<ImageButton>(R.id.btnSkipPrev).setOnClickListener {
            skipToPreviousStation()
        }
        findViewById<ImageButton>(R.id.btnSkipNext).setOnClickListener {
            skipToNextStation()
        }
        findViewById<ImageButton>(R.id.btnFolder).setOnClickListener { }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            showSettingsDialog()
        }

        // Long press on FM button for debug tune
        btnFM.setOnLongClickListener {
            debugTune()
            true
        }
    }

    /**
     * Debug-Funktion: Tune zu 90.4 MHz und zeige RDS-Daten
     */
    private fun debugTune() {
        val targetFreq = 90.4f
        android.util.Log.i("fytFM", "=== DEBUG: Tuning to $targetFreq FM ===")

        // Stelle sicher dass Radio eingeschaltet ist
        if (!isRadioOn) {
            toggleRadioPower()
        }

        // Tune
        frequencyScale.setFrequency(targetFreq)

        android.util.Log.i("fytFM", "=== DEBUG COMPLETE ===")
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)

        val dialog = AlertDialog.Builder(this, R.style.TransparentDialog)
            .setView(dialogView)
            .create()

        // Radio Editor click
        dialogView.findViewById<View>(R.id.itemRadioEditor).setOnClickListener {
            dialog.dismiss()
            showRadioEditorDialog()
        }

        // Power on startup toggle
        val switchPower = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPowerOnStartup)
        switchPower.isChecked = presetRepository.isPowerOnStartup()
        switchPower.setOnCheckedChangeListener { _, isChecked ->
            presetRepository.setPowerOnStartup(isChecked)
        }

        // Show debug infos toggle
        val switchDebug = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchShowDebug)
        switchDebug.isChecked = presetRepository.isShowDebugInfos()
        switchDebug.setOnCheckedChangeListener { _, isChecked ->
            presetRepository.setShowDebugInfos(isChecked)
            updateDebugOverlayVisibility()
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun showRadioEditorDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_radio_editor, null)

        val dialog = AlertDialog.Builder(this, R.style.TransparentDialog)
            .setView(dialogView)
            .create()

        val rvStations = dialogView.findViewById<RecyclerView>(R.id.rvStations)
        rvStations.layoutManager = LinearLayoutManager(this)

        val stations = if (frequencyScale.getMode() == FrequencyScaleView.RadioMode.FM) {
            presetRepository.loadFmStations()
        } else {
            presetRepository.loadAmStations()
        }

        val adapter = at.planqton.fytfm.ui.StationEditorAdapter(
            onEdit = { station ->
                showEditStationDialog(station) { newName ->
                    // Update station name
                    val updatedStations = stations.map {
                        if (it.frequency == station.frequency) it.copy(name = newName) else it
                    }
                    saveStations(updatedStations)
                    loadStationsForCurrentMode()
                }
            },
            onFavorite = { station ->
                val updatedStations = stations.map {
                    if (it.frequency == station.frequency) it.copy(isFavorite = !it.isFavorite) else it
                }
                saveStations(updatedStations)
                rvStations.adapter?.notifyDataSetChanged()
            },
            onDelete = { station ->
                AlertDialog.Builder(this)
                    .setTitle("Sender löschen")
                    .setMessage("${station.getDisplayFrequency()} wirklich löschen?")
                    .setPositiveButton("Ja") { _, _ ->
                        val updatedStations = stations.filter { it.frequency != station.frequency }
                        saveStations(updatedStations)
                        loadStationsForCurrentMode()
                        dialog.dismiss()
                        showRadioEditorDialog() // Reopen with updated list
                    }
                    .setNegativeButton("Nein", null)
                    .show()
            }
        )
        adapter.setStations(stations)
        rvStations.adapter = adapter

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun showEditStationDialog(station: at.planqton.fytfm.data.RadioStation, onSave: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            setText(station.name ?: "")
            hint = "Sendername"
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Sender bearbeiten")
            .setMessage(station.getDisplayFrequency())
            .setView(input)
            .setPositiveButton("Speichern") { _, _ ->
                onSave(input.text.toString())
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun saveStations(stations: List<at.planqton.fytfm.data.RadioStation>) {
        if (frequencyScale.getMode() == FrequencyScaleView.RadioMode.FM) {
            presetRepository.saveFmStations(stations)
        } else {
            presetRepository.saveAmStations(stations)
        }
    }

    private fun loadStationsForCurrentMode() {
        val stations = if (frequencyScale.getMode() == FrequencyScaleView.RadioMode.FM) {
            presetRepository.loadFmStations()
        } else {
            presetRepository.loadAmStations()
        }
        stationAdapter.setStations(stations)
        stationAdapter.setSelectedFrequency(frequencyScale.getFrequency())
    }

    // Scanner-Funktionen deaktiviert

    private fun skipToPreviousStation() {
        val stations = stationAdapter.getStations()
        if (stations.isEmpty()) return

        val currentFreq = frequencyScale.getFrequency()
        val prevStation = stations.lastOrNull { it.frequency < currentFreq - 0.05f }
            ?: stations.lastOrNull()

        prevStation?.let {
            frequencyScale.setFrequency(it.frequency)
        }
    }

    private fun skipToNextStation() {
        val stations = stationAdapter.getStations()
        if (stations.isEmpty()) return

        val currentFreq = frequencyScale.getFrequency()
        val nextStation = stations.firstOrNull { it.frequency > currentFreq + 0.05f }
            ?: stations.firstOrNull()

        nextStation?.let {
            frequencyScale.setFrequency(it.frequency)
        }
    }

    private fun updateFrequencyDisplay(frequency: Float) {
        val mode = frequencyScale.getMode()
        tvFrequency.text = if (mode == FrequencyScaleView.RadioMode.FM) {
            String.format("FM %.2f", frequency)
        } else {
            String.format("AM %d", frequency.toInt())
        }
        // Update debug overlay frequency
        updateDebugInfo(freq = frequency)
    }

    private fun updateModeButton() {
        val mode = frequencyScale.getMode()
        val iconRes = if (mode == FrequencyScaleView.RadioMode.FM) {
            R.drawable.ic_fm
        } else {
            R.drawable.ic_am
        }
        btnFM.setImageResource(iconRes)
    }

    private fun updatePlayPauseButton() {
        val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        btnPlayPause.setImageResource(iconRes)
    }

    /**
     * Radio Ein/Aus schalten.
     *
     * WICHTIGE REIHENFOLGE für RDS:
     * 1. TWUtil.initRadioSequence() - MCU initialisieren
     * 2. TWUtil.radioOn() - Audio-Routing aktivieren
     * 3. FmNative.powerOn() - FM-Chip einschalten
     * 4. RdsManager.enableRds() - RDS aktivieren
     * 5. RdsManager.startPolling() - RDS-Daten abrufen
     */
    private fun toggleRadioPower() {
        android.util.Log.i("fytFM", "======= toggleRadioPower() =======")
        android.util.Log.i("fytFM", "FmNative.isLibraryLoaded: ${FmNative.isLibraryLoaded()}")
        android.util.Log.i("fytFM", "TWUtil available: ${twUtil?.isAvailable}")

        try {
            if (isRadioOn) {
                // Radio ausschalten
                android.util.Log.i("fytFM", "--- Powering OFF ---")
                stopRdsPolling()
                fmNative.powerOff()
                twUtil?.radioOff()
                isRadioOn = false
            } else {
                // Radio einschalten - REIHENFOLGE KRITISCH!
                val frequency = frequencyScale.getFrequency()
                android.util.Log.i("fytFM", "--- Powering ON at $frequency MHz ---")

                // Schritt 1+2: TWUtil (MCU + Audio)
                if (twUtil?.isAvailable == true) {
                    android.util.Log.i("fytFM", "Step 1: TWUtil.initRadioSequence()")
                    twUtil?.initRadioSequence()

                    android.util.Log.i("fytFM", "Step 2: TWUtil.radioOn()")
                    twUtil?.radioOn()
                } else {
                    android.util.Log.w("fytFM", "TWUtil NOT available - skipping MCU init!")
                }

                // Schritt 3: FM-Chip einschalten
                android.util.Log.i("fytFM", "Step 3: FmNative.openDev()")
                val openResult = fmNative.openDev()
                android.util.Log.i("fytFM", "openDev result: $openResult")

                android.util.Log.i("fytFM", "Step 4: FmNative.powerUp($frequency)")
                val powerResult = fmNative.powerUp(frequency)
                android.util.Log.i("fytFM", "powerUp result: $powerResult")

                android.util.Log.i("fytFM", "Step 5: FmNative.tune($frequency)")
                val tuneResult = fmNative.tune(frequency)
                android.util.Log.i("fytFM", "tune result: $tuneResult")

                isRadioOn = openResult && powerResult
                android.util.Log.i("fytFM", "isRadioOn = $isRadioOn")

                if (isRadioOn) {
                    // Schritt 6: RDS aktivieren
                    android.util.Log.i("fytFM", "Step 6: RdsManager.enableRds()")
                    rdsManager.enableRds()

                    // Schritt 7: RDS-Polling starten
                    android.util.Log.i("fytFM", "Step 7: startRdsPolling()")
                    startRdsPolling()
                } else {
                    android.util.Log.e("fytFM", "RADIO FAILED TO START!")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("fytFM", "Radio power toggle failed: ${e.message}", e)
            isRadioOn = false
        }
        android.util.Log.i("fytFM", "======= toggleRadioPower() done =======")
        updatePowerButton()
    }

    /**
     * Tune zu einer Frequenz.
     */
    private fun tuneToFrequency(frequency: Float) {
        android.util.Log.d("fytFM", "Tuning to $frequency MHz")

        try {
            rdsManager.tune(frequency)
        } catch (e: Throwable) {
            android.util.Log.w("fytFM", "tune failed: ${e.message}")
        }
    }

    private fun updatePowerButton() {
        // Update power button appearance based on radio state
        btnPower.alpha = if (isRadioOn) 1.0f else 0.5f
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRdsPolling()
        twUtil?.close()
        // Turn off radio when app closes
        if (isRadioOn) {
            fmNative.powerOff()
        }
    }

    private fun saveLastFrequency(frequency: Float) {
        val prefs = getSharedPreferences("fytfm_prefs", Context.MODE_PRIVATE)
        prefs.edit().putFloat("last_frequency", frequency).apply()
    }

    private fun loadLastFrequency(): Float {
        val prefs = getSharedPreferences("fytfm_prefs", Context.MODE_PRIVATE)
        return prefs.getFloat("last_frequency", 90.4f) // Default: 90.4 MHz
    }
}
