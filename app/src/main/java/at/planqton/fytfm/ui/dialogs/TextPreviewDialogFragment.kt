package at.planqton.fytfm.ui.dialogs

import android.app.Dialog
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import at.planqton.fytfm.R

/**
 * Dialog zur Textvorschau (z.B. Bug Reports, Crash Logs).
 */
class TextPreviewDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "TextPreviewDialog"

        private const val ARG_TITLE = "title"
        private const val ARG_CONTENT = "content"
        private const val ARG_REQUEST_KEY = "request_key"

        fun newInstance(
            title: Int,
            content: String,
            requestKey: String
        ): TextPreviewDialogFragment {
            return TextPreviewDialogFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TITLE, title)
                    putString(ARG_CONTENT, content)
                    putString(ARG_REQUEST_KEY, requestKey)
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val requestKey = args.getString(ARG_REQUEST_KEY, "preview")
        val content = args.getString(ARG_CONTENT, "")

        val scrollView = ScrollView(requireContext())
        val textView = TextView(requireContext()).apply {
            text = content
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)

        return AlertDialog.Builder(requireContext())
            .setTitle(args.getInt(ARG_TITLE))
            .setView(scrollView)
            .setPositiveButton(R.string.save) { _, _ ->
                parentFragmentManager.setFragmentResult(requestKey, Bundle().apply {
                    putBoolean("save", true)
                    putString("content", content)
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
