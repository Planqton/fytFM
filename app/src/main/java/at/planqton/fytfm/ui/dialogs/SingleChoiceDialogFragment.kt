package at.planqton.fytfm.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import at.planqton.fytfm.R

/**
 * Generischer Single-Choice-Dialog als DialogFragment.
 * Ersetzt inline AlertDialog.Builder für Auswahllisten.
 */
class SingleChoiceDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "SingleChoiceDialog"

        private const val ARG_TITLE = "title"
        private const val ARG_OPTIONS = "options"
        private const val ARG_VALUES = "values"
        private const val ARG_SELECTED_INDEX = "selected_index"
        private const val ARG_REQUEST_KEY = "request_key"

        /**
         * Erstellt einen Dialog mit String-Array Optionen und Int-Array Werten.
         */
        fun newInstance(
            title: Int,
            options: Array<String>,
            values: IntArray,
            selectedIndex: Int,
            requestKey: String
        ): SingleChoiceDialogFragment {
            return SingleChoiceDialogFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TITLE, title)
                    putStringArray(ARG_OPTIONS, options)
                    putIntArray(ARG_VALUES, values)
                    putInt(ARG_SELECTED_INDEX, selectedIndex)
                    putString(ARG_REQUEST_KEY, requestKey)
                }
            }
        }

        /**
         * Erstellt einen Dialog mit String-Array Optionen (Index als Wert).
         */
        fun newInstance(
            title: Int,
            options: Array<String>,
            selectedIndex: Int,
            requestKey: String
        ): SingleChoiceDialogFragment {
            return SingleChoiceDialogFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TITLE, title)
                    putStringArray(ARG_OPTIONS, options)
                    putInt(ARG_SELECTED_INDEX, selectedIndex)
                    putString(ARG_REQUEST_KEY, requestKey)
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val requestKey = args.getString(ARG_REQUEST_KEY, "selection")
        val options = args.getStringArray(ARG_OPTIONS) ?: emptyArray()
        val values = args.getIntArray(ARG_VALUES)
        val selectedIndex = args.getInt(ARG_SELECTED_INDEX, 0)

        return AlertDialog.Builder(requireContext())
            .setTitle(args.getInt(ARG_TITLE))
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                val selectedValue = values?.getOrNull(which) ?: which
                parentFragmentManager.setFragmentResult(requestKey, Bundle().apply {
                    putInt("selected_index", which)
                    putInt("selected_value", selectedValue)
                })
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }
}
