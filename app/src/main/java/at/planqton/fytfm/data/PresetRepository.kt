package at.planqton.fytfm.data

import android.content.Context
import at.planqton.fytfm.data.settings.AppSettingsRepository
import at.planqton.fytfm.data.stations.StationRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin facade over [StationRepository] (FM/AM/DAB CRUD + favourites + scan
 * merges) and [AppSettingsRepository] (~50 user settings). Was the original
 * 919-line god-class; the actual logic lives in the two delegate classes
 * now. Kept as a facade so the existing 50+ call sites don't have to
 * change in one big bang — new code should depend on the relevant
 * delegate directly instead.
 */
class PresetRepository(context: Context) {

    private val stationRepository = StationRepository(context) { isOverwriteFavorites() }
    private val settings = AppSettingsRepository(context)

    // ===== Reactive station lists (delegated) =====
    val fmStations: StateFlow<List<RadioStation>> get() = stationRepository.fmStations
    val amStations: StateFlow<List<RadioStation>> get() = stationRepository.amStations
    val dabStations: StateFlow<List<RadioStation>> get() = stationRepository.dabStations
    val dabDevStations: StateFlow<List<RadioStation>> get() = stationRepository.dabDevStations

    // ===== FM/AM/DAB/DAB-Dev CRUD (delegated) =====
    fun saveFmStations(stations: List<RadioStation>) = stationRepository.saveFmStations(stations)
    fun saveAmStations(stations: List<RadioStation>) = stationRepository.saveAmStations(stations)
    fun loadFmStations(): List<RadioStation> = stationRepository.loadFmStations()
    fun loadAmStations(): List<RadioStation> = stationRepository.loadAmStations()
    fun saveDabStations(stations: List<RadioStation>) = stationRepository.saveDabStations(stations)
    fun loadDabStations(): List<RadioStation> = stationRepository.loadDabStations()
    fun clearDabStations() = stationRepository.clearDabStations()
    fun saveDabDevStations(stations: List<RadioStation>) = stationRepository.saveDabDevStations(stations)
    fun loadDabDevStations(): List<RadioStation> = stationRepository.loadDabDevStations()
    fun clearDabDevStations() = stationRepository.clearDabDevStations()
    fun clearFmStations() = stationRepository.clearFmStations()
    fun clearAmStations() = stationRepository.clearAmStations()

    // ===== Favorites (delegated) =====
    fun toggleFavorite(frequency: Float, isAM: Boolean): Boolean =
        stationRepository.toggleFavorite(frequency, isAM)

    fun toggleDabFavorite(serviceId: Int): Boolean = stationRepository.toggleDabFavorite(serviceId)
    fun toggleDabDevFavorite(serviceId: Int): Boolean = stationRepository.toggleDabDevFavorite(serviceId)
    fun isFavorite(frequency: Float, isAM: Boolean): Boolean = stationRepository.isFavorite(frequency, isAM)
    fun isDabFavorite(serviceId: Int): Boolean = stationRepository.isDabFavorite(serviceId)
    fun isDabDevFavorite(serviceId: Int): Boolean = stationRepository.isDabDevFavorite(serviceId)

    // ===== Scanned-station merges (delegated) =====
    /**
     * Liefert `(merged, overwrittenFavorites)`. Der Caller kann mit den
     * überschriebenen Favoriten z.B. zugehörige Logos aufräumen.
     */
    fun mergeScannedStations(
        scannedStations: List<RadioStation>,
        isAM: Boolean,
    ): Pair<List<RadioStation>, List<RadioStation>> =
        stationRepository.mergeScannedStations(scannedStations, isAM)

    fun mergeDabScannedStations(scannedStations: List<RadioStation>): List<RadioStation> =
        stationRepository.mergeDabScannedStations(scannedStations)

    fun mergeDabDevScannedStations(scannedStations: List<RadioStation>): List<RadioStation> =
        stationRepository.mergeDabDevScannedStations(scannedStations)

    // ================== SETTINGS (all delegated to AppSettingsRepository) ==================

    fun isAutoplayAtStartup(): Boolean = settings.isAutoplayAtStartup()
    fun setAutoplayAtStartup(enabled: Boolean) = settings.setAutoplayAtStartup(enabled)

    fun isDeezerEnabledFm(): Boolean = settings.isDeezerEnabledFm()
    fun setDeezerEnabledFm(enabled: Boolean) = settings.setDeezerEnabledFm(enabled)
    fun isDeezerEnabledDab(): Boolean = settings.isDeezerEnabledDab()
    fun setDeezerEnabledDab(enabled: Boolean) = settings.setDeezerEnabledDab(enabled)
    fun isDeezerEnabledForFrequency(frequency: Float): Boolean =
        settings.isDeezerEnabledForFrequency(frequency)
    fun setDeezerEnabledForFrequency(frequency: Float, enabled: Boolean) =
        settings.setDeezerEnabledForFrequency(frequency, enabled)
    fun isDeezerCacheEnabled(): Boolean = settings.isDeezerCacheEnabled()
    fun setDeezerCacheEnabled(enabled: Boolean) = settings.setDeezerCacheEnabled(enabled)

