package at.planqton.fytfm.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import at.planqton.fytfm.dab.DabScanListener
import at.planqton.fytfm.dab.DabStation
import at.planqton.fytfm.dab.DabTunerManager
import at.planqton.fytfm.data.PresetRepository

class DabScanDialog(
    private val context: Context,
    private val dabTunerManager: DabTunerManager,
    private val presetRepository: PresetRepository,
    private val onComplete: (List<DabStation>) -> Unit
) {
    private var dialog: AlertDialog? = null
    private var isScanning = false
    private val foundServices = mutableListOf<DabStation>()
    private var adapter: DabServiceAdapter? = null

    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_dab_scan, null)
        val tvStatus = view.findViewById<TextView>(R.id.tvScanStatus)
        val tvBlock = view.findViewById<TextView>(R.id.tvScanBlock)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBar)
        val tvFoundCount = view.findViewById<TextView>(R.id.tvFoundCount)
        val rvServices = view.findViewById<RecyclerView>(R.id.rvFoundServices)
        val switchOverwriteFavorites = view.findViewById<Switch>(R.id.switchOverwriteFavorites)
        val btnStartScan = view.findViewById<Button>(R.id.btnStartScan)
        val layoutOverwriteFavorites = view.findViewById<View>(R.id.layoutOverwriteFavorites)

        // Overwrite Favorites Switch initialisieren
        switchOverwriteFavorites.isChecked = presetRepository.isOverwriteFavorites()
        switchOverwriteFavorites.setOnCheckedChangeListener { _, isChecked ->
            presetRepository.setOverwriteFavorites(isChecked)
        }

        // Initial: Scan-Bereich ausblenden
        tvStatus.text = context.getString(R.string.dab_scan_press_start)
        progressBar.visibility = View.GONE
        tvBlock.visibility = View.GONE
        tvFoundCount.visibility = View.GONE
        rvServices.visibility = View.GONE

        adapter = DabServiceAdapter()
        rvServices.layoutManager = LinearLayoutManager(context)
        rvServices.adapter = adapter

        dialog = AlertDialog.Builder(context)
            .setTitle(R.string.dab_scan_dialog_title)
            .setView(view)
            .setNegativeButton(R.string.cancel) { _, _ ->
                if (isScanning) {
                    dabTunerManager.stopScan()
                    isScanning = false
                }
            }
            .setPositiveButton(R.string.accept, null)
            .setCancelable(false)
            .create()

        dialog?.show()

        // Positive button disabled bis Sender gefunden
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            if (isScanning) {
                dabTunerManager.stopScan()
                isScanning = false
            }
            onComplete(foundServices.toList())
            dialog?.dismiss()
        }

        // Start-Button Click Listener
        btnStartScan.setOnClickListener {
            // UI umschalten
            btnStartScan.visibility = View.GONE
            layoutOverwriteFavorites.visibility = View.GONE
            progressBar.visibility = View.VISIBLE
            tvBlock.visibility = View.VISIBLE
            tvFoundCount.visibility = View.VISIBLE
            rvServices.visibility = View.VISIBLE

            // Scan starten
            startScan(tvStatus, tvBlock, progressBar, tvFoundCount)
        }
    }

    private fun startScan(
        tvStatus: TextView,
        tvBlock: TextView,
        progressBar: ProgressBar,
        tvFoundCount: TextView
    ) {
        isScanning = true
        dabTunerManager.startScan(object : DabScanListener {
            override fun onScanStarted() {
                tvStatus.text = context.getString(R.string.dab_scan_running)
                progressBar.progress = 0
            }

            override fun onScanProgress(percent: Int, blockLabel: String) {
                progressBar.progress = percent
                tvBlock.text = blockLabel
                tvStatus.text = context.getString(R.string.dab_scanning_progress, percent)
            }

            override fun onServiceFound(service: DabStation) {
                foundServices.add(service)
                adapter?.notifyItemInserted(foundServices.size - 1)
                tvFoundCount.text = context.getString(R.string.stations_found_count, foundServices.size)
                dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
            }

            override fun onScanFinished(services: List<DabStation>) {
                isScanning = false
                progressBar.progress = 100

                if (foundServices.isEmpty()) {
                    tvStatus.text = context.getString(R.string.no_dab_stations_found)
                    tvBlock.text = context.getString(R.string.check_antenna_retry)
                    // Negative Button zu "Schließen" umbenennen
                    dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.text = context.getString(R.string.close)
                } else {
                    tvStatus.text = context.getString(R.string.dab_scan_completed)
                    tvBlock.text = context.getString(R.string.stations_found, foundServices.size)
                    // Auto-übernehmen
                    onComplete(foundServices.toList())
                    dialog?.dismiss()
                }
            }

            override fun onScanError(error: String) {
                isScanning = false
                tvStatus.text = context.getString(R.string.error_prefix, error)
                tvBlock.text = ""
            }
        })
    }

    private inner class DabServiceAdapter : RecyclerView.Adapter<DabServiceAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(android.R.id.text1)
            val tvEnsemble: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val service = foundServices[position]
            holder.tvName.text = service.serviceLabel
            holder.tvEnsemble.text = service.ensembleLabel
        }

        override fun getItemCount(): Int = foundServices.size
    }
}
