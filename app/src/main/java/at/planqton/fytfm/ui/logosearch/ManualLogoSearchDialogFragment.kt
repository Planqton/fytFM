package at.planqton.fytfm.ui.logosearch

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import at.planqton.fytfm.data.logo.DuckDuckGoImageSearch
import at.planqton.fytfm.ui.ImageSearchAdapter

/**
 * Dialog for manually searching a station logo via DuckDuckGo image search.
 * The search HTTP/JSON layer lives in [DuckDuckGoImageSearch]; this Fragment
 * owns the form, the results grid, and the pre-fill. Posts the picked
 * image URL back through FragmentResult so the Activity can run the
 * (download + save + update) flow outside the dialog's lifecycle.
 *
 * Result bundle keys:
 *   - "cancelled" Boolean — dialog dismissed without selection
 *   - "imageUrl"  String  — absolute URL of the chosen image
 */
class ManualLogoSearchDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "ManualLogoSearchDialog"

        private const val ARG_STATION_NAME = "station_name"
        private const val ARG_REQUEST_KEY = "request_key"

        fun newInstance(
            stationName: String,
            requestKey: String,
        ): ManualLogoSearchDialogFragment = ManualLogoSearchDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_STATION_NAME, stationName)
                putString(ARG_REQUEST_KEY, requestKey)
            }
        }
    }

    private val searcher = DuckDuckGoImageSearch()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val ctx = requireContext()
        val requestKey = args.getString(ARG_REQUEST_KEY, TAG)
        val stationName = args.getString(ARG_STATION_NAME, "")

        val dialogView = layoutInflater.inflate(R.layout.dialog_manual_logo_search, null)
        val etSearch = dialogView.findViewById<EditText>(R.id.etSearchQuery)
        val btnSearch = dialogView.findViewById<ImageButton>(R.id.btnSearch)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBar)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tvStatus)
        val rvImages = dialogView.findViewById<RecyclerView>(R.id.rvImages)

        etSearch.setText(if (stationName.isNotBlank()) "$stationName logo" else "")
        rvImages.layoutManager = GridLayoutManager(ctx, 3)

        val adapter = ImageSearchAdapter { imageResult ->
            parentFragmentManager.setFragmentResult(requestKey, Bundle().apply {
                putString("imageUrl", imageResult.url)
            })
            dismiss()
        }
        rvImages.adapter = adapter

        btnSearch.setOnClickListener {
            val query = etSearch.text.toString().trim()
            if (query.isBlank()) {
                Toast.makeText(ctx, R.string.enter_search_please, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            progressBar.visibility = View.VISIBLE
            tvStatus.visibility = View.GONE
            adapter.setImages(emptyList())

            searcher.search(query) { results ->
                mainHandler.post {
                    if (!isAdded) return@post
                    progressBar.visibility = View.GONE
                    if (results.isEmpty()) {
                        tvStatus.text = getString(R.string.no_images_found)
                        tvStatus.visibility = View.VISIBLE
                    } else {
                        tvStatus.visibility = View.GONE
                        adapter.setImages(results)
                    }
                }
            }
        }

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                btnSearch.performClick()
                true
            } else false
        }

        return AlertDialog.Builder(ctx)
            .setView(dialogView)
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
