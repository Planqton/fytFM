package at.planqton.fytfm.ui.edit

import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import at.planqton.fytfm.R

/**
 * Edit dialog for a DAB station: name only; ensemble label shown as info.
 *
 * Result bundle keys:
 *   - "cancelled"  Boolean — true when user dismissed without saving
 *   - "name"       String  — trimmed station name
 */
class EditDabStationDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "EditDabStationDialog"

        private const val ARG_ENSEMBLE_LABEL = "ensemble_label"
        private const val ARG_CURRENT_NAME = "current_name"
        private const val ARG_REQUEST_KEY = "request_key"

        fun newInstance(
            ensembleLabel: String?,
            currentName: String,
            requestKey: String,
        ): EditDabStationDialogFragment = EditDabStationDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_ENSEMBLE_LABEL, ensembleLabel)
                putString(ARG_CURRENT_NAME, currentName)
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

        val ensembleLabel = args.getString(ARG_ENSEMBLE_LABEL)
        val infoText = TextView(ctx).apply {
            text = getString(
                R.string.ensemble_format,
                ensembleLabel?.takeIf { it.isNotBlank() } ?: getString(R.string.unknown),
            )
            setTextColor(resources.getColor(android.R.color.darker_gray, ctx.theme))
            setPadding(0, 0, 0, 16)
        }
        container.addView(infoText)

        val input = EditText(ctx).apply {
            setText(args.getString(ARG_CURRENT_NAME, ""))
            hint = getString(R.string.station_name_hint)
        }
        container.addView(input)

        return AlertDialog.Builder(ctx)
            .setTitle(R.string.dab_edit_station)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                parentFragmentManager.setFragmentResult(requestKey, Bundle().apply {
                    putString("name", input.text.toString())
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
