package at.planqton.fytfm.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import at.planqton.fytfm.R

/**
 * Dialog zum Import von Sendern aus der Original-App.
 */
class ImportStationsDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "ImportStationsDialog"
        const val REQUEST_KEY = "import_stations"

        fun newInstance(): ImportStationsDialogFragment {
            return ImportStationsDialogFragment()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.import_stations_title)
            .setMessage(R.string.import_stations_message)
            .setPositiveButton(R.string.yes) { _, _ ->
                parentFragmentManager.setFragmentResult(REQUEST_KEY, Bundle().apply {
                    putBoolean("import", true)
                })
            }
            .setNegativeButton(R.string.no) { _, _ ->
                parentFragmentManager.setFragmentResult(REQUEST_KEY, Bundle().apply {
                    putBoolean("import", false)
                })
            }
            .create()
    }
}
