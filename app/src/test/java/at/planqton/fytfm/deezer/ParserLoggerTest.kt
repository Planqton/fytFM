package at.planqton.fytfm.deezer

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
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
import org.robolectric.annotation.Config

/**
 * Tests for [ParserLogger]. The class is an `object` (singleton) so we
 * have to wipe its in-memory state and SharedPreferences before each
 * test. We use Kotlin reflection to reach the `prefs`, `fmEntries`,
 * `dabEntries` and listener fields — this is a deliberate trade-off:
 * the alternative (changing the singleton to be DI-friendly) would touch
 * call sites all over the app for marginal gain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ParserLoggerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        // Wipe the persistent prefs file BEFORE re-initialising — otherwise
        // a previous test's state bleeds in via loadEntries().
        context.getSharedPreferences("parser_logger", Context.MODE_PRIVATE)
            .edit { clear() }
        resetSingleton()
        ParserLogger.init(context)
    }

    @After
    fun tearDown() {
        resetSingleton()
    }

    /** Wipe the singleton's in-memory state via reflection. */
    private fun resetSingleton() {
        listOf("fmEntries", "dabEntries", "fmListeners", "dabListeners").forEach { name ->
            val field = ParserLogger::class.java.getDeclaredField(name)
            field.isAccessible = true
            val value = field.get(ParserLogger)
            // CopyOnWriteArrayList and MutableList both expose .clear()
            value::class.java.getMethod("clear").invoke(value)
        }
        // Force re-init by clearing the prefs cache.
        val prefsField = ParserLogger::class.java.getDeclaredField("prefs")
        prefsField.isAccessible = true
        prefsField.set(ParserLogger, null)
    }

    // ============ Basic logging ============

    @Test
    fun `logFm appends an entry to the FM list with the formatted result`() {
        ParserLogger.logFm("FM4", "Now: Beatles - Yesterday", "Beatles", "Yesterday")
        val entries = ParserLogger.getFmEntries()
        assertEquals(1, entries.size)
        assertEquals("FM4", entries[0].station)
        assertEquals("Now: Beatles - Yesterday", entries[0].rawText)
        assertEquals("Beatles - Yesterday", entries[0].parsedResult)
    }

    @Test
    fun `logFm with null artist or title records parsedResult as null`() {
        ParserLogger.logFm("FM4", "noise text", artist = null, title = null)
        ParserLogger.logFm("FM4", "noise text 2", artist = "Beatles", title = null)
        val entries = ParserLogger.getFmEntries()
        assertEquals(2, entries.size)
        assertNull("both null → parsedResult null", entries[0].parsedResult)
        assertNull("artist set + title null → still null (must have both)", entries[1].parsedResult)
    }

    @Test
    fun `logDab keeps DAB and FM streams independent`() {
        ParserLogger.logFm("FM4", "fm text", "FM Artist", "FM Title")
        ParserLogger.logDab("Ö1", "dab text", "DAB Artist", "DAB Title")
        assertEquals(1, ParserLogger.getFmEntries().size)
        assertEquals(1, ParserLogger.getDabEntries().size)
        assertEquals("FM Artist - FM Title", ParserLogger.getFmEntries()[0].parsedResult)
        assertEquals("DAB Artist - DAB Title", ParserLogger.getDabEntries()[0].parsedResult)
    }

    @Test
    fun `legacy log() routes to DAB stream`() {
        ParserLogger.log("Old", "raw", "A", "B")
        assertTrue(ParserLogger.getFmEntries().isEmpty())
        assertEquals(1, ParserLogger.getDabEntries().size)
    }

    // ============ Deduplication ============

    @Test
    fun `addEntry deduplicates consecutive identical entries`() {
        ParserLogger.logFm("FM4", "same text", "Artist", "Title")
        ParserLogger.logFm("FM4", "same text", "Artist", "Title")
        ParserLogger.logFm("FM4", "same text", "Artist", "Title")
        // Exact duplicates collapse to one.
        assertEquals(1, ParserLogger.getFmEntries().size)
    }

    @Test
    fun `addEntry stores after non-duplicate, then dedupes the next exact repeat`() {
        ParserLogger.logFm("FM4", "first", "A", "1")
        ParserLogger.logFm("FM4", "second", "A", "2")
        ParserLogger.logFm("FM4", "second", "A", "2") // dup of last
        ParserLogger.logFm("FM4", "first", "A", "1") // not dup of last (last was "second")
        val entries = ParserLogger.getFmEntries()
        assertEquals(3, entries.size)
        assertEquals("first", entries[0].rawText)
        assertEquals("second", entries[1].rawText)
        assertEquals("first", entries[2].rawText)
    }

    @Test
    fun `addEntry treats different parsedResult on same rawText as a new entry`() {
        ParserLogger.logFm("FM4", "same raw", "A", "1")
        ParserLogger.logFm("FM4", "same raw", "B", "2")
        assertEquals(2, ParserLogger.getFmEntries().size)
    }

    // ============ Cap / rotation ============

    @Test
    fun `addEntry caps at MAX_ENTRIES and drops the oldest`() {
        // MAX_ENTRIES is 200. Use a smaller batch but enough to overflow.
        repeat(ParserLogger.MAX_ENTRIES + 5) { i ->
            // Vary rawText so the dedup check doesn't swallow these.
            ParserLogger.logFm("FM4", "raw $i", "A", "T$i")
        }
        val entries = ParserLogger.getFmEntries()
        assertEquals(ParserLogger.MAX_ENTRIES, entries.size)
        // Oldest 5 evicted → first surviving entry is "raw 5".
        assertEquals("raw 5", entries.first().rawText)
        assertEquals("raw ${ParserLogger.MAX_ENTRIES + 4}", entries.last().rawText)
    }

    // ============ clear ============

    @Test
    fun `clearFm wipes only FM entries`() {
        ParserLogger.logFm("FM4", "fm", "A", "T")
        ParserLogger.logDab("Ö1", "dab", "A", "T")
        ParserLogger.clearFm()
        assertTrue(ParserLogger.getFmEntries().isEmpty())
        assertEquals(1, ParserLogger.getDabEntries().size)
    }

    @Test
    fun `clearDab wipes only DAB entries`() {
        ParserLogger.logFm("FM4", "fm", "A", "T")
        ParserLogger.logDab("Ö1", "dab", "A", "T")
        ParserLogger.clearDab()
        assertEquals(1, ParserLogger.getFmEntries().size)
        assertTrue(ParserLogger.getDabEntries().isEmpty())
    }

    // ============ Listeners ============

    @Test
    fun `addFmListener fires on each new FM entry but not on DAB`() {
        val captured = mutableListOf<ParserLogger.ParserLogEntry>()
        val listener: (ParserLogger.ParserLogEntry) -> Unit = { captured.add(it) }
        ParserLogger.addFmListener(listener)
        ParserLogger.logFm("FM4", "raw1", "A", "T")
        ParserLogger.logDab("Ö1", "ignore", "A", "T")
        ParserLogger.logFm("FM4", "raw2", "A", "T")
        assertEquals(2, captured.size)
        assertEquals("raw1", captured[0].rawText)
        assertEquals("raw2", captured[1].rawText)
        ParserLogger.removeFmListener(listener)
    }

    @Test
    fun `removeFmListener stops further callbacks`() {
        val captured = mutableListOf<ParserLogger.ParserLogEntry>()
        val listener: (ParserLogger.ParserLogEntry) -> Unit = { captured.add(it) }
        ParserLogger.addFmListener(listener)
        ParserLogger.logFm("FM4", "first", "A", "T")
        ParserLogger.removeFmListener(listener)
        ParserLogger.logFm("FM4", "second", "A", "T")
        assertEquals("only first should arrive", 1, captured.size)
    }

    @Test
    fun `dedup-skipped entries do NOT fire the listener`() {
        var count = 0
        val listener: (ParserLogger.ParserLogEntry) -> Unit = { count++ }
        ParserLogger.addFmListener(listener)
        ParserLogger.logFm("FM4", "same", "A", "T")
        ParserLogger.logFm("FM4", "same", "A", "T") // dedup
        assertEquals(1, count)
        ParserLogger.removeFmListener(listener)
    }

    // ============ Persistence ============

    @Test
    fun `entries survive process restart by reading from prefs`() {
        ParserLogger.logFm("FM4", "persist me", "A", "T")
        ParserLogger.logDab("Ö1", "dab persist", "A", "T")

        // Simulate process death: wipe in-memory state, force re-init.
        resetSingleton()
        ParserLogger.init(context)

        val fm = ParserLogger.getFmEntries()
        val dab = ParserLogger.getDabEntries()
        assertEquals(1, fm.size)
        assertEquals("persist me", fm[0].rawText)
        assertEquals(1, dab.size)
        assertEquals("dab persist", dab[0].rawText)
    }

    @Test
    fun `legacy entries key migrates to DAB and is then deleted`() {
        // Pre-seed the OLD prefs key (`entries`) that pre-FM/DAB-split
        // versions used. The reload should pull these into the DAB list
        // and remove the old key on save.
        val legacyEntry = JSONObject().apply {
            put("timestamp", 1_700_000_000_000L)
            put("station", "Old")
            put("rawText", "legacy raw")
            put("parsedResult", "L - egacy")
        }
        val legacyArray = JSONArray().apply { put(legacyEntry) }
        context.getSharedPreferences("parser_logger", Context.MODE_PRIVATE)
            .edit { putString("entries", legacyArray.toString()) }

        resetSingleton()
        ParserLogger.init(context)

        val dab = ParserLogger.getDabEntries()
        assertEquals(1, dab.size)
        assertEquals("legacy raw", dab[0].rawText)
        // Legacy key must be removed after the migration so it doesn't
        // re-import every restart.
        val legacyAfter = context.getSharedPreferences("parser_logger", Context.MODE_PRIVATE)
            .getString("entries", null)
        assertNull("legacy 'entries' key must be deleted after migration", legacyAfter)
    }

    @Test
    fun `legacy migration deduplicates against existing DAB entries`() {
        // First, seed a DAB entry through the normal path.
        ParserLogger.logDab("Ö1", "shared raw", "A", "B")
        val saved = ParserLogger.getDabEntries().single()

        // Then write a "legacy" entry with the SAME rawText and timestamp
        // alongside it (simulating a partially-migrated state).
        val legacyEntry = JSONObject().apply {
            put("timestamp", saved.timestamp)
            put("station", saved.station)
            put("rawText", saved.rawText)
            put("parsedResult", saved.parsedResult ?: "")
        }
        val legacyArray = JSONArray().apply { put(legacyEntry) }
        context.getSharedPreferences("parser_logger", Context.MODE_PRIVATE)
            .edit { putString("entries", legacyArray.toString()) }

        resetSingleton()
        ParserLogger.init(context)

        // Migration must dedupe — still exactly one DAB entry.
        assertEquals(1, ParserLogger.getDabEntries().size)
    }

    // ============ ParserLogEntry JSON ============

    @Test
    fun `ParserLogEntry round-trips through toJson and fromJson`() {
        val original = ParserLogger.ParserLogEntry(
            timestamp = 1_700_000_000_000L,
            station = "FM4",
            rawText = "Beatles - Yesterday",
            parsedResult = "Beatles - Yesterday",
        )
        val restored = ParserLogger.ParserLogEntry.fromJson(original.toJson())
        assertNotNull(restored)
        assertEquals(original.timestamp, restored!!.timestamp)
        assertEquals(original.station, restored.station)
        assertEquals(original.rawText, restored.rawText)
        assertEquals(original.parsedResult, restored.parsedResult)
    }

    @Test
    fun `ParserLogEntry fromJson treats empty parsedResult as null (failed parse)`() {
        val json = JSONObject("""
            {"timestamp":1700000000000,"station":"FM4","rawText":"x","parsedResult":""}
        """.trimIndent())
        val entry = ParserLogger.ParserLogEntry.fromJson(json)
        assertNotNull(entry)
        assertNull(entry!!.parsedResult)
    }

    @Test
    fun `ParserLogEntry fromJson reads legacy rawDls field as rawText`() {
        // Older log format wrote "rawDls" instead of "rawText" — backward compat.
        val json = JSONObject("""
            {"timestamp":1700000000000,"station":"Ö1","rawDls":"legacy raw","parsedResult":""}
        """.trimIndent())
        val entry = ParserLogger.ParserLogEntry.fromJson(json)
        assertNotNull(entry)
        assertEquals("legacy raw", entry!!.rawText)
    }

    @Test
    fun `ParserLogEntry fromJson returns null when required fields are missing`() {
        val json = JSONObject("""{"station":"X"}""") // no timestamp
        assertNull(ParserLogger.ParserLogEntry.fromJson(json))
    }

    // ============ Export ============

    @Test
    fun `exportFm produces a header followed by formatted entries`() {
        ParserLogger.logFm("FM4", "raw", "A", "B")
        val out = ParserLogger.exportFm()
        assertTrue("header present", out.contains("FM RDS Parser Log"))
        assertTrue("entry count line present", out.contains("Entries: 1"))
        assertTrue("formatted body line present", out.contains("raw → A - B"))
    }

    @Test
    fun `export combines FM and DAB sections`() {
        ParserLogger.logFm("FM4", "fm raw", "A", "B")
        ParserLogger.logDab("Ö1", "dab raw", "C", "D")
        val out = ParserLogger.export()
        assertTrue(out.contains("FM RDS Parser Log"))
        assertTrue(out.contains("DAB+ DLS Parser Log"))
        assertTrue(out.contains("fm raw → A - B"))
        assertTrue(out.contains("dab raw → C - D"))
    }

    @Test
    fun `failed-parse entries render with placeholder X in format`() {
        ParserLogger.logFm("FM4", "garbage", null, null)
        val out = ParserLogger.exportFm()
        assertTrue("failed result rendered as 'X'", out.contains("garbage → X"))
        assertFalse(out.contains("garbage → null"))
    }
}
