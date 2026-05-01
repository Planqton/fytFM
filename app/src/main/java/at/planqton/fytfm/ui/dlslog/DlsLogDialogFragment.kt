package at.planqton.fytfm.ui.dlslog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import at.planqton.fytfm.R
import java.io.File

/**
 * DialogFragment für DLS (Dynamic Label Segment) Log-Anzeige.
 * Zeigt empfangene DAB+ DLS-Nachrichten an.
 */
class DlsLogDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "DlsLogDialogFragment"

        fun newInstance(): DlsLogDialogFragment {
            return DlsLogDialogFragment()
        }
    }

    interface DlsLogCallback {
        fun getDlsLogEntries(): MutableList<String>
        fun getLastLoggedDls(): String?
        fun setLastLoggedDls(dls: String?)
        fun saveDlsLogToFile()
        fun getDlsLogFile(): File
    }

    private var callback: DlsLogCallback? = null

    private var logContent: TextView? = null
    private var logCount: TextView? = null
    private var filePath: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.TransparentDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_dls_log, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        logContent = view.findViewById(R.id.dlsLogContent)
        logCount = view.findViewById(R.id.dlsLogCount)
        filePath = view.findViewById(R.id.dlsLogFilePath)
        val btnClear = view.findViewById<Button>(R.id.btnClearDlsLog)
        val btnClose = view.findViewById<Button>(R.id.btnCloseDlsLog)

        // Callback von Activity holen
        callback = activity as? DlsLogCallback

        updateLogDisplay()

        // Dateipfad anzeigen
        callback?.getDlsLogFile()?.let { file ->
            filePath?.text = getString(R.string.file_path_format, file.absolutePath)
        }

        // Clear Button
        btnClear?.setOnClickListener {
            callback?.getDlsLogEntries()?.clear()
            callback?.setLastLoggedDls(null)
            callback?.saveDlsLogToFile()
            updateLogDisplay()
        }

        // Close Button
        btnClose?.setOnClickListener {
            dismiss()
        }
    }

    private fun updateLogDisplay() {
        val entries = callback?.getDlsLogEntries() ?: emptyList()
        if (entries.isEmpty()) {
            logContent?.text = getString(R.string.no_dls_received)
            logCount?.text = getString(R.string.log_entries_count, 0)
        } else {
            logContent?.text = entries.reversed().joinToString("\n")
            logCount?.text = getString(R.string.log_entries_count, entries.size)
        }
    }

    override fun onStart() {
        super.onStart()
        // Dialog-Größe anpassen
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}
