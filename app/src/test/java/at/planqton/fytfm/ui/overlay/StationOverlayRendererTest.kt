package at.planqton.fytfm.ui.overlay

import android.content.Context
import at.planqton.fytfm.StationChangeOverlayService
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.RadioStation
import io.mockk.every
import io.mockk.mockk
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Tests for [StationOverlayRenderer]. Uses Robolectric's ShadowApplication to
 * capture the started-service Intents and inspect their extras, so we can
 * verify the JSON payload + action flags without actually launching the
 * overlay service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StationOverlayRendererTest {

    private lateinit var context: Context
    private lateinit var presetRepository: PresetRepository
    private lateinit var renderer: StationOverlayRenderer

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        presetRepository = mockk(relaxed = true)
        every { presetRepository.isShowStationChangeToast() } returns true
        renderer = StationOverlayRenderer(context, presetRepository)
        drainPendingIntents()
    }

    @After
    fun tearDown() {
        drainPendingIntents()
    }

    private fun drainPendingIntents() {
        // Clear any pre-existing service intents so assertions below don't see
        // leftovers from the previous test.
        val shadow = Shadows.shadowOf(context as android.app.Application)
        while (shadow.nextStartedService != null) { /* drain */ }
    }

    private fun fm(freq: Float, name: String? = null, isAM: Boolean = false) =
        RadioStation(frequency = freq, name = name, isAM = isAM, rssi = 50)

    private fun dab(serviceId: Int, name: String? = null) =
        RadioStation(
            frequency = 0f,
            name = name,
            isDab = true,
            serviceId = serviceId,
            ensembleLabel = "Ensemble",
        )

    // ========== gate: isShowStationChangeToast ==========

    @Test
    fun `showFmAmChange is no-op when toast setting is disabled`() {
        every { presetRepository.isShowStationChangeToast() } returns false
        renderer.showFmAmChange(
            frequency = 98.4f, oldFrequency = 95.5f, isAM = false,
            isAppInForeground = true, stations = listOf(fm(98.4f, "Test")),
            logoFor = { _, _ -> null },
        )
        val shadow = Shadows.shadowOf(context as android.app.Application)
        assertNull(shadow.nextStartedService)
    }

    @Test
    fun `showDabChange is no-op when toast setting is disabled`() {
        every { presetRepository.isShowStationChangeToast() } returns false
        renderer.showDabChange(
            serviceId = 1001, oldServiceId = 0, isAppInForeground = true,
            stations = listOf(dab(1001, "Test")), logoFor = { _, _ -> null },
        )
        val shadow = Shadows.shadowOf(context as android.app.Application)
        assertNull(shadow.nextStartedService)
    }

    @Test
    fun `showPermanent bypasses the toast setting and still fires`() {
        // Permanent overlay is a debug feature — it must show regardless of
        // the per-station-change toggle.
        every { presetRepository.isShowStationChangeToast() } returns false
        renderer.showPermanent(
            frequency = 98.4f, isAM = false, stations = listOf(fm(98.4f, "Test")),
            logoFor = { _, _ -> null },
        )
        val shadow = Shadows.shadowOf(context as android.app.Application)
        assertNotNull(shadow.nextStartedService)
    }

    // ========== FM/AM change intent ==========

    @Test
    fun `showFmAmChange posts correct action + frequency extras`() {
        renderer.showFmAmChange(
            frequency = 98.4f, oldFrequency = 95.5f, isAM = false,
            isAppInForeground = true,
            stations = emptyList(), logoFor = { _, _ -> null },
        )
        val intent = Shadows.shadowOf(context as android.app.Application).nextStartedService
        assertNotNull(intent)
        assertEquals(StationChangeOverlayService.ACTION_SHOW_OVERLAY, intent!!.action)
        assertEquals(98.4f, intent.getFloatExtra(StationChangeOverlayService.EXTRA_FREQUENCY, 0f), 0.01f)
        assertEquals(95.5f, intent.getFloatExtra(StationChangeOverlayService.EXTRA_OLD_FREQUENCY, 0f), 0.01f)
        assertFalse(intent.getBooleanExtra(StationChangeOverlayService.EXTRA_IS_AM, true))
        assertTrue(intent.getBooleanExtra(StationChangeOverlayService.EXTRA_APP_IN_FOREGROUND, false))
    }

    @Test
    fun `showFmAmChange encodes stations JSON with freq-name-logo-isAM`() {
        renderer.showFmAmChange(
            frequency = 88.8f, oldFrequency = 0f, isAM = false,
            isAppInForeground = false,
            stations = listOf(
                fm(88.8f, "Radio Alpha"),
                fm(1008f, "AM Beta", isAM = true),
            ),
            logoFor = { name, _ -> "/logos/$name.png" },
        )
        val intent = Shadows.shadowOf(context as android.app.Application).nextStartedService
        val json = JSONArray(intent!!.getStringExtra(StationChangeOverlayService.EXTRA_STATIONS)!!)
        assertEquals(2, json.length())

        val first = json.getJSONObject(0)
        assertEquals(88.8, first.getDouble("frequency"), 0.01)
        assertEquals("Radio Alpha", first.getString("name"))
        assertEquals("/logos/Radio Alpha.png", first.getString("logoPath"))
        assertFalse(first.getBoolean("isAM"))

        val second = json.getJSONObject(1)
        assertEquals(1008.0, second.getDouble("frequency"), 0.01)
        assertTrue(second.getBoolean("isAM"))
    }

    // ========== DAB change intent ==========

    @Test
    fun `showDabChange posts DAB-specific action + serviceId extras`() {
        renderer.showDabChange(
            serviceId = 1001, oldServiceId = 999, isAppInForeground = true,
            stations = emptyList(), logoFor = { _, _ -> null },
        )
        val intent = Shadows.shadowOf(context as android.app.Application).nextStartedService
        assertNotNull(intent)
        assertEquals(StationChangeOverlayService.ACTION_SHOW_OVERLAY, intent!!.action)
        assertTrue(intent.getBooleanExtra(StationChangeOverlayService.EXTRA_IS_DAB, false))
        assertEquals(1001, intent.getIntExtra(StationChangeOverlayService.EXTRA_DAB_SERVICE_ID, -1))
        assertEquals(999, intent.getIntExtra(StationChangeOverlayService.EXTRA_DAB_OLD_SERVICE_ID, -1))
    }

    @Test
    fun `showDabChange encodes stations JSON with serviceId-isDab`() {
        renderer.showDabChange(
            serviceId = 1001, oldServiceId = 0, isAppInForeground = false,
            stations = listOf(dab(1001, "DAB One"), dab(2002, "DAB Two")),
            logoFor = { name, id -> "/dab-logos/$id-$name.png" },
        )
        val intent = Shadows.shadowOf(context as android.app.Application).nextStartedService
        val json = JSONArray(intent!!.getStringExtra(StationChangeOverlayService.EXTRA_STATIONS)!!)
        assertEquals(2, json.length())

        val first = json.getJSONObject(0)
        assertEquals(0.0, first.getDouble("frequency"), 0.01) // DAB frequency is always 0 in JSON
        assertEquals("DAB One", first.getString("name"))
        assertEquals(1001, first.getInt("serviceId"))
        assertTrue(first.getBoolean("isDab"))
        assertFalse(first.getBoolean("isAM"))
        assertEquals("/dab-logos/1001-DAB One.png", first.getString("logoPath"))
    }

    // ========== Permanent overlay ==========

    @Test
    fun `showPermanent posts the permanent action`() {
        renderer.showPermanent(
            frequency = 100.0f, isAM = false,
            stations = listOf(fm(100.0f, "FM Test")),
            logoFor = { _, _ -> null },
        )
        val intent = Shadows.shadowOf(context as android.app.Application).nextStartedService
        assertEquals(StationChangeOverlayService.ACTION_SHOW_PERMANENT, intent!!.action)
        assertEquals(100.0f, intent.getFloatExtra(StationChangeOverlayService.EXTRA_FREQUENCY, 0f), 0.01f)
    }

    // ========== hide ==========

    @Test
    fun `hidePermanent posts the hide action`() {
        renderer.hidePermanent()
        val intent = Shadows.shadowOf(context as android.app.Application).nextStartedService
        assertEquals(StationChangeOverlayService.ACTION_HIDE_OVERLAY, intent!!.action)
    }

    // ========== logoFor callback plumbing ==========

    @Test
    fun `logoFor null return becomes empty logoPath`() {
        renderer.showFmAmChange(
            frequency = 88.8f, oldFrequency = 0f, isAM = false,
            isAppInForeground = false,
            stations = listOf(fm(88.8f, "NoLogo")),
            logoFor = { _, _ -> null },
        )
        val intent = Shadows.shadowOf(context as android.app.Application).nextStartedService
        val json = JSONArray(intent!!.getStringExtra(StationChangeOverlayService.EXTRA_STATIONS)!!)
        assertEquals("", json.getJSONObject(0).getString("logoPath"))
    }
}
