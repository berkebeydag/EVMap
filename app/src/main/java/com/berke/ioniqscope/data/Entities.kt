package com.berke.ioniqscope.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One completed acceleration run.
 *
 * Split columns mirror the defaults [com.berke.ioniqscope.performance.PerformanceMeter]
 * is constructed with (50/100/120 km/h, 100/402 m). A null means the run ended
 * before that target was reached.
 */
@Entity(tableName = "perf_runs")
data class PerfRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "recorded_at") val recordedAtEpochMs: Long,
    @ColumnInfo(name = "ms_0_50") val zeroTo50Ms: Long?,
    @ColumnInfo(name = "ms_0_100") val zeroTo100Ms: Long?,
    @ColumnInfo(name = "ms_0_120") val zeroTo120Ms: Long?,
    @ColumnInfo(name = "ms_0_100m") val zeroTo100mMs: Long?,
    @ColumnInfo(name = "ms_0_402m") val zeroTo402mMs: Long?,
    @ColumnInfo(name = "max_kmh") val maxKmh: Double,
    @ColumnInfo(name = "distance_m") val distanceM: Double,
    @ColumnInfo(name = "duration_ms") val durationMs: Long
)

/** A logging session. [endedAtEpochMs] is null while it is still running. */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "started_at") val startedAtEpochMs: Long,
    @ColumnInfo(name = "ended_at") val endedAtEpochMs: Long? = null,
    @ColumnInfo(name = "sample_count") val sampleCount: Int = 0
)

/**
 * One reading within a trip. Rows sharing an [atEpochMs] belong to the same poll
 * snapshot, which is what lets the CSV exporter pivot them back into columns.
 *
 * Stored key/value rather than fixed columns so that adding a PID needs no migration.
 */
@Entity(
    tableName = "trip_samples",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["trip_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("trip_id"), Index("trip_id", "at")]
)
data class TripSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "trip_id") val tripId: Long,
    @ColumnInfo(name = "at") val atEpochMs: Long,
    @ColumnInfo(name = "pid_key") val pidKey: String,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "value") val value: Double,
    @ColumnInfo(name = "unit") val unit: String
)
