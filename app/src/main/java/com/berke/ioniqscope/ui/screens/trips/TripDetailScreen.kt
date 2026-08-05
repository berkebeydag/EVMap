package com.berke.ioniqscope.ui.screens.trips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.berke.ioniqscope.ServiceLocator
import com.berke.ioniqscope.data.AppSettings
import com.berke.ioniqscope.data.PidCatalog
import com.berke.ioniqscope.data.SeriesPoint
import com.berke.ioniqscope.data.TripEntity
import com.berke.ioniqscope.obd.StandardPids
import com.berke.ioniqscope.ui.components.ChartPoint
import com.berke.ioniqscope.ui.components.EmptyState
import com.berke.ioniqscope.ui.components.LineChart
import com.berke.ioniqscope.ui.components.SectionLabel
import com.berke.ioniqscope.ui.components.formatDuration
import com.berke.ioniqscope.ui.components.formatReading
import com.berke.ioniqscope.ui.serviceViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.berke.ioniqscope.connection.GPS_SPEED_KEY
import kotlin.math.cos
import kotlin.math.hypot
import com.berke.ioniqscope.connection.GPS_LAT_KEY
import com.berke.ioniqscope.connection.GPS_LON_KEY
import com.berke.ioniqscope.data.RecordedSeries
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.text.style.TextAlign

/** What the battery computer calls the 12V rail; the only source on a car that will
 *  not answer the standard module-voltage PID. */
private const val BMS_AUX_VOLTAGE_KEY = "aux_voltage"

/**
 * Below this, kWh/100km is arithmetic rather than information.
 *
 * A hundred metres of manoeuvring with the air conditioning on divides a real number by
 * a tiny one and reports four hundred kWh/100km, which is true and useless.
 */
private const val MIN_DISTANCE_FOR_CONSUMPTION_M = 300.0

/** A chart's x value is an epoch millisecond; this is what to write under it. */
private fun clockAt(x: Double): String =
    clockFormatter.format(Instant.ofEpochMilli(x.toLong()))

private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    .withZone(ZoneId.systemDefault())

/**
 * Series that are recorded but make no sense as a curve.
 *
 * A latitude drawn against time is a chart of how far north you drove, which nobody
 * has ever wanted to look at; the positions exist to measure distance. Speed and the
 * two the power curve is built from already have their own sections.
 */
private val NOT_WORTH_CHARTING = setOf(
    GPS_LAT_KEY, GPS_LON_KEY, GPS_SPEED_KEY, "hv_current", "hv_voltage"
)

data class TripDetail(
    val trip: TripEntity?,
    /** Counted from the rows themselves; the trip row's own column can be stale. */
    val sampleCount: Int = 0,
    /** When recording actually stopped, for a trip that never closed. */
    val lastSampleAt: Long? = null,
    val speedSeries: List<SeriesPoint> = emptyList(),
    /** Pack power in kW over the trip; negative is regeneration. */
    val powerSeries: List<SeriesPoint> = emptyList(),
    /** Energy out of the pack while driving it. */
    val energyUsedKwh: Double = 0.0,
    /** Energy put back into it by braking and descending. */
    val energyRegainedKwh: Double = 0.0,
    /** Every other reading the trip recorded, with its own trace. */
    val otherSeries: List<Pair<RecordedSeries, List<SeriesPoint>>> = emptyList(),
    val voltSeries: List<SeriesPoint> = emptyList(),
    val maxKmh: Double? = null,
    val avgKmh: Double? = null,
    /** Trapezoidal integral of speed, the same method PerformanceMeter uses. */
    val distanceM: Double = 0.0,
    val minVolts: Double? = null,
    val maxVolts: Double? = null,
    val loading: Boolean = true
)

