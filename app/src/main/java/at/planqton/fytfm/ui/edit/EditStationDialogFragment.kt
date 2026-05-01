package at.planqton.fytfm.ui.edit

import android.app.Dialog
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import at.planqton.fytfm.R

/**
 * Edit dialog for an FM/AM station: name, RDS-sync toggle, per-station Deezer toggle.
 * Form values are posted back via FragmentResult; the Activity does the actual
 * persistence + any side effects (MainActivity keeps the current behaviour).
 *
 * Result bundle keys:
 *   - "cancelled"      Boolean — true when user dismissed without saving
 *   - "name"           String  — trimmed station name
 *   - "syncName"       Boolean — user's sync-with-RDS-PS choice
 *   - "deezerEnabled"  Boolean — user's per-frequency Deezer choice
 */
class EditStationDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "EditStationDialog"

        private const val ARG_FREQUENCY_DISPLAY = "frequency_display"
        private const val ARG_CURRENT_NAME = "current_name"
        private const val ARG_SYNC_NAME = "sync_name"
        private const val ARG_DEEZER_ENABLED = "deezer_enabled"
        private const val ARG_REQUEST_KEY = "request_key"

        fun newInstance(
            frequencyDisplay: String,
            currentName: String,
            syncName: Boolean,
            deezerEnabled: Boolean,
            requestKey: String,
        ): EditStationDialogFragment = EditStationDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_FREQUENCY_DISPLAY, frequencyDisplay)
                putString(ARG_CURRENT_NAME, currentName)
                putBoolean(ARG_SYNC_NAME, syncName)
                putBoolean(ARG_DEEZER_ENABLED, deezerEnabled)
                putString(ARG_REQUEST_KEY, requestKey)
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val ctx = requireContext()
        val requestKey = args.getString(ARG_REQUEST_KEY, TAG)

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val input = EditText(ctx).apply {
            setText(args.getString(ARG_CURRENT_NAME, ""))
            hint = getString(R.string.station_name_hint)
        }
        container.addView(input)

        val syncNameCheckbox = CheckBox(ctx).apply {
            text = getString(R.string.sync_with_rds_ps)
            isChecked = args.getBoolean(ARG_SYNC_NAME, true)
            setPadding(0, 24, 0, 0)
        }
        container.addView(syncNameCheckbox)

        val deezerSwitch = Switch(ctx).apply {
            text = getString(R.string.deezer_local_search)
            isChecked = args.getBoolean(ARG_DEEZER_ENABLED, true)
            setPadding(0, 16, 0, 0)
        }
        container.addView(deezerSwitch)

        return AlertDialog.Builder(ctx)
            .setTitle(R.string.edit_station)
            .setMessage(args.getString(ARG_FREQUENCY_DISPLAY, ""))
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                parentFragmentManager.setFragmentResult(requestKey, Bundle().apply {
                    putString("name", input.text.toString())
                    putBoolean("syncName", syncNameCheckbox.isChecked)
                    putBoolean("deezerEnabled", deezerSwitch.isChecked)
                })
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                parentFragmentManager.setFragmentResult(requestKey, Bundle().apply {
                    putBoolean("cancelled", true)
                })
            }
            .create()
    }
}
