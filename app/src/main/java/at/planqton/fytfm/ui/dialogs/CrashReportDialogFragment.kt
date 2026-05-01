package at.planqton.fytfm.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import at.planqton.fytfm.R

/**
 * Dialog für Crash-Report Benachrichtigung.
 * Zeigt Optionen: Bug Report erstellen, Nur Crash-Log, Ignorieren.
 */
class CrashReportDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "CrashReportDialog"
        const val REQUEST_KEY = "crash_report_action"

        const val ACTION_BUG_REPORT = 0
        const val ACTION_CRASH_LOG = 1
        const val ACTION_IGNORE = 2

        fun newInstance(): CrashReportDialogFragment {
            return CrashReportDialogFragment()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.app_crashed_title)
            .setMessage(R.string.app_crashed_message)
            .setPositiveButton(R.string.bug_report_title) { _, _ ->
                sendResult(ACTION_BUG_REPORT)
            }
            .setNeutralButton(R.string.crash_only_log) { _, _ ->
                sendResult(ACTION_CRASH_LOG)
            }
            .setNegativeButton(R.string.ignore) { _, _ ->
                sendResult(ACTION_IGNORE)
            }
            .create()
    }

    private fun sendResult(action: Int) {
        parentFragmentManager.setFragmentResult(REQUEST_KEY, Bundle().apply {
            putInt("action", action)
        })
    }
}
