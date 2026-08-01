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

/**
 * A 12V auxiliary battery reading, kept as its own long-lived series.
 *
 * Separate from trip samples because trips get deleted and this trend needs to
 * outlive them: the point is to notice a battery degrading over weeks. The
 * Ioniq 5/6 12V/ICCU failure mode is the reason this table exists.
 */
@Entity(tableName = "aux_voltage", indices = [Index("at")])
data class AuxVoltageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "at") val atEpochMs: Long,
    @ColumnInfo(name = "volts") val volts: Double,
    /** True for the first reading of a session — the closest thing to a rested value. */
    @ColumnInfo(name = "at_session_start") val atSessionStart: Boolean
)

/**
 * A public charging station, cached locally.
 *
 * The whole point of caching is that the map works with no signal — which is
 * exactly when you need to find a charger. Network is only ever used to refresh
 * this table.
 *
 * [sourceId] is namespaced by [source] ("osm:123456", "ocm:78910") so the same
 * station arriving from two providers does not become two rows.
 */
@Entity(
    tableName = "charging_stations",
    indices = [Index(value = ["source_id"], unique = true), Index("lat"), Index("lon")]
)
data class ChargingStationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "name") val name: String?,
    @ColumnInfo(name = "operator") val operator: String?,
    @ColumnInfo(name = "lat") val lat: Double,
    @ColumnInfo(name = "lon") val lon: Double,
    /** Comma-separated connector names as reported; null when the source is silent. */
    @ColumnInfo(name = "connectors") val connectors: String?,
    /** Highest advertised power at this location, kW. Null means unknown, not zero. */
    @ColumnInfo(name = "max_power_kw") val maxPowerKw: Double?,
    /**
     * True only when the source positively indicates DC. Unknown stays null rather
     * than false — filtering "DC only" must not silently hide untagged stations
     * without the UI saying so.
     */
    @ColumnInfo(name = "is_dc") val isDc: Boolean?,
    @ColumnInfo(name = "address") val address: String?,
    @ColumnInfo(name = "fetched_at") val fetchedAtEpochMs: Long,
    /**
     * How many individual charge points this record covers. Null when the source
     * never said — most OSM entries — rather than an assumed 1, because a site with
     * six sockets and one with an unstated count must not read the same.
     *
     * Declared last to match the physical column order an `ALTER TABLE ADD COLUMN`
     * produces, so an upgraded database and a freshly created one are identical.
     */
    @ColumnInfo(name = "charge_points") val chargePoints: Int? = null
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

/** One network and how many stations it has, for the brand filter's list. */
data class OperatorCount(
    val name: String,
    val stations: Int
)

/** What the speed samples of one trip add up to. Absent when none were logged. */
data class TripSpeedSummary(
    val tripId: Long,
    val averageSpeed: Double,
    val topSpeed: Double
)