class TripDetailViewModel(
    services: ServiceLocator,
    private val tripId: Long
) : ViewModel() {

    private val dao = services.database.tripDao()

    val settings: StateFlow<AppSettings> = services.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _detail = MutableStateFlow(TripDetail(trip = null))
    val detail: StateFlow<TripDetail> = _detail.asStateFlow()

    init {
        viewModelScope.launch {
            val trip = dao.trip(tripId)
            // Counted, not read off the trip row. That column is only written when a
            // trip closes cleanly, so a trip whose process was killed reported zero
            // samples for ever — this one had 1,314 of them and said 0.
            val realCount = dao.sampleCount(tripId)
            val lastAt = dao.lastSampleAt(tripId)
            val speed = dao.series(tripId, PidCatalog.speed.key)
                .ifEmpty { dao.series(tripId, GPS_SPEED_KEY) }
            val volts = dao.series(tripId, StandardPids.moduleVolt.key)
                .ifEmpty { dao.series(tripId, BMS_AUX_VOLTAGE_KEY) }
            // An aggregate over no rows is still a row — count 0, everything null —
            // so a plain `?:` never reached the fallback and the maximum and average
            // stayed blank beside a chart that plainly had both.
            val speedStats = dao.stats(tripId, PidCatalog.speed.key)
                ?.takeIf { it.sampleCount > 0 }
                ?: dao.stats(tripId, GPS_SPEED_KEY)?.takeIf { it.sampleCount > 0 }
            val voltStats = dao.stats(tripId, StandardPids.moduleVolt.key)
                ?.takeIf { it.sampleCount > 0 }
                ?: dao.stats(tripId, BMS_AUX_VOLTAGE_KEY)?.takeIf { it.sampleCount > 0 }

            // Distance from where the car went, falling back to the speed trace when
            // there are no positions — an old trip, or one recorded indoors.
            val lats = dao.series(tripId, GPS_LAT_KEY)
            val lons = dao.series(tripId, GPS_LON_KEY)
            val travelled = pathDistance(lats, lons).takeIf { it > 0.0 }
                ?: integrateDistance(speed)

            // Energy is the integral of pack power, and pack power is the product of
            // two things the battery computer already reports. Negative current is
            // regeneration, so it subtracts itself — which is the whole point of
            // measuring consumption this way rather than from the state of charge,
            // whose resolution is half a percent and whose meaning drifts with
            // temperature.
            val amps = dao.series(tripId, "hv_current")
            val volts2 = dao.series(tripId, "hv_voltage")
            val power = pairSeries(amps, volts2) { a, v -> a * v / 1000.0 }
            // Kept apart rather than netted. "0.98 kWh, regeneration already
            // subtracted" answers a question nobody asked — what a driver wants to
            // know is how much went out and how much came back, and the ratio between
            // them is the whole story of how a drive was driven.
            val (used, regained) = splitEnergy(power)

            // Everything else the trip holds, charted in whatever order it comes back.
            val others = dao.recordedSeries(tripId)
                .filter { it.sampleCount >= 2 && it.pidKey !in NOT_WORTH_CHARTING }
                .map { it to dao.series(tripId, it.pidKey) }

            _detail.value = TripDetail(
                trip = trip,
                powerSeries = power,
                energyUsedKwh = used,
                energyRegainedKwh = regained,
                otherSeries = others,
                sampleCount = realCount,
                lastSampleAt = lastAt,
                speedSeries = speed,
                voltSeries = volts,
                maxKmh = speedStats?.maxValue,
                avgKmh = speedStats?.avgValue,
                distanceM = travelled,
                minVolts = voltStats?.minValue,
                maxVolts = voltStats?.maxValue,
                loading = false
            )
        }
    }

    /**
     * How far the car actually went, from its own positions.
     *
     * Latitude and longitude are written in the same snapshot, so they share a
     * timestamp and can be paired by it. Segments longer than [MAX_SEGMENT_M] are
     * dropped: a fix that jumps a kilometre between two seconds is a bad fix, not a
     * kilometre, and one of those would add more error than the whole rest of a drive.
     */
    private fun pathDistance(lats: List<SeriesPoint>, lons: List<SeriesPoint>): Double {
        if (lats.size < 2 || lons.isEmpty()) return 0.0
        val lonAt = lons.associate { it.atEpochMs to it.value }

        var total = 0.0
        var previous: Pair<Double, Double>? = null
        for (point in lats) {
            val lon = lonAt[point.atEpochMs] ?: continue
            val here = point.value to lon
            previous?.let { (pLat, pLon) ->
                val dLat = (here.first - pLat) * 111_320.0
                val dLon = (here.second - pLon) * 111_320.0 * cos(Math.toRadians(pLat))
                val step = hypot(dLat, dLon)
                // A fix that jumps a kilometre between two seconds is a bad fix, not a
                // kilometre. One of those adds more error than a whole drive of good ones.
                if (step <= MAX_SEGMENT_M) total += step
            }
            previous = here
        }
        return total
    }

    /** Two series sharing timestamps, combined value by value. */
    private fun pairSeries(
        first: List<SeriesPoint>,
        second: List<SeriesPoint>,
        combine: (Double, Double) -> Double
    ): List<SeriesPoint> {
        if (first.isEmpty() || second.isEmpty()) return emptyList()
        val byTime = second.associate { it.atEpochMs to it.value }
        return first.mapNotNull { point ->
            byTime[point.atEpochMs]?.let {
                SeriesPoint(point.atEpochMs, combine(point.value, it))
            }
        }
    }

    /**
     * Splits the power trace into what was spent and what came back, in kWh.
     *
     * A segment whose endpoints straddle zero is cut at the crossing and each side
     * counted separately, rather than assigned whole to whichever sign its average
     * happened to have. That first version was defensible on paper and useless in
     * practice: a drive that alternates between pulling and regenerating reported
     * exactly zero regeneration, because every individual segment averaged positive.
     * The crossing is where the trapezoid becomes two triangles and the arithmetic is
     * no harder.
     */
    private fun splitEnergy(power: List<SeriesPoint>): Pair<Double, Double> {
        var used = 0.0
        var regained = 0.0

        for (i in 1 until power.size) {
            val hours = (power[i].atEpochMs - power[i - 1].atEpochMs) / 3_600_000.0
            if (hours <= 0 || hours > MAX_GAP_H) continue
            val a = power[i - 1].value
            val b = power[i].value

            if (a >= 0 && b >= 0) {
                used += (a + b) / 2.0 * hours
            } else if (a <= 0 && b <= 0) {
                regained += -(a + b) / 2.0 * hours
            } else {
                // Crosses zero. The fraction of the step before the crossing is
                // a / (a - b), and each side is a triangle of its own endpoint.
                val cross = a / (a - b)
                val first = a / 2.0 * (hours * cross)
                val second = b / 2.0 * (hours * (1 - cross))
                if (a > 0) used += first else regained += -first
                if (b > 0) used += second else regained += -second
            }
        }
        return used to regained
    }

    /**
     * Distance from the speed trace. Trapezoidal, matching PerformanceMeter, so the
     * two never disagree about how far a given drive was.
     */
    private fun integrateDistance(series: List<SeriesPoint>): Double {
        var metres = 0.0
        for (i in 1 until series.size) {
            val dtMs = series[i].atEpochMs - series[i - 1].atEpochMs
            // A gap this large means logging was interrupted, not that the car
            // travelled at the average of two readings minutes apart.
            if (dtMs <= 0 || dtMs > MAX_GAP_MS) continue
            val vAvg = (series[i].value + series[i - 1].value) / 2.0 / 3.6
            metres += vAvg * (dtMs / 1000.0)
        }
        return metres
    }

    private companion object {
        /** Beyond this in one step, the receiver jumped rather than the car moved. */
        const val MAX_SEGMENT_M = 400.0

        /** A gap longer than this is a pause in recording, not an hour of driving. */
        const val MAX_GAP_H = 1.0 / 60.0

        const val MAX_GAP_MS = 30_000L
    }
}

