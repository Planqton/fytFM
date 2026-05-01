package at.planqton.fytfm.data.logo

import android.util.Log
import at.planqton.fytfm.ui.ImageResult
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Scrapes DuckDuckGo image search results for use as manual station-logo
 * candidates. Two-step flow: GET the search page to find the `vqd` token,
 * then call the i.js JSON endpoint. Falls back to direct HTML image URL
 * extraction when the token can't be found (DDG has changed the payload
 * shape several times; the fallback keeps it working on bad days).
 *
 * Extracted from MainActivity so the scraping heuristics can evolve with
 * their own tests and without dragging in an Activity dependency.
 */
class DuckDuckGoImageSearch {

    companion object {
        private const val TAG = "DuckDuckGoImageSearch"
        private const val MAX_RESULTS = 30
        private val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val VQD_PATTERNS = listOf(
            Regex("""vqd=["']([^"']+)["']"""),
            Regex("""vqd=([0-9]+-[0-9]+)"""),
            Regex(""""vqd":"([^"]+)""""),
            Regex("""vqd%3D([^&"']+)"""),
        )
        private val DIRECT_IMAGE_PATTERN = Regex(""""ou":"(https?://[^"]+)"""")
        private val IMAGE_EXTENSIONS = listOf(".jpg", ".png", ".jpeg", ".webp")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Runs the search on a background thread and posts the list via [callback].
     * An empty list is delivered on any error — failure is not silently
     * swallowed, it becomes "no results".
     */
    fun search(query: String, callback: (List<ImageResult>) -> Unit) {
        Thread {
            val results = try {
                runBlockingSearch(query)
            } catch (e: Exception) {
                Log.e(TAG, "DDG image search failed for '$query': ${e.message}", e)
                emptyList()
            }
            callback(results)
        }.start()
    }

    private fun runBlockingSearch(query: String): List<ImageResult> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val html = fetchSearchPage(encodedQuery)
        Log.d(TAG, "DDG page HTML length: ${html.length}")

        val vqd = findVqdToken(html)
        if (vqd != null) {
            Log.d(TAG, "Found vqd token: $vqd")
            val fromApi = fetchFromJsonApi(query, encodedQuery, vqd)
            if (fromApi.isNotEmpty()) return fromApi
        } else {
            Log.d(TAG, "No vqd token — falling back to direct HTML extraction")
        }

        // Fallback path — extract image URLs embedded in the page HTML.
        return extractImagesFromHtml(html, query)
    }

    private fun fetchSearchPage(encodedQuery: String): String {
        val tokenUrl = "https://duckduckgo.com/?q=$encodedQuery&iax=images&ia=images"
        val request = Request.Builder()
            .url(tokenUrl)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .build()
        return client.newCall(request).execute().use { it.body?.string() ?: "" }
    }

    private fun fetchFromJsonApi(
        query: String,
        encodedQuery: String,
        vqd: String,
    ): List<ImageResult> {
        val imageUrl = "https://duckduckgo.com/i.js?l=de-de&o=json&q=$encodedQuery&vqd=$vqd&f=,,,,,&p=1"
        val request = Request.Builder()
            .url(imageUrl)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("Referer", "https://duckduckgo.com/")
            .build()

        val jsonStr = client.newCall(request).execute().use { it.body?.string() ?: "" }
        val results = parseJsonResults(jsonStr, fallbackName = query)
        Log.d(TAG, "DDG JSON API returned ${results.size} result(s)")
        return results
    }

    /** Extracts the first vqd token from the DDG landing-page HTML; null when
     *  none of the known patterns match. Package-private for testing. */
    internal fun findVqdToken(html: String): String? =
        VQD_PATTERNS.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }

    /** Parses the DDG `i.js` JSON response into [ImageResult]s. Returns empty
     *  on empty/malformed input (the caller treats that as "no results"). */
    internal fun parseJsonResults(jsonStr: String, fallbackName: String): List<ImageResult> {
        if (jsonStr.isEmpty()) return emptyList()
        val resultsArray = try {
            JSONObject(jsonStr).optJSONArray("results") ?: return emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse DDG JSON: ${e.message}")
            return emptyList()
        }
        val out = mutableListOf<ImageResult>()
        val limit = minOf(resultsArray.length(), MAX_RESULTS)
        for (i in 0 until limit) {
            val item = resultsArray.getJSONObject(i)
            val imgUrl = item.optString("image", "")
            val thumbnail = item.optString("thumbnail", "")
            val title = item.optString("title", fallbackName)
            // Prefer thumbnail (smaller, faster to load)
            val finalUrl = if (thumbnail.isNotBlank()) thumbnail else imgUrl
            if (finalUrl.isNotBlank() && finalUrl.startsWith("http")) {
                out += ImageResult(url = finalUrl, name = title)
            }
        }
        return out
    }

    /** Extracts image URLs directly from the DDG landing-page HTML using the
     *  `"ou":"…"`-style fields DDG occasionally embeds. Fallback when the
     *  JSON API can't be reached via [findVqdToken]. */
    internal fun extractImagesFromHtml(html: String, query: String): List<ImageResult> {
        val out = mutableListOf<ImageResult>()
        for (match in DIRECT_IMAGE_PATTERN.findAll(html).take(MAX_RESULTS)) {
            val url = match.groupValues[1]
                .replace("\\u002F", "/")
                .replace("\\/", "/")
            if (IMAGE_EXTENSIONS.any { url.contains(it) }) {
                out += ImageResult(url = url, name = query)
            }
        }
        Log.d(TAG, "Direct HTML extraction returned ${out.size} result(s)")
        return out
    }
}