    fun isShowDebugInfos(): Boolean = settings.isShowDebugInfos()
    fun setShowDebugInfos(enabled: Boolean) = settings.setShowDebugInfos(enabled)

    fun isAutoStartEnabled(): Boolean = settings.isAutoStartEnabled()
    fun setAutoStartEnabled(enabled: Boolean) = settings.setAutoStartEnabled(enabled)

    fun isAutoBackgroundEnabled(): Boolean = settings.isAutoBackgroundEnabled()
    fun setAutoBackgroundEnabled(enabled: Boolean) = settings.setAutoBackgroundEnabled(enabled)
    fun getAutoBackgroundDelay(): Int = settings.getAutoBackgroundDelay()
    fun setAutoBackgroundDelay(seconds: Int) = settings.setAutoBackgroundDelay(seconds)
    fun isAutoBackgroundOnlyOnBoot(): Boolean = settings.isAutoBackgroundOnlyOnBoot()
    fun setAutoBackgroundOnlyOnBoot(onlyOnBoot: Boolean) = settings.setAutoBackgroundOnlyOnBoot(onlyOnBoot)

    fun getDarkModePreference(): Int = settings.getDarkModePreference()
    fun setDarkModePreference(mode: Int) = settings.setDarkModePreference(mode)

    fun isLocalMode(): Boolean = settings.isLocalMode()
    fun setLocalMode(enabled: Boolean) = settings.setLocalMode(enabled)
    fun isMonoMode(): Boolean = settings.isMonoMode()
    fun setMonoMode(enabled: Boolean) = settings.setMonoMode(enabled)
    fun getRadioArea(): Int = settings.getRadioArea()
    fun setRadioArea(area: Int) = settings.setRadioArea(area)
    fun getWorldAreaId(): Int = settings.getWorldAreaId()
    fun setWorldAreaId(id: Int) = settings.setWorldAreaId(id)
    fun getCountry(): String = settings.getCountry()
    fun setCountry(name: String) = settings.setCountry(name)

    fun getFmFrequencyStep(): Float = settings.getFmFrequencyStep()
    fun setFmFrequencyStep(step: Float) = settings.setFmFrequencyStep(step)
    fun getAmFrequencyStep(): Float = settings.getAmFrequencyStep()
    fun setAmFrequencyStep(step: Float) = settings.setAmFrequencyStep(step)

    fun isSignalIconEnabledFm(): Boolean = settings.isSignalIconEnabledFm()
    fun setSignalIconEnabledFm(enabled: Boolean) = settings.setSignalIconEnabledFm(enabled)
    fun isSignalIconEnabledAm(): Boolean = settings.isSignalIconEnabledAm()
    fun setSignalIconEnabledAm(enabled: Boolean) = settings.setSignalIconEnabledAm(enabled)
    fun isSignalIconEnabledDab(): Boolean = settings.isSignalIconEnabledDab()
    fun setSignalIconEnabledDab(enabled: Boolean) = settings.setSignalIconEnabledDab(enabled)
    fun getFmAutoparseMode(): Int = settings.getFmAutoparseMode()
    fun setFmAutoparseMode(mode: Int) = settings.setFmAutoparseMode(mode)

    fun isShowFavoritesOnlyFm(): Boolean = settings.isShowFavoritesOnlyFm()
    fun setShowFavoritesOnlyFm(enabled: Boolean) = settings.setShowFavoritesOnlyFm(enabled)
    fun isShowFavoritesOnlyAm(): Boolean = settings.isShowFavoritesOnlyAm()
    fun setShowFavoritesOnlyAm(enabled: Boolean) = settings.setShowFavoritesOnlyAm(enabled)
    fun isShowFavoritesOnlyDab(): Boolean = settings.isShowFavoritesOnlyDab()
    fun setShowFavoritesOnlyDab(enabled: Boolean) = settings.setShowFavoritesOnlyDab(enabled)

    fun isOverwriteFavorites(): Boolean = settings.isOverwriteFavorites()
    fun setOverwriteFavorites(enabled: Boolean) = settings.setOverwriteFavorites(enabled)
    fun isAutoScanSensitivity(): Boolean = settings.isAutoScanSensitivity()
    fun setAutoScanSensitivity(enabled: Boolean) = settings.setAutoScanSensitivity(enabled)

