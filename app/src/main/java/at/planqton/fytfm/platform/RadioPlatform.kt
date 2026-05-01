package at.planqton.fytfm.platform

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Platform-specific hooks the FM/AM controller triggers during the power-on
 * sequence. Keeps vendor-specific intent class names and broadcast actions
 * out of the controller so the core logic can run unchanged (or with a
 * [NoopRadioPlatform]) on non-FYT hardware and under unit tests.
 */
interface RadioPlatform {
    /**
     * Wake up platform audio-routing services so the FM chip's output lands
     * on the system speakers. Best-effort — failures must not propagate.
     */
    fun prepareFmAudioRouting()

    /**
     * Tell the platform we're leaving FM/AM, so it can release the audio
     * mux back to default routing for other apps. Counterpart to
     * [prepareFmAudioRouting].
     */
    fun releaseFmAudioRouting()
}

/**
 * FYT head-unit implementation: triggert das Audio-Mux-Switching der MCU
 * indirekt über `com.syu.music`'s exportierten `MService`. Das Action-
 * Triple `switch_fm` / `switch_none` ist genau das Hook, das die
 * Original-Radio-Apps benutzen — `MService.onStartCommand` ruft intern
 * die FYT-Vendor-AudioManager-Hidden-Methoden (`setVoiceSwitch2iis`,
 * `setAudioSwitch2iis`, `setWiredDeviceConnectionState`) auf, die nur mit
 * `android.uid.system` erreichbar sind. Wir als untrusted-App können sie
 * nicht direkt aufrufen, aber via dieses Service-Intents triggern.
 *
 * Reverse-Engineering-Quelle: `com.syu.music`'s AndroidManifest deklariert
 * `MService` mit intent-filter-actions `com.syu.music.switch_fm` /
 * `com.syu.music.switch_none` — exported. Der eigentliche Routing-Code
 * lebt in der Bangcle-protected APK, ist aber semantisch das was die
 * Original-Carradio-App in onResume + onPause auslöst.
 */
class FytHeadunitPlatform(private val context: Context) : RadioPlatform {
    companion object {
        private const val TAG = "FytHeadunitPlatform"
        private const val SYU_MUSIC_PACKAGE = "com.syu.music"
        private const val SYU_MS_PACKAGE = "com.syu.ms"
        private const val MUSIC_SWITCH_FM = "com.syu.music.switch_fm"
        private const val MUSIC_SWITCH_NONE = "com.syu.music.switch_none"
        private const val FM_SERVICE_CLASS = "com.android.fmradio.FmService"
        private const val FM_SERVICE_ACTION = "com.android.fmradio.IFmRadioService"
        private const val OPEN_RADIO_ACTION = "com.action.ACTION_OPEN_RADIO"
    }

    override fun prepareFmAudioRouting() {
        // 1. PRIMARY: bitte com.syu.music's MService den Audio-Mux auf FM
        //    schalten. Das ist die documented exportierte Action und
        //    triggert die System-UID-only AudioManager-Hidden-Methoden,
        //    die wir nicht direkt erreichen können.
        try {
            val intent = Intent(MUSIC_SWITCH_FM).apply { setPackage(SYU_MUSIC_PACKAGE) }
            val cn = context.startService(intent)
            Log.i(TAG, "startService($MUSIC_SWITCH_FM @ $SYU_MUSIC_PACKAGE) -> $cn")
        } catch (e: Exception) {
            Log.w(TAG, "Could not start MService switch_fm: ${e.message}")
        }
        // 2. Auch FmService in com.syu.ms anstoßen — manche Builds brauchen
        //    das zusätzlich für den Tuner-Start.
        try {
            val intent = Intent(FM_SERVICE_ACTION).apply { setPackage(SYU_MS_PACKAGE) }
            context.startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start FmService via action: ${e.message}")
        }
        try {
            val intent = Intent().apply { setClassName(SYU_MS_PACKAGE, FM_SERVICE_CLASS) }
            context.startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start FmService via component: ${e.message}")
        }
        // 3. Legacy-Broadcast — manche FYT-Versionen lauschen darauf.
        try {
            context.sendBroadcast(Intent(OPEN_RADIO_ACTION))
        } catch (e: Exception) {
            Log.w(TAG, "Could not send ACTION_OPEN_RADIO: ${e.message}")
        }
    }

    override fun releaseFmAudioRouting() {
        // Sagt MService: „release the audio mux, default routing wieder".
        // So bleiben andere Apps (Spotify, BT-Audio) nicht im FM-Source-
        // State stecken nachdem wir DAB starten oder Radio ganz aus geht.
        try {
            val intent = Intent(MUSIC_SWITCH_NONE).apply { setPackage(SYU_MUSIC_PACKAGE) }
            val cn = context.startService(intent)
            Log.i(TAG, "startService($MUSIC_SWITCH_NONE @ $SYU_MUSIC_PACKAGE) -> $cn")
        } catch (e: Exception) {
            Log.w(TAG, "Could not start MService switch_none: ${e.message}")
        }
    }
}

/** Default for non-FYT hardware and unit tests — does nothing. */
object NoopRadioPlatform : RadioPlatform {
    override fun prepareFmAudioRouting() = Unit
    override fun releaseFmAudioRouting() = Unit
}
