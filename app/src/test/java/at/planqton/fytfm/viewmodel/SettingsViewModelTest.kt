package at.planqton.fytfm.viewmodel

import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.UpdateRepository
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SettingsViewModel
 */
class SettingsViewModelTest {

    private lateinit var mockPresetRepository: PresetRepository
    private lateinit var mockUpdateRepository: UpdateRepository
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        mockPresetRepository = mockk(relaxed = true)
        mockUpdateRepository = mockk(relaxed = true)

        // Setup default return values for all settings
        every { mockPresetRepository.isAutoplayAtStartup() } returns false
        every { mockPresetRepository.isShowDebugInfos() } returns false
        every { mockPresetRepository.getDarkModePreference() } returns 0
        every { mockPresetRepository.isAutoStartEnabled() } returns false
        every { mockPresetRepository.isAutoBackgroundEnabled() } returns false
        every { mockPresetRepository.getAutoBackgroundDelay() } returns 5
        every { mockPresetRepository.isAutoBackgroundOnlyOnBoot() } returns false
        every { mockPresetRepository.isShowStationChangeToast() } returns false
        every { mockPresetRepository.isTickSoundEnabled() } returns false
        every { mockPresetRepository.getTickSoundVolume() } returns 50
        every { mockPresetRepository.isRevertPrevNext() } returns false
        every { mockPresetRepository.isLocalMode() } returns false
        every { mockPresetRepository.isMonoMode() } returns false
        every { mockPresetRepository.getRadioArea() } returns 2
        every { mockPresetRepository.isAutoScanSensitivity() } returns false
        every { mockPresetRepository.isDeezerEnabledFm() } returns true
        every { mockPresetRepository.isDeezerEnabledDab() } returns true
        every { mockPresetRepository.isDabVisualizerEnabled() } returns false
        every { mockPresetRepository.getDabVisualizerStyle() } returns 0
        every { mockPresetRepository.getDabRecordingPath() } returns null
        every { mockPresetRepository.isDabDevModeEnabled() } returns false
        every { mockPresetRepository.isShowLogosInFavorites() } returns true
        every { mockPresetRepository.isDeezerCacheEnabled() } returns true
        every { mockPresetRepository.getNowPlayingAnimation() } returns 1
        every { mockPresetRepository.isCorrectionHelpersEnabled() } returns true
        every { mockUpdateRepository.getCurrentVersion() } returns "1.0.0"

        viewModel = SettingsViewModel(mockPresetRepository, mockUpdateRepository)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ========== INITIAL STATE TESTS ==========

    @Test
    fun `initial state loads values from repository`() {
        val state = viewModel.state.value

        assertEquals(false, state.isAutoplayAtStartup)
        assertEquals(false, state.isShowDebugInfos)
        assertEquals(0, state.darkModePreference)
        assertEquals(5, state.autoBackgroundDelay)
        assertEquals(2, state.radioArea)
        assertEquals(true, state.isDeezerEnabledFm)
        assertEquals("1.0.0", state.currentVersion)
    }

    @Test
    fun `initial state with enabled settings`() {
        every { mockPresetRepository.isAutoplayAtStartup() } returns true
        every { mockPresetRepository.isShowDebugInfos() } returns true
        every { mockPresetRepository.getDarkModePreference() } returns 2

        val newViewModel = SettingsViewModel(mockPresetRepository, mockUpdateRepository)
        val state = newViewModel.state.value

        assertEquals(true, state.isAutoplayAtStartup)
        assertEquals(true, state.isShowDebugInfos)
        assertEquals(2, state.darkModePreference)
    }

    // ========== GENERAL SETTINGS TESTS ==========

    @Test
    fun `setAutoplayAtStartup updates state and repository`() {
        viewModel.setAutoplayAtStartup(true)

        verify { mockPresetRepository.setAutoplayAtStartup(true) }
        assertEquals(true, viewModel.state.value.isAutoplayAtStartup)
    }

    @Test
    fun `setShowDebugInfos updates state and repository`() {
        viewModel.setShowDebugInfos(true)

        verify { mockPresetRepository.setShowDebugInfos(true) }
        assertEquals(true, viewModel.state.value.isShowDebugInfos)
    }

    @Test
    fun `setDarkModePreference updates state and repository`() {
        viewModel.setDarkModePreference(2)

        verify { mockPresetRepository.setDarkModePreference(2) }
        assertEquals(2, viewModel.state.value.darkModePreference)
    }

    @Test
    fun `setAutoStartEnabled updates state and repository`() {
        viewModel.setAutoStartEnabled(true)

        verify { mockPresetRepository.setAutoStartEnabled(true) }
        assertEquals(true, viewModel.state.value.isAutoStartEnabled)
    }

    @Test
    fun `setAutoBackgroundEnabled updates state and repository`() {
        viewModel.setAutoBackgroundEnabled(true)

        verify { mockPresetRepository.setAutoBackgroundEnabled(true) }
        assertEquals(true, viewModel.state.value.isAutoBackgroundEnabled)
    }

    @Test
    fun `setAutoBackgroundDelay updates state and repository`() {
        viewModel.setAutoBackgroundDelay(30)

        verify { mockPresetRepository.setAutoBackgroundDelay(30) }
        assertEquals(30, viewModel.state.value.autoBackgroundDelay)
    }

    @Test
    fun `setTickSoundEnabled updates state and repository`() {
        viewModel.setTickSoundEnabled(true)

        verify { mockPresetRepository.setTickSoundEnabled(true) }
        assertEquals(true, viewModel.state.value.isTickSoundEnabled)
    }

