package at.planqton.fytfm.ui.pi

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import at.planqton.fytfm.data.pi.PiNameTable

/**
 * Dialog der die statische `PiNameTable` als scrollbare Liste mit
 * Volltext-Suche anzeigt. Reine Anzeige — wird nirgendwo zum Auto-Naming
 * herangezogen (Stand jetzt).
 */
class PiListDialogFragment : DialogFragment() {

    private lateinit var adapter: PiListAdapter
    private lateinit var tvCount: TextView

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_pi_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rv = view.findViewById<RecyclerView>(R.id.rvPiList)
        val search = view.findViewById<EditText>(R.id.etPiSearch)
        val close = view.findViewById<Button>(R.id.btnPiListClose)
        tvCount = view.findViewById(R.id.tvPiListCount)

        adapter = PiListAdapter()
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        applyFilter("")

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString() ?: "")
            }
        })

        close.setOnClickListener { dismiss() }
    }

    private fun applyFilter(query: String) {
        val filtered = PiNameTable.search(query)
        adapter.setEntries(filtered)
        tvCount.text = getString(R.string.pi_list_count_format, filtered.size)
    }

    override fun onStart() {
        super.onStart()
        // Großzügige Dialog-Größe (etwa 80 % der Screen-Breite/Höhe).
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85f).toInt(),
            (resources.displayMetrics.heightPixels * 0.85f).toInt(),
        )
    }

    companion object {
        const val TAG = "PiListDialogFragment"
    }
}
