package at.planqton.fytfm.controller

import android.os.Bundle
import com.android.fmradio.FmNative

/**
 * Pure-Kotlin facade over the JNI-backed [com.android.fmradio.FmNative].
 *
 * Why this exists: `FmNative` declares native methods that load from
 * `libfmjni.so`. JVM-only unit tests can't satisfy those symbols, and
 * mocking native methods needs `mockk-agent-jvm` (not currently in the
 * classpath). Routing [FmAmController] / [at.planqton.fytfm.scanner.RadioScanner]
 * through this interface lets tests substitute a plain MockK mock instead.
 *
 * Production wiring uses [FmNativeAdapter]. The interface lists exactly
 * the surface the callers need — no more — so the dependency stays minimal.
 */
interface FmNativeApi {
    fun powerOn(frequency: Float): Boolean
    fun powerOff(): Boolean
    fun openDev(): Boolean
    fun powerUp(frequency: Float): Boolean
    fun tune(frequency: Float): Boolean
    fun seek(frequency: Float, isUp: Boolean): FloatArray?
    fun setMute(mute: Boolean): Int
    fun setMonoMode(enabled: Boolean): Boolean
    fun setLocalMode(enabled: Boolean): Boolean
    fun setRadioArea(area: Int): Boolean
    fun getrssi(): Int
    /** Whether `libfmjni.so` loaded successfully at process start. */
    fun isLibraryLoaded(): Boolean

    // ===== Scanner-only surface =====
    // These additional methods are only needed by [RadioScanner]. They live
    // on the same interface so we don't have two parallel facades over the
    // same JNI singleton.
    fun setRds(enabled: Boolean): Int
    fun fmsyu_jni(cmd: Int, inBundle: Bundle, outBundle: Bundle): Int
    fun sql_getrssi(): Int
    fun stopScan(): Boolean
}

/** Production adapter that forwards every call to the real [FmNative] singleton. */
class FmNativeAdapter(private val fmNative: FmNative) : FmNativeApi {
    override fun powerOn(frequency: Float): Boolean = fmNative.powerOn(frequency)
    override fun powerOff(): Boolean = fmNative.powerOff()
    override fun openDev(): Boolean = fmNative.openDev()
    override fun powerUp(frequency: Float): Boolean = fmNative.powerUp(frequency)
    override fun tune(frequency: Float): Boolean = fmNative.tune(frequency)
    override fun seek(frequency: Float, isUp: Boolean): FloatArray? = fmNative.seek(frequency, isUp)
    override fun setMute(mute: Boolean): Int = fmNative.setMute(mute)
    override fun setMonoMode(enabled: Boolean): Boolean = fmNative.setMonoMode(enabled)
    override fun setLocalMode(enabled: Boolean): Boolean = fmNative.setLocalMode(enabled)
    override fun setRadioArea(area: Int): Boolean = fmNative.setRadioArea(area)
    override fun getrssi(): Int = fmNative.getrssi()
    override fun isLibraryLoaded(): Boolean = FmNative.isLibraryLoaded()
    override fun setRds(enabled: Boolean): Int = fmNative.setRds(enabled)
    override fun fmsyu_jni(cmd: Int, inBundle: Bundle, outBundle: Bundle): Int =
        fmNative.fmsyu_jni(cmd, inBundle, outBundle)
    override fun sql_getrssi(): Int = fmNative.sql_getrssi()
    override fun stopScan(): Boolean = fmNative.stopScan()
}
