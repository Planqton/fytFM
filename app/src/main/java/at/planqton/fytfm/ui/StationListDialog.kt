package at.planqton.fytfm.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.Button
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
    private val onStationSelected: (RadioStation) -> Unit
) : Dialog(context) {

    private lateinit var btnFmTab: Button
    private lateinit var btnAmTab: Button
    private lateinit var stationRecycler: RecyclerView
    private lateinit var tvEmptyState: TextView
    private lateinit var scanProgressContainer: LinearLayout
    private lateinit var scanProgress: ProgressBar
    private lateinit var tvScanStatus: TextView
    private lateinit var btnScan: Button
    private lateinit var btnClose: Button

    private val stationAdapter = StationAdapter { station ->
        onStationSelected(station)
        dismiss()
    }

    private var isShowingFM = true
    private var fmStations: List<RadioStation> = emptyList()
    private var amStations: List<RadioStation> = emptyList()

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

    private fun initViews() {
        btnFmTab = findViewById(R.id.btnFmTab)
        btnAmTab = findViewById(R.id.btnAmTab)
        stationRecycler = findViewById(R.id.stationRecycler)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        scanProgressContainer = findViewById(R.id.scanProgressContainer)
        scanProgress = findViewById(R.id.scanProgress)
        tvScanStatus = findViewById(R.id.tvScanStatus)
        btnScan = findViewById(R.id.btnScan)
        btnClose = findViewById(R.id.btnClose)

        stationRecycler.layoutManager = LinearLayoutManager(
            context, LinearLayoutManager.HORIZONTAL, false
        )
        stationRecycler.adapter = stationAdapter
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
            stationAdapter.setStations(stations)
        }
    }

    private fun startScan() {
        scanProgressContainer.visibility = View.VISIBLE
        btnScan.isEnabled = false
        scanProgress.progress = 0

        if (isShowingFM) {
            tvScanStatus.text = "Scanning FM..."
            radioScanner.scanFM(
                onProgress = { progress, frequency, remainingSec, _ ->
                    scanProgress.progress = progress
                    val timeStr = if (remainingSec > 60) "%d:%02d".format(remainingSec / 60, remainingSec % 60) else "%ds".format(remainingSec)
                    tvScanStatus.text = "FM %.1f MHz | ~$timeStr".format(frequency)
                },
                onComplete = { stations ->
                    fmStations = stations
                    scanProgressContainer.visibility = View.GONE
                    btnScan.isEnabled = true
                    updateStationList()
                }
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
                    amStations = stations
                    scanProgressContainer.visibility = View.GONE
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
