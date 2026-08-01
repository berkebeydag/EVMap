package com.berke.ioniqscope.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PerfRunDao {

    @Insert
    suspend fun insert(run: PerfRunEntity): Long

    @Query("SELECT * FROM perf_runs ORDER BY recorded_at DESC")
    fun observeAll(): Flow<List<PerfRunEntity>>

    /** Fastest recorded 0-100 km/h, or null if no run has ever reached 100. */
    @Query("SELECT MIN(ms_0_100) FROM perf_runs WHERE ms_0_100 IS NOT NULL")
    fun observeBest0To100(): Flow<Long?>

    @Query("DELETE FROM perf_runs WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM perf_runs")
    suspend fun deleteAll()
}

@Dao
interface ChargingStationDao {

    /** Re-syncing the same area must update rows, not duplicate them. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(stations: List<ChargingStationEntity>)

    @Query("SELECT COUNT(*) FROM charging_stations")
    fun observeCount(): Flow<Int>

    @Query("SELECT MAX(fetched_at) FROM charging_stations")
    fun observeLastSync(): Flow<Long?>

    /**
     * Everything inside a bounding box. Cheap enough for a map viewport and it
     * keeps the "which markers are visible" decision in SQL rather than in memory.
     *
     * [limit] is a runaway guard, not a display cap, and is deliberately set far
     * above the size of the dataset. A cap that actually bites here cuts by row
     * order, which is insertion order, which is source order — so at country zoom
     * it filled one half of the country with markers and left the other half
     * looking as though it had no chargers at all.
     *
     * The filters are here rather than in Kotlin because the cost of this call is
     * building the row objects, not the scan. Measured at country zoom: 13,379 rows
     * came back in 81 ms and half of them were discarded a moment later — so half
     * that time was spent constructing objects for stations the user had asked not
     * to see.
     *
     * `is_dc IS NULL` survives [dcOnly] deliberately, matching what the Kotlin filter
     * did: a source that never said whether a site is DC is not the same as one that
     * said AC, and hiding the unknowns would hide real fast chargers.
     */
    @Query(
        "SELECT * FROM charging_stations " +
            "WHERE lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon " +
            "AND (:dcOnly = 0 OR is_dc IS NULL OR is_dc != 0) " +
            "AND (:minPowerKw <= 0 OR (max_power_kw IS NOT NULL AND max_power_kw >= :minPowerKw)) " +
            "AND (:allBrands = 1 OR operator IN (:brands)) " +
            "LIMIT :limit"
    )
    suspend fun inBounds(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        dcOnly: Boolean = false,
        minPowerKw: Double = 0.0,
        allBrands: Boolean = true,
        brands: List<String> = listOf(""),
        limit: Int = 100_000
    ): List<ChargingStationEntity>

    /**
     * Nearest stations to a point, ordered by squared planar distance.
     *
     * Planar rather than great-circle: over the tens of kilometres this is used
     * for, the error is far smaller than the position uncertainty, and it keeps
     * the ordering doable in SQLite. The longitude term is scaled by cos(lat) so
     * a degree of longitude is not treated as a degree of latitude.
     */
    @Query(
        "SELECT * FROM charging_stations WHERE " +
            "(:dcOnly = 0 OR is_dc IS NULL OR is_dc != 0) " +
            "AND (:minPowerKw <= 0 OR (max_power_kw IS NOT NULL AND max_power_kw >= :minPowerKw)) " +
            "AND (:allBrands = 1 OR operator IN (:brands)) " +
            "ORDER BY " +
            "((lat - :lat) * (lat - :lat)) + " +
            "((lon - :lon) * (lon - :lon) * :lonScale * :lonScale) " +
            "LIMIT :limit"
    )
    suspend fun nearest(
        lat: Double,
        lon: Double,
        lonScale: Double,
        dcOnly: Boolean = false,
        minPowerKw: Double = 0.0,
        allBrands: Boolean = true,
        brands: List<String> = listOf(""),
        limit: Int = 100
    ): List<ChargingStationEntity>

