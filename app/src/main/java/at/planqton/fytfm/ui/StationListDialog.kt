package at.planqton.fytfm.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import at.planqton.fytfm.data.RadioStation
import at.planqton.fytfm.scanner.RadioScanner

class StationListDialog(
    context: Context,
    private val radioScanner: RadioScanner,
    private val onStationsAdded: (List<RadioStation>) -> Unit,
    private val onStationSelected: (RadioStation) -> Unit,
    private val initialMode: Boolean = true,  // true = FM, false = AM
    private val highSensitivity: Boolean = false,
    private val config: ScanConfig
) : Dialog(context) {

    private lateinit var btnFmTab: Button
    private lateinit var btnAmTab: Button
    private lateinit var tabContainer: View
    private lateinit var stationRecycler: RecyclerView
    private lateinit var tvEmptyState: TextView
    private lateinit var scanProgressContainer: LinearLayout
    private lateinit var scanProgress: ProgressBar
    private lateinit var scanSpinner: ProgressBar
    private lateinit var tvScanStatus: TextView
    private lateinit var btnScan: Button
    private lateinit var btnFilter: Button
    private lateinit var btnAdd: Button
    private lateinit var btnClose: Button
    private lateinit var btnSkip: Button
    private lateinit var filterOptionsContainer: LinearLayout
    private lateinit var cbFilterPs: CheckBox
    private lateinit var cbFilterPi: CheckBox
    private lateinit var rgFilterLogic: RadioGroup
    private lateinit var rbFilterAnd: RadioButton
    private lateinit var rbFilterOr: RadioButton
    private lateinit var tvRdsTimeout: TextView
    private lateinit var seekRdsTimeout: SeekBar

    private var hasBeenFiltered = false  // Track ob bereits gefiltert wurde

    private val scanAdapter = ScanStationAdapter { station ->
        onStationSelected(station)
        dismiss()
    }

    private var isShowingFM = initialMode
    private var fmStations: List<RadioStation> = emptyList()
    private var amStations: List<RadioStation> = emptyList()
    private val scanResultsLive = mutableListOf<RadioStation>()
    private var currentPhase = ""
    private var scanCancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_station_list)

        // Make dialog wider
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )

        initViews()
        setupListeners()
        updateTabSelection()

        // Scan automatisch starten
        startScan()
    }

    override fun onStop() {
        super.onStop()
        scanCancelled = true
        radioScanner.stopScan()
    }

    private fun initViews() {
        tabContainer = findViewById(R.id.tabContainer)
        btnFmTab = findViewById(R.id.btnFmTab)
        btnAmTab = findViewById(R.id.btnAmTab)
        stationRecycler = findViewById(R.id.stationRecycler)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        scanProgressContainer = findViewById(R.id.scanProgressContainer)
        scanProgress = findViewById(R.id.scanProgress)
        scanSpinner = findViewById(R.id.scanSpinner)
        tvScanStatus = findViewById(R.id.tvScanStatus)
        btnScan = findViewById(R.id.btnScan)
        btnFilter = findViewById(R.id.btnFilter)
        btnClose = findViewById(R.id.btnClose)
        btnAdd = findViewById(R.id.btnAdd)
        btnSkip = findViewById(R.id.btnSkip)
        filterOptionsContainer = findViewById(R.id.filterOptionsContainer)
        cbFilterPs = findViewById(R.id.cbFilterPs)
        cbFilterPi = findViewById(R.id.cbFilterPi)
        rgFilterLogic = findViewById(R.id.rgFilterLogic)
        rbFilterAnd = findViewById(R.id.rbFilterAnd)
        rbFilterOr = findViewById(R.id.rbFilterOr)
        tvRdsTimeout = findViewById(R.id.tvRdsTimeout)
        seekRdsTimeout = findViewById(R.id.seekRdsTimeout)

        // Filter-Optionen mit config-Werten initialisieren
        initFilterOptionsFromConfig()
        filterOptionsContainer.visibility = View.GONE
        btnFilter.visibility = View.GONE

        // Nur den aktuellen Modus-Tab anzeigen
        if (isShowingFM) {
            btnAmTab.visibility = View.GONE
        } else {
            btnFmTab.visibility = View.GONE
        }

        stationRecycler.layoutManager = LinearLayoutManager(
            context, LinearLayoutManager.VERTICAL, false
        )
        stationRecycler.adapter = scanAdapter
    }

    private fun setupListeners() {
        btnFmTab.setOnClickListener {
            isShowingFM = true
            updateTabSelection()
            updateStationList()
        }

        btnAmTab.setOnClickListener {
            isShowingFM = false
            updateTabSelection()
            updateStationList()
        }

        btnScan.setOnClickListener {
            startScan()
        }

        btnClose.setOnClickListener {
            dismiss()
        }

        btnSkip.setOnClickListener {
            radioScanner.skipScan()
        }

        btnAdd.setOnClickListener {
            val stations = if (isShowingFM) fmStations else amStations
            if (stations.isNotEmpty()) {
                onStationsAdded(stations)
            }
            dismiss()
        }

        btnFilter.setOnClickListener {
            val filterMode = getSelectedFilterMode()
            if (fmStations.isNotEmpty()) {
                hasBeenFiltered = true
                btnFilter.visibility = View.GONE
                filterOptionsContainer.visibility = View.GONE
                scanProgressContainer.visibility = View.VISIBLE
                if (filterMode != RadioScanner.FilterMode.NONE) {
                    startRdsFilter()
                } else {
                    // Kein Filter ausgewählt - direkt als abgeschlossen markieren
                    onScanComplete()
                }
            }
        }

        // Filter-Checkbox Änderungen
        cbFilterPs.setOnCheckedChangeListener { _, _ -> updateFilterLogicVisibility() }
        cbFilterPi.setOnCheckedChangeListener { _, _ -> updateFilterLogicVisibility() }

        // Timeout Slider
        seekRdsTimeout.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvRdsTimeout.text = "RDS-Wartezeit: ${progress}s"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun initFilterOptionsFromConfig() {
        // Checkboxen basierend auf config setzen
        when (config.filterMode) {
            RadioScanner.FilterMode.REQUIRE_PS -> {
                cbFilterPs.isChecked = true
                cbFilterPi.isChecked = false
            }
            RadioScanner.FilterMode.REQUIRE_PI -> {
                cbFilterPs.isChecked = false
                cbFilterPi.isChecked = true
            }
            RadioScanner.FilterMode.REQUIRE_PS_AND_PI -> {
                cbFilterPs.isChecked = true
                cbFilterPi.isChecked = true
                rbFilterAnd.isChecked = true
            }
            RadioScanner.FilterMode.REQUIRE_PS_OR_PI -> {
                cbFilterPs.isChecked = true
                cbFilterPi.isChecked = true
                rbFilterOr.isChecked = true
            }
            else -> {
                cbFilterPs.isChecked = false
                cbFilterPi.isChecked = false
            }
        }

        // Timeout setzen
        val timeout = if (config.rdsTimeoutSeconds > 0) config.rdsTimeoutSeconds else 8
        seekRdsTimeout.progress = timeout
        tvRdsTimeout.text = "RDS-Wartezeit: ${timeout}s"

        updateFilterLogicVisibility()
    }

    private fun updateFilterLogicVisibility() {
        val hasBothFilters = cbFilterPs.isChecked && cbFilterPi.isChecked
        rgFilterLogic.visibility = if (hasBothFilters) View.VISIBLE else View.GONE

        // Filtern-Button aktivieren wenn mindestens ein Filter ausgewählt
        val hasFilter = cbFilterPs.isChecked || cbFilterPi.isChecked
        btnFilter.isEnabled = hasFilter
        btnFilter.alpha = if (hasFilter) 1.0f else 0.5f
    }

    private fun getSelectedFilterMode(): RadioScanner.FilterMode {
        val requirePs = cbFilterPs.isChecked
        val requirePi = cbFilterPi.isChecked

        return when {
            requirePs && requirePi && rbFilterAnd.isChecked -> RadioScanner.FilterMode.REQUIRE_PS_AND_PI
            requirePs && requirePi && rbFilterOr.isChecked -> RadioScanner.FilterMode.REQUIRE_PS_OR_PI
            requirePs -> RadioScanner.FilterMode.REQUIRE_PS
            requirePi -> RadioScanner.FilterMode.REQUIRE_PI
            else -> RadioScanner.FilterMode.NONE
        }
    }

    private fun updateTabSelection() {
        btnFmTab.isSelected = isShowingFM
        btnAmTab.isSelected = !isShowingFM
    }

    private fun updateStationList() {
        val stations = if (isShowingFM) fmStations else amStations

        if (stations.isEmpty()) {
            stationRecycler.visibility = View.GONE
            tvEmptyState.visibility = View.VISIBLE
        } else {
            stationRecycler.visibility = View.VISIBLE
            tvEmptyState.visibility = View.GONE
            scanAdapter.setStations(stations)
        }
    }

    private fun startScan() {
        scanCancelled = false
        hasBeenFiltered = false  // Reset bei neuem Scan
        scanProgressContainer.visibility = View.VISIBLE
        btnAdd.visibility = View.GONE
        btnFilter.visibility = View.GONE
        btnSkip.visibility = View.GONE
        btnScan.isEnabled = false
        scanProgress.progress = 0

        scanResultsLive.clear()
        scanAdapter.setStations(scanResultsLive)
        stationRecycler.visibility = View.VISIBLE
        tvEmptyState.visibility = View.GONE

        if (isShowingFM) {
            currentPhase = ""

            if (config.useNativeMethod) {
                // Native Scan - Spinner statt Progressbar
                scanProgress.visibility = View.GONE
                scanSpinner.visibility = View.VISIBLE
                tvScanStatus.text = "Native Scan..."
                radioScanner.scanFMNative(
                    highSensitivity = highSensitivity,
                    onProgress = { _, _, _, _, phase ->
                        val countStr = " | ${scanResultsLive.size} gefunden"
                        tvScanStatus.text = "$phase$countStr"
                    },
                    onStationFound = { station ->
                        scanResultsLive.add(station)
                        scanAdapter.setStations(scanResultsLive.toList())
                        stationRecycler.scrollToPosition(scanResultsLive.size - 1)
                        if (scanResultsLive.isNotEmpty() && btnSkip.visibility != View.VISIBLE) {
                            btnSkip.visibility = View.VISIBLE
                        }
                    },
                    onComplete = { stations ->
                        if (stations.isNotEmpty()) {
                            fmStations = stations
                        }
                        onPhase1Complete()
                    }
                )
            } else {
                // Experimenteller Scan - Progressbar
                scanSpinner.visibility = View.GONE
                scanProgress.visibility = View.VISIBLE
                scanProgress.isIndeterminate = false
                tvScanStatus.text = "Signal-Scan..."
                radioScanner.scanFMSignalOnly(
                    highSensitivity = highSensitivity,
                    onProgress = { progress, frequency, remainingSec, phase ->
                        scanProgress.progress = progress
                        val timeStr = if (remainingSec > 60) "%d:%02d".format(remainingSec / 60, remainingSec % 60) else "%ds".format(remainingSec)
                        val countStr = " | ${scanResultsLive.size} gefunden"
                        tvScanStatus.text = "$phase: %.1f MHz | ~$timeStr$countStr".format(frequency)
                    },
                    onStationFound = { station ->
                        scanResultsLive.add(station)
                        scanAdapter.setStations(scanResultsLive.toList())
                        stationRecycler.scrollToPosition(scanResultsLive.size - 1)
                        if (scanResultsLive.isNotEmpty() && btnSkip.visibility != View.VISIBLE) {
                            btnSkip.visibility = View.VISIBLE
                        }
                    },
                    onComplete = { stations ->
                        if (stations.isNotEmpty()) {
                            fmStations = stations
                        }
                        onPhase1Complete()
                    }
                )
            }
        } else {
            tvScanStatus.text = "Scanning AM..."
            radioScanner.scanAM(
                onProgress = { progress, frequency, remainingSec, _ ->
                    scanProgress.progress = progress
                    val timeStr = if (remainingSec > 60) "%d:%02d".format(remainingSec / 60, remainingSec % 60) else "%ds".format(remainingSec)
                    tvScanStatus.text = "AM %d kHz | ~$timeStr".format(frequency.toInt())
                },
                onComplete = { stations ->
                    if (stations.isNotEmpty()) {
                        amStations = stations
                    }
                    onScanComplete()
                }
            )
        }
    }

    private fun onPhase1Complete() {
        // Quick Scan: Direkt hinzufügen und Dialog schließen
        if (config.quickScan) {
            if (fmStations.isNotEmpty()) {
                onStationsAdded(fmStations)
            }
            dismiss()
            return
        }

        // Auto-Filter: Automatisch RDS-Filter starten
        if (config.autoFilter && config.filterMode != RadioScanner.FilterMode.NONE && fmStations.isNotEmpty()) {
            startRdsFilter()
            return
        }

        // Standard: Ergebnisse anzeigen
        onScanComplete()
    }

    private fun onScanComplete() {
        scanProgress.isIndeterminate = false
        scanProgressContainer.visibility = View.GONE
        btnSkip.visibility = View.GONE
        btnScan.isEnabled = true

        val hasStations = if (isShowingFM) fmStations.isNotEmpty() else amStations.isNotEmpty()
        btnAdd.visibility = if (hasStations) View.VISIBLE else View.GONE

        // Filtern-Button und Optionen anzeigen wenn:
        // - Es Sender gibt
        // - Noch nicht gefiltert wurde
        // - Nicht autoFilter (sonst wurde bereits automatisch gefiltert)
        val canFilter = hasStations &&
                        !hasBeenFiltered &&
                        !config.autoFilter
        btnFilter.visibility = if (canFilter) View.VISIBLE else View.GONE
        filterOptionsContainer.visibility = if (canFilter) View.VISIBLE else View.GONE

        updateStationList()
    }

    private fun startRdsFilter() {
        if (fmStations.isEmpty()) {
            onScanComplete()
            return
        }

        scanCancelled = false
        scanSpinner.visibility = View.GONE
        scanProgress.visibility = View.VISIBLE
        scanProgress.isIndeterminate = false
        scanProgress.progress = 0
        btnSkip.visibility = View.GONE

        scanResultsLive.clear()
        scanAdapter.setStations(scanResultsLive)

        // UI-Werte verwenden (User kann vor Filter anpassen)
        val filterMode = getSelectedFilterMode()
        val rdsTimeoutMs = seekRdsTimeout.progress * 1000L

        tvScanStatus.text = "RDS-Filter..."
        radioScanner.collectRdsAndFilter(
            stations = fmStations,
            filterMode = filterMode,
            rdsTimeoutMs = rdsTimeoutMs,
            onProgress = { progress, frequency, remainingSec, filteredCount ->
                scanProgress.progress = progress
                val timeStr = if (remainingSec > 60) "%d:%02d".format(remainingSec / 60, remainingSec % 60) else "%ds".format(remainingSec)
                val filterStr = if (filteredCount > 0) " | $filteredCount gefiltert" else ""
                val countStr = " | ${scanResultsLive.size} verifiziert"
                tvScanStatus.text = "RDS: %.1f MHz | ~$timeStr$filterStr$countStr".format(frequency)
            },
            onStationVerified = { station ->
                scanResultsLive.add(station)
                scanAdapter.setStations(scanResultsLive.toList())
                stationRecycler.scrollToPosition(scanResultsLive.size - 1)
                if (scanResultsLive.isNotEmpty() && btnSkip.visibility != View.VISIBLE) {
                    btnSkip.visibility = View.VISIBLE
                }
            },
            onComplete = { verifiedStations ->
                if (verifiedStations.isNotEmpty()) {
                    fmStations = verifiedStations
                }
                onScanComplete()
            }
        )
    }

    fun setFmStations(stations: List<RadioStation>) {
        fmStations = stations
        if (isShowingFM) updateStationList()
    }

    fun setAmStations(stations: List<RadioStation>) {
        amStations = stations
        if (!isShowingFM) updateStationList()
    }
}
