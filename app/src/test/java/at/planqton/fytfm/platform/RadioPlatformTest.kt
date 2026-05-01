package at.planqton.fytfm.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Tests for the [RadioPlatform] hierarchy. Robolectric's
 * [org.robolectric.shadows.ShadowApplication] captures the started service
 * and sent broadcast intents so we can assert on the FYT-specific component
 * names + action strings without actually launching anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RadioPlatformTest {

    private lateinit var context: Context
    private lateinit var shadow: org.robolectric.shadows.ShadowApplication

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        shadow = Shadows.shadowOf(context as Application)
        // Drain anything queued by previous tests.
        while (shadow.nextStartedService != null) { /* drain */ }
        shadow.clearBroadcastIntents()
    }

    // ========== FytHeadunitPlatform ==========

    @Test
    fun `prepareFmAudioRouting starts the SYU FmService with the right component`() {
        FytHeadunitPlatform(context).prepareFmAudioRouting()

        val intent = shadow.nextStartedService
        assertNotNull("FmService start intent expected", intent)
        val component = intent!!.component
        assertNotNull(component)
        assertEquals("com.syu.music", component!!.packageName)
        assertEquals("com.android.fmradio.FmService", component.className)
    }

    @Test
    fun `prepareFmAudioRouting broadcasts the OPEN_RADIO action`() {
        FytHeadunitPlatform(context).prepareFmAudioRouting()

        val broadcasts = shadow.broadcastIntents
        val openRadio = broadcasts.find { it.action == "com.action.ACTION_OPEN_RADIO" }
        assertNotNull("ACTION_OPEN_RADIO broadcast expected", openRadio)
    }

    @Test
    fun `prepareFmAudioRouting fires both effects in one call`() {
        FytHeadunitPlatform(context).prepareFmAudioRouting()

        // Service started AND broadcast sent — both, not just one.
        assertNotNull(shadow.nextStartedService)
        assertNotNull(shadow.broadcastIntents.find { it.action == "com.action.ACTION_OPEN_RADIO" })
    }

    // ========== NoopRadioPlatform ==========

    @Test
    fun `NoopRadioPlatform does nothing`() {
        NoopRadioPlatform.prepareFmAudioRouting()

        assertNull(shadow.nextStartedService)
        assertEquals(
            "no broadcasts expected",
            0,
            shadow.broadcastIntents.count { it.action == "com.action.ACTION_OPEN_RADIO" },
        )
    }

    @Test
    fun `NoopRadioPlatform is the same singleton instance`() {
        // It's an `object` declaration — both references must be the same instance.
        assertEquals(NoopRadioPlatform, NoopRadioPlatform)
    }
}
