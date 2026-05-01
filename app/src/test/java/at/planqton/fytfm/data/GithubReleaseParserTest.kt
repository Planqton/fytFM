package at.planqton.fytfm.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [GithubReleaseParser]. Inputs are real `/releases/latest`
 * shapes from the GitHub REST API (trimmed to the fields the parser
 * touches). Robolectric is required for `org.json.JSONObject`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class GithubReleaseParserTest {

    private fun release(
        tagName: String? = "v1.2.3",
        body: String? = "Release notes",
        assets: List<JSONObject> = listOf(apkAsset()),
    ): JSONObject = JSONObject().apply {
        tagName?.let { put("tag_name", it) }
        body?.let { put("body", it) }
        put("assets", org.json.JSONArray().apply { assets.forEach { put(it) } })
    }

    private fun apkAsset(
        name: String = "fytFM-1.2.3.apk",
        url: String = "https://github.com/.../fytFM-1.2.3.apk",
        size: Long = 12_345_678L,
    ): JSONObject = JSONObject().apply {
        put("name", name)
        put("browser_download_url", url)
        put("size", size)
    }

    @Test
    fun `parse extracts tag, notes, apk url and size`() {
        val r = GithubReleaseParser.parse(release())
        assertNotNull(r)
        assertEquals("1.2.3", r!!.tagName)
        assertEquals("Release notes", r.releaseNotes)
        assertEquals("https://github.com/.../fytFM-1.2.3.apk", r.apkDownloadUrl)
        assertEquals(12_345_678L, r.apkFileSize)
    }

    @Test
    fun `parse strips lowercase v prefix from tag`() {
        val r = GithubReleaseParser.parse(release(tagName = "v0.5.0"))
        assertEquals("0.5.0", r!!.tagName)
    }

    @Test
    fun `parse strips uppercase V prefix from tag`() {
        // Some GitHub repos tag with capital V — should be tolerated.
        val r = GithubReleaseParser.parse(release(tagName = "V2.0.0"))
        assertEquals("2.0.0", r!!.tagName)
    }

    @Test
    fun `parse keeps tags without a v prefix verbatim`() {
        val r = GithubReleaseParser.parse(release(tagName = "1.5.0"))
        assertEquals("1.5.0", r!!.tagName)
    }

    @Test
    fun `parse picks the first apk asset and skips non-apk assets`() {
        val mixed = release(assets = listOf(
            apkAsset(name = "fytFM.aab", url = "https://x/aab"),     // wrong ext
            apkAsset(name = "checksums.txt", url = "https://x/txt"), // wrong ext
            apkAsset(name = "fytFM-1.2.3.apk", url = "https://x/apk"),
            apkAsset(name = "fytFM-1.2.3-debug.apk", url = "https://x/apk-debug"), // not picked (first wins)
        ))
        val r = GithubReleaseParser.parse(mixed)
        assertEquals("https://x/apk", r!!.apkDownloadUrl)
    }

    @Test
    fun `parse returns null when no apk asset exists`() {
        val noApk = release(assets = listOf(
            apkAsset(name = "fytFM.aab"),
            apkAsset(name = "checksums.txt"),
        ))
        assertNull(GithubReleaseParser.parse(noApk))
    }

    @Test
    fun `parse returns null when assets array is empty`() {
        assertNull(GithubReleaseParser.parse(release(assets = emptyList())))
    }

    @Test
    fun `parse returns null when assets array is missing entirely`() {
        val noAssets = JSONObject().apply { put("tag_name", "v1.0.0") }
        assertNull(GithubReleaseParser.parse(noAssets))
    }

    @Test
    fun `parse returns null when tag_name is missing or blank`() {
        val noTag = JSONObject().apply {
            put("assets", org.json.JSONArray().apply { put(apkAsset()) })
        }
        assertNull(GithubReleaseParser.parse(noTag))

        val blankTag = release(tagName = "")
        assertNull(GithubReleaseParser.parse(blankTag))
    }

    @Test
    fun `parse returns null when apk asset has blank download url`() {
        val brokenAsset = release(assets = listOf(apkAsset(url = "")))
        assertNull(GithubReleaseParser.parse(brokenAsset))
    }

    @Test
    fun `parse defaults missing asset size to 0`() {
        val sizelessAsset = JSONObject().apply {
            put("name", "fytFM.apk")
            put("browser_download_url", "https://x/apk")
            // no "size" field
        }
        val r = GithubReleaseParser.parse(release(assets = listOf(sizelessAsset)))
        assertEquals(0L, r!!.apkFileSize)
    }

    @Test
    fun `parse treats empty body as null releaseNotes`() {
        val emptyBody = release(body = "")
        val r = GithubReleaseParser.parse(emptyBody)
        assertNull(r!!.releaseNotes)
    }

    @Test
    fun `parse treats missing body as null releaseNotes`() {
        val noBody = JSONObject().apply {
            put("tag_name", "v1.0.0")
            put("assets", org.json.JSONArray().apply { put(apkAsset()) })
        }
        val r = GithubReleaseParser.parse(noBody)
        assertNull(r!!.releaseNotes)
    }

    @Test
    fun `parse handles multi-line markdown body`() {
        val md = "## What's new\n- feature A\n- bugfix B\n\n## Notes\nfoo"
        val r = GithubReleaseParser.parse(release(body = md))
        assertEquals(md, r!!.releaseNotes)
    }

    @Test
    fun `parse returns null when input JSON is null`() {
        assertNull(GithubReleaseParser.parse(null))
    }

    @Test
    fun `parse skips non-object asset entries gracefully`() {
        // Construct an asset array with a JSON null entry between two real
        // assets. The parser must not crash.
        val arr = org.json.JSONArray().apply {
            put(JSONObject.NULL)
            put(apkAsset())
        }
        val obj = JSONObject().apply {
            put("tag_name", "v1.0.0")
            put("assets", arr)
        }
        val r = GithubReleaseParser.parse(obj)
        assertNotNull(r)
        assertEquals("https://github.com/.../fytFM-1.2.3.apk", r!!.apkDownloadUrl)
    }
}
