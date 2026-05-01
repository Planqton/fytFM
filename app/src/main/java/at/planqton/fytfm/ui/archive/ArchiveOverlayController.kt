package at.planqton.fytfm.ui.archive

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import at.planqton.fytfm.R
import at.planqton.fytfm.data.rdslog.RdsLogRepository
import at.planqton.fytfm.databinding.OverlayArchiveBinding
import at.planqton.fytfm.ui.RdsLogAdapter
import at.planqton.fytfm.ui.dialogs.ConfirmationDialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Owns the archive overlay UI: show/hide, search, frequency filter, data loading.
 * State (adapter, collection job, query/filter) lives here — the Activity only
 * toggles visibility. Clear-confirmation still flows through the Activity's
 * FragmentResultListener because only the Activity has a lifecycle owner for it.
 */
class ArchiveOverlayController(
    private val binding: OverlayArchiveBinding,
    private val rdsLogRepository: RdsLogRepository,
    private val fragmentManager: FragmentManager,
    private val coroutineScope: CoroutineScope,
) {
    private val context get() = binding.root.context
    private val adapter: RdsLogAdapter by lazy {
        RdsLogAdapter().also {
            binding.archiveRecycler.layoutManager = LinearLayoutManager(context)
            binding.archiveRecycler.adapter = it
        }
    }

    private var dataJob: Job? = null
    private var searchQuery: String = ""
    private var filterFrequency: Float? = null
    private var listenersWired = false

    fun show() {
        binding.root.visibility = View.VISIBLE
        // Force adapter creation on first show.
        adapter
        if (!listenersWired) {
            wireListeners()
            listenersWired = true
        }
        loadData()
    }

    fun hide() {
        binding.root.visibility = View.GONE
        dataJob?.cancel()
        dataJob = null
    }

    private fun wireListeners() {
        binding.btnArchiveBack.setOnClickListener { hide() }

        val searchContainer = binding.archiveSearchContainer
        val etSearch = binding.etArchiveSearch

        binding.btnArchiveSearch.setOnClickListener {
            if (searchContainer.visibility == View.VISIBLE) {
                searchContainer.visibility = View.GONE
                searchQuery = ""
                loadData()
            } else {
                searchContainer.visibility = View.VISIBLE
                etSearch.requestFocus()
            }
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString() ?: ""
                loadData()
            }
        })

        binding.btnArchiveClear.setOnClickListener {
            ConfirmationDialogFragment.newInstance(
                title = R.string.clear_archive_title,
                message = R.string.clear_archive_message,
                positiveText = R.string.delete,
                requestKey = "clear_archive",
            ).show(fragmentManager, ConfirmationDialogFragment.TAG)
        }

        binding.chipAllFrequencies.setOnClickListener {
            filterFrequency = null
            updateFilterChipSelection()
            loadData()
        }
    }

    private fun loadData() {
        dataJob?.cancel()
        val flow = when {
            searchQuery.isNotBlank() -> rdsLogRepository.searchRt(searchQuery)
            filterFrequency != null -> rdsLogRepository.getEntriesForFrequency(filterFrequency!!)
            else -> rdsLogRepository.getAllEntries()
        }
        dataJob = coroutineScope.launch {
            flow.collectLatest { entries ->
                adapter.setEntries(entries)
                binding.tvArchiveStats.text =
                    context.getString(R.string.entries_format, entries.size)
                val empty = entries.isEmpty()
                binding.archiveRecycler.visibility = if (empty) View.GONE else View.VISIBLE
                binding.archiveEmptyState.visibility = if (empty) View.VISIBLE else View.GONE
            }
        }
    }

    private fun updateFilterChipSelection() {
        val chipAll = binding.chipAllFrequencies
        if (filterFrequency == null) {
            chipAll.setBackgroundResource(R.drawable.chip_selected)
            chipAll.setTextColor(context.resources.getColor(android.R.color.white, null))
        } else {
            chipAll.setBackgroundResource(R.drawable.chip_unselected)
            chipAll.setTextColor(context.resources.getColor(android.R.color.black, null))
        }
    }
}
