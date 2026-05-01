package at.planqton.fytfm.data.logo

import android.content.Context
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
import java.io.File

/**
 * Integration tests for [RadioLogoRepository] backed by Robolectric's
 * real SharedPreferences and filesDir. We don't mock the storage layer
 * because the bugs we want to catch (cache invalidation, JSON-shape
 * regressions, file deletion) only show up when prefs and disk actually
 * round-trip a value.
 *
 * Network paths (`downloadLogos`) are NOT covered here — they need a
 * MockWebServer-based integration test. The repo's pure CRUD/cache logic
 * is what's exercised below.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RadioLogoRepositoryTest {

    private lateinit var context: Context
    private lateinit var repo: RadioLogoRepository

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        // Each test gets a clean prefs file — Robolectric tears down the
        // app on @After, but explicit clear avoids cross-test bleed when
        // running with --tests filters.
        context.getSharedPreferences("radio_logos", Context.MODE_PRIVATE)
            .edit().clear().commit()
        // Also wipe any logos dir from a previous test run.
        File(context.filesDir, "logos").deleteRecursively()
        repo = RadioLogoRepository(context)
    }

    private fun makeTemplate(
        name: String = "Test-Template",
        area: Int = 2,
        stations: List<StationLogo> = listOf(
            StationLogo(ps = "FM4", logoUrl = "https://cdn/fm4.png"),
        ),
    ) = RadioLogoTemplate(name = name, area = area, stations = stations)

    // ============ Basic CRUD ============

    @Test
    fun `getTemplates returns empty list on fresh prefs`() {
        assertTrue(repo.getTemplates().isEmpty())
    }

    @Test
    fun `saveTemplate then getTemplates round-trips through prefs`() {
        repo.saveTemplate(makeTemplate(name = "First"))
        val templates = repo.getTemplates()
        assertEquals(1, templates.size)
        assertEquals("First", templates[0].name)
        assertEquals("FM4", templates[0].stations.firstOrNull()?.ps)
    }

    @Test
    fun `saveTemplate replaces an existing template with the same name`() {
        repo.saveTemplate(makeTemplate(name = "Same", stations = listOf(
            StationLogo(ps = "Old", logoUrl = "https://cdn/old.png"),
        )))
        repo.saveTemplate(makeTemplate(name = "Same", stations = listOf(
            StationLogo(ps = "New", logoUrl = "https://cdn/new.png"),
        )))
        val templates = repo.getTemplates()
        assertEquals("upsert must not duplicate", 1, templates.size)
        assertEquals("New", templates[0].stations.firstOrNull()?.ps)
    }

    @Test
    fun `saveTemplate adds a second template alongside the first`() {
        repo.saveTemplate(makeTemplate(name = "A"))
        repo.saveTemplate(makeTemplate(name = "B"))
        assertEquals(2, repo.getTemplates().size)
    }

    @Test
    fun `getTemplatesForArea filters by area code`() {
        repo.saveTemplate(makeTemplate(name = "EU", area = 2))
        repo.saveTemplate(makeTemplate(name = "USA", area = 0))
        repo.saveTemplate(makeTemplate(name = "EU2", area = 2))
        val euOnly = repo.getTemplatesForArea(2)
        assertEquals(2, euOnly.size)
        assertTrue(euOnly.all { it.area == 2 })
    }

    @Test
    fun `deleteTemplate removes the template from prefs`() {
        repo.saveTemplate(makeTemplate(name = "Keep"))
        repo.saveTemplate(makeTemplate(name = "Drop"))
        repo.deleteTemplate("Drop")
        val remaining = repo.getTemplates()
        assertEquals(1, remaining.size)
        assertEquals("Keep", remaining[0].name)
    }

    @Test
    fun `deleteTemplate removes the on-disk logo directory`() {
        repo.saveTemplate(makeTemplate(name = "WithFiles"))
        // Drop a fake logo file in the template's dir to verify cleanup.
        val safeDirName = "WithFiles" // matches the safeName regex (alnum + _-)
        val templateDir = File(File(context.filesDir, "logos"), safeDirName).apply { mkdirs() }
        val fakeLogo = File(templateDir, "fake.png").apply { writeText("png-bytes") }
        assertTrue("fake logo created", fakeLogo.exists())

        repo.deleteTemplate("WithFiles")
        assertFalse("template dir gone after delete", templateDir.exists())
    }

    @Test
    fun `deleteTemplate clears the active pointer when the active is dropped`() {
        repo.saveTemplate(makeTemplate(name = "ActiveOne"))
        repo.setActiveTemplate("ActiveOne")
        repo.deleteTemplate("ActiveOne")
        assertNull(repo.getActiveTemplateName())
        assertNull(repo.getActiveTemplate())
    }

    @Test
    fun `deleteTemplate keeps active pointer if a different template is dropped`() {
        repo.saveTemplate(makeTemplate(name = "Active"))
        repo.saveTemplate(makeTemplate(name = "Other"))
        repo.setActiveTemplate("Active")
        repo.deleteTemplate("Other")
        assertEquals("Active", repo.getActiveTemplateName())
    }

    // ============ Active-template caching ============

    @Test
    fun `setActiveTemplate stores the pointer and reads back`() {
        repo.saveTemplate(makeTemplate(name = "Pick"))
        repo.setActiveTemplate("Pick")
        assertEquals("Pick", repo.getActiveTemplateName())
        assertNotNull(repo.getActiveTemplate())
        assertEquals("Pick", repo.getActiveTemplate()?.name)
    }

    @Test
    fun `setActiveTemplate to null clears the pointer`() {
        repo.saveTemplate(makeTemplate(name = "X"))
        repo.setActiveTemplate("X")
        repo.setActiveTemplate(null)
        assertNull(repo.getActiveTemplateName())
        assertNull(repo.getActiveTemplate())
    }

    @Test
    fun `setActiveTemplate switching templates wipes the old template's logo files`() {
        // Pre-create old template's logo dir + a stub file.
        repo.saveTemplate(makeTemplate(name = "Old"))
        repo.saveTemplate(makeTemplate(name = "New"))
        repo.setActiveTemplate("Old")
        val oldDir = File(File(context.filesDir, "logos"), "Old").apply { mkdirs() }
        val oldLogo = File(oldDir, "stale.png").apply { writeText("bytes") }
        assertTrue(oldLogo.exists())

        repo.setActiveTemplate("New")
        // Active switched → old template's logo files must be wiped (the
        // dir itself stays, but the contents are gone).
        assertFalse("stale logo file must be deleted on active-switch", oldLogo.exists())
        assertEquals("New", repo.getActiveTemplateName())
    }

    @Test
    fun `saveTemplate refreshes cachedTemplate when saving the active one`() {
        repo.saveTemplate(makeTemplate(name = "Live", stations = listOf(
            StationLogo(ps = "v1", logoUrl = "https://cdn/v1.png"),
        )))
        repo.setActiveTemplate("Live")
        repo.saveTemplate(makeTemplate(name = "Live", stations = listOf(
            StationLogo(ps = "v2", logoUrl = "https://cdn/v2.png"),
        )))
        // Without cache refresh we'd see the first-saved version.
        val active = repo.getActiveTemplate()
        assertEquals("v2", active?.stations?.firstOrNull()?.ps)
    }

    @Test
    fun `invalidateCache forces a reload from prefs`() {
        repo.saveTemplate(makeTemplate(name = "Cached"))
        repo.setActiveTemplate("Cached")
        // Mutate prefs through a second repo instance — the first repo's
        // cache is stale.
        val repo2 = RadioLogoRepository(context)
        repo2.saveTemplate(makeTemplate(name = "Cached", stations = listOf(
            StationLogo(ps = "Updated", logoUrl = "https://cdn/u.png"),
        )))
        // First repo still serves cached version.
        assertEquals("FM4", repo.getActiveTemplate()?.stations?.firstOrNull()?.ps)
        // After invalidate, it picks up the change.
        repo.invalidateCache()
        assertEquals("Updated", repo.getActiveTemplate()?.stations?.firstOrNull()?.ps)
    }

    // ============ getLogoForStation ============

    @Test
    fun `getLogoForStation returns null when no active template`() {
        assertNull(repo.getLogoForStation(ps = "FM4", pi = null, frequency = null))
    }

    @Test
    fun `getLogoForStation picks the highest-priority match (PI over PS)`() {
        val template = makeTemplate(
            name = "Mixed",
            stations = listOf(
                StationLogo(ps = "FM4", logoUrl = "ps.png", localPath = "/data/ps.png"),
                StationLogo(pi = "A3E0", logoUrl = "pi.png", localPath = "/data/pi.png"),
            ),
        )
        repo.saveTemplate(template)
        repo.setActiveTemplate("Mixed")

        val result = repo.getLogoForStation(ps = "FM4", pi = 0xA3E0, frequency = null)
        assertEquals("PI match must beat PS match", "/data/pi.png", result)
    }

    @Test
    fun `getLogoForStation returns null when station's localPath is unset`() {
        val template = makeTemplate(
            name = "NoLocal",
            stations = listOf(StationLogo(ps = "FM4", logoUrl = "x.png", localPath = null)),
        )
        repo.saveTemplate(template)
        repo.setActiveTemplate("NoLocal")
        assertNull(repo.getLogoForStation("FM4", null, null))
    }

    @Test
    fun `getLogoForStation falls back to frequency when ps and pi miss`() {
        val template = makeTemplate(
            name = "FreqOnly",
            stations = listOf(
                StationLogo(
                    frequencies = listOf(99.5f),
                    logoUrl = "x.png",
                    localPath = "/data/freq.png",
                )
            ),
        )
        repo.saveTemplate(template)
        repo.setActiveTemplate("FreqOnly")
        assertEquals("/data/freq.png", repo.getLogoForStation(ps = null, pi = null, frequency = 99.5f))
    }

    // ============ Import / Export ============

    @Test
    fun `exportTemplate strips localPath so the JSON is portable`() {
        val template = makeTemplate(
            name = "ToExport",
            stations = listOf(StationLogo(
                ps = "FM4",
                logoUrl = "https://cdn/fm4.png",
                localPath = "/device-specific/fm4.png", // must be stripped
            )),
        )
        val json = repo.exportTemplate(template)
        assertFalse("device-specific path must not leak", json.contains("device-specific"))
        // Round-trip back: localPath should be null.
        val reimported = repo.importTemplate(json)
        assertNull(reimported.stations.firstOrNull()?.localPath)
    }
}
