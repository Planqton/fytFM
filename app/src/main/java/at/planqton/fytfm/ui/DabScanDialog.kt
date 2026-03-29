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
import at.planqton.fytfm.DabManager
import at.planqton.fytfm.R
import at.planqton.fytfm.data.RadioStation
import org.omri.radioservice.RadioService
import org.omri.radioservice.RadioServiceDab

class DabScanDialog(
    context: Context,
    private val dabManager: DabManager,
    private val onStationSelected: (RadioService) -> Unit
) : Dialog(context) {

    private lateinit var progressContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnScan: Button
    private lateinit var btnClose: Button

    private val foundStations = mutableListOf<RadioService>()
    private val dabAdapter = DabStationAdapter { station ->
        // Find matching DAB service by name (first part before \n)
        val name = station.name?.split("\n")?.getOrNull(0)?.trim()
        val index = foundStations.indexOfFirst {
            it.serviceLabel?.trim() == name
        }
        if (index >= 0) {
            onStationSelected(foundStations[index])
            dismiss()
        }
    }

    private val scanCallback = object : DabManager.DabCallback {
        override fun onTunerReady() {}
        override fun onTunerError() {}
        override fun onStationsLoaded(stations: List<RadioService>) {}

        override fun onScanStarted() {
            (context as? android.app.Activity)?.runOnUiThread {
                progressContainer.visibility = View.VISIBLE
                progressBar.progress = 0
                tvStatus.text = "Suche DAB+ Sender..."
                btnScan.text = "Abbrechen"
                tvEmpty.visibility = View.GONE
            }
        }

        override fun onScanProgress(percent: Int) {
            (context as? android.app.Activity)?.runOnUiThread {
                progressBar.progress = percent
                tvStatus.text = "Suche... $percent% (${foundStations.size} Sender)"
            }
        }

        override fun onScanServiceFound(service: RadioService) {
            (context as? android.app.Activity)?.runOnUiThread {
                foundStations.add(service)
                updateList()
            }
        }

        override fun onScanFinished(count: Int) {
            (context as? android.app.Activity)?.runOnUiThread {
                progressContainer.visibility = View.GONE
                btnScan.text = "Neu scannen"
                tvStatus.text = "${foundStations.size} Sender gefunden"
                if (foundStations.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = "Keine DAB+ Sender gefunden."
                }
            }
        }

        override fun onServiceStarted(service: RadioService) {}
        override fun onServiceStopped() {}
        override fun onDynamicLabel(text: String) {}
        override fun onDlPlus(artist: String?, title: String?) {}
        override fun onSlideshow(bitmap: android.graphics.Bitmap) {}
        override fun onLogo(bitmap: android.graphics.Bitmap) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_dab_scan)

        progressContainer = findViewById(R.id.dabScanProgressContainer)
        progressBar = findViewById(R.id.dabScanProgress)
        tvStatus = findViewById(R.id.tvDabScanStatus)
        recycler = findViewById(R.id.dabStationRecycler)
        tvEmpty = findViewById(R.id.tvDabEmptyState)
        btnScan = findViewById(R.id.btnDabScan)
        btnClose = findViewById(R.id.btnDabClose)

        recycler.layoutManager = LinearLayoutManager(context)
        recycler.adapter = dabAdapter

        // Load existing stations
        val existingServices = dabManager.let {
            try {
                val tuners = org.omri.radio.Radio.getInstance()
                    .getAvailableTuners(org.omri.tuner.TunerType.TUNER_TYPE_DAB)
                if (tuners != null && tuners.isNotEmpty()) {
                    tuners[0].radioServices?.filter { s ->
                        s is RadioServiceDab && s.isProgrammeService()
                    } ?: emptyList()
                } else emptyList()
            } catch (_: Exception) { emptyList() }
        }
        foundStations.addAll(existingServices)
        updateList()

        btnScan.setOnClickListener {
            if (dabManager.isScanning) {
                dabManager.stopScan()
                progressContainer.visibility = View.GONE
                btnScan.text = "Scan starten"
            } else {
                foundStations.clear()
                updateList()
                dabManager.callback = scanCallback
                dabManager.startScan()
            }
        }

        btnClose.setOnClickListener {
            if (dabManager.isScanning) {
                dabManager.stopScan()
            }
            dismiss()
        }
    }

    private fun updateList() {
        val radioStations = foundStations.map { service ->
            val label = service.serviceLabel?.trim() ?: "???"
            val ensemble = if (service is RadioServiceDab) {
                service.ensembleLabel?.trim() ?: ""
            } else ""
            RadioStation(
                frequency = 0f,
                name = "$label\n$ensemble",
                rssi = 0,
                isAM = false,
                isFavorite = false
            )
        }
        dabAdapter.setStations(radioStations)
        tvEmpty.visibility = if (radioStations.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (radioStations.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun dismiss() {
        // Restore main callback
        dabManager.callback = (context as? at.planqton.fytfm.MainActivity)?.let {
            // The main callback is set in MainActivity, it will be restored
            null
        }
        super.dismiss()
    }
}
