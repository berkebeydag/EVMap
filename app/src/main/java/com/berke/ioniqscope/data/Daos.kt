package com.berke.ioniqscope.data

import androidx.room.Dao
import androidx.room.Insert
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
}

/** Projection used to build CSV headers. */
data class PidColumn(
    @androidx.room.ColumnInfo(name = "pid_key") val pidKey: String,
    @androidx.room.ColumnInfo(name = "label") val label: String,
    @androidx.room.ColumnInfo(name = "unit") val unit: String
)
