package at.planqton.fytfm.ui.bugreport

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import at.planqton.fytfm.R

/**
 * DialogFragment für Bug Reports.
 * Unterstützt verschiedene Report-Typen: Allgemein, Parser, Deezer.
 */
class BugReportDialogFragment : DialogFragment() {

    enum class ReportType {
        GENERAL,
        PARSER,
        DEEZER
    }

    interface BugReportCallback {
        fun onBugReportTypeSelected(type: ReportType)
        fun collectParserReportData(userDescription: String?): String
        fun collectDeezerReportData(userDescription: String?): String
        fun collectGeneralReportData(userDescription: String?): String
        fun onBugReportGenerated(content: String, isCrashReport: Boolean)
    }

    private var callback: BugReportCallback? = null
    private var reportType: ReportType = ReportType.GENERAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.TransparentDialog)
        reportType = arguments?.getString(ARG_TYPE)?.let { ReportType.valueOf(it) } ?: ReportType.GENERAL
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_bug_report, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        callback = activity as? BugReportCallback
        if (callback == null) {
            dismiss()
            return
        }

        when (reportType) {
            ReportType.GENERAL -> showTypeSelectionDialog()
            ReportType.PARSER -> showParserReportDialog()
            ReportType.DEEZER -> showDeezerReportDialog()
        }
    }

    private fun showTypeSelectionDialog() {
        val options = arrayOf(
            getString(R.string.general_bug_report),
            getString(R.string.parser_bug_report),
            getString(R.string.deezer_bug_report)
        )

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.bug_report_type_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showGeneralReportInputDialog()
                    1 -> showParserReportDialog()
                    2 -> showDeezerReportDialog()
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> dismiss() }
            .setOnCancelListener { dismiss() }
            .show()
    }

    private fun showGeneralReportInputDialog() {
        val cb = callback ?: return
        Toast.makeText(context, getString(R.string.collecting_logs), Toast.LENGTH_SHORT).show()

        val editText = createDescriptionEditText(getString(R.string.deezer_bug_hint))

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.create_bug_report_title)
            .setMessage(R.string.bug_report_message)
            .setView(editText)
            .setPositiveButton(R.string.next) { _, _ ->
                val userDescription = editText.text.toString()
                val reportContent = cb.collectGeneralReportData(userDescription.ifEmpty { null })
                showReportPreview(reportContent)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> dismiss() }
            .setOnCancelListener { dismiss() }
            .show()
    }

    private fun showParserReportDialog() {
        val cb = callback ?: return
        Toast.makeText(context, getString(R.string.collecting_parser_logs), Toast.LENGTH_SHORT).show()

        val editText = createDescriptionEditText(getString(R.string.parser_bug_hint))

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.parser_bug_report_title)
            .setMessage(R.string.parser_bug_message)
            .setView(editText)
            .setPositiveButton(R.string.next) { _, _ ->
                val userDescription = editText.text.toString()
                val reportContent = cb.collectParserReportData(userDescription.ifEmpty { null })
                showReportPreview(reportContent)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> dismiss() }
            .setOnCancelListener { dismiss() }
            .show()
    }

    private fun showDeezerReportDialog() {
        val cb = callback ?: return
        Toast.makeText(context, getString(R.string.collecting_deezer_data), Toast.LENGTH_SHORT).show()

        val editText = createDescriptionEditText(getString(R.string.deezer_bug_hint))

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.deezer_bug_report_title)
            .setMessage(R.string.deezer_bug_message)
            .setView(editText)
            .setPositiveButton(R.string.next) { _, _ ->
                val userDescription = editText.text.toString()
                val reportContent = cb.collectDeezerReportData(userDescription.ifEmpty { null })
                showReportPreview(reportContent)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> dismiss() }
            .setOnCancelListener { dismiss() }
            .show()
    }

    private fun createDescriptionEditText(hint: String): EditText {
        return EditText(requireContext()).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            setPadding(32, 24, 32, 24)
        }
    }

    private fun showReportPreview(content: String) {
        val scrollView = ScrollView(requireContext())
        val textView = TextView(requireContext()).apply {
            text = content
            textSize = 9f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.report_preview_title)
            .setView(scrollView)
            .setPositiveButton(R.string.save) { _, _ ->
                callback?.onBugReportGenerated(content, false)
                dismiss()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> dismiss() }
            .setOnCancelListener { dismiss() }
            .show()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    companion object {
        const val TAG = "BugReportDialog"
        private const val ARG_TYPE = "report_type"

        fun newInstance(type: ReportType = ReportType.GENERAL): BugReportDialogFragment {
            return BugReportDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TYPE, type.name)
                }
            }
        }
    }
}
