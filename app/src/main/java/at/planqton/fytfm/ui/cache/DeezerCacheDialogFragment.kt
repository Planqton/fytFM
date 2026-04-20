package at.planqton.fytfm.ui.cache

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import at.planqton.fytfm.deezer.CachedTrackAdapter
import at.planqton.fytfm.deezer.DeezerCache

/**
 * DialogFragment zur Anzeige des Deezer-Cache.
 * Zeigt alle gecachten Tracks mit Suchfunktion.
 */
class DeezerCacheDialogFragment : DialogFragment() {

    interface DeezerCacheCallback {
        fun getDeezerCache(): DeezerCache?
    }

    private var callback: DeezerCacheCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.TransparentDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_deezer_cache, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        callback = activity as? DeezerCacheCallback
        if (callback == null) {
            dismiss()
            return
        }

        setupDialog(view)
    }

    private fun setupDialog(view: View) {
        val tvCount = view.findViewById<TextView>(R.id.tvCacheCount)
        val etSearch = view.findViewById<EditText>(R.id.etCacheSearch)
        val rvTracks = view.findViewById<RecyclerView>(R.id.rvCacheTracks)
        val tvEmpty = view.findViewById<TextView>(R.id.tvCacheEmpty)
        val btnClose = view.findViewById<TextView>(R.id.btnCloseCache)

        val adapter = CachedTrackAdapter { track ->
            track.deezerUrl?.let { url ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, getString(R.string.cannot_open_deezer), Toast.LENGTH_SHORT).show()
                }
            }
        }

        rvTracks.layoutManager = LinearLayoutManager(requireContext())
        rvTracks.adapter = adapter

        val tracks = callback?.getDeezerCache()?.getAllCachedTracks() ?: emptyList()
        tvCount.text = getString(R.string.tracks_count, tracks.size)

        if (tracks.isEmpty()) {
            rvTracks.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
        } else {
            rvTracks.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
            adapter.setTracks(tracks)
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s?.toString() ?: "")
            }
        })

        btnClose.setOnClickListener {
            dismiss()
        }
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
        const val TAG = "DeezerCacheDialog"

        fun newInstance(): DeezerCacheDialogFragment {
            return DeezerCacheDialogFragment()
        }
    }
}
