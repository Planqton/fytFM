package at.planqton.fytfm

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class BugReportActivity : AppCompatActivity() {

    private lateinit var bugReportHelper: BugReportHelper
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: BugReportAdapter

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bug_reports)

        bugReportHelper = BugReportHelper(this)

        recyclerView = findViewById(R.id.bugReportsRecyclerView)
        emptyView = findViewById(R.id.emptyView)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = BugReportAdapter(
            onItemClick = { file -> showReportContent(file) },
            onItemLongClick = { file -> showDeleteDialog(file) }
        )
        recyclerView.adapter = adapter

        findViewById<View>(R.id.btnBack)?.setOnClickListener {
            finish()
        }

        loadReports()
    }

    private fun loadReports() {
        val reports = bugReportHelper.getBugReports()
        adapter.setReports(reports)

        if (reports.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
        }
    }

    private fun showReportContent(file: File) {
        val content = bugReportHelper.readBugReport(file)
        if (content != null) {
            val intent = android.content.Intent(this, BugReportDetailActivity::class.java)
            intent.putExtra("file_path", file.absolutePath)
            startActivity(intent)
        } else {
            Toast.makeText(this, "Failed to read report", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeleteDialog(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete Report")
            .setMessage("Delete this bug report?")
            .setPositiveButton("Delete") { _, _ ->
                if (bugReportHelper.deleteBugReport(file)) {
                    Toast.makeText(this, "Report deleted", Toast.LENGTH_SHORT).show()
                    loadReports()
                } else {
                    Toast.makeText(this, "Failed to delete report", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    class BugReportAdapter(
        private val onItemClick: (File) -> Unit,
        private val onItemLongClick: (File) -> Unit
    ) : RecyclerView.Adapter<BugReportAdapter.ViewHolder>() {

        private var reports: List<File> = emptyList()

        fun setReports(newReports: List<File>) {
            reports = newReports
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_bug_report, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = reports[position]
            holder.bind(file, onItemClick, onItemLongClick)
        }

        override fun getItemCount() = reports.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val titleText: TextView = itemView.findViewById(R.id.reportTitle)
            private val dateText: TextView = itemView.findViewById(R.id.reportDate)
            private val sizeText: TextView = itemView.findViewById(R.id.reportSize)

            fun bind(file: File, onClick: (File) -> Unit, onLongClick: (File) -> Unit) {
                // Parse date from filename: bugreport_2024-01-15_14-30-00.txt
                val filename = file.name
                val dateStr = filename.removePrefix("bugreport_").removeSuffix(".txt")
                val displayDate = try {
                    val parsed = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).parse(dateStr)
                    SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(parsed!!)
                } catch (e: Exception) {
                    dateStr
                }

                titleText.text = "Bug Report"
                dateText.text = displayDate
                sizeText.text = formatFileSize(file.length())

                itemView.setOnClickListener { onClick(file) }
                itemView.setOnLongClickListener {
                    onLongClick(file)
                    true
                }
            }

            private fun formatFileSize(bytes: Long): String {
                return when {
                    bytes < 1024 -> "$bytes B"
                    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                    else -> "${bytes / (1024 * 1024)} MB"
                }
            }
        }
    }
}
