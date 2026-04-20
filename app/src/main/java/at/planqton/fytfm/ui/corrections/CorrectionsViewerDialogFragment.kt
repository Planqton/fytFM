package at.planqton.fytfm.ui.corrections

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import at.planqton.fytfm.data.rdslog.RtCorrectionDao
import at.planqton.fytfm.ui.CorrectionsAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * DialogFragment zur Anzeige der RT-Korrekturen.
 * Zeigt alle gespeicherten Korrekturen mit Lösch-Funktion.
 */
class CorrectionsViewerDialogFragment : DialogFragment() {

    interface CorrectionsCallback {
        fun getRtCorrectionDao(): RtCorrectionDao?
    }

    private var callback: CorrectionsCallback? = null
    private var collectJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.TransparentDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_corrections_viewer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        callback = activity as? CorrectionsCallback
        if (callback == null) {
            dismiss()
            return
        }

        setupDialog(view)
    }

    private fun setupDialog(view: View) {
        val recyclerCorrections = view.findViewById<RecyclerView>(R.id.recyclerCorrections)
        val textEmptyCorrections = view.findViewById<TextView>(R.id.textEmptyCorrections)
        val btnClearAll = view.findViewById<TextView>(R.id.btnClearAllCorrections)

        recyclerCorrections.layoutManager = LinearLayoutManager(requireContext())

        val correctionsAdapter = CorrectionsAdapter { correction ->
            CoroutineScope(Dispatchers.IO).launch {
                callback?.getRtCorrectionDao()?.delete(correction)
            }
        }
        recyclerCorrections.adapter = correctionsAdapter

        // Observe corrections
        collectJob = CoroutineScope(Dispatchers.Main).launch {
            callback?.getRtCorrectionDao()?.getAllCorrections()?.collectLatest { corrections ->
                correctionsAdapter.submitList(corrections)
                if (corrections.isEmpty()) {
                    textEmptyCorrections.visibility = View.VISIBLE
                    recyclerCorrections.visibility = View.GONE
                } else {
                    textEmptyCorrections.visibility = View.GONE
                    recyclerCorrections.visibility = View.VISIBLE
                }
            }
        }

        // Clear all button
        btnClearAll.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.delete_all_title)
                .setMessage(R.string.delete_all_corrections_message)
                .setPositiveButton(R.string.delete) { _, _ ->
                    CoroutineScope(Dispatchers.IO).launch {
                        callback?.getRtCorrectionDao()?.deleteAll()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        collectJob?.cancel()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    companion object {
        const val TAG = "CorrectionsViewerDialog"

        fun newInstance(): CorrectionsViewerDialogFragment {
            return CorrectionsViewerDialogFragment()
        }
    }
}
