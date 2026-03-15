package at.planqton.fytfm.media

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Simple HTTP server that serves cover images locally.
 * Binds to all interfaces so other apps can access it.
 */
class CoverHttpServer(
    private val context: Context,
    private val port: Int = 8765
) : NanoHTTPD("0.0.0.0", port) {

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
     * Get the URL to access the current cover using device IP
     */
    fun getCoverUrl(): String? {
        if (currentCoverPath == null) return null
        val ip = getDeviceIpAddress()
        return if (ip != null) {
            "http://$ip:$port/cover.jpg?v=$coverVersion"
        } else {
            // Fallback to localhost
            "http://127.0.0.1:$port/cover.jpg?v=$coverVersion"
        }
    }

    /**
     * Get the device's IP address
     */
    private fun getDeviceIpAddress(): String? {
        // Use NetworkInterface (doesn't need permissions)
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { networkInterface ->
                networkInterface.inetAddresses?.toList()?.forEach { address ->
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress
                        if (ip != null && ip != "0.0.0.0") {
                            Log.d(TAG, "Using network interface IP: $ip")
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address", e)
        }
        return null
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
