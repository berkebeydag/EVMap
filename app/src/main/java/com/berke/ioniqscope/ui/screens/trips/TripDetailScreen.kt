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

/** What the battery computer calls the 12V rail; the only source on a car that will
 *  not answer the standard module-voltage PID. */
private const val BMS_AUX_VOLTAGE_KEY = "aux_voltage"

data class TripDetail(
    val trip: TripEntity?,
    /** Counted from the rows themselves; the trip row's own column can be stale. */
    val sampleCount: Int = 0,
    /** When recording actually stopped, for a trip that never closed. */
    val lastSampleAt: Long? = null,
    val speedSeries: List<SeriesPoint> = emptyList(),
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

            _detail.value = TripDetail(
                trip = trip,
                sampleCount = realCount,
                lastSampleAt = lastAt,
                speedSeries = speed,
                voltSeries = volts,
                maxKmh = speedStats?.maxValue,
                avgKmh = speedStats?.avgValue,
                distanceM = integrateDistance(speed),
                minVolts = voltStats?.minValue,
                maxVolts = voltStats?.maxValue,
                loading = false
            )
        }
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
            .padding(16.dp),
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
                }
            )
        }

        if (detail.voltSeries.size >= 2) {
            HorizontalDivider()
            SectionLabel("Bu seferdeki 12V")
            LineChart(
                points = detail.voltSeries.map { ChartPoint(it.atEpochMs.toDouble(), it.value) },
                lineColor = MaterialTheme.colorScheme.tertiary,
                valueFormatter = { String.format(Locale.US, "%.2f V", it) }
            )
            Text(
                "While driving this is the DC-DC converter's output rather than the " +
                    "battery's own state — the resting trend on the 12V screen is the " +
                    "one that says something about battery health.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                horizontalArrangement = Arrangement.SpaceEvenly
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
                horizontalArrangement = Arrangement.SpaceEvenly
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
        }
    }
}

@Composable
private fun Stat(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Monospace
        )
        Text(
            if (unit.isBlank()) label else "$label · $unit",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private val dateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())
