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
class ChargerRepository(
    private val dao: ChargingStationDao,
    private val sources: List<ChargerSource>
) {

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    val stationCount: Flow<Int> = dao.observeCount()
    val lastSync: Flow<Long?> = dao.observeLastSync()

    fun availableSources(): List<ChargerSource> = sources.filter { it.isAvailable() }

    /**
     * Refreshes from [source] over [box].
     *
     * Replaces that source's rows outright rather than merging into them, so
     * stations withdrawn upstream disappear. Other sources are untouched, so
     * running two of them leaves both sets present.
     */
    suspend fun sync(source: ChargerSource, box: BoundingBox = BoundingBox.TURKEY) {
        _syncState.value = SyncState.Running(source.displayName)
        try {
            val result = source.fetch(box)
            if (result.stations.isEmpty()) {
                _syncState.value = SyncState.Failed(
                    "${source.displayName} bu alan için istasyon döndürmedi."
                )
                return
            }

            // Only a complete fetch may replace what is cached. A partial one is
            // merged instead — otherwise one slow request during a refresh would
            // delete stations that are perfectly good and still out there.
            if (result.complete) {
                dao.replaceSource(source.id, result.stations)
            } else {
                dao.upsertAll(result.stations)
            }

            _syncState.value = SyncState.Done(
                added = result.stations.size,
                sourceName = source.displayName,
                partial = !result.complete
            )
        } catch (e: Exception) {
            _syncState.value = SyncState.Failed(e.message ?: "Yenileme başarısız.")
        }
    }

    fun clearSyncState() { _syncState.value = SyncState.Idle }

    suspend fun inBounds(
        box: BoundingBox,
        dcOnly: Boolean = false,
        minPowerKw: Double = 0.0,
        operators: Set<String> = emptySet(),
        limit: Int = 100_000
    ): List<ChargingStationEntity> = dao.inBounds(
        box.minLat, box.maxLat, box.minLon, box.maxLon,
        dcOnly, minPowerKw, operators.isEmpty(), operators.orPlaceholder(), limit
    )

    /** Everything in the box, filters ignored — the denominator for "5 of 72". */
    suspend fun countInBounds(box: BoundingBox): Int =
        dao.countInBounds(box.minLat, box.maxLat, box.minLon, box.maxLon)

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
        dcOnly: Boolean = false,
        minPowerKw: Double = 0.0,
        operators: Set<String> = emptySet(),
        limit: Int = 100
    ): List<ChargingStationEntity> = dao.nearest(
        lat, lon, cos(lat * PI / 180.0),
        dcOnly, minPowerKw, operators.isEmpty(), operators.orPlaceholder(), limit
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
        val escaped = cleaned.replace("%", "\\%").replace("_", "\\_")
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
