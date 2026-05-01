package at.planqton.fytfm.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [VersionComparator]. No Android dependencies — pure JUnit.
 *
 * The corner cases below pin behaviour the production code currently
 * has (deliberately or not). If we ever decide to teach the comparator
 * about pre-release suffixes (`-beta`, `-rc.1`), these tests will be
 * the contract that flips.
 */
class VersionComparatorTest {

    @Test
    fun `equal versions are not newer (no self-update prompt)`() {
        assertFalse(VersionComparator.isNewer("1.0.0", "1.0.0"))
        assertFalse(VersionComparator.isNewer("2.5.13", "2.5.13"))
    }

    @Test
    fun `larger major version is newer`() {
        assertTrue(VersionComparator.isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun `larger minor version is newer when major is equal`() {
        assertTrue(VersionComparator.isNewer("1.5.0", "1.4.99"))
    }

    @Test
    fun `larger patch version is newer when major and minor are equal`() {
        assertTrue(VersionComparator.isNewer("1.0.5", "1.0.4"))
    }

    @Test
    fun `older version is not newer (downgrade returns false)`() {
        assertFalse(VersionComparator.isNewer("1.0.0", "1.0.1"))
        assertFalse(VersionComparator.isNewer("0.9.9", "1.0.0"))
    }

    @Test
    fun `numeric comparison treats 10 as larger than 9 (not lexical)`() {
        // "10" > "9" lexically would compare wrong — must use Int.
        assertTrue(VersionComparator.isNewer("1.0.10", "1.0.9"))
        assertTrue(VersionComparator.isNewer("1.10.0", "1.9.99"))
    }

    @Test
    fun `truncated version equals zero-padded version`() {
        // "1.0" → [1,0]; "1.0.0" → [1,0,0]; missing index defaults to 0.
        assertFalse(VersionComparator.isNewer("1.0", "1.0.0"))
        assertFalse(VersionComparator.isNewer("1.0.0", "1.0"))
    }

    @Test
    fun `extra zero-suffix segments do not count as newer`() {
        // "1.0.0.0" vs "1.0.0" → all segments equal, so not newer.
        assertFalse(VersionComparator.isNewer("1.0.0.0", "1.0.0"))
    }

    @Test
    fun `non-numeric segments collapse to 0 (current limitation)`() {
        // Pinned: pre-release suffixes are silently ignored. So "1.0.0-beta"
        // is treated as "1.0.0". A release tagged 1.0.0-rc1 then 1.0.0
        // would NOT register as newer. This is a known behaviour gap; the
        // production update flow uses GitHub's "latest" pointer which never
        // selects a pre-release, so the gap doesn't surface today.
        assertFalse(VersionComparator.isNewer("1.0.0-beta", "1.0.0"))
        assertFalse(VersionComparator.isNewer("1.0.0", "1.0.0-beta"))
    }

    @Test
    fun `empty string segments collapse to 0`() {
        // "1..0" → ["1","","0"] → [1,0,0]
        assertFalse(VersionComparator.isNewer("1..0", "1.0.0"))
    }

    @Test
    fun `single-segment version compared against multi-segment`() {
        // "2" → [2]; "1.99.99" → [1,99,99]. Major wins.
        assertTrue(VersionComparator.isNewer("2", "1.99.99"))
        assertFalse(VersionComparator.isNewer("1", "1.0.1"))
    }

    @Test
    fun `realistic GitHub release jump from 0_2_3 to 1_0_0`() {
        assertTrue(VersionComparator.isNewer("1.0.0", "0.2.3"))
    }

    @Test
    fun `caller is responsible for stripping v prefix (we don't)`() {
        // "v1.0.0".split(".")[0] = "v1" → toIntOrNull → null → 0.
        // So [0,0,0] vs [1,0,0] → current wins → not newer.
        // Test documents that stripping the "v" is the caller's job.
        assertFalse(VersionComparator.isNewer("v1.0.0", "1.0.0"))
    }
}
