package com.berke.ioniqscope.charging

import com.berke.ioniqscope.data.ChargingStationEntity
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Charging stations from OpenStreetMap via the Overpass API.
 *
 * Free, no key, no account — which is why it is the default. The data is ODbL
 * licensed, so anything published from it needs attribution.
 *
 * Known limitation, measured rather than assumed: within the Türkiye boundary this
 * returns roughly 650 stations, the large networks are heavily under-reported (ZES
 * appears with a small fraction of its real footprint), and only about one entry in
 * seven carries a DC connector tag. It is a usable base layer, not a complete
 * picture, and the UI says so.
 */
class OsmChargerSource(
    private val now: () -> Long = System::currentTimeMillis
) : ChargerSource {

    override val id = "osm"
    override val displayName = "OpenStreetMap (no key needed)"
    override fun isAvailable() = true

    override suspend fun fetch(box: BoundingBox): List<ChargingStationEntity> {
        val stations = LinkedHashMap<String, ChargingStationEntity>()
        var lastError: Exception? = null

        // One country-wide query completes in about twenty seconds once the area
        // filter is in play, so there is no need to split it up.
        for (endpoint in ENDPOINTS) {
            try {
                parse(Http.postForm(endpoint, "data=" + encode(query(box))), stations)
                return stations.values.toList()
            } catch (e: Exception) {
                lastError = e
            }
        }

        // Both mirrors refused the single query; fall back to strips, which are
        // individually small enough that a busy server will usually still serve them.
        for (strip in splitLongitude(box, STRIP_DEGREES)) {
            for (endpoint in ENDPOINTS) {
                try {
                    parse(Http.postForm(endpoint, "data=" + encode(query(strip))), stations)
                    break
                } catch (e: Exception) {
                    lastError = e
                }
            }
        }

        if (stations.isEmpty() && lastError != null) throw lastError
        return stations.values.toList()
    }

    /**
     * Constrained to the Türkiye boundary relation, not just a bounding box.
     *
     * A box around Türkiye also catches Greece, Bulgaria, Cyprus and a slice of the
     * Caucasus and Levant — measured, that was roughly half of everything returned.
     * Addressing the area by relation id is also far cheaper than making Overpass
     * resolve it from an ISO tag, which times out on the public instances.
     */
    private fun query(box: BoundingBox) =
        "[out:json][timeout:$QUERY_TIMEOUT_S];" +
            "area($TURKEY_AREA_ID)->.country;" +
            // nwr, not node: a couple of dozen Turkish stations are mapped as areas
            // rather than points, and `out center` gives those a usable coordinate.
            "nwr[\"amenity\"=\"charging_station\"](area.country)" +
            "(${box.minLat},${box.minLon},${box.maxLat},${box.maxLon});" +
            "out tags center;"

    private fun parse(json: String, into: MutableMap<String, ChargingStationEntity>) {
        val elements = JSONObject(json).optJSONArray("elements") ?: return
        val timestamp = now()

        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue

            // Nodes carry lat/lon directly; ways and relations get theirs from the
            // `center` block that `out center` adds.
            val centre = element.optJSONObject("center")
            val lat = element.optDouble("lat", centre?.optDouble("lat", Double.NaN) ?: Double.NaN)
            val lon = element.optDouble("lon", centre?.optDouble("lon", Double.NaN) ?: Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue

            val tags = element.optJSONObject("tags") ?: JSONObject()
            // Ids are only unique per element type, so a way and a node can share one.
            val sourceId = "osm:${element.optString("type", "node")}:${element.optLong("id")}"

            into[sourceId] = ChargingStationEntity(
                sourceId = sourceId,
                source = id,
                name = tags.optString("name").ifBlank { null },
                operator = normaliseOperator(
                    tags.optString("operator").ifBlank { null }
                        ?: tags.optString("brand").ifBlank { null }
                        ?: tags.optString("network").ifBlank { null }
                ),
                lat = lat,
                lon = lon,
                connectors = connectorsOf(tags),
                maxPowerKw = powerOf(tags),
                isDc = dcOf(tags),
                address = addressOf(tags),
                fetchedAtEpochMs = timestamp
            )
        }
    }

    /** OSM spells the same operator several ways; fold the common Turkish ones. */
    private fun normaliseOperator(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val upper = value.uppercase()
            .replace('İ', 'I').replace('Ş', 'S').replace('Ğ', 'G')
            .replace('Ü', 'U').replace('Ö', 'O').replace('Ç', 'C')
        return KNOWN_OPERATORS.firstOrNull { upper.contains(it) } ?: value
    }

    private fun connectorsOf(tags: JSONObject): String? {
        val found = tags.keys().asSequence()
            .filter { it.startsWith("socket:") && !it.contains(":output") }
            .mapNotNull { key ->
                val count = tags.optString(key)
                // "no" means explicitly absent, not present-with-unknown-count.
                if (count.equals("no", true) || count.isBlank()) null
                else key.removePrefix("socket:")
            }
            .distinct()
            .toList()
        return found.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /**
     * Power in kW. OSM records it inconsistently — "50 kW", "50000 W", "50" — and
     * sometimes per socket. Anything unparseable stays null rather than guessed.
     */
    private fun powerOf(tags: JSONObject): Double? {
        val candidates = tags.keys().asSequence()
            .filter { it == "charge" || it == "maxpower" || it.endsWith(":output") }
            .mapNotNull { parsePower(tags.optString(it)) }
            .toList()
        return candidates.maxOrNull()
    }

    private fun parsePower(raw: String?): Double? {
        val text = raw?.trim()?.lowercase() ?: return null
        val number = POWER_PATTERN.find(text)?.value?.toDoubleOrNull() ?: return null
        return when {
            "kw" in text -> number
            "w" in text && "kw" !in text -> number / 1000.0
            else -> number
        }.takeIf { it > 0 && it < 1000 }
    }

    /** Null, not false, when nothing indicates the current type either way. */
    private fun dcOf(tags: JSONObject): Boolean? {
        val keys = tags.keys().asSequence().toList()
        val dc = keys.any { key ->
            (key.startsWith("socket:") &&
                DC_CONNECTORS.any { key.contains(it) } &&
                !tags.optString(key).equals("no", true))
        }
        if (dc) return true
        val ac = keys.any { key ->
            key.startsWith("socket:") && AC_CONNECTORS.any { key.contains(it) } &&
                !tags.optString(key).equals("no", true)
        }
        return if (ac) false else null
    }

    private fun addressOf(tags: JSONObject): String? {
        val parts = listOfNotNull(
            tags.optString("addr:street").ifBlank { null },
            tags.optString("addr:housenumber").ifBlank { null },
            tags.optString("addr:district").ifBlank { null },
            tags.optString("addr:city").ifBlank { null },
            tags.optString("addr:province").ifBlank { null }
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun splitLongitude(box: BoundingBox, step: Double): List<BoundingBox> {
        val result = mutableListOf<BoundingBox>()
        var west = box.minLon
        while (west < box.maxLon) {
            val east = minOf(west + step, box.maxLon)
            result += box.copy(minLon = west, maxLon = east)
            west = east
        }
        return result
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private companion object {
        val ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )
        const val QUERY_TIMEOUT_S = 120
        /** Narrow enough that a strip completes inside the public timeout. */
        const val STRIP_DEGREES = 4.0
        /** OSM relation 174737 (Türkiye); Overpass area ids are 3600000000 + relation. */
        const val TURKEY_AREA_ID = 3600174737L
        val POWER_PATTERN = Regex("""\d+(\.\d+)?""")
        val DC_CONNECTORS = listOf("combo", "ccs", "chademo", "tesla_supercharger")
        val AC_CONNECTORS = listOf("type2", "schuko", "type1", "typee")
        val KNOWN_OPERATORS = listOf(
            "SHARZ", "ZES", "ESARJ", "VOLTRUN", "TRUGO", "TESLA",
            "ASTOR", "TOGG", "BORENCO", "WAT MOBILITE", "BEEFULL", "OTOWATT"
        )
    }
}
