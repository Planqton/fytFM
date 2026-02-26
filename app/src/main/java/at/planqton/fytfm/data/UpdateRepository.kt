package at.planqton.fytfm.data

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private const val TAG = "UpdateRepository"
private const val GITHUB_USER = "Planqton"
private const val GITHUB_REPO = "fytFM"

data class UpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val releaseNotes: String?,
    val downloadUrl: String,
    val fileSize: Long,
    val isUpdateAvailable: Boolean
)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpdateAvailable(val info: UpdateInfo) : UpdateState()
    object NoUpdate : UpdateState()
    data class Downloading(val progress: Int) : UpdateState()
    object DownloadComplete : UpdateState()
    data class Error(val message: String) : UpdateState()
}

class UpdateRepository(private val context: Context) {

    private var _updateState: UpdateState = UpdateState.Idle
    val updateState: UpdateState get() = _updateState

    private var stateListener: ((UpdateState) -> Unit)? = null

    private var currentDownloadId: Long = -1
    private val executor = Executors.newSingleThreadExecutor()

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1
            if (id == currentDownloadId) {
                handleDownloadComplete()
            }
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED
            )
        } else {
            context.registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    fun setStateListener(listener: ((UpdateState) -> Unit)?) {
        stateListener = listener
    }

    private fun setState(state: UpdateState) {
        _updateState = state
        stateListener?.invoke(state)
    }

    fun getCurrentVersion(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting version", e)
            "1.0.0"
        }
    }

    fun checkForUpdates() {
        checkForUpdatesInternal(silent = false)
    }

    /**
     * Prüft still auf Updates - bei Fehler (kein Internet etc.) wird nichts angezeigt
     */
    fun checkForUpdatesSilent() {
        checkForUpdatesInternal(silent = true)
    }

    private fun checkForUpdatesInternal(silent: Boolean) {
        if (!silent) {
            setState(UpdateState.Checking)
        }

        executor.execute {
            try {
                val release = fetchLatestRelease()
                if (release == null) {
                    if (!silent) setState(UpdateState.NoUpdate)
                    return@execute
                }

                val currentVersion = getCurrentVersion()
                val latestVersion = release.getString("tag_name").removePrefix("v")

                // Find APK asset
                val assets = release.getJSONArray("assets")
                var apkAsset: JSONObject? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        apkAsset = asset
                        break
                    }
                }

                if (apkAsset == null) {
                    if (!silent) setState(UpdateState.Error("Keine APK im Release gefunden"))
                    return@execute
                }

                val isNewer = isVersionNewer(latestVersion, currentVersion)

                if (isNewer) {
                    val updateInfo = UpdateInfo(
                        currentVersion = currentVersion,
                        latestVersion = latestVersion,
                        releaseNotes = release.optString("body", "").takeIf { it.isNotEmpty() },
                        downloadUrl = apkAsset.getString("browser_download_url"),
                        fileSize = apkAsset.getLong("size"),
                        isUpdateAvailable = true
                    )
                    setState(UpdateState.UpdateAvailable(updateInfo))
                } else if (!silent) {
                    setState(UpdateState.NoUpdate)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error checking for updates", e)
                if (!silent) {
                    setState(UpdateState.Error("Fehler: ${e.message}"))
                }
            }
        }
    }

    private fun fetchLatestRelease(): JSONObject? {
        try {
            val url = URL("https://api.github.com/repos/$GITHUB_USER/$GITHUB_REPO/releases/latest")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            return when (connection.responseCode) {
                200 -> {
                    val response = connection.inputStream.bufferedReader().readText()
                    JSONObject(response)
                }
                404 -> {
                    Log.i(TAG, "No releases found (404)")
                    null
                }
                else -> {
                    Log.e(TAG, "GitHub API error: ${connection.responseCode}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching release", e)
            return null
        }
    }

    private fun isVersionNewer(latest: String, current: String): Boolean {
        try {
            val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }

            for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error comparing versions", e)
            return false
        }
    }

    fun downloadUpdate(downloadUrl: String, version: String) {
        try {
            setState(UpdateState.Downloading(0))

            val fileName = "fytFM-v${version}.apk"

            // Delete old APK files from public Downloads
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadDir?.listFiles()?.filter { it.name.startsWith("fytFM") && it.name.endsWith(".apk") }
                ?.forEach { it.delete() }

            val request = DownloadManager.Request(Uri.parse(downloadUrl))
                .setTitle("fytFM Update v$version")
                .setDescription("Tippe nach Download um zu installieren")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setMimeType("application/vnd.android.package-archive")

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            currentDownloadId = downloadManager.enqueue(request)

            Log.i(TAG, "Download started with ID: $currentDownloadId -> $fileName")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting download", e)
            setState(UpdateState.Error("Download fehlgeschlagen: ${e.message}"))
        }
    }

    private fun handleDownloadComplete() {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(currentDownloadId)
            val cursor = downloadManager.query(query)

            if (cursor.moveToFirst()) {
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val status = cursor.getInt(statusIndex)

                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    setState(UpdateState.DownloadComplete)
                    Log.i(TAG, "Download complete - tap notification to install")
                } else {
                    setState(UpdateState.Error("Download fehlgeschlagen"))
                }
            }
            cursor.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling download complete", e)
            setState(UpdateState.Error("Fehler: ${e.message}"))
        }
    }

    fun resetState() {
        setState(UpdateState.Idle)
    }

    fun destroy() {
        try {
            context.unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
        executor.shutdown()
    }
}