    /**
     * Every network, most stations first.
     *
     * Ordered by how many each one has because that is the order they are useful in:
     * a filter listing 613 operators alphabetically buries ZES and Trugo — between
     * them a fifth of the country — under names with one station apiece.
     */
    @Query(
        "SELECT operator AS name, COUNT(*) AS stations FROM charging_stations " +
            "WHERE operator IS NOT NULL AND operator != '' " +
            "GROUP BY operator ORDER BY stations DESC, name ASC"
    )
    fun observeOperators(): Flow<List<OperatorCount>>

    /**
     * Free-text search over the cached stations.
     *
     * Ordered so that a name match beats an operator match beats an address match:
     * typing "zes" should reach the ZES branded sites before every street that
     * happens to contain the letters, and typing a district name should still find
     * it. Nearest-first within each tier, because there are hundreds of ZES sites
     * and the useful one is the one you can reach.
     */
    @Query(
        // ESCAPE is required for the caller's backslash-escaping of % and _ to mean
        // anything; without it the backslash is just another character to match.
        "SELECT * FROM charging_stations WHERE " +
            "name LIKE :pattern ESCAPE '\' OR operator LIKE :pattern ESCAPE '\' " +
            "OR address LIKE :pattern ESCAPE '\' " +
            "ORDER BY CASE " +
            "  WHEN name LIKE :pattern ESCAPE '\' THEN 0 " +
            "  WHEN operator LIKE :pattern ESCAPE '\' THEN 1 " +
            "  ELSE 2 END, " +
            "((lat - :lat) * (lat - :lat)) + " +
            "((lon - :lon) * (lon - :lon) * :lonScale * :lonScale) " +
            "LIMIT :limit"
    )
    suspend fun search(
        pattern: String,
        lat: Double,
        lon: Double,
        lonScale: Double,
        limit: Int = 40
    ): List<ChargingStationEntity>

    /**
     * Just the coordinates, for deciding where a sweep needs to look.
     *
     * Sweeping a provider by tiling the country's bounding box spends most of its
     * requests on sea and empty steppe. The stations already cached are a far better
     * map of where chargers actually are, and there are only a few thousand of them.
     */
    @Query("SELECT lat, lon FROM charging_stations")
    suspend fun allCoordinates(): List<Coordinate>

    @Query("DELETE FROM charging_stations WHERE source = :source")
    suspend fun deleteBySource(source: String)

    /**
     * Replaces one source's data wholesale.
     *
     * Upserting alone is not enough: a station deleted upstream, or one whose
     * identifier scheme changed on our side, would linger for ever. Transactional
     * so a failed insert cannot leave the map empty.
     */
    @Transaction
    suspend fun replaceSource(source: String, stations: List<ChargingStationEntity>) {
        deleteBySource(source)
        upsertAll(stations)
    }

    @Query("DELETE FROM charging_stations")
    suspend fun deleteAll()

    @Query("DELETE FROM charging_stations WHERE source IN (:sources)")
    suspend fun deleteBySources(sources: List<String>)

    /**
     * Swaps out the rows belonging to [sources] and puts [stations] in their place.
     *
     * Scoped rather than wholesale. The bundled list is re-seeded on every app
     * update, and a blanket delete would take anything the user had fetched
     * themselves with their own key down with it — which is the one part of the
     * table they cannot get back without spending their own request allowance again.
     *
     * Transactional so a failure cannot leave the map empty, which on this screen
     * would read as "there are no chargers near you".
     */
    @Transaction
    suspend fun replaceSources(
        sources: List<String>,
        stations: List<ChargingStationEntity>
    ) {
        if (sources.isNotEmpty()) deleteBySources(sources)
        upsertAll(stations)
    }
}

@Dao
interface AuxVoltageDao {

    @Insert
    suspend fun insert(sample: AuxVoltageEntity)

    @Query("SELECT * FROM aux_voltage ORDER BY at ASC")
    fun observeAll(): Flow<List<AuxVoltageEntity>>

    /**
     * Session-start readings only. These are taken before the car has been driven,
     * so they are the closest thing to a rested voltage and the only ones worth
     * comparing across days.
     */
    @Query("SELECT * FROM aux_voltage WHERE at_session_start = 1 ORDER BY at ASC")
    fun observeSessionStarts(): Flow<List<AuxVoltageEntity>>

    @Query("SELECT * FROM aux_voltage ORDER BY at DESC LIMIT 1")
    fun observeLatest(): Flow<AuxVoltageEntity?>

