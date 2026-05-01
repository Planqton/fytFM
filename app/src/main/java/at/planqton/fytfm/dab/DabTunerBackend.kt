package at.planqton.fytfm.dab

import android.content.Context
import android.graphics.Bitmap

/**
 * Backend abstraction for DAB+ tuner implementations. Both the real
 * [DabTunerManager] (talking to the FYT head-unit hardware) and the
 * dev-mode [MockDabTunerManager] implement this — so [DabController]
 * can be swapped between them at runtime via [DabController.setBackend]
 * without MainActivity touching either implementation directly.
 *
 * Callbacks mirror the signatures the two existing managers already
 * expose; the interface only formalises what was previously a parallel
 * API surface kept in sync by convention.
 */
interface DabTunerBackend {

    // ===== Tuner / service callbacks =====
    var onServiceStarted: ((DabStation) -> Unit)?
    var onServiceStopped: (() -> Unit)?
    var onTunerReady: (() -> Unit)?
    var onTunerError: ((String) -> Unit)?
    var onDynamicLabel: ((String) -> Unit)?
    var onDlPlus: ((artist: String?, title: String?) -> Unit)?
    var onAudioStarted: ((audioSessionId: Int) -> Unit)?
    var onSlideshow: ((Bitmap) -> Unit)?
    var onReceptionStats: ((sync: Boolean, quality: String, snr: Int) -> Unit)?

    // ===== Recording callbacks =====
    var onRecordingStarted: (() -> Unit)?
    var onRecordingStopped: ((java.io.File) -> Unit)?
    var onRecordingError: ((String) -> Unit)?
    var onRecordingProgress: ((durationSeconds: Long) -> Unit)?

    // ===== EPG callbacks =====
    var onEpgDataReceived: ((EpgData) -> Unit)?

    // ===== Lifecycle =====
    fun initialize(context: Context): Boolean
    fun deinitialize()

    // ===== Service control =====
    fun tuneService(serviceId: Int, ensembleId: Int): Boolean
    fun stopService()
    fun getServices(): List<DabStation>
    fun getCurrentService(): DabStation?

    // ===== Scan =====
    fun startScan(listener: DabScanListener)
    fun stopScan()

    // ===== Status =====
    fun hasTuner(): Boolean
    fun isDabAvailable(context: Context): Boolean
    fun getAudioSessionId(): Int

    // ===== Recording =====
    fun startRecording(context: Context, folderUri: String): Boolean
    fun stopRecording(): String?
    fun isRecording(): Boolean

    // ===== EPG =====
    fun getCurrentEpgData(): EpgData?
}
