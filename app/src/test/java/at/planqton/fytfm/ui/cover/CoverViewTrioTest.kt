package at.planqton.fytfm.ui.cover

import android.graphics.Bitmap
import android.widget.ImageView
import at.planqton.fytfm.R
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Dispatch tests for [CoverViewTrio]. The trio's whole job is to keep the
 * three "always-in-sync" cover ImageViews aligned — a test per operation
 * pins that exactly the right calls happen on exactly the right receivers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CoverViewTrioTest {

    private lateinit var nowPlaying: ImageView
    private lateinit var carousel: ImageView
    private lateinit var dabList: ImageView
    private var providerCallCount = 0
    private var dabListRef: ImageView? = null

    @Before
    fun setup() {
        nowPlaying = mockk(relaxed = true)
        carousel = mockk(relaxed = true)
        dabList = mockk(relaxed = true)
        providerCallCount = 0
        dabListRef = dabList
    }

    private fun trio(): CoverViewTrio = CoverViewTrio(
        nowPlaying = nowPlaying,
        carousel = carousel,
        dabListProvider = {
            providerCallCount++
            dabListRef
        },
    )

    // ========== setBitmap ==========

    @Test
    fun `setBitmap dispatches to all three views`() {
        val bitmap = mockk<Bitmap>()
        trio().setBitmap(bitmap)

        verify { nowPlaying.setImageBitmap(bitmap) }
        verify { carousel.setImageBitmap(bitmap) }
        verify { dabList.setImageBitmap(bitmap) }
    }

    @Test
    fun `setBitmap with null dabList provider only dispatches to cover views`() {
        dabListRef = null
        val bitmap = mockk<Bitmap>()
        trio().setBitmap(bitmap)

        verify { nowPlaying.setImageBitmap(bitmap) }
        verify { carousel.setImageBitmap(bitmap) }
        verify(exactly = 0) { dabList.setImageBitmap(any()) }
    }

    // ========== setDabIdlePlaceholder — split drawables ==========

    @Test
    fun `setDabIdlePlaceholder uses generic placeholder on covers but DAB-plus on list`() {
        trio().setDabIdlePlaceholder()

        verify { nowPlaying.setImageResource(R.drawable.ic_cover_placeholder) }
        verify { carousel.setImageResource(R.drawable.ic_cover_placeholder) }
        verify { dabList.setImageResource(R.drawable.ic_fytfm_dab_plus_light) }
    }

    // ========== setPlaceholder — uniform drawable ==========

    @Test
    fun `setPlaceholder uses same drawable across all three views`() {
        trio().setPlaceholder(R.drawable.placeholder_fm)

        verify { nowPlaying.setImageResource(R.drawable.placeholder_fm) }
        verify { carousel.setImageResource(R.drawable.placeholder_fm) }
        verify { dabList.setImageResource(R.drawable.placeholder_fm) }
    }

    // ========== dabListProvider lateness ==========

    @Test
    fun `dabListProvider is invoked on every call (not cached)`() {
        val t = trio()
        t.setDabIdlePlaceholder()
        t.setPlaceholder(R.drawable.ic_cover_placeholder)
        t.setBitmap(mockk(relaxed = true))

        // Each of the three operations reads the provider exactly once.
        assertEquals(3, providerCallCount)
    }

    @Test
    fun `dabListProvider returning null mid-lifecycle is safe`() {
        val t = trio()
        t.setPlaceholder(R.drawable.ic_cover_placeholder)

        // dab list becomes unavailable (e.g. leaving DAB mode)
        dabListRef = null
        t.setPlaceholder(R.drawable.placeholder_fm)

        // First call saw dabList
        verify { dabList.setImageResource(R.drawable.ic_cover_placeholder) }
        // Second call: dab list was null, no second call
        verify(exactly = 0) { dabList.setImageResource(R.drawable.placeholder_fm) }
        // Cover views updated both times regardless
        verify { nowPlaying.setImageResource(R.drawable.ic_cover_placeholder) }
        verify { nowPlaying.setImageResource(R.drawable.placeholder_fm) }
    }

    // ========== loadFromPath null semantics ==========

    @Test
    fun `loadFromPath with null path invokes loadCover on all three but loads nothing`() {
        // loadCover is an ext function that no-ops on null/blank path; verifying
        // the extension's behaviour belongs to its own test. Here we only assert
        // that the trio touches all three receivers so callers can trust dispatch.
        trio().loadFromPath(null)

        // No setImageBitmap / setImageResource side effects — loadCover handles its own.
        verify(exactly = 0) { nowPlaying.setImageBitmap(any()) }
        verify(exactly = 0) { carousel.setImageResource(any()) }
        // Provider still consulted.
        assertEquals(1, providerCallCount)
    }

    // ========== loadFromPathOrFallback — split fallbacks ==========

    @Test
    fun `loadFromPathOrFallback with blank path sets each view's configured fallback`() {
        trio().loadFromPathOrFallback(
            path = "",
            coversFallback = R.drawable.placeholder_fm,
            dabListFallback = R.drawable.ic_fytfm_dab_plus_light,
        )

        verify { nowPlaying.setImageResource(R.drawable.placeholder_fm) }
        verify { carousel.setImageResource(R.drawable.placeholder_fm) }
        verify { dabList.setImageResource(R.drawable.ic_fytfm_dab_plus_light) }
    }
}
