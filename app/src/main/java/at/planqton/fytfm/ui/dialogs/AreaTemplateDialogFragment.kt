package at.planqton.fytfm.ui.dialogs

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import at.planqton.fytfm.R

/**
 * Second step of the radio-area flow: pick a logo template for the chosen
 * area. The Fragment is deliberately simple — it only reports WHICH item the
 * user picked; the activity decides what to do (activate directly vs. kick
 * off a download) because that decision depends on model-layer state the
 * Fragment shouldn't know about.
 *
 * Title is passed as a plain String because it's composed at runtime
 * ("Europe - Template", etc.) from [getRadioAreaName], which is not a
 * single string resource.
 *
 * Result bundle keys:
 *   - "cancelled"      Boolean — dialog dismissed (negative button / outside tap)
 *   - "selectedIndex"  Int     — 0 = "no template", 1..N = index into original template list + 1
 */
class AreaTemplateDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "AreaTemplateDialog"

        private const val ARG_TITLE = "title"
        private const val ARG_OPTIONS = "options"
        private const val ARG_SELECTED_INDEX = "selected_index"
        private const val ARG_REQUEST_KEY = "request_key"

        fun newInstance(
            title: String,
            options: Array<String>,
            selectedIndex: Int,
            requestKey: String,
        ): AreaTemplateDialogFragment = AreaTemplateDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_TITLE, title)
                putStringArray(ARG_OPTIONS, options)
                putInt(ARG_SELECTED_INDEX, selectedIndex)
                putString(ARG_REQUEST_KEY, requestKey)
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val requestKey = args.getString(ARG_REQUEST_KEY, TAG)
        val options = args.getStringArray(ARG_OPTIONS) ?: emptyArray()
        val selectedIndex = args.getInt(ARG_SELECTED_INDEX, 0)

        return AlertDialog.Builder(requireContext())
            .setTitle(args.getString(ARG_TITLE))
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                parentFragmentManager.setFragmentResult(requestKey, Bundle().apply {
                    putInt("selectedIndex", which)
                })
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                parentFragmentManager.setFragmentResult(requestKey, Bundle().apply {
                    putBoolean("cancelled", true)
                })
            }
            .create()
    }

    override fun onCancel(dialog: DialogInterface) {
        parentFragmentManager.setFragmentResult(
            requireArguments().getString(ARG_REQUEST_KEY, TAG),
            Bundle().apply { putBoolean("cancelled", true) },
        )
        super.onCancel(dialog)
    }
}
