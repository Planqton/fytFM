package at.planqton.fytfm.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import at.planqton.fytfm.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Image-loading helpers for [FytFMMediaService]'s MediaMetadata artwork.
 * Handles local files, HTTPS URLs, drawable resources and raw Bitmaps, and
 * emits 300×300 JPEG (or PNG for drawables/bitmaps) byte arrays that fit
 * the MediaSession notification size budget.
 *
 * Extracted from FytFMMediaService (5 previously-inline private helpers,
 * ~195 lines). Methods are thread-compatible but the URL fetch blocks —
 * call [loadImageAsBytes] with an http URL from an IO dispatcher.
 */
class MediaMetadataImageLoader(private val context: Context) {

    companion object {
        private const val TAG = "MediaImageLoader"
        private const val ARTWORK_SIZE = 300
        private const val JPEG_QUALITY = 85
        private const val HTTP_TIMEOUT_MS = 5_000
    }

    /** PNG-compressed byte array of [bitmap] at its current resolution. */
    fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    /**
     * Loads an image (local file OR http/https URL) and returns a JPEG byte
     * array resized to [ARTWORK_SIZE]². Returns null on any failure.
     */
    fun loadImageAsBytes(imagePath: String): ByteArray? {
        return try {
            val bitmap = if (imagePath.startsWith("http://") || imagePath.startsWith("https://")) {
                loadBitmapFromUrl(imagePath)
            } else {
                loadBitmapFromFile(imagePath)
            } ?: run {
                Log.w(TAG, "Failed to decode bitmap: $imagePath")
                return null
            }

            val scaled = Bitmap.createScaledBitmap(bitmap, ARTWORK_SIZE, ARTWORK_SIZE, true)
            if (scaled != bitmap) bitmap.recycle()

            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            val bytes = stream.toByteArray()
            scaled.recycle()
            Log.d(TAG, "Loaded artwork: ${bytes.size} bytes (300x300 JPEG) from $imagePath")
            bytes
        } catch (e: Exception) {
            Log.e(TAG, "Error loading artwork: $imagePath", e)
            null
        }
    }

    /**
     * Rasterises a drawable resource onto a 300x300 canvas with a dark
     * background (for MediaSession contrast) and returns a PNG byte array.
     * Used as fallback artwork when no cover/logo is available.
     */
    fun loadDrawableAsBytes(@DrawableRes drawableResId: Int): ByteArray? {
        return try {
            val bitmap = loadDrawableAsBitmap(drawableResId) ?: return null
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val bytes = stream.toByteArray()
            bitmap.recycle()
            Log.d(TAG, "Loaded fallback drawable: ${bytes.size} bytes")
            bytes
        } catch (e: Exception) {
            Log.e(TAG, "Error loading drawable: $drawableResId", e)
            null
        }
    }

    /**
     * Same rasterisation as [loadDrawableAsBytes] but returns the Bitmap
     * directly — für legacy MediaSession APIs die eine Bitmap statt ByteArray
     * brauchen. Caller is responsible for recycling.
     *
     * Spiegelt das `loadCoverOrFallback`-Look der App-internen NowPlaying-
     * Cover wider: theme-aware Background, FIT_CENTER (Aspect-Ratio
     * erhalten, Padding rundherum), Tint auf `radio_text_primary` damit
     * der Vektor-Pfad gegen den Hintergrund kontrastiert.
     */
    fun loadDrawableAsBitmap(@DrawableRes drawableResId: Int): Bitmap? {
        return try {
            val drawable = ContextCompat.getDrawable(context, drawableResId)?.mutate() ?: return null
            val bitmap = Bitmap.createBitmap(ARTWORK_SIZE, ARTWORK_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // Hintergrund + Tint analog zur App-UI (theme-aware via ressource).
            val bgColor = ContextCompat.getColor(context, R.color.radio_background)
            val tintColor = ContextCompat.getColor(context, R.color.radio_text_primary)
            canvas.drawColor(bgColor)
            DrawableCompat.setTint(drawable, tintColor)

            // FIT_CENTER: Vektor proportional skalieren, dann mittig platzieren.
            val intrinsicW = drawable.intrinsicWidth
            val intrinsicH = drawable.intrinsicHeight
            if (intrinsicW > 0 && intrinsicH > 0) {
                val scale = minOf(
                    ARTWORK_SIZE.toFloat() / intrinsicW,
                    ARTWORK_SIZE.toFloat() / intrinsicH
                )
                val scaledW = (intrinsicW * scale).toInt()
                val scaledH = (intrinsicH * scale).toInt()
                val left = (ARTWORK_SIZE - scaledW) / 2
                val top = (ARTWORK_SIZE - scaledH) / 2
                drawable.setBounds(left, top, left + scaledW, top + scaledH)
            } else {
                drawable.setBounds(0, 0, ARTWORK_SIZE, ARTWORK_SIZE)
            }
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error rasterising drawable: $drawableResId", e)
            null
        }
    }

    private fun loadBitmapFromFile(filePath: String): Bitmap? {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                Log.w(TAG, "Bitmap file does not exist: $filePath")
                return null
            }
            if (!file.canRead()) {
                Log.w(TAG, "Bitmap file not readable: $filePath")
                return null
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, bounds)

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "Invalid image dimensions: ${bounds.outWidth}x${bounds.outHeight} for $filePath")
                return null
            }

            var sampleSize = 1
            while (bounds.outWidth / sampleSize > ARTWORK_SIZE || bounds.outHeight / sampleSize > ARTWORK_SIZE) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeFile(filePath, decodeOptions)
            if (bitmap == null) Log.w(TAG, "Failed to decode bitmap (corrupted?): $filePath")
            bitmap
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemory loading bitmap: $filePath", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap from file: $filePath", e)
            null
        }
    }

    private fun loadBitmapFromUrl(url: String): Bitmap? {
        if (url.isBlank()) {
            Log.w(TAG, "Empty URL provided")
            return null
        }

        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                doInput = true
                instanceFollowRedirects = true
                connect()
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP error $responseCode for URL: $url")
                return null
            }

            inputStream = connection.inputStream
            val bitmap = BitmapFactory.decodeStream(inputStream)
            if (bitmap == null) {
                Log.w(TAG, "Failed to decode bitmap from URL (invalid format?): $url")
            } else {
                Log.d(TAG, "Loaded bitmap from URL: $url (${bitmap.width}x${bitmap.height})")
            }
            bitmap
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Timeout loading bitmap from URL: $url")
            null
        } catch (e: IOException) {
            Log.w(TAG, "IO error loading bitmap from URL: $url - ${e.message}")
            null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemory loading bitmap from URL: $url", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap from URL: $url", e)
            null
        } finally {
            runCatching { inputStream?.close() }
            runCatching { connection?.disconnect() }
        }
    }
}
