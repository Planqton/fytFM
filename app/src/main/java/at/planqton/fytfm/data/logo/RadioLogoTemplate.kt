package at.planqton.fytfm.data.logo

import org.json.JSONArray
import org.json.JSONObject

/**
 * Template containing radio station logos for a specific area.
 * Area IDs: 0=USA, 1=Latin America, 2=Europe, 3=Russia, 4=Japan
 */
data class RadioLogoTemplate(
    val name: String,
    val area: Int,           // Radio area ID (0-4)
    val version: Int = 1,
    val stations: List<StationLogo>
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("area", area)
            put("version", version)
            put("stations", JSONArray().apply {
                stations.forEach { put(it.toJson()) }
            })
        }
    }

    companion object {
        fun fromJson(json: JSONObject): RadioLogoTemplate {
            val stationsArray = json.getJSONArray("stations")
            val stations = mutableListOf<StationLogo>()
            for (i in 0 until stationsArray.length()) {
                stations.add(StationLogo.fromJson(stationsArray.getJSONObject(i)))
            }
            return RadioLogoTemplate(
                name = json.getString("name"),
                area = json.optInt("area", 2), // Default to Europe
                version = json.optInt("version", 1),
                stations = stations
            )
        }

        fun fromJsonString(jsonString: String): RadioLogoTemplate {
            return fromJson(JSONObject(jsonString))
        }
    }
}

/**
 * Single station logo entry with matching criteria.
 * Matching priority: PI > PS > Frequency
 */
data class StationLogo(
    val ps: String? = null,           // PS-Name Match (case-insensitive)
    val pi: String? = null,           // PI-Code Match (hex string, e.g., "A3E0")
    val frequencies: List<Float>? = null,  // Frequency Matches (MHz, e.g., [88.8, 90.4, 99.5])
    val logoUrl: String,              // Original URL for download
    var localPath: String? = null     // Local cached file path (set after download)
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            ps?.let { put("ps", it) }
            pi?.let { put("pi", it) }
            frequencies?.let { freqs ->
                put("frequencies", JSONArray().apply {
                    freqs.forEach { put(it.toDouble()) }
                })
            }
            put("logoUrl", logoUrl)
            localPath?.let { put("localPath", it) }
        }
    }

    /**
     * Check if this entry matches the given station parameters.
     * Returns match priority: 3 = PI match, 2 = PS match, 1 = Frequency match, 0 = no match
     */
    fun matchPriority(stationPs: String?, stationPi: Int?, stationFrequency: Float?): Int {
        // PI match (highest priority)
        if (pi != null && stationPi != null) {
            val templatePi = pi.uppercase().removePrefix("0X")
            val stationPiHex = String.format("%04X", stationPi)
            if (templatePi == stationPiHex) return 3
        }

        // PS match
        if (ps != null && stationPs != null) {
            if (ps.equals(stationPs.trim(), ignoreCase = true)) return 2
        }

        // Frequency match (with tolerance ±0.05 MHz) - check all frequencies
        if (frequencies != null && stationFrequency != null) {
            if (frequencies.any { kotlin.math.abs(it - stationFrequency) < 0.05f }) return 1
        }

        return 0
    }

    companion object {
        fun fromJson(json: JSONObject): StationLogo {
            // Parse frequencies - support both old "frequency" (single) and new "frequencies" (array)
            val frequencies: List<Float>? = when {
                json.has("frequencies") -> {
                    val arr = json.getJSONArray("frequencies")
                    (0 until arr.length()).map { arr.getDouble(it).toFloat() }
                }
                json.has("frequency") -> {
                    listOf(json.getDouble("frequency").toFloat())
                }
                else -> null
            }

            return StationLogo(
                ps = json.optString("ps", null).takeIf { !it.isNullOrBlank() },
                pi = json.optString("pi", null).takeIf { !it.isNullOrBlank() },
                frequencies = frequencies,
                logoUrl = json.getString("logoUrl"),
                localPath = json.optString("localPath", null).takeIf { !it.isNullOrBlank() }
            )
        }
    }
}
