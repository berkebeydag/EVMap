package com.berke.ioniqscope.charging

import com.berke.ioniqscope.data.ChargingStationEntity
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.floor
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
    /** Where the unfinished part of a sweep is remembered between runs. */
    private val progress: SweepProgress,
    /**
     * Coordinates already known, used to decide where to look.
     *
     * Measured: tiling the country's bounding box down to the 50 km query radius is
     * 1,024 requests before a single station is found, most of them spent on sea,
     * empty steppe and the neighbouring countries. Tiling the 6,102 coordinates we
     * already hold covers every one of them in 304, and anywhere a charger exists in
     * Türkiye, one of those is within 50 km.
     */
    private val knownPoints: suspend () -> List<Pair<Double, Double>>,
    private val now: () -> Long = System::currentTimeMillis
) : ChargerSource {

    /**
     * The boxes a sweep has not got to yet.
     *
     * A full sweep of Türkiye measured at over 1,200 requests against a free
     * allowance of 2,500 a month, so a run that stops half way through must not
     * start again from the top: that would spend the whole of the next month
     * re-fetching what is already on the phone. Whatever is left is written down and
     * picked up next time.
     */
    interface SweepProgress {
        fun load(): List<Pair<BoundingBox, Int>>
        fun save(remaining: List<Pair<BoundingBox, Int>>)
    }

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
        val resume = progress.load()
        when {
            resume.isNotEmpty() -> queue += resume
            else -> queue += seedTiles(box)
        }

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
            val results = try {
                query(key, area, radius.coerceAtMost(MAX_RADIUS_M))
            } catch (e: Http.HttpException) {
                when {
                    // Out of monthly allowance. Nothing is gained by hammering it for
                    // the rest of the sweep, and the user needs to be told why it
                    // stopped rather than left with a quietly short list.
                    e.code == HTTP_FORBIDDEN -> {
                        queue.addFirst(area to depth)
                        progress.save(queue.toList())
                        throw IllegalStateException(
                            "TomTom aylık istek hakkı doldu. Kalan bölgeler kaydedildi; " +
                                "hak yenilendiğinde tekrar çalıştır, kaldığı yerden devam eder."
                        )
                    }
                    // Throttled. Wait it out rather than burning budget on retries.
                    e.code == HTTP_TOO_MANY -> {
                        delay(THROTTLE_BACKOFF_MS)
                        queue.addFirst(area to depth)
                        budget++
                        continue
                    }
                    else -> throw e
                }
            }
            results.forEach { found[it.sourceId] = it }

            // A full page means the area holds more than was returned, so the answer
            // for it is incomplete until it is split. Without this, a city returns
            // its first hundred and the rest silently do not exist.
            if (results.size >= PAGE_SIZE && depth < MAX_DEPTH) {
                queue += area.quarters().map { it to depth + 1 }
            } else if (results.size >= PAGE_SIZE) {
                truncated = true
            }

            // Paced. An unthrottled sweep gets rate limited within a couple of
            // hundred requests, and the retries it then makes come out of the same
            // monthly allowance as the real ones.
            delay(REQUEST_SPACING_MS)
        }

        progress.save(queue.toList())
        return FetchResult(found.values.toList(), complete = !truncated && queue.isEmpty())
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

    /**
     * Where to start looking: one tile per populated cell, not one per map cell.
     *
     * Falls back to the plain box when nothing is cached yet, which only happens if
     * the bundled list failed to load — in that case there is no better guess than
     * the whole area.
     */
    private suspend fun seedTiles(box: BoundingBox): List<Pair<BoundingBox, Int>> {
        val points = runCatching { knownPoints() }.getOrDefault(emptyList())
            .filter { (lat, lon) ->
                lat in box.minLat..box.maxLat && lon in box.minLon..box.maxLon
            }
        if (points.isEmpty()) return listOf(box to 0)

        // Cells sized so one request covers each. The longitude step has to come
        // from the *row's* latitude, not from each station's own: deriving it
        // per-station puts the station in a cell whose box, rebuilt from the row,
        // does not contain it. Measured, that left 141 of 6,102 outside the circle
        // meant to cover them.
        val stepLat = TILE_KM / 111.32
        val cells = HashSet<Pair<Int, Int>>()
        for ((lat, lon) in points) {
            val row = floor(lat / stepLat).toInt()
            cells += Pair(row, floor(lon / stepLonFor(row, stepLat)).toInt())
        }

        return cells.map { (row, column) ->
            val stepLon = stepLonFor(row, stepLat)
            BoundingBox(
                minLat = row * stepLat,
                minLon = column * stepLon,
                maxLat = (row + 1) * stepLat,
                maxLon = (column + 1) * stepLon
            ) to 0
        }
    }

    /** Longitude degrees per tile in a given row, taken at the row's mid-latitude. */
    private fun stepLonFor(row: Int, stepLat: Double): Double {
        val centreLat = (row + 0.5) * stepLat
        return TILE_KM / (111.32 * kotlin.math.cos(Math.toRadians(centreLat)))
    }

    private fun BoundingBox.centreLat() = (minLat + maxLat) / 2
    private fun BoundingBox.centreLon() = (minLon + maxLon) / 2

    /**
     * Half the diagonal, plus a little, so the circle covers the corners of the box.
     *
     * Exactly half the diagonal puts a station standing on a corner precisely on the
     * circle, where rounding decides whether it is inside. One of the 6,102 cached
     * stations sits there. The overlap costs nothing — neighbouring circles were
     * always going to overlap — and removes the coin toss.
     */
    private fun BoundingBox.radiusMetres(): Double {
        val height = (maxLat - minLat) * 111_320.0
        val width = (maxLon - minLon) * 111_320.0 *
            kotlin.math.cos(Math.toRadians(centreLat()))
        return hypot(height, width) / 2 * CORNER_MARGIN
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
         * Seed tile size, in kilometres.
         *
         * Matched to the query radius: a cell this wide fits inside one request, so
         * the initial pass is one request per populated cell. Measured over the
         * cached stations, 50 km gives 304 tiles and leaves none of them uncovered.
         */
        const val TILE_KM = 50.0

        /** Enough that a station on a tile corner is unambiguously inside it. */
        const val CORNER_MARGIN = 1.03

        /**
         * Ceiling on requests per sweep.
         *
         * Sized from a measured sweep of the whole country rather than guessed. The
         * first version capped at sixty out of caution, which could not finish
         * Türkiye and left most of it missing — the free allowance is 2,500 requests
         * a month, so sixty was protecting nothing and costing everything.
         *
         * The cap is still here as a backstop: past it the result is reported as
         * incomplete, which the caller already treats as "merge, do not replace".
         */
        const val MAX_REQUESTS = 900
        const val MAX_DEPTH = 6

        const val HTTP_FORBIDDEN = 403
        const val HTTP_TOO_MANY = 429

        /**
         * Gap between requests.
         *
         * Measured: a sweep with no spacing at all started collecting 429s after
         * about two hundred requests and kept collecting them for the rest of the
         * run. Those retries are charged like any other request, so pacing is
         * cheaper than not pacing.
         */
        const val REQUEST_SPACING_MS = 250L
        const val THROTTLE_BACKOFF_MS = 3_000L
    }
}
