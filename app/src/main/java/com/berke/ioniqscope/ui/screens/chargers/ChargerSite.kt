package com.berke.ioniqscope.ui.screens.chargers

import com.berke.ioniqscope.data.ChargingStationEntity
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * One physical place you can charge at, which is not the same thing as one row in
 * the database.
 *
 * OSM models a charging site as an `amenity=charging_station` plus a separate
 * `man_made=charge_point` node for each individual socket, and the operator feeds
 * do much the same. So a single supermarket car park arrives as four or five
 * records at almost identical coordinates. Drawn straight, that reads as "5
 * stations" on the map when it is one place with five sockets — which is what made
 * the numbers on the map look wrong.
 *
 * Grouping happens here, once per viewport change, rather than in the overlay's
 * draw loop.
 */
data class ChargerSite(
    /** Stable enough for a list key: the lowest row id at this site. */
    val id: Long,
    val lat: Double,
    val lon: Double,
    val name: String?,
    val operator: String?,
    val maxPowerKw: Double?,
    val isDc: Boolean?,
    val connectors: String?,
    val address: String?,
    /**
     * How many sockets are here, or null when no source said. Never a guessed 1:
     * a site with six sockets and one that simply never published a count must not
     * read the same.
     */
    val chargePoints: Int?,
    val distanceMetres: Double?
)

/** Records closer together than this are treated as the same place. */
private const val SITE_RADIUS_M = 60.0

private const val METRES_PER_DEGREE = 111_320.0

/**
 * Collapses co-located records into sites.
 *
 * A plain grid would split a site straddling a cell boundary, so each record is
 * checked against the sites already found in the nine surrounding cells. The grid
 * is only an index; the accept/reject test is real distance.
 */
fun groupIntoSites(items: List<ChargerListItem>): List<ChargerSite> {
    if (items.isEmpty()) return emptyList()

    val cell = SITE_RADIUS_M / METRES_PER_DEGREE
    val index = HashMap<Long, MutableList<SiteBuilder>>()
    val builders = ArrayList<SiteBuilder>(items.size)

    for (item in items) {
        val station = item.station
        // floor, not truncation: toInt() rounds towards zero, which would fold the
        // cells either side of the equator and the prime meridian into one.
        val gx = floor(station.lat / cell).toInt()
        val gy = floor(station.lon / cell).toInt()

        var target: SiteBuilder? = null
        outer@ for (dx in -1..1) {
            for (dy in -1..1) {
                val bucket = index[pack(gx + dx, gy + dy)] ?: continue
                for (candidate in bucket) {
                    if (candidate.metresFrom(station.lat, station.lon) < SITE_RADIUS_M) {
                        target = candidate
                        break@outer
                    }
                }
            }
        }

        if (target == null) {
            target = SiteBuilder(station.lat, station.lon)
            index.getOrPut(pack(gx, gy)) { mutableListOf() }.add(target)
            builders.add(target)
        }
        target.add(item)
    }

    return builders.map { it.build() }
}

private fun pack(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

private class SiteBuilder(val anchorLat: Double, val anchorLon: Double) {

    private val members = mutableListOf<ChargingStationEntity>()
    private var nearest: Double? = null

    fun metresFrom(lat: Double, lon: Double): Double {
        val dLat = (lat - anchorLat) * METRES_PER_DEGREE
        val dLon = (lon - anchorLon) * METRES_PER_DEGREE * cos(Math.toRadians(lat))
        return hypot(dLat, dLon)
    }

    fun add(item: ChargerListItem) {
        members += item.station
        val distance = item.distanceMetres
        if (distance != null && (nearest == null || distance < nearest!!)) nearest = distance
    }

    fun build(): ChargerSite {
        // The best-described record names the site: a bare charge-point node
        // usually has no name at all, while the station it belongs to does.
        val primary = members.maxByOrNull { station ->
            (if (station.name != null) 4 else 0) +
                (if (station.operator != null) 2 else 0) +
                (if (station.maxPowerKw != null) 1 else 0)
        } ?: members.first()

        val connectors = members.mapNotNull { it.connectors }
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

        // Any DC socket makes the site useful for fast charging; "all AC" is only
        // claimed when every record actually said AC. Silence stays unknown.
        val isDc = when {
            members.any { it.isDc == true } -> true
            members.all { it.isDc == false } -> false
            else -> null
        }

        return ChargerSite(
            id = members.minOf { it.id },
            lat = members.sumOf { it.lat } / members.size,
            lon = members.sumOf { it.lon } / members.size,
            name = primary.name,
            operator = primary.operator ?: members.firstNotNullOfOrNull { it.operator },
            maxPowerKw = members.mapNotNull { it.maxPowerKw }.maxOrNull(),
            isDc = isDc,
            connectors = connectors.joinToString(", ").ifEmpty { null },
            address = primary.address ?: members.firstNotNullOfOrNull { it.address },
            // Each record is at least one charge point, so where some members state
            // a count and others do not, the unstated ones contribute one apiece —
            // a floor, not an estimate. A lone record that said nothing stays null.
            chargePoints = if (members.size > 1 || members.any { it.chargePoints != null }) {
                members.sumOf { it.chargePoints ?: 1 }
            } else null,
            distanceMetres = nearest
        )
    }
}