    @Query("DELETE FROM aux_voltage")
    suspend fun deleteAll()
}

@Dao
interface TripDao {

    @Insert
    suspend fun insertTrip(trip: TripEntity): Long

    @Insert
    suspend fun insertSamples(samples: List<TripSampleEntity>)

    @Query("UPDATE trips SET ended_at = :endedAt, sample_count = :count WHERE id = :tripId")
    suspend fun finishTrip(tripId: Long, endedAt: Long, count: Int)

    @Query("SELECT COUNT(*) FROM trip_samples WHERE trip_id = :tripId")
    suspend fun sampleCount(tripId: Long): Int

    @Query("SELECT * FROM trips ORDER BY started_at DESC")
    fun observeTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun trip(id: Long): TripEntity?

    /** Column set for the CSV header, in a stable order. */
    /**
     * Average and top speed per trip, in one pass over the samples.
     *
     * One query for the whole list rather than one per row: the log is a LazyColumn and
     * a query per card would fire as the user scrolls, on the main thread's timing, for
     * a number that is the same every time it is asked.
     *
     * Trips with no speed samples simply do not appear in the result, which the caller
     * reads as "not known" rather than as zero — a trip logged without the speed PID
     * selected has no average speed, and 0 km/h is a different claim.
     */
    @Query(
        "SELECT trip_id AS tripId, AVG(value) AS averageSpeed, MAX(value) AS topSpeed " +
            "FROM trip_samples WHERE pid_key = 'speed' GROUP BY trip_id"
    )
    fun observeSpeedSummaries(): Flow<List<TripSpeedSummary>>

    @Query("SELECT DISTINCT pid_key FROM trip_samples WHERE trip_id = :tripId ORDER BY pid_key")
    suspend fun pidKeys(tripId: Long): List<String>

    @Query(
        "SELECT DISTINCT pid_key, label, unit FROM trip_samples " +
            "WHERE trip_id = :tripId ORDER BY pid_key"
    )
    suspend fun pidColumns(tripId: Long): List<PidColumn>

    /** Paged so a long trip never has to be materialised in memory all at once. */
    @Query(
        "SELECT * FROM trip_samples WHERE trip_id = :tripId " +
            "ORDER BY at ASC, pid_key ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun samplesPage(tripId: Long, limit: Int, offset: Int): List<TripSampleEntity>

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteTrip(id: Long)

    // --- trip detail ---

    /** One PID's series across a trip, for plotting. */
    @Query(
        "SELECT at AS atEpochMs, value AS value FROM trip_samples " +
            "WHERE trip_id = :tripId AND pid_key = :pidKey ORDER BY at ASC"
    )
    suspend fun series(tripId: Long, pidKey: String): List<SeriesPoint>

    @Query(
        "SELECT MIN(value) AS minValue, MAX(value) AS maxValue, AVG(value) AS avgValue, " +
            "COUNT(*) AS sampleCount FROM trip_samples WHERE trip_id = :tripId AND pid_key = :pidKey"
    )
    suspend fun stats(tripId: Long, pidKey: String): PidStats?
}

/** A bare lat/lon pair, for queries that need nothing else. */
data class Coordinate(
    @androidx.room.ColumnInfo(name = "lat") val lat: Double,
    @androidx.room.ColumnInfo(name = "lon") val lon: Double
)

data class SeriesPoint(
    @androidx.room.ColumnInfo(name = "atEpochMs") val atEpochMs: Long,
    @androidx.room.ColumnInfo(name = "value") val value: Double
)

data class PidStats(
    @androidx.room.ColumnInfo(name = "minValue") val minValue: Double?,
    @androidx.room.ColumnInfo(name = "maxValue") val maxValue: Double?,
    @androidx.room.ColumnInfo(name = "avgValue") val avgValue: Double?,
    @androidx.room.ColumnInfo(name = "sampleCount") val sampleCount: Int
)

/** Projection used to build CSV headers. */
data class PidColumn(
    @androidx.room.ColumnInfo(name = "pid_key") val pidKey: String,
    @androidx.room.ColumnInfo(name = "label") val label: String,
    @androidx.room.ColumnInfo(name = "unit") val unit: String
)