@Composable
fun TripDetailScreen(services: ServiceLocator, tripId: Long) {
    val vm = serviceViewModel(services) { TripDetailViewModel(it, tripId) }
    val detail by vm.detail.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    if (detail.loading) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) { CircularProgressIndicator() }
        return
    }

    val trip = detail.trip
    if (trip == null) {
        EmptyState("Bu sefer artık mevcut değil.")
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Extra at the foot, because the navigation bar sits over the bottom of
            // this column and was cutting the last chart's caption in half.
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            dateTimeFormatter.format(Instant.ofEpochMilli(trip.startedAtEpochMs)),
            style = MaterialTheme.typography.titleMedium
        )

        SummaryCard(detail, settings)

        HorizontalDivider()
        SectionLabel("Hız")
        if (detail.speedSeries.size < 2) {
            EmptyState("Bu sefer için hız verisi kaydedilmemiş.")
        } else {
            LineChart(
                points = detail.speedSeries.map {
                    ChartPoint(
                        it.atEpochMs.toDouble(),
                        settings.speedUnit.fromKmh(it.value)
                    )
                },
                valueFormatter = {
                    String.format(Locale.US, "%.0f %s", it, settings.speedUnit.suffix)
                },
                xFormatter = ::clockAt
            )
        }

        if (detail.powerSeries.size >= 2) {
            HorizontalDivider()
            SectionLabel("Güç")
            LineChart(
                points = detail.powerSeries.map { ChartPoint(it.atEpochMs.toDouble(), it.value) },
                lineColor = MaterialTheme.colorScheme.secondary,
                // Zero drawn in, so regeneration reads as below a line rather than
                // merely low on a chart whose floor happens to be negative.
                reference = 0.0,
                referenceColor = MaterialTheme.colorScheme.outline,
                valueFormatter = { String.format(Locale.US, "%.0f kW", it) },
                xFormatter = ::clockAt
            )
            Text(
                // Said plainly: a curve that dips under zero looks like an error
                // unless you know it is the car putting energy back.
                "Sıfırın altı geri kazanım — frende ve yokuş aşağı bataryaya geri " +
                    "veriyor. Bu seferde " +
                    "${String.format(Locale.US, "%.2f", detail.energyUsedKwh)} kWh " +
                    "harcandı, " +
                    "${String.format(Locale.US, "%.2f", detail.energyRegainedKwh)} kWh " +
                    "geri kazanıldı.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (detail.voltSeries.size >= 2) {
            HorizontalDivider()
        SectionLabel("Bu seferdeki 12V")
            LineChart(
                points = detail.voltSeries.map { ChartPoint(it.atEpochMs.toDouble(), it.value) },
                lineColor = MaterialTheme.colorScheme.tertiary,
                valueFormatter = { String.format(Locale.US, "%.2f V", it) },
                xFormatter = ::clockAt
            )
            Text(
                "Sürerken bu, akünün kendi durumu değil DC-DC dönüştürücünün çıkışı — " +
                    "akü sağlığı hakkında bir şey söyleyen, 12V ekranındaki dinlenmiş " +
                    "eğilim.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Everything else the trip wrote down. The screen used to show three charts
        // against a table holding ten or more series, so state of charge, pack
        // temperatures and battery health were recorded on every drive and had
        // nowhere to appear.
        detail.otherSeries.forEach { (meta, series) ->
            HorizontalDivider()
            SectionLabel(meta.label)
            LineChart(
                points = series.map { ChartPoint(it.atEpochMs.toDouble(), it.value) },
                lineColor = MaterialTheme.colorScheme.primary,
                valueFormatter = { String.format(Locale.US, "%.1f %s", it, meta.unit) },
                xFormatter = ::clockAt
            )
        }
    }
}

@Composable
private fun SummaryCard(detail: TripDetail, settings: AppSettings) {
    val trip = detail.trip ?: return
    // A trip that was never closed has no end time, and showed no duration at all.
    // Its last sample is when it stopped recording, which is the honest answer.
    val endedAt = trip.endedAtEpochMs ?: detail.lastSampleAt
    val duration = endedAt?.let { it - trip.startedAtEpochMs }?.takeIf { it > 0 }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Stat(
                    "Mesafe",
                    String.format(Locale.US, "%.1f", detail.distanceM / 1000.0),
                    "km"
                )
                Stat("Süre", duration?.let { formatDuration(it) } ?: "—", "")
                Stat("Ölçüm", detail.sampleCount.toString(), "")
            }
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Stat(
                    "En yüksek",
                    detail.maxKmh?.let { formatReading(settings.speedUnit.fromKmh(it)) } ?: "—",
                    settings.speedUnit.suffix
                )
                Stat(
                    "Ortalama",
                    detail.avgKmh?.let { formatReading(settings.speedUnit.fromKmh(it)) } ?: "—",
                    settings.speedUnit.suffix
                )
                Stat(
                    "12V aralığı",
                    if (detail.minVolts != null && detail.maxVolts != null) {
                        String.format(Locale.US, "%.1f–%.1f", detail.minVolts, detail.maxVolts)
                    } else "—",
                    "V"
                )
            }
            HorizontalDivider()
            // Its own row rather than a fourth column. Four of these across a phone
            // ran the units into each other — "km/hOrtalama · km/hTüketim" — and this
            // is the figure an EV driver compares between drives, so it gets the room.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Stat(
                    "Tüketim",
                    if (detail.distanceM > MIN_DISTANCE_FOR_CONSUMPTION_M) {
                        String.format(
                            Locale.US, "%.1f",
                            (detail.energyUsedKwh - detail.energyRegainedKwh) /
                                (detail.distanceM / 1000.0) * 100.0
                        )
                    } else "—",
                    "kWh/100km"
                )
                Stat(
                    "Harcanan",
                    if (detail.powerSeries.size >= 2) {
                        String.format(Locale.US, "%.2f", detail.energyUsedKwh)
                    } else "—",
                    "kWh"
                )
                Stat(
                    "Geri kazanım",
                    if (detail.powerSeries.size >= 2) {
                        String.format(Locale.US, "%.2f", detail.energyRegainedKwh)
                    } else "—",
                    "kWh"
                )
            }
        }
    }
}

@Composable
private fun RowScope.Stat(label: String, value: String, unit: String) {
    // Weighted rather than spaced apart. Three of these across a phone with
    // "Geri kazanılan · kWh" among them ran into each other — SpaceEvenly divides the
    // gaps, not the columns, so a long label simply grew past its neighbour. An equal
    // share each, and the label wraps inside its own column instead.
    Column(
        modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (unit.isNotBlank()) {
            Text(
                unit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                textAlign = TextAlign.Center
            )
        }
    }
}

private val dateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())
