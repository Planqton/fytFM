package at.planqton.fytfm.data.logo

import android.util.Log
import at.planqton.fytfm.data.RadioStation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * EXPERIMENTAL FEATURE - See docs/EXPERIMENTAL_LOGO_SEARCH.md for removal instructions.
 *
 * Service for searching radio station logos from various sources.
 *
 * For DAB: Uses RadioDNS (via DNS over HTTPS) to lookup station metadata including logos.
 * For all: Falls back to radio-browser.info API search by station name.
 */
class LogoSearchService {

    companion object {
        private const val TAG = "LogoSearchService"
        private const val RADIO_BROWSER_API = "https://de1.api.radio-browser.info/json/stations/byname"
        // Google DNS over HTTPS for CNAME lookups
        private const val DOH_API = "https://dns.google/resolve"

        // Global Country Codes for RadioDNS
        private const val GCC_GERMANY = "de0"
        private const val GCC_AUSTRIA = "at0"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class LogoSearchResult(
        val station: RadioStation,
        val logoUrl: String?,
        val source: String,  // "RadioDNS", "radio-browser.info", "not found"
        val stationName: String  // Display name for results
    )

    /**
     * Search for logos for a list of stations.
     * Returns results for each station with found logo URL or null.
     */
    suspend fun searchLogos(
        stations: List<RadioStation>,
        onProgress: (current: Int, total: Int, stationName: String) -> Unit
    ): List<LogoSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<LogoSearchResult>()

        stations.forEachIndexed { index, station ->
            val stationName = station.name ?: station.ensembleLabel ?: "Unknown"
            onProgress(index + 1, stations.size, stationName)

            val result = if (station.isDab) {
                searchDabLogo(station)
            } else {
                searchFmAmLogo(station)
            }
            results.add(result)
        }

        results
    }

    /**
     * Search logo for DAB station using RadioDNS, fallback to radio-browser.info
     */
    private suspend fun searchDabLogo(station: RadioStation): LogoSearchResult {
        val stationName = station.name ?: station.ensembleLabel ?: "Unknown"

        // Try RadioDNS first
        val radioDnsLogo = tryRadioDnsLookup(station)
        if (radioDnsLogo != null) {
            Log.d(TAG, "RadioDNS found logo for $stationName: $radioDnsLogo")
            return LogoSearchResult(station, radioDnsLogo, "RadioDNS", stationName)
        }

        // Fallback to radio-browser.info
        val radioBrowserLogo = tryRadioBrowserSearch(stationName)
        if (radioBrowserLogo != null) {
            Log.d(TAG, "radio-browser.info found logo for $stationName: $radioBrowserLogo")
            return LogoSearchResult(station, radioBrowserLogo, "radio-browser.info", stationName)
        }

        Log.d(TAG, "No logo found for $stationName")
        return LogoSearchResult(station, null, "not found", stationName)
    }

    /**
     * Search logo for FM/AM station using radio-browser.info
     */
    private suspend fun searchFmAmLogo(station: RadioStation): LogoSearchResult {
        val stationName = station.name ?: return LogoSearchResult(
            station, null, "not found",
            if (station.isAM) "AM ${station.frequency.toInt()}" else "FM %.1f".format(station.frequency)
        )

        val logo = tryRadioBrowserSearch(stationName)
        val displayName = stationName

        return if (logo != null) {
            Log.d(TAG, "radio-browser.info found logo for $stationName: $logo")
            LogoSearchResult(station, logo, "radio-browser.info", displayName)
        } else {
            Log.d(TAG, "No logo found for $stationName")
            LogoSearchResult(station, null, "not found", displayName)
        }
    }

    /**
     * Try RadioDNS lookup for DAB station using DNS over HTTPS.
     * RadioDNS FQDN format: <scId>.<sId>.<eId>.<gcc>.dab.radiodns.org
     */
    private suspend fun tryRadioDnsLookup(station: RadioStation): String? = withContext(Dispatchers.IO) {
        try {
            val serviceId = station.serviceId
            val ensembleId = station.ensembleId

            if (serviceId <= 0 || ensembleId <= 0) {
                Log.d(TAG, "Invalid DAB IDs for RadioDNS lookup: sId=$serviceId, eId=$ensembleId")
                return@withContext null
            }

            // Try both German and Austrian GCC
            for (gcc in listOf(GCC_GERMANY, GCC_AUSTRIA)) {
                val logo = tryRadioDnsWithGcc(serviceId, ensembleId, gcc)
                if (logo != null) return@withContext logo
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "RadioDNS lookup failed: ${e.message}", e)
            null
        }
    }

    private suspend fun tryRadioDnsWithGcc(serviceId: Int, ensembleId: Int, gcc: String): String? {
        try {
            val fqdn = LogoParsers.buildRadioDnsFqdn(serviceId, ensembleId, gcc)

            Log.d(TAG, "RadioDNS lookup: $fqdn")

            // DNS CNAME lookup via DNS over HTTPS (Google)
            val cname = dohCnameLookup(fqdn)
            if (cname == null) {
                Log.d(TAG, "No CNAME record found for $fqdn")
                return null
            }

            Log.d(TAG, "RadioDNS CNAME: $cname")

            // Fetch SI.xml from the broadcaster's server
            val siUrl = "https://$cname/radiodns/spi/3.1/SI.xml"
            return fetchLogoFromSiXml(siUrl, serviceId)

        } catch (e: Exception) {
            Log.d(TAG, "RadioDNS lookup with GCC $gcc failed: ${e.message}")
            return null
        }
    }

