package at.planqton.fytfm.data

import org.json.JSONObject

/**
 * Pure parsing helper for the GitHub `/releases/latest` payload that
 * [UpdateRepository] consumes. Extracted so the JSON-shape assumptions
 * (tag_name format, asset filtering by `.apk` suffix, optional body) can
 * be unit-tested without a real HTTP round-trip.
 *
 * Returns null when:
 * - JSON is malformed
 * - no asset with a `.apk` filename exists (i.e. "release without an APK")
 * - required fields (`tag_name`, asset `browser_download_url`) are missing
 *
 * [tagName] has any leading `v`/`V` stripped to match `UpdateRepository`'s
 * semver assumption.
 */
internal object GithubReleaseParser {

    data class Release(
        val tagName: String,
        val releaseNotes: String?,
        val apkDownloadUrl: String,
        val apkFileSize: Long,
    )

    fun parse(json: JSONObject?): Release? {
        if (json == null) return null
        return try {
            val rawTag = json.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
            val tagName = rawTag.removePrefix("v").removePrefix("V")

            val assets = json.optJSONArray("assets") ?: return null
            var apkAsset: JSONObject? = null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name").endsWith(".apk")) {
                    apkAsset = asset
                    break
                }
            }
            apkAsset ?: return null

            val downloadUrl = apkAsset.optString("browser_download_url")
                .takeIf { it.isNotBlank() } ?: return null
            val fileSize = apkAsset.optLong("size", 0L)

            val releaseNotes = json.optString("body").takeIf { it.isNotEmpty() }

            Release(
                tagName = tagName,
                releaseNotes = releaseNotes,
                apkDownloadUrl = downloadUrl,
                apkFileSize = fileSize,
            )
        } catch (e: Exception) {
            null
        }
    }
}
