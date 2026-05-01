package at.planqton.fytfm.ui.cover

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.LinearLayout
import at.planqton.fytfm.data.PresetRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for the decision-logic in [CoverDisplayController]:
 * source-availability computation, tap-to-cycle, long-press-lock. The
 * view-rendering methods (indicator dots, watermarks) need a themed context
 * + real views to be exercised and aren't covered here — they delegate to
 * well-defined framework APIs (setImageResource, visibility flags).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CoverDisplayControllerTest {

    private lateinit var context: Context
    private lateinit var coverTrio: CoverViewTrio
    private lateinit var presetRepository: PresetRepository

    // Providers the controller reads state from. Mutate the backing fields in
    // tests to simulate DAB-stream changes.
    private var radioLogoPath: String? = null
    private var slideshowBitmap: Bitmap? = null
    private var deezerCoverPath: String? = null
    private var deezerEnabledForDab = true

    private var localCoverLoadedCallCount = 0
    private var lastLocalCoverLoadedPath: String? = null
    private var mediaSyncCallCount = 0
    private var lastMediaSyncSnapshot: CoverDisplayController.MediaSessionSnapshot? = null

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        coverTrio = mockk(relaxed = true)
        presetRepository = mockk(relaxed = true)
        every { presetRepository.isDeezerEnabledDab() } answers { deezerEnabledForDab }

        radioLogoPath = null
        slideshowBitmap = null
        deezerCoverPath = null
        deezerEnabledForDab = true
        localCoverLoadedCallCount = 0
        lastLocalCoverLoadedPath = null
        mediaSyncCallCount = 0
        lastMediaSyncSnapshot = null
    }

    private fun controller() = CoverDisplayController(
        context = context,
        coverTrio = coverTrio,
        dotContainersProvider = { listOf<LinearLayout?>(null, null, null) },
        deezerWatermarkProvider = { listOf<View?>(null, null, null) },
        presetRepository = presetRepository,
        radioLogoPathProvider = { radioLogoPath },
        currentSlideshowProvider = { slideshowBitmap },
        currentDeezerCoverPathProvider = { deezerCoverPath },
        onLocalCoverImageLoaded = { path ->
            localCoverLoadedCallCount++
            lastLocalCoverLoadedPath = path
        },
        mediaSessionSync = { snap ->
            mediaSyncCallCount++
            lastMediaSyncSnapshot = snap
        },
    )

    // ========== computeAvailableCoverSources ==========

    @Test
    fun `computeAvailableCoverSources returns only DAB_LOGO when nothing is available`() {
        val sources = controller().computeAvailableCoverSources()
        assertEquals(listOf(DabCoverSource.DAB_LOGO), sources)
    }

    @Test
    fun `adds STATION_LOGO when radioLogoPath is present`() {
        radioLogoPath = "/logos/station.png"
        val sources = controller().computeAvailableCoverSources()
        assertTrue(sources.contains(DabCoverSource.STATION_LOGO))
    }

    @Test
    fun `adds SLIDESHOW when slideshow bitmap is present`() {
        slideshowBitmap = mockk()
        val sources = controller().computeAvailableCoverSources()
        assertTrue(sources.contains(DabCoverSource.SLIDESHOW))
    }

    @Test
    fun `adds DEEZER when both setting is enabled and cover path present`() {
        deezerEnabledForDab = true
        deezerCoverPath = "/deezer/cover.jpg"
        val sources = controller().computeAvailableCoverSources()
        assertTrue(sources.contains(DabCoverSource.DEEZER))
    }

    @Test
    fun `skips DEEZER when setting is disabled even with cover path present`() {
        deezerEnabledForDab = false
        deezerCoverPath = "/deezer/cover.jpg"
        val sources = controller().computeAvailableCoverSources()
        assertFalse(sources.contains(DabCoverSource.DEEZER))
    }

    @Test
    fun `skips DEEZER when path is blank even with setting enabled`() {
        deezerEnabledForDab = true
        deezerCoverPath = ""
        val sources = controller().computeAvailableCoverSources()
        assertFalse(sources.contains(DabCoverSource.DEEZER))
    }

    @Test
    fun `source order is DAB_LOGO STATION_LOGO SLIDESHOW DEEZER`() {
        radioLogoPath = "/s.png"
        slideshowBitmap = mockk()
        deezerCoverPath = "/d.jpg"
        val sources = controller().computeAvailableCoverSources()
        assertEquals(
            listOf(DabCoverSource.DAB_LOGO, DabCoverSource.STATION_LOGO, DabCoverSource.SLIDESHOW, DabCoverSource.DEEZER),
            sources,
        )
    }

    @Test
    fun `refreshAvailableSources updates the cached property`() {
        val ctrl = controller()
        assertEquals(listOf(DabCoverSource.DAB_LOGO), ctrl.availableCoverSources.ifEmpty { emptyList() }.ifEmpty { listOf(DabCoverSource.DAB_LOGO) })

        radioLogoPath = "/s.png"
        ctrl.refreshAvailableSources()
        assertTrue(ctrl.availableCoverSources.contains(DabCoverSource.STATION_LOGO))
    }

    // ========== updateDabDisplay: source-resolution ==========

    @Test
    fun `updateDabDisplay picks DEEZER in auto mode when available`() {
        slideshowBitmap = mockk()
        deezerCoverPath = "/d.jpg"
        radioLogoPath = "/s.png"

        controller().updateDabDisplay()

        assertEquals("/d.jpg", lastMediaSyncSnapshot?.deezerCoverPath)
        assertNull("slideshow is superseded by Deezer", lastMediaSyncSnapshot?.slideshowBitmap)
    }

    @Test
    fun `updateDabDisplay picks SLIDESHOW in auto mode when Deezer unavailable`() {
        slideshowBitmap = mockk()
        radioLogoPath = "/s.png"
        // no deezer

        controller().updateDabDisplay()

        assertEquals(slideshowBitmap, lastMediaSyncSnapshot?.slideshowBitmap)
        assertNull(lastMediaSyncSnapshot?.deezerCoverPath)
    }

    @Test
    fun `updateDabDisplay picks STATION_LOGO when only logo is present`() {
        radioLogoPath = "/s.png"

        controller().updateDabDisplay()

        assertEquals("/s.png", lastMediaSyncSnapshot?.radioLogoPath)
        assertNull(lastMediaSyncSnapshot?.slideshowBitmap)
    }

    @Test
    fun `updateDabDisplay falls back to DAB_LOGO when nothing available`() {
        controller().updateDabDisplay()

        assertEquals(1, mediaSyncCallCount)
        val snap = lastMediaSyncSnapshot!!
        assertNull(snap.slideshowBitmap)
        assertNull(snap.radioLogoPath)
        assertNull(snap.deezerCoverPath)
        verify { coverTrio.setPlaceholder(any()) }
    }

    @Test
    fun `updateDabDisplay DEEZER path triggers onLocalCoverImageLoaded`() {
        deezerCoverPath = "/d.jpg"
        controller().updateDabDisplay()

        assertEquals(1, localCoverLoadedCallCount)
        assertEquals("/d.jpg", lastLocalCoverLoadedPath)
    }

    @Test
    fun `updateDabDisplay respects explicit selectedCoverSourceIndex`() {
        // Three sources available, user picked index 1 (STATION_LOGO).
        slideshowBitmap = mockk()
        deezerCoverPath = "/d.jpg"
        radioLogoPath = "/s.png"

        val ctrl = controller()
        ctrl.refreshAvailableSources()
        // Auto mode would pick DEEZER; explicit pick overrides.
        ctrl.selectedCoverSourceIndex = ctrl.availableCoverSources.indexOf(DabCoverSource.STATION_LOGO)
        ctrl.updateDabDisplay()

        assertEquals("/s.png", lastMediaSyncSnapshot?.radioLogoPath)
        // Deezer cover NOT in snapshot because we're not on deezer branch.
        assertNull(lastMediaSyncSnapshot?.deezerCoverPath)
    }

    @Test
    fun `updateDabDisplay respects lock when source available`() {
        slideshowBitmap = mockk()
        deezerCoverPath = "/d.jpg"

        val ctrl = controller()
        ctrl.coverSourceLocked = true
        ctrl.lockedCoverSource = DabCoverSource.SLIDESHOW
        ctrl.updateDabDisplay()

        assertEquals(slideshowBitmap, lastMediaSyncSnapshot?.slideshowBitmap)
    }

    @Test
    fun `updateDabDisplay ignores lock when locked source is unavailable`() {
        // Locked to DEEZER but path not present → falls through to auto-best-available.
        radioLogoPath = "/s.png"
        val ctrl = controller()
        ctrl.coverSourceLocked = true
        ctrl.lockedCoverSource = DabCoverSource.DEEZER
        ctrl.updateDabDisplay()

        assertEquals("/s.png", lastMediaSyncSnapshot?.radioLogoPath)
    }

    // ========== toggleDabCover ==========

    @Test
    fun `toggleDabCover from auto-mode moves to index 0`() {
        radioLogoPath = "/s.png"
        slideshowBitmap = mockk()
        val ctrl = controller()
        assertEquals(-1, ctrl.selectedCoverSourceIndex)

        ctrl.toggleDabCover()

        assertEquals(0, ctrl.selectedCoverSourceIndex)
    }

    @Test
    fun `toggleDabCover wraps around at end`() {
        radioLogoPath = "/s.png"
        slideshowBitmap = mockk()
        val ctrl = controller()
        ctrl.refreshAvailableSources()
        ctrl.selectedCoverSourceIndex = ctrl.availableCoverSources.lastIndex

        ctrl.toggleDabCover()

        assertEquals(0, ctrl.selectedCoverSourceIndex)
    }

    @Test
    fun `toggleDabCover releases lock before advancing`() {
        radioLogoPath = "/s.png"
        slideshowBitmap = mockk()
        val ctrl = controller()
        ctrl.coverSourceLocked = true
        ctrl.lockedCoverSource = DabCoverSource.STATION_LOGO

        ctrl.toggleDabCover()

        assertFalse(ctrl.coverSourceLocked)
        assertNull(ctrl.lockedCoverSource)
        verify { presetRepository.setCoverSourceLocked(false) }
        verify { presetRepository.setLockedCoverSource(null) }
    }

    @Test
    fun `toggleDabCover is a no-op when only one source is available`() {
        // DAB_LOGO is always there; nothing else. size==1 → no cycling.
        val ctrl = controller()
        ctrl.toggleDabCover()
        assertEquals(-1, ctrl.selectedCoverSourceIndex) // unchanged
    }

    // ========== toggleCoverSourceLock ==========

    @Test
    fun `toggleCoverSourceLock locks onto explicit selection`() {
        radioLogoPath = "/s.png"
        slideshowBitmap = mockk()
        val ctrl = controller()
        ctrl.refreshAvailableSources()
        ctrl.selectedCoverSourceIndex = ctrl.availableCoverSources.indexOf(DabCoverSource.STATION_LOGO)

        ctrl.toggleCoverSourceLock()

        assertTrue(ctrl.coverSourceLocked)
        assertEquals(DabCoverSource.STATION_LOGO, ctrl.lockedCoverSource)
        verify { presetRepository.setCoverSourceLocked(true) }
        verify { presetRepository.setLockedCoverSource("STATION_LOGO") }
    }

    @Test
    fun `toggleCoverSourceLock in auto mode locks onto best-available`() {
        // Auto-best-available is the priority order DEEZER > SLIDESHOW > STATION_LOGO > DAB_LOGO.
        radioLogoPath = "/s.png"
        slideshowBitmap = mockk()
        val ctrl = controller()
        ctrl.refreshAvailableSources()
        // selectedCoverSourceIndex = -1 (auto)

        ctrl.toggleCoverSourceLock()

        assertTrue(ctrl.coverSourceLocked)
        assertEquals(DabCoverSource.SLIDESHOW, ctrl.lockedCoverSource) // best available of the two
    }

    @Test
    fun `toggleCoverSourceLock when already locked releases lock`() {
        val ctrl = controller()
        ctrl.coverSourceLocked = true
        ctrl.lockedCoverSource = DabCoverSource.STATION_LOGO

        ctrl.toggleCoverSourceLock()

        assertFalse(ctrl.coverSourceLocked)
        assertNull(ctrl.lockedCoverSource)
        verify { presetRepository.setCoverSourceLocked(false) }
        verify { presetRepository.setLockedCoverSource(null) }
    }

    // ========== Deezer watermarks ==========

    @Test
    fun `updateDeezerWatermarks sets visibility across all provided views`() {
        val w1 = mockk<View>(relaxed = true)
        val w2 = mockk<View>(relaxed = true)
        val w3 = mockk<View>(relaxed = true)
        val ctrl = CoverDisplayController(
            context = context,
            coverTrio = coverTrio,
            dotContainersProvider = { emptyList() },
            deezerWatermarkProvider = { listOf(w1, w2, w3) },
            presetRepository = presetRepository,
            radioLogoPathProvider = { null },
            currentSlideshowProvider = { null },
            currentDeezerCoverPathProvider = { null },
            onLocalCoverImageLoaded = {},
            mediaSessionSync = {},
        )

        ctrl.updateDeezerWatermarks(true)
        verify { w1.visibility = View.VISIBLE }
        verify { w2.visibility = View.VISIBLE }
        verify { w3.visibility = View.VISIBLE }

        ctrl.updateDeezerWatermarks(false)
        verify { w1.visibility = View.GONE }
    }
}