    /**
     * Perform DNS CNAME lookup using Google's DNS over HTTPS API.
     * Returns the CNAME target or null if not found.
     */
    private fun dohCnameLookup(fqdn: String): String? {
        return try {
            val url = "$DOH_API?name=$fqdn&type=CNAME"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "DoH lookup failed: HTTP ${response.code}")
                    return null
                }
                LogoParsers.parseDohCnameAnswer(response.body?.string())
            }
        } catch (e: Exception) {
            Log.e(TAG, "DoH lookup failed: ${e.message}", e)
            null
        }
    }

    private fun fetchLogoFromSiXml(siUrl: String, serviceId: Int): String? {
        return try {
            Log.d(TAG, "Fetching SI.xml: $siUrl")

            val request = Request.Builder()
                .url(siUrl)
                .header("User-Agent", "fytFM/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "SI.xml fetch failed: HTTP ${response.code}")
                    return null
                }

                val xml = response.body?.string() ?: return null
                parseLogoFromSiXml(xml, serviceId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch SI.xml: ${e.message}", e)
            null
        }
    }

    private fun parseLogoFromSiXml(xml: String, serviceId: Int): String? {
        return try {
            LogoParsers.parseSiXmlForServiceLogo(xml, serviceId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SI.xml: ${e.message}", e)
            null
        }
    }

    /**
     * Search radio-browser.info API by station name.
     * Returns the favicon URL if found.
     * Also tries to fetch favicon from station homepage if not in database.
     */
    private suspend fun tryRadioBrowserSearch(stationName: String): String? = withContext(Dispatchers.IO) {
        try {
            val cleaned = LogoParsers.cleanStationNameForSearch(stationName) ?: return@withContext null
            for (variant in LogoParsers.buildSearchVariants(cleaned)) {
                val result = searchRadioBrowserVariant(variant)
                if (result != null) return@withContext result
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "radio-browser.info search failed: ${e.message}", e)
            null
        }
    }

    private fun searchRadioBrowserVariant(searchName: String): String? {
        try {
            val encodedName = java.net.URLEncoder.encode(searchName, "UTF-8")

            // Try both general search and country-specific (Austria, Germany)
            val urls = listOf(
                "$RADIO_BROWSER_API/$encodedName?limit=10&hidebroken=true",
                "https://de1.api.radio-browser.info/json/stations/search?name=$encodedName&country=austria&limit=10",
                "https://de1.api.radio-browser.info/json/stations/search?name=$encodedName&country=germany&limit=10"
            )

            for (url in urls) {
                Log.d(TAG, "radio-browser.info search: $url")

                val result = searchSingleUrl(url, searchName)
                if (result != null) return result
            }

            return null
        } catch (e: Exception) {
            Log.d(TAG, "Search variant failed: ${e.message}")
            return null
        }
    }

    private fun searchSingleUrl(url: String, searchName: String): String? {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "fytFM/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null

                val json = response.body?.string() ?: return null
                val array = JSONArray(json)

                if (array.length() == 0) return null

                // First pass: Find exact match with favicon
                for (i in 0 until array.length()) {
                    val station = array.getJSONObject(i)
                    val name = station.optString("name", "")
                    val favicon = station.optString("favicon", "")

                    if (favicon.isNotBlank() && favicon != "null" &&
                        name.contains(searchName, ignoreCase = true)) {
                        Log.d(TAG, "radio-browser.info: Found match '$name' with favicon")
                        return favicon
                    }
                }

                // Second pass: Try to get favicon from homepage
                for (i in 0 until array.length()) {
                    val station = array.getJSONObject(i)
                    val name = station.optString("name", "")
                    val homepage = station.optString("homepage", "")

                    if (homepage.isNotBlank() && name.contains(searchName, ignoreCase = true)) {
                        val faviconFromHomepage = tryFetchFaviconFromHomepage(homepage)
                        if (faviconFromHomepage != null) {
                            Log.d(TAG, "radio-browser.info: Found favicon from homepage for '$name'")
                            return faviconFromHomepage
                        }
                    }
                }

                // Third pass: Any result with favicon
                for (i in 0 until array.length()) {
                    val station = array.getJSONObject(i)
                    val favicon = station.optString("favicon", "")
                    if (favicon.isNotBlank() && favicon != "null") {
                        Log.d(TAG, "radio-browser.info: Using first result with favicon")
                        return favicon
                    }
                }

                return null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Single URL search failed: ${e.message}")
            return null
        }
    }

    /**
     * Try to fetch favicon.ico from a website's homepage.
     */
    private fun tryFetchFaviconFromHomepage(homepageUrl: String): String? {
        return try {
            val faviconUrls = LogoParsers.buildFaviconCandidates(homepageUrl)
            if (faviconUrls.isEmpty()) return null

            for (faviconUrl in faviconUrls) {
                val request = Request.Builder()
                    .url(faviconUrl)
                    .header("User-Agent", "fytFM/1.0")
                    .build()

                try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful &&
                            response.body?.contentType()?.type?.contains("image") == true) {
                            Log.d(TAG, "Found favicon at: $faviconUrl")
                            return faviconUrl
                        }
                    }
                } catch (e: Exception) {
                    // Continue to next URL
                }
            }

            null
        } catch (e: Exception) {
            Log.d(TAG, "Failed to fetch favicon from homepage: ${e.message}")
            null
        }
    }
}
