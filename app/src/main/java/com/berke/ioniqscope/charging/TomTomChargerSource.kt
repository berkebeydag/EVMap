package com.berke.ioniqscope.charging

import com.berke.ioniqscope.data.ChargingStationEntity
import org.json.JSONObject
import kotlin.math.hypot

/**
 * Charging stations from TomTom's point-of-interest search.
 *
 * Measured against a six-area sample of the bundled dataset: about half of what it
 * returns we did not have, it carries the networks whose own lists are unreachable —
 * Eşarj, Voltrun, Sharz, WAT — and, unlike every other source, it states the power on
 * every single result. Power is known on under a third of the bundled sites.
 *
 * Deliberately *not* part of the bundle that ships inside the APK. TomTom's terms are
 * for use by the licence holder, and the bundle is a file published on a public
 * repository for anyone to download — that would be redistributing their data. Here
 * it works the way Open Charge Map does: the user registers their own free key, the
 * results are fetched with that key and cached on that user's own device, and with no
 * key the source simply stays off.
 */
class TomTomChargerSource(
    private val apiKeyProvider: () -> String?,
    private val now: () -> Long = System::currentTimeMillis
) : ChargerSource {

    override val id = "tomtom"
    override val displayName = "TomTom (ücretsiz anahtar gerekir)"
    override fun isAvailable() = !apiKeyProvider().isNullOrBlank()

    override suspend fun fetch(box: BoundingBox): FetchResult {
        val key = apiKeyProvider()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("TomTom API anahtarı ayarlanmamış")

        val found = LinkedHashMap<String, ChargingStationEntity>()
        var budget = MAX_REQUESTS
        var truncated = false

        // Breadth-first rather than recursive: a box that comes back full is split
        // into quarters and queued, so the request budget is spent evenly over the
        // whole area instead of being exhausted inside the first dense city.
        val queue = ArrayDeque<Pair<BoundingBox, Int>>()
        queue += box to 0

        while (queue.isNotEmpty()) {
            if (budget <= 0) {
                truncated = true
                break
            }
            val (area, depth) = queue.removeFirst()

            val radius = area.radiusMetres()
            if (radius > MAX_RADIUS_M && depth < MAX_DEPTH) {
                queue += area.quarters().map { it to depth + 1 }
                continue
            }

            budget--
            val results = query(key, area, radius.coerceAtMost(MAX_RADIUS_M))
            results.forEach { found[it.sourceId] = it }

            // A full page means the area holds more than was returned, so the answer
            // for it is incomplete until it is split. Without this, a city returns
            // its first hundred and the rest silently do not exist.
            if (results.size >= PAGE_SIZE && depth < MAX_DEPTH) {
                queue += area.quarters().map { it to depth + 1 }
            } else if (results.size >= PAGE_SIZE) {
                truncated = true
            }
        }

        return FetchResult(found.values.toList(), complete = !truncated)
    }

    private suspend fun query(key: String, box: BoundingBox, radius: Double): List<ChargingStationEntity> {
        val url = "https://api.tomtom.com/search/2/nearbySearch/.json" +
            "?key=$key" +
            "&lat=${box.centreLat()}&lon=${box.centreLon()}" +
            "&radius=${radius.toInt()}" +
            "&categorySet=$EV_CATEGORY" +
            "&limit=$PAGE_SIZE" +
            "&countrySet=TR"

        val results = JSONObject(Http.get(url)).optJSONArray("results") ?: return emptyList()
        val timestamp = now()
        val out = ArrayList<ChargingStationEntity>(results.length())

        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val position = r.optJSONObject("position") ?: continue
            val lat = position.optDouble("lat", Double.NaN)
            val lon = position.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue

            val poi = r.optJSONObject("poi")
            val connectors = r.optJSONObject("chargingPark")?.optJSONArray("connectors")

            val types = linkedSetOf<String>()
            var maxKw: Double? = null
            var sawDc = false
            var sawAc = false
            val sockets = connectors?.length() ?: 0

            for (c in 0 until (connectors?.length() ?: 0)) {
                val connector = connectors?.optJSONObject(c) ?: continue
                connector.optString("connectorType").takeIf { it.isNotBlank() }?.let(types::add)
                val kw = connector.optDouble("ratedPowerKW", Double.NaN)
                if (!kw.isNaN() && kw > 0) maxKw = maxOf(maxKw ?: 0.0, kw)
                // "DC", "AC1", "AC3" — the prefix is the part that matters.
                when {
                    connector.optString("currentType").startsWith("DC") -> sawDc = true
                    connector.optString("currentType").startsWith("AC") -> sawAc = true
                }
            }

            out += ChargingStationEntity(
                sourceId = "tomtom:${r.optString("id")}",
                source = id,
                name = poi?.optString("name")?.ifBlank { null },
                operator = poi?.optJSONArray("brands")?.optJSONObject(0)
                    ?.optString("name")?.ifBlank { null }
                    ?: poi?.optString("name")?.ifBlank { null },
                lat = lat,
                lon = lon,
                connectors = types.takeIf { it.isNotEmpty() }?.joinToString(", "),
                maxPowerKw = maxKw,
                isDc = when {
                    sawDc -> true
                    sawAc -> false
                    else -> null
                },
                address = r.optJSONObject("address")?.optString("freeformAddress")
                    ?.ifBlank { null },
                fetchedAtEpochMs = timestamp,
                chargePoints = sockets.takeIf { it in 1..PLAUSIBLE_MAX_CHARGE_POINTS }
            )
        }
        return out
    }

    private fun BoundingBox.centreLat() = (minLat + maxLat) / 2
    private fun BoundingBox.centreLon() = (minLon + maxLon) / 2

    /** Half the diagonal, so the circle covers the corners of the box. */
    private fun BoundingBox.radiusMetres(): Double {
        val height = (maxLat - minLat) * 111_320.0
        val width = (maxLon - minLon) * 111_320.0 *
            kotlin.math.cos(Math.toRadians(centreLat()))
        return hypot(height, width) / 2
    }

    private fun BoundingBox.quarters(): List<BoundingBox> {
        val midLat = centreLat()
        val midLon = centreLon()
        return listOf(
            BoundingBox(minLat, minLon, midLat, midLon),
            BoundingBox(minLat, midLon, midLat, maxLon),
            BoundingBox(midLat, minLon, maxLat, midLon),
            BoundingBox(midLat, midLon, maxLat, maxLon)
        )
    }

    private companion object {
        /** TomTom's category id for an electric vehicle charging station. */
        const val EV_CATEGORY = 7309
        const val PAGE_SIZE = 100
        const val MAX_RADIUS_M = 50_000.0

        /**
         * Ceiling on requests per refresh.
         *
         * The free allowance is a monthly budget, and a country-sized box split until
         * every city stops coming back full would eat a large slice of it in one tap.
         * Sixty covers a region thoroughly; past that the result is reported as
         * incomplete, which the caller already knows means "merge, do not replace".
         */
        const val MAX_REQUESTS = 60
        const val MAX_DEPTH = 5
    }
}
