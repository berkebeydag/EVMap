package com.berke.ioniqscope.charging

import org.json.JSONObject
import java.util.Locale

/** A driveable route to somewhere, as the road actually goes. */
data class Route(
    /** Ordered lat/lon pairs along the road. */
    val points: List<Pair<Double, Double>>,
    val metres: Double,
    val seconds: Double
)

/**
 * Road routes from OSRM.
 *
 * Note what this costs in privacy: unlike map tiles, which only reveal roughly what
 * area is being looked at, a routing request sends the user's own position and where
 * they intend to go to a third party. That is unavoidable for drawing a real route —
 * the alternative is a straight line, which is not what was asked for — but it is a
 * step beyond everything else the app does, so the map screen says it is happening
 * and the feature does nothing until the user asks for it.
 *
 * The public OSRM instance is a demo service with no key and no account, and no
 * uptime promise either. A failed route is therefore an expected outcome rather than
 * an error worth interrupting anyone over: the caller draws what it got and leaves
 * the rest alone.
 */
class RouteService {

    suspend fun route(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): Route? = runCatching {
        val url = String.format(
            Locale.US,
            "%s/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&alternatives=false&steps=false",
            ENDPOINT, fromLon, fromLat, toLon, toLat
        )

        val root = JSONObject(Http.get(url))
        if (root.optString("code") != "Ok") return@runCatching null

        val route = root.optJSONArray("routes")?.optJSONObject(0)
            ?: return@runCatching null
        val coordinates = route.optJSONObject("geometry")?.optJSONArray("coordinates")
            ?: return@runCatching null

        // GeoJSON is lon,lat — the opposite order to everything else here, and a
        // silent source of routes drawn in the Indian Ocean if taken at face value.
        val points = ArrayList<Pair<Double, Double>>(coordinates.length())
        for (i in 0 until coordinates.length()) {
            val pair = coordinates.optJSONArray(i) ?: continue
            points += pair.optDouble(1) to pair.optDouble(0)
        }
        if (points.size < 2) return@runCatching null

        Route(points, route.optDouble("distance", 0.0), route.optDouble("duration", 0.0))
    }.getOrNull()

    private companion object {
        const val ENDPOINT = "https://router.project-osrm.org/route/v1/driving"
    }
}
