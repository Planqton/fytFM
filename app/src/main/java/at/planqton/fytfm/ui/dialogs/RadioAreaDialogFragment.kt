package at.planqton.fytfm.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import at.planqton.fytfm.R

/**
 * Radio-Area picker. Lists five regions (USA, Latin America, Europe, Russia,
 * Japan); a check mark marks the currently-active region and a chevron marks
 * regions that have downloadable logo templates. Posts back the clicked area
 * + whether it has templates; orchestration of the follow-up template dialog
 * lives in the hosting Activity.
 *
 * Result bundle keys:
 *   - "cancelled"    Boolean — dialog was dismissed without selection
 *   - "areaId"       Int     — 0..4 clicked area
 *   - "hasTemplates" Boolean — whether the clicked area has templates
 */
class RadioAreaDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "RadioAreaDialog"

        private const val ARG_CURRENT_AREA = "current_area"
        private const val ARG_AREAS_WITH_TEMPLATES = "areas_with_templates"
        private const val ARG_REQUEST_KEY = "request_key"

        fun newInstance(
            currentArea: Int,
            areasWithTemplates: Set<Int>,
            requestKey: String,
        ): RadioAreaDialogFragment = RadioAreaDialogFragment().apply {
            arguments = Bundle().apply {
                putInt(ARG_CURRENT_AREA, currentArea)
                putIntArray(ARG_AREAS_WITH_TEMPLATES, areasWithTemplates.toIntArray())
                putString(ARG_REQUEST_KEY, requestKey)
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val ctx = requireContext()
        val requestKey = args.getString(ARG_REQUEST_KEY, TAG)
        val currentArea = args.getInt(ARG_CURRENT_AREA, 2)
        val templatesAreas = args.getIntArray(ARG_AREAS_WITH_TEMPLATES)?.toHashSet() ?: emptySet()

        val view = layoutInflater.inflate(R.layout.dialog_radio_area, null)
        val dialog = AlertDialog.Builder(ctx, R.style.TransparentDialog)
            .setView(view)
            .create()

        val areas = listOf(
            AreaRow(0, R.id.itemAreaUSA, R.id.checkUSA, R.id.chevronUSA),
            AreaRow(1, R.id.itemAreaLatinAmerica, R.id.checkLatinAmerica, R.id.chevronLatinAmerica),
            AreaRow(2, R.id.itemAreaEurope, R.id.checkEurope, R.id.chevronEurope),
            AreaRow(3, R.id.itemAreaRussia, R.id.checkRussia, R.id.chevronRussia),
            AreaRow(4, R.id.itemAreaJapan, R.id.checkJapan, R.id.chevronJapan),
        )

        for (row in areas) {
            view.findViewById<ImageView>(row.checkId).visibility =
                if (row.id == currentArea) View.VISIBLE else View.GONE
            view.findViewById<ImageView>(row.chevronId).visibility =
                if (row.id in templatesAreas) View.VISIBLE else View.GONE
            view.findViewById<View>(row.itemId).setOnClickListener {
                parentFragmentManager.setFragmentResult(requestKey, Bundle().apply {
                    putInt("areaId", row.id)
                    putBoolean("hasTemplates", row.id in templatesAreas)
                })
                dismiss()
            }
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return dialog
    }

    override fun onCancel(dialog: android.content.DialogInterface) {
        parentFragmentManager.setFragmentResult(
            requireArguments().getString(ARG_REQUEST_KEY, TAG),
            Bundle().apply { putBoolean("cancelled", true) },
        )
        super.onCancel(dialog)
    }

    private data class AreaRow(val id: Int, val itemId: Int, val checkId: Int, val chevronId: Int)
}
