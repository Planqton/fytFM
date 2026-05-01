package at.planqton.fytfm.dab

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * DAB+ Audio Recorder - Records PCM audio data to MP3 files.
 * Uses the AndroidLame library for MP3 encoding.
 */
class DabRecorder(private val context: Context) {

    companion object {
        private const val TAG = "DabRecorder"
        private const val BITRATE = 192 // kbps
        private const val QUALITY = 5 // 0=best, 9=worst
    }

    @Volatile
    private var isRecording = false
    private var outputUri: Uri? = null
    private var outputStream: OutputStream? = null
    private var androidLame: AndroidLame? = null
    private var mp3Buffer: ByteArray? = null

    private var currentSampleRate = 0
    private var currentChannels = 0
    private var stationName: String = "Unknown"
    private var recordingStartTime: Long = 0
    private var outputFileName: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    private val encoderExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Callbacks
    var onRecordingStarted: (() -> Unit)? = null
    var onRecordingStopped: ((File) -> Unit)? = null
    var onRecordingError: ((String) -> Unit)? = null
    var onRecordingProgress: ((durationSeconds: Long) -> Unit)? = null

    /**
     * Start recording with the given station name to a user-selected directory (SAF).
     */
    fun startRecording(stationName: String, folderUri: String): Boolean {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return false
        }

        this.stationName = sanitizeFileName(stationName)

