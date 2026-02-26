package at.planqton.fytfm.ui

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
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
    private val highSensitivity: Boolean = false  // true = detect weak signals
) : Dialog(context) {

    private lateinit var btnFmTab: Button
    private lateinit var btnAmTab: Button
    private lateinit var tabContainer: View
    private lateinit var stationRecycler: RecyclerView
    private lateinit var tvEmptyState: TextView
    private lateinit var scanProgressContainer: LinearLayout
    private lateinit var scanOptionsContainer: LinearLayout
    private lateinit var scanProgress: ProgressBar
    private lateinit var tvScanStatus: TextView
    private lateinit var btnScan: Button
    private lateinit var btnAdd: Button
    private lateinit var btnClose: Button
    private lateinit var cbRequirePs: CheckBox
    private lateinit var cbRequirePi: CheckBox

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
        updateStationList()
    }

    override fun onStop() {
        super.onStop()
        // Scan stoppen wenn Dialog geschlossen wird
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
        scanOptionsContainer = findViewById(R.id.scanOptionsContainer)
        scanProgress = findViewById(R.id.scanProgress)
        tvScanStatus = findViewById(R.id.tvScanStatus)
        btnScan = findViewById(R.id.btnScan)
        btnClose = findViewById(R.id.btnClose)
        cbRequirePs = findViewById(R.id.cbRequirePs)
        cbRequirePi = findViewById(R.id.cbRequirePi)
        btnAdd = findViewById(R.id.btnAdd)

        // Checkboxen standardmäßig aktiviert
        cbRequirePs.isChecked = true
        cbRequirePi.isChecked = true

        // Nur den aktuellen Modus-Tab anzeigen, anderen verstecken
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

        btnAdd.setOnClickListener {
            val stations = if (isShowingFM) fmStations else amStations
            if (stations.isNotEmpty()) {
                onStationsAdded(stations)
            }
            dismiss()
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
        scanCancelled = false  // Reset für neuen Scan
        scanProgressContainer.visibility = View.VISIBLE
        scanOptionsContainer.visibility = View.GONE  // Optionen während Scan ausblenden
        btnAdd.visibility = View.GONE  // Hinzufügen-Button ausblenden
        btnScan.isEnabled = false
        scanProgress.progress = 0

        // Live-Liste leeren und RecyclerView anzeigen für Live-Updates
        scanResultsLive.clear()
        scanAdapter.setStations(scanResultsLive)
        stationRecycler.visibility = View.VISIBLE
        tvEmptyState.visibility = View.GONE

        val requirePs = cbRequirePs.isChecked
        val requirePi = cbRequirePi.isChecked

        if (isShowingFM) {
            currentPhase = ""
            tvScanStatus.text = "Phase 1: Signal..."
            radioScanner.scanFM(
                highSensitivity = highSensitivity,
                onProgress = { progress, frequency, remainingSec, filteredCount, phase ->
                    // Bei Phasenwechsel Liste leeren
                    if (phase != currentPhase) {
                        if (phase.contains("Phase 2") && currentPhase.contains("Phase 1")) {
                            scanResultsLive.clear()
                            scanAdapter.setStations(scanResultsLive.toList())
                        }
                        currentPhase = phase
                    }

                    scanProgress.progress = progress
                    val timeStr = if (remainingSec > 60) "%d:%02d".format(remainingSec / 60, remainingSec % 60) else "%ds".format(remainingSec)
                    val filterStr = if (filteredCount > 0) " | $filteredCount gefiltert" else ""
                    val countStr = " | ${scanResultsLive.size} gefunden"
                    tvScanStatus.text = "$phase: %.1f MHz | ~$timeStr$filterStr$countStr".format(frequency)
                },
                onStationFound = { station ->
                    // Live-Update: Sender sofort zur Liste hinzufügen
                    scanResultsLive.add(station)
                    scanAdapter.setStations(scanResultsLive.toList())
                    stationRecycler.scrollToPosition(scanResultsLive.size - 1)
                },
                onComplete = { stations ->
                    // Bei Abbruch: alte Sender behalten, nur UI zurücksetzen
                    if (!scanCancelled && stations.isNotEmpty()) {
                        fmStations = stations
                    }
                    scanProgressContainer.visibility = View.GONE
                    scanOptionsContainer.visibility = View.VISIBLE
                    btnAdd.visibility = if (fmStations.isNotEmpty()) View.VISIBLE else View.GONE
                    btnScan.isEnabled = true
                    updateStationList()
                },
                requirePs = requirePs,
                requirePi = requirePi
            )
        } else {
            tvScanStatus.text = "Scanning AM..."
            radioScanner.scanAM(
                onProgress = { progress, frequency, remainingSec, _ ->
                    scanProgress.progress = progress
                    val timeStr = if (remainingSec > 60) "%d:%02d".format(remainingSec / 60, remainingSec % 60) else "%ds".format(remainingSec)
                    tvScanStatus.text = "AM %d kHz | ~$timeStr".format(frequency.toInt())
                },
                onComplete = { stations ->
                    // Bei Abbruch: alte Sender behalten, nur UI zurücksetzen
                    if (!scanCancelled && stations.isNotEmpty()) {
                        amStations = stations
                    }
                    scanProgressContainer.visibility = View.GONE
                    scanOptionsContainer.visibility = View.VISIBLE
                    btnAdd.visibility = if (amStations.isNotEmpty()) View.VISIBLE else View.GONE
                    btnScan.isEnabled = true
                    updateStationList()
                }
            )
        }
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
