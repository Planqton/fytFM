package at.planqton.fytfm.ui.lifecycle

import android.content.Context
import android.os.Looper
import at.planqton.fytfm.data.PresetRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Unit tests for [AutoBackgroundController]. The timer posts to the main
 * Looper, so the tests drive it deterministically with Robolectric's paused
 * Looper: `idleFor(ms)` advances virtual time and fires any due `Runnable`s.
 *
 * The controller's observable outputs are the `onTimerExpired` lambda (fired
 * once when the countdown reaches zero) and the side-effect on the toast
 * layer (skipped here — toast instrumentation is noisy and not a behaviour
 * contract worth pinning).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AutoBackgroundControllerTest {

    private lateinit var context: Context
    private lateinit var presetRepository: PresetRepository
    private var expiredCount = 0

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        presetRepository = mockk(relaxed = true)
        expiredCount = 0
        // Sensible defaults — each test overrides what it cares about.
        every { presetRepository.isAutoBackgroundEnabled() } returns true
        every { presetRepository.isAutoBackgroundOnlyOnBoot() } returns false
        every { presetRepository.getAutoBackgroundDelay() } returns 3
    }

    private fun controller() = AutoBackgroundController(
        context = context,
        presetRepository = presetRepository,
        onTimerExpired = { expiredCount++ },
    )

    /** Advances virtual time on the main Looper by [ms], firing any due tasks. */
    private fun advance(ms: Long) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    // ========== startIfNeeded decision logic ==========

    @Test
    fun `startIfNeeded does nothing when feature is disabled`() {
        every { presetRepository.isAutoBackgroundEnabled() } returns false
        val ctrl = controller()

        ctrl.startIfNeeded(wasStartedFromBoot = true)
        advance(10_000)

        assertEquals(0, expiredCount)
    }

    @Test
    fun `startIfNeeded skips when onlyOnBoot=true but not from boot`() {
        every { presetRepository.isAutoBackgroundOnlyOnBoot() } returns true
        val ctrl = controller()

        ctrl.startIfNeeded(wasStartedFromBoot = false)
        advance(10_000)

        assertEquals(0, expiredCount)
    }

    @Test
    fun `startIfNeeded starts timer when onlyOnBoot=true AND from boot`() {
        every { presetRepository.isAutoBackgroundOnlyOnBoot() } returns true
        val ctrl = controller()

        ctrl.startIfNeeded(wasStartedFromBoot = true)
        advance(3_500)

        assertEquals(1, expiredCount)
    }

    @Test
    fun `startIfNeeded starts timer when onlyOnBoot=false regardless of boot flag`() {
        val ctrl = controller()
        ctrl.startIfNeeded(wasStartedFromBoot = false)
        advance(3_500)
        assertEquals(1, expiredCount)
    }

    // ========== Timer firing ==========

    @Test
    fun `timer fires onTimerExpired once after configured delay`() {
        every { presetRepository.getAutoBackgroundDelay() } returns 5
        val ctrl = controller()

        ctrl.startIfNeeded(wasStartedFromBoot = true)

        // 4s in: hasn't fired yet
        advance(4_000)
        assertEquals(0, expiredCount)

        // After 5s: fired exactly once
        advance(1_500)
        assertEquals(1, expiredCount)

        // Does not fire again afterwards
        advance(10_000)
        assertEquals(1, expiredCount)
    }

    // ========== cancel ==========

    @Test
    fun `cancel stops the countdown before it fires`() {
        val ctrl = controller()
        ctrl.startIfNeeded(wasStartedFromBoot = true)
        advance(2_000)
        assertEquals(0, expiredCount)

        ctrl.cancel()
        advance(10_000)

        assertEquals(0, expiredCount)
    }

    // ========== onUserInteraction grace-window semantics ==========

    @Test
    fun `onUserInteraction within grace window does NOT cancel`() {
        // USER_INTERACTION_GRACE_MS is 1000 — anything within that is ignored.
        val ctrl = controller()
        ctrl.startIfNeeded(wasStartedFromBoot = true)
        advance(500) // still within grace

        ctrl.onUserInteraction()

        advance(5_000)
        assertEquals("timer must still fire", 1, expiredCount)
    }

    @Test
    fun `onUserInteraction past grace window cancels the countdown`() {
        val ctrl = controller()
        ctrl.startIfNeeded(wasStartedFromBoot = true)
        advance(1_500) // past 1000ms grace window

        ctrl.onUserInteraction()

        advance(10_000)
        assertEquals(0, expiredCount)
    }

    @Test
    fun `onUserInteraction when no timer is running is a no-op`() {
        val ctrl = controller()
        // No startIfNeeded call — timer not running.
        ctrl.onUserInteraction()
        advance(10_000)
        assertEquals(0, expiredCount)
    }

    // ========== restart ==========

    @Test
    fun `startIfNeeded called again resets the countdown`() {
        every { presetRepository.getAutoBackgroundDelay() } returns 5
        val ctrl = controller()

        ctrl.startIfNeeded(wasStartedFromBoot = true)
        advance(3_000) // 3s elapsed, 2s remaining

        ctrl.startIfNeeded(wasStartedFromBoot = true) // restart
        advance(3_000) // 3s into the NEW countdown, 2s remaining

        assertEquals("restart resets — total elapsed 6s but only 3s into new run", 0, expiredCount)

        advance(3_000) // 6s into new run → fires
        assertEquals(1, expiredCount)
    }
}