        try {
            // Parse the folder URI
            val treeUri = Uri.parse(folderUri)
            val documentFile = DocumentFile.fromTreeUri(context, treeUri)

            if (documentFile == null || !documentFile.canWrite()) {
                Log.e(TAG, "Cannot write to selected folder")
                mainHandler.post { onRecordingError?.invoke("Kein Schreibzugriff auf den gewählten Ordner") }
                return false
            }

            // Create output file with timestamp
            val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
            val timestamp = dateFormat.format(Date())
            outputFileName = "${this.stationName}_$timestamp.mp3"

            // Create the file in the selected directory
            val newFile = documentFile.createFile("audio/mpeg", outputFileName)
            if (newFile == null) {
                Log.e(TAG, "Failed to create file")
                mainHandler.post { onRecordingError?.invoke("Datei konnte nicht erstellt werden") }
                return false
            }

            outputUri = newFile.uri
            outputStream = context.contentResolver.openOutputStream(newFile.uri)

            if (outputStream == null) {
                Log.e(TAG, "Failed to open output stream")
                mainHandler.post { onRecordingError?.invoke("Ausgabestream konnte nicht geöffnet werden") }
                return false
            }

            isRecording = true
            recordingStartTime = System.currentTimeMillis()

            Log.i(TAG, "Recording started: ${newFile.uri}")
            mainHandler.post { onRecordingStarted?.invoke() }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            mainHandler.post { onRecordingError?.invoke("Aufnahme konnte nicht gestartet werden: ${e.message}") }
            cleanup()
            return false
        }
    }

    /**
     * Stop recording and finalize the MP3 file.
     */
    fun stopRecording(): String? {
        if (!isRecording) {
            Log.w(TAG, "Not recording")
            return null
        }

        isRecording = false

        // Drain in-flight encoder tasks before closing the stream — otherwise
        // a pending encodeAndWrite() can race into os.write() after close().
        encoderExecutor.shutdown()
        try {
            if (!encoderExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                encoderExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            encoderExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }

        try {
            // Flush remaining data
            androidLame?.let { lame ->
                mp3Buffer?.let { buffer ->
                    val flushed = lame.flush(buffer)
                    if (flushed > 0) {
                        outputStream?.write(buffer, 0, flushed)
                    }
                }
            }

            outputStream?.flush()
            outputStream?.close()

            val fileName = outputFileName
            Log.i(TAG, "Recording stopped: $fileName")

            // Create a dummy File object for the callback (for backwards compatibility)
            val dummyFile = File(fileName)
            mainHandler.post {
                onRecordingStopped?.invoke(dummyFile)
            }

            cleanup()
            return fileName
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording: ${e.message}", e)
            mainHandler.post {
                onRecordingError?.invoke(
                    context.getString(at.planqton.fytfm.R.string.dab_recording_stop_error_format, e.message ?: "")
                )
            }
            cleanup()
            return null
        }
    }

    /**
     * Write PCM audio data to the recorder.
     * This will be encoded to MP3 in a background thread.
     */
    fun writePcmData(data: ByteArray, channels: Int, sampleRate: Int) {
        if (!isRecording) return

        // Initialize encoder if format changed
        if (currentSampleRate != sampleRate || currentChannels != channels) {
            initEncoder(sampleRate, channels)
        }

        // Encode in background thread
        encoderExecutor.execute {
            try {
                encodeAndWrite(data, channels)
            } catch (e: Exception) {
                Log.e(TAG, "Encoding error: ${e.message}", e)
            }
        }

        // Update progress periodically
        val durationSeconds = (System.currentTimeMillis() - recordingStartTime) / 1000
        if (durationSeconds % 1 == 0L) {
            mainHandler.post { onRecordingProgress?.invoke(durationSeconds) }
        }
    }

    private fun initEncoder(sampleRate: Int, channels: Int) {
        Log.i(TAG, "Initializing encoder: sampleRate=$sampleRate, channels=$channels")

        currentSampleRate = sampleRate
        currentChannels = channels

        androidLame = LameBuilder()
            .setInSampleRate(sampleRate)
            .setOutSampleRate(sampleRate)
            .setOutChannels(channels)
            .setOutBitrate(BITRATE)
            .setQuality(QUALITY)
            .build()

        // Buffer for encoded MP3 data (1.25x input + 7200 as per LAME docs)
        val maxSamples = 8192
        mp3Buffer = ByteArray((maxSamples * 1.25 + 7200).toInt())
    }

    private fun encodeAndWrite(data: ByteArray, channels: Int) {
        val lame = androidLame ?: return
        val buffer = mp3Buffer ?: return
        val os = outputStream ?: return

        // Convert ByteArray to ShortArray (PCM 16-bit)
        val samples = data.size / 2
        val shortBuffer = ShortArray(samples)
        for (i in 0 until samples) {
            shortBuffer[i] = ((data[i * 2 + 1].toInt() shl 8) or (data[i * 2].toInt() and 0xFF)).toShort()
        }

        val encodedBytes: Int
        if (channels == 1) {
            // Mono
            encodedBytes = lame.encode(shortBuffer, shortBuffer, samples, buffer)
        } else {
            // Stereo - deinterleave
            val samplesPerChannel = samples / 2
            val leftChannel = ShortArray(samplesPerChannel)
            val rightChannel = ShortArray(samplesPerChannel)
            for (i in 0 until samplesPerChannel) {
                leftChannel[i] = shortBuffer[i * 2]
                rightChannel[i] = shortBuffer[i * 2 + 1]
            }
            encodedBytes = lame.encode(leftChannel, rightChannel, samplesPerChannel, buffer)
        }

        if (encodedBytes > 0) {
            synchronized(os) {
                os.write(buffer, 0, encodedBytes)
            }
        }
    }

    fun isRecording(): Boolean = isRecording

    fun getRecordingDuration(): Long {
        if (!isRecording) return 0
        return (System.currentTimeMillis() - recordingStartTime) / 1000
    }

    private fun cleanup() {
        try {
            outputStream?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing stream: ${e.message}")
        }

        androidLame?.close()
        androidLame = null
        outputStream = null
        outputUri = null
        mp3Buffer = null
        currentSampleRate = 0
        currentChannels = 0
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9äöüÄÖÜß\\s-]"), "")
            .replace(Regex("\\s+"), "_")
            .take(50) // Limit length
            .ifEmpty { "Recording" }
    }

    fun release() {
        if (isRecording) {
            stopRecording()
        }
        encoderExecutor.shutdown()
    }
}