    fun setDebugWindowOpen(windowId: String, isOpen: Boolean) = settings.setDebugWindowOpen(windowId, isOpen)
    fun isDebugWindowOpen(windowId: String, default: Boolean = false): Boolean =
        settings.isDebugWindowOpen(windowId, default)
    fun setDebugWindowPosition(windowId: String, x: Float, y: Float) =
        settings.setDebugWindowPosition(windowId, x, y)
    fun getDebugWindowPositionX(windowId: String): Float = settings.getDebugWindowPositionX(windowId)
    fun clearAllDebugWindowPositions() = settings.clearAllDebugWindowPositions()
    fun getDebugWindowPositionY(windowId: String): Float = settings.getDebugWindowPositionY(windowId)

    fun getNowPlayingAnimation(): Int = settings.getNowPlayingAnimation()
    fun setNowPlayingAnimation(type: Int) = settings.setNowPlayingAnimation(type)

    fun isCorrectionHelpersEnabled(): Boolean = settings.isCorrectionHelpersEnabled()
    fun setCorrectionHelpersEnabled(enabled: Boolean) = settings.setCorrectionHelpersEnabled(enabled)

    fun isShowLogosInFavorites(): Boolean = settings.isShowLogosInFavorites()
    fun setShowLogosInFavorites(enabled: Boolean) = settings.setShowLogosInFavorites(enabled)

    fun isCarouselMode(): Boolean = settings.isCarouselMode()
    fun setCarouselMode(enabled: Boolean) = settings.setCarouselMode(enabled)
    fun isCarouselModeForRadioMode(radioMode: String): Boolean =
        settings.isCarouselModeForRadioMode(radioMode)
    fun setCarouselModeForRadioMode(radioMode: String, enabled: Boolean) =
        settings.setCarouselModeForRadioMode(radioMode, enabled)

    fun hasAskedAboutImport(): Boolean = settings.hasAskedAboutImport()
    fun setAskedAboutImport(asked: Boolean) = settings.setAskedAboutImport(asked)

    fun isShowStationChangeToast(): Boolean = settings.isShowStationChangeToast()
    fun setShowStationChangeToast(enabled: Boolean) = settings.setShowStationChangeToast(enabled)

    fun isPermanentStationOverlay(): Boolean = settings.isPermanentStationOverlay()
    fun setPermanentStationOverlay(enabled: Boolean) = settings.setPermanentStationOverlay(enabled)

    fun isRevertPrevNext(): Boolean = settings.isRevertPrevNext()
    fun setRevertPrevNext(enabled: Boolean) = settings.setRevertPrevNext(enabled)

    fun isDabVisualizerEnabled(): Boolean = settings.isDabVisualizerEnabled()
    fun setDabVisualizerEnabled(enabled: Boolean) = settings.setDabVisualizerEnabled(enabled)
    fun getDabVisualizerStyle(): Int = settings.getDabVisualizerStyle()
    fun setDabVisualizerStyle(style: Int) = settings.setDabVisualizerStyle(style)

    fun getDabRecordingPath(): String? = settings.getDabRecordingPath()
    fun setDabRecordingPath(path: String?) = settings.setDabRecordingPath(path)
    fun isDabRecordingEnabled(): Boolean = settings.isDabRecordingEnabled()

    fun isTickSoundEnabled(): Boolean = settings.isTickSoundEnabled()
    fun setTickSoundEnabled(enabled: Boolean) = settings.setTickSoundEnabled(enabled)
    fun getTickSoundVolume(): Int = settings.getTickSoundVolume()
    fun setTickSoundVolume(volume: Int) = settings.setTickSoundVolume(volume)

    fun isCoverSourceLocked(): Boolean = settings.isCoverSourceLocked()
    fun setCoverSourceLocked(locked: Boolean) = settings.setCoverSourceLocked(locked)
    fun getLockedCoverSource(): String? = settings.getLockedCoverSource()
    fun setLockedCoverSource(source: String?) = settings.setLockedCoverSource(source)

    fun isDabDevModeEnabled(): Boolean = settings.isDabDevModeEnabled()
    fun setDabDevModeEnabled(enabled: Boolean) = settings.setDabDevModeEnabled(enabled)

    fun isAllowRootFallback(): Boolean = settings.isAllowRootFallback()
    fun setAllowRootFallback(enabled: Boolean) = settings.setAllowRootFallback(enabled)

    fun getAccentColorDay(): Int = settings.getAccentColorDay()
    fun setAccentColorDay(color: Int) = settings.setAccentColorDay(color)
    fun getAccentColorNight(): Int = settings.getAccentColorNight()
    fun setAccentColorNight(color: Int) = settings.setAccentColorNight(color)
    fun resetAccentColors() = settings.resetAccentColors()
}
