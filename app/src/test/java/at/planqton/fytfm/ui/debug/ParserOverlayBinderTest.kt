package at.planqton.fytfm.ui.debug

import android.content.Context
import at.planqton.fytfm.databinding.ActivityMainBinding
import at.planqton.fytfm.deezer.ParserLogger
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for [ParserOverlayBinder] — focused on the export-handoff contract
 * (filename pattern, content source) and the tab default. UI rendering paths
 * (`updateLogDisplay`, `updateTabButtons`, `setup`) touch real binding views
 * — not asserted here, since a relaxed mock binding can't fully back the
 * generated ViewBinding's @NonNull fields. The bits we DO test are the ones
 * a future tweak could silently break (filename format the export pipeline
 * depends on, default-tab choice that determines which logger source is
 * shown on first open).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ParserOverlayBinderTest {

    private lateinit var context: Context
    private lateinit var binding: ActivityMainBinding

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        binding = mockk(relaxed = true)
        ParserLogger.init(context)
        ParserLogger.clearFm()
        ParserLogger.clearDab()
    }

    @After
    fun tearDown() {
        unmockkAll()
        ParserLogger.clearFm()
        ParserLogger.clearDab()
    }

    private var capturedFilename: String? = null
    private var capturedContent: String? = null

    private fun newBinder() = ParserOverlayBinder(
        binding = binding,
        context = context,
        onExportRequested = { filename, content ->
            capturedFilename = filename
            capturedContent = content
        },
    )

    // ============ Export filename pattern ============

    @Test
    fun `export uses fm slug and timestamp suffix when FM tab active`() {
        val binder = newBinder()
        // FM is the default tab — no toggle needed.
        binder.export()

        assertNotNull("onExportRequested must fire", capturedFilename)
        val name = capturedFilename!!
        assertTrue("starts with fytfm_parser_fm_log_", name.startsWith("fytfm_parser_fm_log_"))
        assertTrue("ends with .txt", name.endsWith(".txt"))
        // 14-digit timestamp + extension.
        assertTrue(
            "filename has yyyyMMdd_HHmmss timestamp",
            name.matches(Regex("""fytfm_parser_fm_log_\d{8}_\d{6}\.txt""")),
        )
    }

    @Test
    fun `export content for FM tab matches ParserLogger_exportFm`() {
        val binder = newBinder()
        binder.export()
        // Compare against ParserLogger's own export — this guards against
        // the binder accidentally calling exportDab in the FM branch.
        assertEquals(ParserLogger.exportFm(), capturedContent)
    }

    @Test
    fun `export captures content at export time, not at construction time`() {
        // Add an entry AFTER constructing the binder — the export should
        // include it (i.e. the binder reads ParserLogger lazily).
        val binder = newBinder()
        ParserLogger.logFm("Ö1", "Some Test Track", "Some", "Track")
        binder.export()
        assertNotNull(capturedContent)
        assertTrue(
            "exported content reflects entries added after construction",
            capturedContent!!.contains("Some Test Track"),
        )
    }

    // ============ Default tab ============

    @Test
    fun `currentParserTab defaults to FM`() {
        val binder = newBinder()
        assertEquals(ParserLogger.Source.FM, binder.currentParserTab)
    }

    @Test
    fun `currentParserTab is read-only externally`() {
        // Doc-style smoke test: the binder API doesn't expose mutation.
        // Callers flip the tab via the binding's tab buttons (wired in setup),
        // never by direct assignment.
        val binder = newBinder()
        assertNotNull(binder.currentParserTab)
        assertFalse(binder.currentParserTab == ParserLogger.Source.DAB)
    }

    // ============ Listener detach ============

    @Test
    fun `release before setup is a no-op`() {
        val binder = newBinder()
        binder.release()  // no exception
    }

    @Test
    fun `double release is safe`() {
        val binder = newBinder()
        binder.release()
        binder.release()  // no exception, parserLogListener is nulled after first
    }
}
