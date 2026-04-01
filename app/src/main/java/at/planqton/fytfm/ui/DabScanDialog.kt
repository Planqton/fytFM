package at.planqton.fytfm.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import at.planqton.fytfm.dab.DabScanListener
import at.planqton.fytfm.dab.DabStation
import at.planqton.fytfm.dab.DabTunerManager

class DabScanDialog(
    private val context: Context,
    private val dabTunerManager: DabTunerManager,
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

        adapter = DabServiceAdapter()
        rvServices.layoutManager = LinearLayoutManager(context)
        rvServices.adapter = adapter

        dialog = AlertDialog.Builder(context)
            .setTitle("DAB+ Sendersuche")
            .setView(view)
            .setNegativeButton("Abbrechen") { _, _ ->
                if (isScanning) {
                    dabTunerManager.stopScan()
                    isScanning = false
                }
            }
            .setPositiveButton("Stopp & Übernehmen", null) // wird unten gesetzt
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

        // Scan starten
        isScanning = true
        dabTunerManager.startScan(object : DabScanListener {
            override fun onScanStarted() {
                tvStatus.text = "DAB+ Sendersuche läuft..."
                progressBar.progress = 0
            }

            override fun onScanProgress(percent: Int, blockLabel: String) {
                progressBar.progress = percent
                tvBlock.text = blockLabel
                tvStatus.text = "Scanne... $percent%"
            }

            override fun onServiceFound(service: DabStation) {
                foundServices.add(service)
                adapter?.notifyItemInserted(foundServices.size - 1)
                tvFoundCount.text = "Gefundene Sender: ${foundServices.size}"
                dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
            }

            override fun onScanFinished(services: List<DabStation>) {
                isScanning = false
                progressBar.progress = 100

                if (foundServices.isEmpty()) {
                    tvStatus.text = "Keine DAB+ Sender gefunden"
                    tvBlock.text = "Bitte prüfen Sie die Antenne und versuchen Sie es erneut."
                    // Negative Button zu "Schließen" umbenennen
                    dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.text = "Schließen"
                } else {
                    tvStatus.text = "Sendersuche abgeschlossen"
                    tvBlock.text = "${foundServices.size} Sender gefunden"
                    // Auto-übernehmen
                    onComplete(foundServices.toList())
                    dialog?.dismiss()
                }
            }

            override fun onScanError(error: String) {
                isScanning = false
                tvStatus.text = "Fehler: $error"
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
