package at.planqton.fytfm.plugin.api

import android.content.Context
import android.graphics.Bitmap

/**
 * Typ des Radio-Plugins. Bestimmt die UI, die angezeigt wird.
 */
enum class TunerType {
    FM, AM, DAB
}

/**
 * Hardware-Gruppe für Ressourcen-Arbitrierung.
 */
enum class HardwareGroup {
    FM_CHIP,
    DAB_USB
}

/**
 * Metadata die vom Plugin an den Host gesendet wird.
 */
data class TunerMetadata(
    val stationName: String? = null,
    val radioText: String? = null,
    val artist: String? = null,
    val title: String? = null,
    val frequency: Float = 0f,
    val rssi: Int = 0,
    val pi: Int = 0,
    val pty: Int = 0,
    val tp: Int = 0,
    val ta: Int = 0,
    val afList: ShortArray? = null,
    val coverBitmap: Bitmap? = null,
    val isAM: Boolean = false
)

/**
 * Scan-Konfiguration.
 */
data class ScanConfig(
    val method: Int = 0,
    val filterPs: Boolean = false,
    val filterPi: Boolean = false,
    val filterLogic: Int = 0,
    val rdsTimeout: Int = 4,
    val overwriteFavorites: Boolean = false
)

/**
 * Callback-Interface für Plugin -> Host Kommunikation.
 */
interface TunerPluginCallback {
    fun onMetadataUpdate(metadata: TunerMetadata)
    fun onFrequencyChanged(frequency: Float)
    fun onStationsChanged(stations: List<RadioStation>)
    fun onPlaybackStateChanged(isPlaying: Boolean)
    fun onScanStarted()
    fun onScanProgress(percent: Int)
    fun onScanFinished(count: Int)
    fun onScanServiceFound(station: RadioStation)
    fun onError(message: String)
}

/**
 * Interface für ein Tuner-Plugin.
 * Plugins implementieren dieses Interface und werden über TunerHostApi
 * mit der Hardware verbunden.
 */
interface TunerPlugin {
    /** Eindeutige Plugin-ID (z.B. "fyt_fm", "hikity_dab") */
    val pluginId: String

    /** Plugin-Typ - bestimmt welche UI angezeigt wird */
    val type: TunerType

    /** Anzeigename des Plugins */
    val displayName: String

    /** Hardware-Gruppe für Ressourcen-Arbitrierung */
    val hardwareGroup: HardwareGroup

    /** Frequenzbereich (null für DAB) */
    val frequencyRange: ClosedFloatingPointRange<Float>?

    /** Frequenz-Schrittweite (null für DAB) */
    val frequencyStep: Float?

    /** Wird vom Host nach Instanziierung aufgerufen - Hardware-Zugang bereitstellen */
    fun initialize(hostApi: TunerHostApi) {}

    /** Prüft ob die benötigte Hardware verfügbar ist */
    fun isHardwareAvailable(): Boolean

    /** Callback setzen */
    fun setCallback(callback: TunerPluginCallback?)

    // === Lifecycle ===
    fun activate(context: Context, tunerId: String)
    fun deactivate()
    val isActive: Boolean

    // === Power ===
    fun powerOn(): Boolean
    fun powerOff()
    val isPoweredOn: Boolean

    // === Tuning (FM/AM) ===
    fun tune(frequency: Float)
    fun getCurrentFrequency(): Float

    // === Audio ===
    fun mute()
    fun unmute()

    // === Station Navigation ===
    fun seekToStation(seekUp: Boolean, onProgress: ((Float) -> Unit)? = null, onResult: ((Float?) -> Unit)? = null)

    // === Scanning ===
    fun startScan(config: ScanConfig? = null)
    fun stopScan()
    val isScanning: Boolean

    // === Settings ===
    /** Gibt die verfügbaren Settings dieses Plugins zurück */
    fun getSettings(): List<PluginSetting> = emptyList()

    /** Wendet gespeicherte Settings an */
    fun applySettings(settings: Map<String, Any>)

    // === DAB-spezifisch (default no-ops) ===
    fun startDabService(serviceIndex: Int) {}
    fun stopDabService() {}

    // === RDS ===
    fun enableRds() {}
    fun clearRds() {}
}
