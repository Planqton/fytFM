package at.planqton.fytfm

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class BugReportDetailActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bug_report_detail)

        val filePath = intent.getStringExtra("file_path")
        if (filePath == null) {
            Toast.makeText(this, "No file specified", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val contentView = findViewById<TextView>(R.id.reportContent)
        val titleView = findViewById<TextView>(R.id.reportDetailTitle)

        titleView.text = file.name

        try {
            val content = file.readText()
            contentView.text = content
        } catch (e: Exception) {
            contentView.text = "Error reading file: ${e.message}"
        }

        findViewById<View>(R.id.btnBack)?.setOnClickListener {
            finish()
        }

        // Share button
        findViewById<View>(R.id.btnShare)?.setOnClickListener {
            shareReport(file)
        }
    }

    private fun shareReport(file: File) {
        try {
            val content = file.readText()
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, "fytFM Bug Report")
                putExtra(android.content.Intent.EXTRA_TEXT, content)
            }
            startActivity(android.content.Intent.createChooser(intent, "Share Bug Report"))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to share report", Toast.LENGTH_SHORT).show()
        }
    }
}
