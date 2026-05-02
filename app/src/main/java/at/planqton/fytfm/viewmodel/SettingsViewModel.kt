package at.planqton.fytfm.viewmodel

import androidx.lifecycle.ViewModel
import at.planqton.fytfm.SqlFMServiceClient
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.UpdateRepository
import at.planqton.fytfm.data.UpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * UI State für Settings
 */
data class SettingsUiState(
    // General
    val isAutoplayAtStartup: Boolean = false,
    val isShowDebugInfos: Boolean = false,
    val isAllowRootFallback: Boolean = false,
    val darkModePreference: Int = 0,  // 0=System, 1=Light, 2=Dark
    val isAutoStartEnabled: Boolean = false,
    val isAutoBackgroundEnabled: Boolean = false,
    val autoBackgroundDelay: Int = 5,
    val isAutoBackgroundOnlyOnBoot: Boolean = false,
    val isShowStationChangeToast: Boolean = false,
    val isTickSoundEnabled: Boolean = false,
    val tickSoundVolume: Int = 50,
    val isRevertPrevNext: Boolean = false,

    // FM Settings
    val isLocalMode: Boolean = false,
    val isMonoMode: Boolean = false,
    val radioArea: Int = 2,  // Default: Europe
    val isAutoScanSensitivity: Boolean = false,
    val isDeezerEnabledFm: Boolean = true,
    val isSignalIconEnabledFm: Boolean = true,
    val isSignalIconEnabledAm: Boolean = true,

    // DAB Settings
    val isDeezerEnabledDab: Boolean = true,
    val isSignalIconEnabledDab: Boolean = true,
    val isDabVisualizerEnabled: Boolean = false,
    val dabVisualizerStyle: Int = 0,
    val dabRecordingPath: String? = null,
    val isDabDevModeEnabled: Boolean = false,

    // Radio Logos
    val activeLogoTemplate: String? = null,
    val isShowLogosInFavorites: Boolean = true,

    // Deezer Cache
    val isDeezerCacheEnabled: Boolean = true,

    // App
    val currentVersion: String = "",
    val language: String = "system",
    val nowPlayingAnimation: Int = 1,
    val isCorrectionHelpersEnabled: Boolean = true,

    // Update State
    val updateState: UpdateState = UpdateState.Idle
)

/**
 * ViewModel für Settings-Dialog
 */
/**
 * ViewModel für Settings-Dialog
 */
