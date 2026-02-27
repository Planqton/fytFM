package at.planqton.fytfm.media

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

/**
 * Simple HTTP server that serves cover images locally.
 * Used as fallback when Spotify URL is not available but local cached image exists.
 * Only started when local Spotify cache is enabled.
 */
class CoverHttpServer(private val port: Int = 8765) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "CoverHttpServer"
    }

    @Volatile
    private var currentCoverPath: String? = null

    @Volatile
    private var coverVersion: Long = 0

    /**
     * Set the current cover image path to serve
     */
    fun setCoverPath(path: String?) {
        currentCoverPath = path
        coverVersion = System.currentTimeMillis()  // Cache-busting
        Log.d(TAG, "Cover path set: $path (version=$coverVersion)")
    }

    /**
     * Get the URL to access the current cover
     * Includes timestamp parameter to prevent client caching
     */
    fun getCoverUrl(): String? {
        return if (currentCoverPath != null) {
            "http://127.0.0.1:$port/cover.jpg?v=$coverVersion"
        } else null
    }

    override fun serve(session: IHTTPSession): Response {
        Log.d(TAG, "Request: ${session.uri}")

        val path = currentCoverPath
        if (path == null) {
            Log.d(TAG, "No cover path set")
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "No cover available"
            )
        }

        val file = File(path)
        if (!file.exists()) {
            Log.d(TAG, "Cover file not found: $path")
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "Cover file not found"
            )
        }

        return try {
            val fis = FileInputStream(file)
            newFixedLengthResponse(
                Response.Status.OK,
                "image/jpeg",
                fis,
                file.length()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error serving cover", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Error: ${e.message}"
            )
        }
    }

    override fun start() {
        try {
            super.start()
            Log.i(TAG, "Cover server started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start cover server", e)
        }
    }

    override fun stop() {
        super.stop()
        Log.i(TAG, "Cover server stopped")
    }
}
