package at.planqton.fytfm.data.logo

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure string-/JSON-/XML-parsing helpers for [LogoSearchService]. Extracted
 * so the fragile regex paths can be unit-tested in isolation, without
 * spinning up Robolectric or hitting the network.
 *
 * All functions are stateless and side-effect free. They MUST NOT log or
 * throw — return null for "no match" / "malformed input" instead, since
 * the caller's HTTP wrappers already swallow exceptions and treat null as
 * "try the next strategy".
 */
internal object LogoParsers {

    /**
     * Builds the RadioDNS FQDN for a DAB service:
     * `0.<sIdHex>.<eIdHex>.<gcc>.dab.radiodns.org` (lowercase, 4-digit hex).
     * Service Component ID is hard-coded to 0 (primary audio), matching
     * the broadcaster convention.
     */
    fun buildRadioDnsFqdn(serviceId: Int, ensembleId: Int, gcc: String): String {
        val sIdHex = "%04x".format(serviceId).lowercase()
        val eIdHex = "%04x".format(ensembleId).lowercase()
        return "0.$sIdHex.$eIdHex.$gcc.dab.radiodns.org"
    }

    /**
     * Extracts the CNAME target from a Google DNS-over-HTTPS JSON response.
     * Returns the trimmed name (no trailing dot) or null if there is no
     * Answer section or no record of type 5 (CNAME).
     */
    fun parseDohCnameAnswer(json: String?): String? {
        if (json.isNullOrBlank()) return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (!obj.has("Answer")) return null
        val answers = runCatching { obj.getJSONArray("Answer") }.getOrNull() ?: return null
        for (i in 0 until answers.length()) {
            val answer = answers.optJSONObject(i) ?: continue
            // Type 5 = CNAME
            if (answer.optInt("type") == 5) {
                val data = answer.optString("data").takeIf { it.isNotBlank() } ?: continue
                return data.trimEnd('.')
            }
        }
        return null
    }

    /**
     * Pulls the best logo URL out of a RadioDNS SI.xml document.
     *
     * Strategy: locate `<service>…</service>` blocks containing the
     * service-id (hex, case-insensitive), then pick the `<multimedia
     * url="…" width="…">` with the largest `width`. Falls back to the
     * first `<multimedia url="…">` anywhere in the document if no service
     * matches — broadcasters with a single-service ensemble often omit a
     * service-id on their multimedia entries.
     */
    fun parseSiXmlForServiceLogo(xml: String?, serviceId: Int): String? {
        if (xml.isNullOrBlank()) return null

        val sIdHex = "%04X".format(serviceId)
        val sIdHexLower = sIdHex.lowercase()

        val servicePattern = Regex("""<service[^>]*>.*?</service>""", RegexOption.DOT_MATCHES_ALL)

        for (service in servicePattern.findAll(xml)) {
            val serviceXml = service.value
            val matches = serviceXml.contains(sIdHex, ignoreCase = true) ||
                serviceXml.contains(sIdHexLower, ignoreCase = true) ||
                serviceXml.contains("sid:$sIdHex", ignoreCase = true) ||
                serviceXml.contains("\"$sIdHex\"", ignoreCase = true)
            if (!matches) continue

            val logos = MULTIMEDIA_TAG.findAll(serviceXml).mapNotNull { match ->
                val tag = match.value
                val url = URL_ATTR.find(tag)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val width = WIDTH_ATTR.find(tag)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                url to width
            }.toList()
            if (logos.isEmpty()) continue

            return logos.maxByOrNull { it.second }?.first
        }

        // Document-wide fallback for single-service ensembles.
        return MULTIMEDIA_TAG.findAll(xml)
            .mapNotNull { URL_ATTR.find(it.value)?.groupValues?.getOrNull(1)?.takeIf { u -> u.isNotBlank() } }
            .firstOrNull()
    }

    private val MULTIMEDIA_TAG = Regex("""<multimedia\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val URL_ATTR = Regex("""\burl="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val WIDTH_ATTR = Regex("""\bwidth="(\d+)"""", RegexOption.IGNORE_CASE)

    /**
     * Search-name normalisation matching [LogoSearchService.tryRadioBrowserSearch]:
     * strips characters outside `[a-zA-Z0-9äöüÄÖÜß\s]`, trims whitespace.
     * Returns null if the cleaned name is shorter than 2 chars (the
     * radio-browser API would return noise for 1-char queries).
     */
    fun cleanStationNameForSearch(stationName: String?): String? {
        if (stationName.isNullOrBlank()) return null
        val cleaned = stationName
            .replace(Regex("[^a-zA-Z0-9äöüÄÖÜß\\s]"), "")
            .trim()
        return cleaned.takeIf { it.length >= 2 }
    }

    /**
     * Generates the de-duplicated list of search variants the service
     * tries against radio-browser.info: full name, first word only, and
     * spaces stripped. Order is preserved so the caller hits "exact" first.
     */
    fun buildSearchVariants(cleanedName: String): List<String> {
        return listOf(
            cleanedName,
            cleanedName.split(" ").firstOrNull() ?: cleanedName,
            cleanedName.replace(" ", ""),
        ).distinct()
    }

    /**
     * Three-pass favicon picker over a radio-browser.info JSON array (one
     * URL's response). Mirrors the production pass order so swapping the
     * implementation keeps behaviour identical:
     *  1. station name contains [searchName] AND `favicon` is non-blank
     *  2. (skipped — homepage-favicon pass needs HTTP, see [LogoSearchService])
     *  3. first entry with any non-blank favicon (fallback)
     *
     * Returns null when nothing usable is in the array. The literal string
     * "null" is treated as missing (the API serialises null values that way).
     */
    fun pickFaviconFromRadioBrowserJson(json: String?, searchName: String): String? {
        if (json.isNullOrBlank()) return null
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return null
        if (array.length() == 0) return null

        // Pass 1: name contains searchName AND has favicon
        for (i in 0 until array.length()) {
            val station = array.optJSONObject(i) ?: continue
            val name = station.optString("name", "")
            val favicon = station.optString("favicon", "")
            if (favicon.isNotBlank() && favicon != "null" &&
                name.contains(searchName, ignoreCase = true)
            ) {
                return favicon
            }
        }

        // Pass 3: first entry with favicon (pass 2 needs HTTP — skipped here)
        for (i in 0 until array.length()) {
            val station = array.optJSONObject(i) ?: continue
            val favicon = station.optString("favicon", "")
            if (favicon.isNotBlank() && favicon != "null") return favicon
        }

        return null
    }

    /**
     * Returns the candidate favicon URLs to probe under a station's
     * homepage, in the production order (.ico → .png → apple-touch).
     * Trailing slashes on the homepage URL are removed.
     */
    fun buildFaviconCandidates(homepageUrl: String?): List<String> {
        if (homepageUrl.isNullOrBlank()) return emptyList()
        val baseUrl = homepageUrl.trimEnd('/')
        return listOf(
            "$baseUrl/favicon.ico",
            "$baseUrl/favicon.png",
            "$baseUrl/apple-touch-icon.png",
            "$baseUrl/apple-touch-icon-precomposed.png",
        )
    }
}
