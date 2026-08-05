package com.berke.ioniqscope.data

import com.berke.ioniqscope.connection.GPS_LAT_KEY
import com.berke.ioniqscope.connection.GPS_LON_KEY
import kotlin.math.cos
import kotlin.math.hypot

/** What a trip came to: how far, how much out, how much back. */
data class TripTotals(
    val distanceM: Double,
    val energyUsedKwh: Double,
    val energyRegainedKwh: Double
) {
    /** kWh per 100 km, or null when the distance is too short to divide by. */
    val consumptionPer100Km: Double?
        get() = if (distanceM > MIN_DISTANCE_M) {
            (energyUsedKwh - energyRegainedKwh) / (distanceM / 1000.0) * 100.0
        } else null

    companion object {
        /**
         * Below this, kWh/100km is arithmetic rather than information: a hundred metres
         * of manoeuvring with the air conditioning on divides a real number by a tiny
         * one and reports four hundred, which is true and useless.
         */
        const val MIN_DISTANCE_M = 300.0
    }
}

/**
 * Works a trip's totals out from its samples.
 *
 * Lives here rather than on the detail screen because three things need the same
 * answer and they must not disagree: the row that gets written when a trip closes, the
 * backfill that fills in trips recorded before there was anywhere to put it, and the
 * screen itself.
 */
object TripSummary {

    suspend fun compute(dao: TripDao, tripId: Long): TripTotals {
        val distance = pathDistance(
            dao.series(tripId, GPS_LAT_KEY),
            dao.series(tripId, GPS_LON_KEY)
        )
        val amps = dao.series(tripId, HV_CURRENT_KEY)
        val volts = dao.series(tripId, HV_VOLTAGE_KEY)
        val (used, regained) = splitEnergy(power(amps, volts))
        return TripTotals(distance, used, regained)
    }

    /** Pack power in kW, from the two figures the battery computer reports. */
    fun power(amps: List<SeriesPoint>, volts: List<SeriesPoint>): List<SeriesPoint> {
        if (amps.isEmpty() || volts.isEmpty()) return emptyList()
        val voltAt = volts.associate { it.atEpochMs to it.value }
        return amps.mapNotNull { point ->
            voltAt[point.atEpochMs]?.let {
                SeriesPoint(point.atEpochMs, point.value * it / 1000.0)
            }
        }
    }

    /**
     * How far the car went, from its own positions.
     *
     * Steps longer than [MAX_SEGMENT_M] are dropped: a receiver that jumps four hundred
     * metres between two fixes jumped, it did not drive, and one of those adds more
     * error than a whole trip of good ones.
     */
    fun pathDistance(lats: List<SeriesPoint>, lons: List<SeriesPoint>): Double {
        if (lats.size < 2 || lons.isEmpty()) return 0.0
        val lonAt = lons.associate { it.atEpochMs to it.value }

        var total = 0.0
        var previous: Pair<Double, Double>? = null
        for (point in lats) {
            val lon = lonAt[point.atEpochMs] ?: continue
            previous?.let { (pLat, pLon) ->
                val dLat = (point.value - pLat) * 111_320.0
                val dLon = (lon - pLon) * 111_320.0 * cos(Math.toRadians(pLat))
                val step = hypot(dLat, dLon)
                if (step <= MAX_SEGMENT_M) total += step
            }
            previous = point.value to lon
        }
        return total
    }

    /**
     * Splits the power trace into what was spent and what came back, in kWh.
     *
     * A segment whose endpoints straddle zero is cut at the crossing and each side
     * counted separately. Assigning each segment whole to the sign of its own average
     * is defensible on paper and useless in practice: a drive alternating between
     * pulling and regenerating reported exactly zero regeneration, because every
     * individual segment averaged positive.
     */
    fun splitEnergy(power: List<SeriesPoint>): Pair<Double, Double> {
        var used = 0.0
        var regained = 0.0

        for (i in 1 until power.size) {
            val hours = (power[i].atEpochMs - power[i - 1].atEpochMs) / 3_600_000.0
            if (hours <= 0 || hours > MAX_GAP_H) continue
            val a = power[i - 1].value
            val b = power[i].value

            when {
                a >= 0 && b >= 0 -> used += (a + b) / 2.0 * hours
                a <= 0 && b <= 0 -> regained += -(a + b) / 2.0 * hours
                else -> {
                    val cross = a / (a - b)
                    val first = a / 2.0 * (hours * cross)
                    val second = b / 2.0 * (hours * (1 - cross))
                    if (a > 0) used += first else regained += -first
                    if (b > 0) used += second else regained += -second
                }
            }
        }
        return used to regained
    }

    const val HV_CURRENT_KEY = "hv_current"
    const val HV_VOLTAGE_KEY = "hv_voltage"

    /** Beyond this in one step, the receiver jumped rather than the car moved. */
    private const val MAX_SEGMENT_M = 400.0

    /** A gap longer than this is a pause in recording, not an hour of driving. */
    private const val MAX_GAP_H = 1.0 / 60.0
}