    @Test
    fun `setTickSoundVolume updates state and repository`() {
        viewModel.setTickSoundVolume(75)

        verify { mockPresetRepository.setTickSoundVolume(75) }
        assertEquals(75, viewModel.state.value.tickSoundVolume)
    }

    @Test
    fun `setRevertPrevNext updates state and repository`() {
        viewModel.setRevertPrevNext(true)

        verify { mockPresetRepository.setRevertPrevNext(true) }
        assertEquals(true, viewModel.state.value.isRevertPrevNext)
    }

    // ========== FM SETTINGS TESTS ==========

    @Test
    fun `setLocalMode updates state and repository`() {
        viewModel.setLocalMode(true)

        verify { mockPresetRepository.setLocalMode(true) }
        assertEquals(true, viewModel.state.value.isLocalMode)
    }

    @Test
    fun `setMonoMode updates state and repository`() {
        viewModel.setMonoMode(true)

        verify { mockPresetRepository.setMonoMode(true) }
        assertEquals(true, viewModel.state.value.isMonoMode)
    }

    @Test
    fun `setRadioArea updates state and repository`() {
        viewModel.setRadioArea(4)

        verify { mockPresetRepository.setRadioArea(4) }
        assertEquals(4, viewModel.state.value.radioArea)
    }

    @Test
    fun `setAutoScanSensitivity updates state and repository`() {
        viewModel.setAutoScanSensitivity(true)

        verify { mockPresetRepository.setAutoScanSensitivity(true) }
        assertEquals(true, viewModel.state.value.isAutoScanSensitivity)
    }

    @Test
    fun `setDeezerEnabledFm updates state and repository`() {
        viewModel.setDeezerEnabledFm(false)

        verify { mockPresetRepository.setDeezerEnabledFm(false) }
        assertEquals(false, viewModel.state.value.isDeezerEnabledFm)
    }

    // ========== DAB SETTINGS TESTS ==========

    @Test
    fun `setDeezerEnabledDab updates state and repository`() {
        viewModel.setDeezerEnabledDab(false)

        verify { mockPresetRepository.setDeezerEnabledDab(false) }
        assertEquals(false, viewModel.state.value.isDeezerEnabledDab)
    }

    @Test
    fun `setDabVisualizerEnabled updates state and repository`() {
        viewModel.setDabVisualizerEnabled(true)

        verify { mockPresetRepository.setDabVisualizerEnabled(true) }
        assertEquals(true, viewModel.state.value.isDabVisualizerEnabled)
    }

    @Test
    fun `setDabVisualizerStyle updates state and repository`() {
        viewModel.setDabVisualizerStyle(2)

        verify { mockPresetRepository.setDabVisualizerStyle(2) }
        assertEquals(2, viewModel.state.value.dabVisualizerStyle)
    }

    @Test
    fun `setDabRecordingPath updates state and repository`() {
        val path = "content://test/path"
        viewModel.setDabRecordingPath(path)

        verify { mockPresetRepository.setDabRecordingPath(path) }
        assertEquals(path, viewModel.state.value.dabRecordingPath)
    }

    @Test
    fun `setDabDevModeEnabled updates state and repository`() {
        viewModel.setDabDevModeEnabled(true)

        verify { mockPresetRepository.setDabDevModeEnabled(true) }
        assertEquals(true, viewModel.state.value.isDabDevModeEnabled)
    }

    // ========== RADIO LOGOS SETTINGS TESTS ==========

    @Test
    fun `setShowLogosInFavorites updates state and repository`() {
        viewModel.setShowLogosInFavorites(false)

        verify { mockPresetRepository.setShowLogosInFavorites(false) }
        assertEquals(false, viewModel.state.value.isShowLogosInFavorites)
    }

    // ========== DEEZER CACHE SETTINGS TESTS ==========

    @Test
    fun `setDeezerCacheEnabled updates state and repository`() {
        viewModel.setDeezerCacheEnabled(false)

        verify { mockPresetRepository.setDeezerCacheEnabled(false) }
        assertEquals(false, viewModel.state.value.isDeezerCacheEnabled)
    }

    // ========== APP SETTINGS TESTS ==========

    @Test
    fun `setNowPlayingAnimation updates state and repository`() {
        viewModel.setNowPlayingAnimation(2)

        verify { mockPresetRepository.setNowPlayingAnimation(2) }
        assertEquals(2, viewModel.state.value.nowPlayingAnimation)
    }

    @Test
    fun `setCorrectionHelpersEnabled updates state and repository`() {
        viewModel.setCorrectionHelpersEnabled(false)

        verify { mockPresetRepository.setCorrectionHelpersEnabled(false) }
        assertEquals(false, viewModel.state.value.isCorrectionHelpersEnabled)
    }

    // ========== UPDATE TESTS ==========

    @Test
    fun `checkForUpdates calls repository`() {
        viewModel.checkForUpdates()

        verify { mockUpdateRepository.checkForUpdates() }
    }

    // ========== STATE FLOW TESTS ==========

    @Test
    fun `state is updated reactively`() {
        val initialState = viewModel.state.value
        assertEquals(false, initialState.isAutoplayAtStartup)

        viewModel.setAutoplayAtStartup(true)

        val updatedState = viewModel.state.value
        assertEquals(true, updatedState.isAutoplayAtStartup)
    }

    @Test
    fun `multiple state changes are reflected`() {
        viewModel.setAutoplayAtStartup(true)
        viewModel.setShowDebugInfos(true)
        viewModel.setDarkModePreference(1)
        viewModel.setTickSoundVolume(80)

        val state = viewModel.state.value
        assertEquals(true, state.isAutoplayAtStartup)
        assertEquals(true, state.isShowDebugInfos)
        assertEquals(1, state.darkModePreference)
        assertEquals(80, state.tickSoundVolume)
    }
}
