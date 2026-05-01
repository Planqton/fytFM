package at.planqton.fytfm.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import at.planqton.fytfm.R

/**
 * Dialog mit Texteingabe.
 * Für Beschreibungen bei Bug Reports etc.
 */
class InputDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "InputDialog"

        private const val ARG_TITLE = "title"
        private const val ARG_MESSAGE = "message"
        private const val ARG_HINT = "hint"
        private const val ARG_REQUEST_KEY = "request_key"

        fun newInstance(
            title: Int,
            message: Int,
            hint: Int,
            requestKey: String
        ): InputDialogFragment {
            return InputDialogFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TITLE, title)
                    putInt(ARG_MESSAGE, message)
                    putInt(ARG_HINT, hint)
                    putString(ARG_REQUEST_KEY, requestKey)
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val requestKey = args.getString(ARG_REQUEST_KEY, "input")

        val editText = EditText(requireContext()).apply {
            hint = getString(args.getInt(ARG_HINT))
            setPadding(48, 24, 48, 24)
            minLines = 2
        }

        return AlertDialog.Builder(requireContext())
            .setTitle(args.getInt(ARG_TITLE))
            .setMessage(args.getInt(ARG_MESSAGE))
            .setView(editText)
            .setPositiveButton(R.string.next) { _, _ ->
                parentFragmentManager.setFragmentResult(requestKey, Bundle().apply {
                    putString("input", editText.text.toString())
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
