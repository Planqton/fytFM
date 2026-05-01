package at.planqton.fytfm.ui.logotemplate

import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import at.planqton.fytfm.R
import at.planqton.fytfm.data.logo.RadioLogoRepository
import at.planqton.fytfm.data.logo.RadioLogoTemplate
import kotlinx.coroutines.launch

/**
 * Runs the "download + activate" flow for a [RadioLogoTemplate]: progress
 * dialog → per-item updates via repository callback → final save + activate,
 * with a success toast or an error dialog listing failed downloads.
 *
 * Bound to the Activity's [lifecycleScope] so a download in flight is
 * cancelled on Activity destruction — the previous inline implementation
 * used a free-floating `CoroutineScope(Dispatchers.Main)` that could leak
 * past the Activity lifecycle.
 */
class LogoTemplateDownloader(
    private val activity: AppCompatActivity,
    private val radioLogoRepository: RadioLogoRepository,
) {
    fun downloadAndActivate(template: RadioLogoTemplate, onComplete: () -> Unit) {
        val progressView = activity.layoutInflater
            .inflate(android.R.layout.simple_list_item_1, null)
        val progressText = progressView.findViewById<TextView>(android.R.id.text1).apply {
            text = activity.getString(R.string.loading_logos)
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val progressDialog = AlertDialog.Builder(activity)
            .setView(progressView)
            .setCancelable(false)
            .create()
        progressDialog.show()

        activity.lifecycleScope.launch {
            val (updatedTemplate, failed) = radioLogoRepository.downloadLogos(template) { current, total ->
                activity.runOnUiThread {
                    progressText.text = activity.getString(R.string.loading_logos_progress, current, total)
                }
            }

            progressDialog.dismiss()

            radioLogoRepository.saveTemplate(updatedTemplate)
            radioLogoRepository.setActiveTemplate(updatedTemplate.name)

            if (failed.isEmpty()) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.logos_loaded_count, updatedTemplate.stations.size),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                val loaded = updatedTemplate.stations.size - failed.size
                val total = updatedTemplate.stations.size
                AlertDialog.Builder(activity)
                    .setTitle(R.string.logos_loaded_title)
                    .setMessage(activity.getString(R.string.logos_loaded_with_failures_format, loaded, total, failed.joinToString("\n")))
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }

            onComplete()
        }
    }
}
