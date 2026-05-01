package at.planqton.fytfm.deezer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [ClassicalMusicNormalizer]. The maps (composers, key names,
 * compound / individual music terms) have grown large without any executable
 * spec — these tests pin the real current behaviour so future edits don't
 * silently regress the Deezer-search pipeline for classical tracks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ClassicalMusicNormalizerTest {

    // ========== isClassicalFormat ==========

    @Test
    fun `isClassicalFormat true for year range`() {
        assertTrue(ClassicalMusicNormalizer.isClassicalFormat("Mozart (1756-1791)"))
        assertTrue(ClassicalMusicNormalizer.isClassicalFormat("Beethoven (1770)"))
    }

    @Test
    fun `isClassicalFormat true for LASTNAME Firstname pattern`() {
        assertTrue(ClassicalMusicNormalizer.isClassicalFormat("BACH, Johann Sebastian"))
    }

    @Test
    fun `isClassicalFormat true for German classical terms`() {
        assertTrue(ClassicalMusicNormalizer.isClassicalFormat("Klavierkonzert Nr. 2"))
        assertTrue(ClassicalMusicNormalizer.isClassicalFormat("Symphonie in G-Dur"))
        assertTrue(ClassicalMusicNormalizer.isClassicalFormat("Sonate op. 27"))
    }

    @Test
    fun `isClassicalFormat true for known composer name in text`() {
        assertTrue(ClassicalMusicNormalizer.isClassicalFormat("Tschaikowsky - Overture"))
    }

    @Test
    fun `isClassicalFormat false for pop music`() {
        assertFalse(ClassicalMusicNormalizer.isClassicalFormat("The Beatles - Hey Jude"))
        assertFalse(ClassicalMusicNormalizer.isClassicalFormat("Queen - Bohemian Rhapsody"))
    }

    // ========== normalizeArtist ==========

    @Test
    fun `normalizeArtist maps known composer spelling variant to canonical form`() {
        assertEquals("Tchaikovsky", ClassicalMusicNormalizer.normalizeArtist("TSCHAIKOWSKY, PETER ILJITSCH (1840-1893)"))
    }

    @Test
    fun `normalizeArtist maps alternate composer spellings`() {
        assertEquals("Handel", ClassicalMusicNormalizer.normalizeArtist("Händel"))
        assertEquals("Handel", ClassicalMusicNormalizer.normalizeArtist("HAENDEL"))
        assertEquals("Rachmaninoff", ClassicalMusicNormalizer.normalizeArtist("Rachmaninow"))
        assertEquals("Shostakovich", ClassicalMusicNormalizer.normalizeArtist("Schostakowitsch"))
    }

    @Test
    fun `normalizeArtist strips year ranges`() {
        // Unknown composer so the name survives cleanup with year stripped.
        assertEquals("Unknown", ClassicalMusicNormalizer.normalizeArtist("Unknown (1800-1870)"))
    }

    @Test
    fun `normalizeArtist flips LASTNAME comma FIRSTNAME and maps to known variant`() {
        // "BACH, JOHANN SEBASTIAN" → flipped + title-cased → "Johann Sebastian Bach",
        // then the known-variant lookup on "bach" substring collapses to "Bach".
        assertEquals("Bach", ClassicalMusicNormalizer.normalizeArtist("BACH, JOHANN SEBASTIAN"))
    }

    @Test
    fun `normalizeArtist preserves unknown composer but cleans casing`() {
        assertEquals(
            "Unknown Person",
            ClassicalMusicNormalizer.normalizeArtist("UNKNOWN PERSON"),
        )
    }

    // ========== normalizeTitle ==========

    @Test
    fun `normalizeTitle translates compound term klavierkonzert`() {
        val result = ClassicalMusicNormalizer.normalizeTitle("Klavierkonzert Nr. 2 G-Dur, op. 44")
        // op.44 stripped, "klavierkonzert" → "Piano Concerto", "Nr." → "No.",
        // "g-dur" → "G Major".
        assertTrue("expected 'Piano Concerto' in '$result'", result.contains("Piano Concerto"))
        assertTrue("expected 'No. 2' in '$result'", result.contains("No. 2"))
        assertTrue("expected 'G Major' in '$result'", result.contains("G Major"))
        assertFalse("opus number should be stripped", result.contains("op. 44", ignoreCase = true))
    }

    @Test
    fun `normalizeTitle strips BWV catalog numbers`() {
        val result = ClassicalMusicNormalizer.normalizeTitle("Toccata und Fuge BWV 565")
        assertFalse("BWV reference should be stripped", result.contains("BWV", ignoreCase = true))
    }

    @Test
    fun `normalizeTitle strips KV catalog numbers`() {
        val result = ClassicalMusicNormalizer.normalizeTitle("Jupiter-Sinfonie KV 551")
        assertFalse("KV reference should be stripped", result.contains("KV", ignoreCase = true))
    }

    @Test
    fun `normalizeTitle translates key names correctly`() {
        assertTrue(
            ClassicalMusicNormalizer.normalizeTitle("Sinfonie in C-Dur").contains("C Major")
        )
        assertTrue(
            ClassicalMusicNormalizer.normalizeTitle("Sonate in A-Moll").contains("A Minor")
        )
        assertTrue(
            ClassicalMusicNormalizer.normalizeTitle("Konzert in Es-Dur").contains("E-flat Major")
        )
    }

    @Test
    fun `normalizeTitle translates standalone form names`() {
        assertTrue(ClassicalMusicNormalizer.normalizeTitle("Walzer").contains("Waltz"))
        assertTrue(ClassicalMusicNormalizer.normalizeTitle("Rhapsodie").contains("Rhapsody"))
    }

    @Test
    fun `normalizeTitle replaces Nr with No`() {
        assertTrue(ClassicalMusicNormalizer.normalizeTitle("Sinfonie Nr. 5").contains("No. 5"))
    }

    @Test
    fun `normalizeTitle does not produce double dots for No 5 suffix`() {
        val result = ClassicalMusicNormalizer.normalizeTitle("Sinfonie Nr. 5")
        assertFalse("'No..' should not appear", result.contains("No.."))
    }

    // ========== getSearchVariations ==========

    @Test
    fun `getSearchVariations includes normalized and a concerto-only simplification`() {
        val variations = ClassicalMusicNormalizer.getSearchVariations(
            "TSCHAIKOWSKY",
            "Klavierkonzert Nr. 1",
        )
        val asSet = variations.map { "${it.first}|${it.second}" }.toSet()

        assertTrue(
            "should include normalized pair: $asSet",
            asSet.any { it.startsWith("Tchaikovsky|") && it.contains("Piano Concerto") },
        )
        // Bare-composer fallback ("just the artist, empty title") must be present.
        assertTrue("should include composer-only fallback: $asSet", asSet.contains("Tchaikovsky|"))
    }

    @Test
    fun `getSearchVariations de-duplicates identical pairs`() {
        val variations = ClassicalMusicNormalizer.getSearchVariations("Mozart", "Allegro")
        val distinctKeys = variations.map { "${it.first.lowercase()}|${it.second.lowercase()}" }
        assertEquals(distinctKeys.size, distinctKeys.toSet().size)
    }

}
