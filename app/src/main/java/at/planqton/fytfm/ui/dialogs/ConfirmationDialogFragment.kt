package at.planqton.fytfm.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import at.planqton.fytfm.R

/**
 * Generischer Bestätigungs-Dialog als DialogFragment.
 * Ersetzt inline AlertDialog.Builder für einfache Ja/Nein Dialoge.
 */
class ConfirmationDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "ConfirmationDialog"

        private const val ARG_TITLE = "title"
        private const val ARG_MESSAGE = "message"
        private const val ARG_POSITIVE_TEXT = "positive"
        private const val ARG_NEGATIVE_TEXT = "negative"
        private const val ARG_REQUEST_KEY = "request_key"

        fun newInstance(
            title: Int,
            message: Int,
            positiveText: Int = R.string.yes,
            negativeText: Int = R.string.cancel,
            requestKey: String = "confirmation"
        ): ConfirmationDialogFragment {
            return ConfirmationDialogFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TITLE, title)
                    putInt(ARG_MESSAGE, message)
                    putInt(ARG_POSITIVE_TEXT, positiveText)
                    putInt(ARG_NEGATIVE_TEXT, negativeText)
                    putString(ARG_REQUEST_KEY, requestKey)
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val requestKey = args.getString(ARG_REQUEST_KEY, "confirmation")

        return AlertDialog.Builder(requireContext())
            .setTitle(args.getInt(ARG_TITLE))
            .setMessage(args.getInt(ARG_MESSAGE))
            .setPositiveButton(args.getInt(ARG_POSITIVE_TEXT)) { _, _ ->
                parentFragmentManager.setFragmentResult(requestKey, Bundle().apply {
                    putBoolean("confirmed", true)
                })
            }
            .setNegativeButton(args.getInt(ARG_NEGATIVE_TEXT), null)
            .create()
    }
}
