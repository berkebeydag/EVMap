package com.berke.ioniqscope.charging

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.math.floor

/** Something near a charger that is worth knowing about while you wait at it. */
data class Poi(
    val lat: Double,
    val lon: Double,
    /** cafe, restaurant, fast_food, fuel, pharmacy, toilets, supermarket, … */
    val kind: String,
    val name: String?
)

/**
 * What is around the chargers.
 *
 * Half an hour on a charger is half an hour standing somewhere, and the question is
 * always whether there is a coffee, a toilet or a shop. OpenStreetMap knows and the
 * basemap does not draw it: CARTO's raster styles carry no POI symbology at any zoom,
 * which was measured rather than assumed.
 *
 * Only what sits within 250 m of a charging station is here. A country-wide POI file
 * would be enormous and would be answering a question this app is not for — the point
 * is not to be a map of Turkey's cafes, it is to say what is at the place you are about
 * to stop at.
 *
 * Indexed into a grid on load so a viewport query touches the cells on screen rather
 * than fifty thousand rows.
 */
class PoiIndex(private val context: Context) {

    @Volatile private var grid: Map<Long, List<Poi>>? = null

    private suspend fun load(): Map<Long, List<Poi>> = grid ?: withContext(Dispatchers.IO) {
        val built = runCatching {
            val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            val array = JSONArray(text)
            val cells = HashMap<Long, MutableList<Poi>>()
            for (i in 0 until array.length()) {
                val row = array.getJSONArray(i)
                // Stored as a tuple rather than an object: fifty thousand copies of
                // "lat"/"lon"/"kind"/"name" is a megabyte of the same eleven words.
                val poi = Poi(
                    lat = row.getDouble(0),
                    lon = row.getDouble(1),
                    kind = row.getString(2),
                    name = row.optString(3).takeIf { it.isNotEmpty() }
                )
                cells.getOrPut(cellOf(poi.lat, poi.lon)) { mutableListOf() }.add(poi)
            }
            cells.mapValues { it.value.toList() }
        }.getOrDefault(emptyMap())
        grid = built
        built
    }

    /** Everything inside [box], up to [limit]; empty when the box is huge. */
    suspend fun inBounds(box: BoundingBox, limit: Int = 400): List<Poi> {
        val cells = load()
        if (cells.isEmpty()) return emptyList()

        val out = ArrayList<Poi>(64)
        var latCell = floor(box.minLat / CELL).toInt()
        val lastLat = floor(box.maxLat / CELL).toInt()
        while (latCell <= lastLat) {
            var lonCell = floor(box.minLon / CELL).toInt()
            val lastLon = floor(box.maxLon / CELL).toInt()
            while (lonCell <= lastLon) {
                cells[pack(latCell, lonCell)]?.forEach { poi ->
                    if (poi.lat in box.minLat..box.maxLat && poi.lon in box.minLon..box.maxLon) {
                        out += poi
                    }
                }
                if (out.size >= limit) return out.take(limit)
                lonCell++
            }
            latCell++
        }
        return out
    }

    private companion object {
        const val ASSET = "pois_tr.json"

        /** About 1.1 km. Small enough that a close viewport reads a handful of cells. */
        const val CELL = 0.01

        fun cellOf(lat: Double, lon: Double): Long =
            pack(floor(lat / CELL).toInt(), floor(lon / CELL).toInt())

        fun pack(latCell: Int, lonCell: Int): Long =
            (latCell.toLong() shl 32) or (lonCell.toLong() and 0xFFFFFFFFL)
    }
}
