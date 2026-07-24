package com.berke.ioniqscope.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
     */
    @Query(
        "SELECT * FROM charging_stations " +
            "WHERE lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon " +
            "LIMIT :limit"
    )
    suspend fun inBounds(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        limit: Int = 2000
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
        "SELECT * FROM charging_stations ORDER BY " +
            "((lat - :lat) * (lat - :lat)) + " +
            "((lon - :lon) * (lon - :lon) * :lonScale * :lonScale) " +
            "LIMIT :limit"
    )
    suspend fun nearest(
        lat: Double,
        lon: Double,
        lonScale: Double,
        limit: Int = 100
    ): List<ChargingStationEntity>

    @Query("DELETE FROM charging_stations")
    suspend fun deleteAll()
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
