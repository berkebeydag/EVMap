package com.berke.ioniqscope.charging

import com.berke.ioniqscope.data.ChargingStationDao
import com.berke.ioniqscope.data.OperatorCount
import com.berke.ioniqscope.data.ChargingStationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.cos
import kotlin.math.PI

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val sourceName: String) : SyncState
    data class Done(val added: Int, val sourceName: String, val partial: Boolean) : SyncState
    data class Failed(val message: String) : SyncState
}

/**
 * Owns the local charging-station cache.
 *
 * The map always reads from Room, never from the network. A refresh writes to
 * Room and the map follows — so the map keeps working with no signal, which is
 * precisely the situation in which you need to find a charger.
 */
class ChargerRepository(private val dao: ChargingStationDao) {

    val stationCount: Flow<Int> = dao.observeCount()
    val lastSync: Flow<Long?> = dao.observeLastSync()

    suspend fun inBounds(
        box: BoundingBox,
        wantAc: Boolean = true,
        wantDc: Boolean = true,
        minPowerKw: Double = 0.0,
        operators: Set<String> = emptySet(),
        limit: Int = 100_000
    ): List<ChargingStationEntity> = dao.inBounds(
        box.minLat, box.maxLat, box.minLon, box.maxLon,
        wantAc, wantDc, minPowerKw, operators.isEmpty(), operators.orPlaceholder(), limit
    )

    /** Every network with a station, most first. Drives the brand filter's list. */
    val operators: Flow<List<OperatorCount>> = dao.observeOperators()

    /**
     * Room expands `IN (:brands)` literally, and an empty list would render `IN ()`,
     * which SQLite refuses to parse. The query never reads the list when the
     * all-brands flag is set, so a single unmatchable value keeps it valid.
     */
    private fun Set<String>.orPlaceholder(): List<String> =
        if (isEmpty()) listOf("") else toList()

    /**
     * Nearest stations to a point.
     *
     * Longitude degrees are narrower than latitude degrees away from the equator,
     * so they are scaled by cos(lat) before distances are compared — without it,
     * at Turkish latitudes an east-west offset would look about 25% closer than it is.
     */
    suspend fun nearest(
        lat: Double,
        lon: Double,
        wantAc: Boolean = true,
        wantDc: Boolean = true,
        minPowerKw: Double = 0.0,
        operators: Set<String> = emptySet(),
        limit: Int = 100
    ): List<ChargingStationEntity> = dao.nearest(
        lat, lon, cos(lat * PI / 180.0),
        wantAc, wantDc, minPowerKw, operators.isEmpty(), operators.orPlaceholder(), limit
    )

    /**
     * Free-text search, anchored at [lat]/[lon] so results come back nearest-first.
     *
     * `%` and `_` are LIKE wildcards, so a query containing them would silently
     * match far more than the user typed; they are escaped rather than stripped,
     * since an address can legitimately contain either.
     */
    suspend fun search(
        query: String,
        lat: Double,
        lon: Double,
        limit: Int = 40
    ): List<ChargingStationEntity> {
        val cleaned = query.trim()
        if (cleaned.length < MIN_SEARCH_CHARS) return emptyList()
        // The backslash goes first: escaping % and _ with one, without escaping
        // the backslash itself, turns a typed backslash into an escape character
        // for whatever the user typed next.
        val escaped = cleaned
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return dao.search("%$escaped%", lat, lon, cos(lat * PI / 180.0), limit)
    }

    suspend fun clearAll() = dao.deleteAll()

    companion object {
        /** Shorter than this matches most of the country and helps nobody. */
        const val MIN_SEARCH_CHARS = 2

        /** Great-circle distance in metres. */
        fun distanceMetres(
            lat1: Double, lon1: Double,
            lat2: Double, lon2: Double
        ): Double {
            val r = 6_371_000.0
            val dLat = (lat2 - lat1) * PI / 180.0
            val dLon = (lon2 - lon1) * PI / 180.0
            val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
            return 2 * r * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        }
    }
}
