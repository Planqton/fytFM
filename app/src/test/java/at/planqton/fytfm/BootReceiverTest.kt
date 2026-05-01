package at.planqton.fytfm

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import org.junit.Assert.assertEquals
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
 * Tests for [BootReceiver] — the autostart trigger.
 *
 * Robolectric's `ShadowApplication` captures `startActivity` calls so we
 * can assert on the launched intent without actually starting anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class BootReceiverTest {

    private lateinit var context: Context
    private lateinit var shadow: org.robolectric.shadows.ShadowApplication
    private lateinit var receiver: BootReceiver

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        shadow = Shadows.shadowOf(context as Application)
        // Wipe prefs and any queued intents from a previous test.
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit { clear() }
        shadow.clearNextStartedActivities()
        receiver = BootReceiver()
    }

    @Test
    fun `BOOT_COMPLETED with autostart enabled launches MainActivity`() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit { putBoolean("auto_start_enabled", true) }

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val launched = shadow.nextStartedActivity
        assertEquals(MainActivity::class.java.name, launched?.component?.className)
    }

    @Test
    fun `launched intent carries EXTRA_FROM_BOOT flag`() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit { putBoolean("auto_start_enabled", true) }

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val launched = shadow.nextStartedActivity
        assertTrue(
            "EXTRA_FROM_BOOT must be true so MainActivity can adapt to a boot launch",
            launched!!.getBooleanExtra(BootReceiver.EXTRA_FROM_BOOT, false),
        )
    }

    @Test
    fun `launched intent carries NEW_TASK flag (required to launch from receiver context)`() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit { putBoolean("auto_start_enabled", true) }

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val launched = shadow.nextStartedActivity!!
        assertEquals(
            "FLAG_ACTIVITY_NEW_TASK is mandatory when starting an Activity from a non-Activity context",
            Intent.FLAG_ACTIVITY_NEW_TASK,
            launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK,
        )
    }

    @Test
    fun `BOOT_COMPLETED with autostart disabled does NOT launch anything`() {
        // Default: autostart_enabled is false (no prefs entry).
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(
            "must NOT launch MainActivity when autostart is disabled",
            shadow.nextStartedActivity,
        )
    }

    @Test
    fun `QUICKBOOT_POWERON action is also handled (some OEMs use it)`() {
        // Some OEM ROMs emit "android.intent.action.QUICKBOOT_POWERON"
        // instead of BOOT_COMPLETED — must trigger autostart too.
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit { putBoolean("auto_start_enabled", true) }

        receiver.onReceive(context, Intent("android.intent.action.QUICKBOOT_POWERON"))

        val launched = shadow.nextStartedActivity
        assertEquals(MainActivity::class.java.name, launched?.component?.className)
    }

    @Test
    fun `unrelated action is ignored even with autostart enabled`() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit { putBoolean("auto_start_enabled", true) }

        // Receiver should only react to BOOT_COMPLETED / QUICKBOOT_POWERON,
        // not random other broadcasts (e.g. PACKAGE_REPLACED).
        receiver.onReceive(context, Intent(Intent.ACTION_PACKAGE_REPLACED))
        assertNull(shadow.nextStartedActivity)
    }

    @Test
    fun `autostart_enabled defaults to false on fresh prefs (don't surprise users)`() {
        // Verify the safety default — fresh install must NOT autostart.
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadow.nextStartedActivity)
    }
}