class SettingsViewModel(
    private val presetRepository: PresetRepository,
    private val updateRepository: UpdateRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        _state.value = SettingsUiState(
            // General
            isAutoplayAtStartup = presetRepository.isAutoplayAtStartup(),
            isShowDebugInfos = presetRepository.isShowDebugInfos(),
            isAllowRootFallback = presetRepository.isAllowRootFallback(),
            darkModePreference = presetRepository.getDarkModePreference(),
            isAutoStartEnabled = presetRepository.isAutoStartEnabled(),
            isAutoBackgroundEnabled = presetRepository.isAutoBackgroundEnabled(),
            autoBackgroundDelay = presetRepository.getAutoBackgroundDelay(),
            isAutoBackgroundOnlyOnBoot = presetRepository.isAutoBackgroundOnlyOnBoot(),
            isShowStationChangeToast = presetRepository.isShowStationChangeToast(),
            isTickSoundEnabled = presetRepository.isTickSoundEnabled(),
            tickSoundVolume = presetRepository.getTickSoundVolume(),
            isRevertPrevNext = presetRepository.isRevertPrevNext(),

            // FM Settings
            isLocalMode = presetRepository.isLocalMode(),
            isMonoMode = presetRepository.isMonoMode(),
            radioArea = presetRepository.getRadioArea(),
            isAutoScanSensitivity = presetRepository.isAutoScanSensitivity(),
            isDeezerEnabledFm = presetRepository.isDeezerEnabledFm(),
            isSignalIconEnabledFm = presetRepository.isSignalIconEnabledFm(),
            isSignalIconEnabledAm = presetRepository.isSignalIconEnabledAm(),

            // DAB Settings
            isDeezerEnabledDab = presetRepository.isDeezerEnabledDab(),
            isSignalIconEnabledDab = presetRepository.isSignalIconEnabledDab(),
            isDabVisualizerEnabled = presetRepository.isDabVisualizerEnabled(),
            dabVisualizerStyle = presetRepository.getDabVisualizerStyle(),
            dabRecordingPath = presetRepository.getDabRecordingPath(),
            isDabDevModeEnabled = presetRepository.isDabDevModeEnabled(),

            // Radio Logos
            isShowLogosInFavorites = presetRepository.isShowLogosInFavorites(),

            // Deezer Cache
            isDeezerCacheEnabled = presetRepository.isDeezerCacheEnabled(),

            // App
            currentVersion = updateRepository.getCurrentVersion(),
            nowPlayingAnimation = presetRepository.getNowPlayingAnimation(),
            isCorrectionHelpersEnabled = presetRepository.isCorrectionHelpersEnabled()
        )
    }

    // ========== GENERAL SETTINGS ==========

    fun setAutoplayAtStartup(enabled: Boolean) {
        presetRepository.setAutoplayAtStartup(enabled)
        _state.update { it.copy(isAutoplayAtStartup = enabled) }
    }

    fun setShowDebugInfos(enabled: Boolean) {
        presetRepository.setShowDebugInfos(enabled)
        _state.update { it.copy(isShowDebugInfos = enabled) }
    }

    fun setAllowRootFallback(enabled: Boolean) {
        presetRepository.setAllowRootFallback(enabled)
        SqlFMServiceClient.setRootFallbackAllowed(enabled)
        _state.update { it.copy(isAllowRootFallback = enabled) }
    }

    fun setDarkModePreference(mode: Int) {
        presetRepository.setDarkModePreference(mode)
        _state.update { it.copy(darkModePreference = mode) }
    }

    fun setAutoStartEnabled(enabled: Boolean) {
        presetRepository.setAutoStartEnabled(enabled)
        _state.update { it.copy(isAutoStartEnabled = enabled) }
    }

    fun setAutoBackgroundEnabled(enabled: Boolean) {
        presetRepository.setAutoBackgroundEnabled(enabled)
        _state.update { it.copy(isAutoBackgroundEnabled = enabled) }
    }

    fun setAutoBackgroundDelay(delay: Int) {
        presetRepository.setAutoBackgroundDelay(delay)
        _state.update { it.copy(autoBackgroundDelay = delay) }
    }

    fun setAutoBackgroundOnlyOnBoot(enabled: Boolean) {
        presetRepository.setAutoBackgroundOnlyOnBoot(enabled)
        _state.update { it.copy(isAutoBackgroundOnlyOnBoot = enabled) }
    }

    fun setShowStationChangeToast(enabled: Boolean) {
        presetRepository.setShowStationChangeToast(enabled)
        _state.update { it.copy(isShowStationChangeToast = enabled) }
    }

    fun setTickSoundEnabled(enabled: Boolean) {
        presetRepository.setTickSoundEnabled(enabled)
        _state.update { it.copy(isTickSoundEnabled = enabled) }
    }

    fun setTickSoundVolume(volume: Int) {
        presetRepository.setTickSoundVolume(volume)
        _state.update { it.copy(tickSoundVolume = volume) }
    }

    fun setRevertPrevNext(enabled: Boolean) {
        presetRepository.setRevertPrevNext(enabled)
        _state.update { it.copy(isRevertPrevNext = enabled) }
    }

    // ========== FM SETTINGS ==========

    fun setLocalMode(enabled: Boolean) {
        presetRepository.setLocalMode(enabled)
        _state.update { it.copy(isLocalMode = enabled) }
    }

    fun setMonoMode(enabled: Boolean) {
        presetRepository.setMonoMode(enabled)
        _state.update { it.copy(isMonoMode = enabled) }
    }

    fun setRadioArea(area: Int) {
        presetRepository.setRadioArea(area)
        _state.update { it.copy(radioArea = area) }
    }

    fun setAutoScanSensitivity(enabled: Boolean) {
        presetRepository.setAutoScanSensitivity(enabled)
        _state.update { it.copy(isAutoScanSensitivity = enabled) }
    }

    fun setDeezerEnabledFm(enabled: Boolean) {
        presetRepository.setDeezerEnabledFm(enabled)
        _state.update { it.copy(isDeezerEnabledFm = enabled) }
    }

    fun setSignalIconEnabledFm(enabled: Boolean) {
        presetRepository.setSignalIconEnabledFm(enabled)
        _state.update { it.copy(isSignalIconEnabledFm = enabled) }
    }

    fun setSignalIconEnabledAm(enabled: Boolean) {
        presetRepository.setSignalIconEnabledAm(enabled)
        _state.update { it.copy(isSignalIconEnabledAm = enabled) }
    }

    fun setSignalIconEnabledDab(enabled: Boolean) {
        presetRepository.setSignalIconEnabledDab(enabled)
        _state.update { it.copy(isSignalIconEnabledDab = enabled) }
    }

    // ========== DAB SETTINGS ==========

    fun setDeezerEnabledDab(enabled: Boolean) {
        presetRepository.setDeezerEnabledDab(enabled)
        _state.update { it.copy(isDeezerEnabledDab = enabled) }
    }

    fun setDabVisualizerEnabled(enabled: Boolean) {
        presetRepository.setDabVisualizerEnabled(enabled)
        _state.update { it.copy(isDabVisualizerEnabled = enabled) }
    }

    fun setDabVisualizerStyle(style: Int) {
        presetRepository.setDabVisualizerStyle(style)
        _state.update { it.copy(dabVisualizerStyle = style) }
    }

    fun setDabRecordingPath(path: String?) {
        presetRepository.setDabRecordingPath(path)
        _state.update { it.copy(dabRecordingPath = path) }
    }

    fun setDabDevModeEnabled(enabled: Boolean) {
        presetRepository.setDabDevModeEnabled(enabled)
        _state.update { it.copy(isDabDevModeEnabled = enabled) }
    }

    // ========== RADIO LOGOS ==========

    fun setShowLogosInFavorites(enabled: Boolean) {
        presetRepository.setShowLogosInFavorites(enabled)
        _state.update { it.copy(isShowLogosInFavorites = enabled) }
    }

    // ========== DEEZER CACHE ==========

    fun setDeezerCacheEnabled(enabled: Boolean) {
        presetRepository.setDeezerCacheEnabled(enabled)
        _state.update { it.copy(isDeezerCacheEnabled = enabled) }
    }

    // ========== APP ==========

    fun setNowPlayingAnimation(type: Int) {
        presetRepository.setNowPlayingAnimation(type)
        _state.update { it.copy(nowPlayingAnimation = type) }
    }

    fun setCorrectionHelpersEnabled(enabled: Boolean) {
        presetRepository.setCorrectionHelpersEnabled(enabled)
        _state.update { it.copy(isCorrectionHelpersEnabled = enabled) }
    }

    // ========== UPDATE ==========

    fun checkForUpdates() {
        updateRepository.checkForUpdates()
    }

    fun downloadUpdate(downloadUrl: String, version: String) {
        updateRepository.downloadUpdate(downloadUrl, version)
    }

    /**
     * Call this to refresh update state from UpdateRepository
     */
    fun refreshUpdateState() {
        // UpdateRepository uses callback, so we need to observe it externally
    }
}
