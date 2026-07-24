package com.berke.ioniqscope.ui.screens.battery

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.berke.ioniqscope.ServiceLocator
import com.berke.ioniqscope.data.AuxBatteryHealth
import com.berke.ioniqscope.data.AuxBatteryStatus
import com.berke.ioniqscope.data.AuxVoltageEntity
import com.berke.ioniqscope.ui.components.Banner
import com.berke.ioniqscope.ui.components.BannerTone
import com.berke.ioniqscope.ui.components.ChartPoint
import com.berke.ioniqscope.ui.components.EmptyState
import com.berke.ioniqscope.ui.components.LineChart
import com.berke.ioniqscope.ui.components.SectionLabel
import com.berke.ioniqscope.ui.serviceViewModel
import com.berke.ioniqscope.ui.theme.StatusAmber
import com.berke.ioniqscope.ui.theme.StatusGreen
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class AuxBatteryViewModel(services: ServiceLocator) : ViewModel() {

    private val dao = services.database.auxVoltageDao()

    val sessionStarts: StateFlow<List<AuxVoltageEntity>> = dao.observeSessionStarts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val health: StateFlow<AuxBatteryHealth> =
        combine(dao.observeSessionStarts(), dao.observeLatest()) { starts, latest ->
            AuxBatteryHealth.evaluate(starts, latest)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AuxBatteryHealth.empty)

    fun clearHistory() = viewModelScope.launch { dao.deleteAll() }
}

@Composable
fun AuxBatteryScreen(services: ServiceLocator) {
    val vm = serviceViewModel(services) { AuxBatteryViewModel(it) }
    val health by vm.health.collectAsStateWithLifecycle()
    val starts by vm.sessionStarts.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusCard(health)

        when (health.status) {
            AuxBatteryStatus.Critical -> Banner(
                title = "Below 12.0 V at rest",
                text = "This is the range where a 12V battery starts failing to crank the " +
                    "car awake. On an Ioniq this is also the signature of the ICCU issue. " +
                    "Worth getting checked rather than waiting for a no-start.",
                tone = BannerTone.Error
            )
            AuxBatteryStatus.Low -> Banner(
                title = "Lower than it should be",
                text = "A rested 12V battery normally sits near 12.6 V. Persistently below " +
                    "12.2 V suggests it is not being fully recharged.",
                tone = BannerTone.Warning
            )
            AuxBatteryStatus.Good -> if (health.isDeclining) {
                Banner(
                    title = "Level is fine but trending down",
                    text = "The level is still healthy, but session-start readings have been " +
                        "falling. Worth keeping an eye on.",
                    tone = BannerTone.Warning
                )
            }
            AuxBatteryStatus.Unknown -> Banner(
                text = "No readings yet. Connect to the adapter with “Modül voltajı (12V)” " +
                    "among the polled PIDs and a reading is recorded automatically.",
                tone = BannerTone.Info
            )
        }

        HorizontalDivider()
        SectionLabel("Trend at session start")
        Text(
            "Only readings taken as a session begins are plotted. A reading taken while " +
                "driving shows the DC-DC converter's output, not the battery's own state, " +
                "so mixing them in would flatten the very trend we are looking for.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (starts.size < 2) {
            EmptyState("Needs at least two sessions before a trend can be drawn.")
        } else {
            LineChart(
                points = starts.map { ChartPoint(it.atEpochMs.toDouble(), it.volts) },
                reference = AuxBatteryHealth.LOW_V,
                valueFormatter = { String.format(Locale.US, "%.2f V", it) }
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    dateFormatter.format(Instant.ofEpochMilli(starts.first().atEpochMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    dateFormatter.format(Instant.ofEpochMilli(starts.last().atEpochMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "Dashed line is ${AuxBatteryHealth.LOW_V} V.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider()
        Text(
            "IoniqScope does not keep the adapter awake to watch a parked car. A dongle " +
                "left plugged in and connected is itself a drain, which on a car with a " +
                "known 12V problem would make this app part of the problem.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (starts.isNotEmpty()) {
            TextButton(onClick = vm::clearHistory) { Text("Clear 12V history") }
        }
    }
}

@Composable
private fun StatusCard(health: AuxBatteryHealth) {
    val scheme = MaterialTheme.colorScheme
    val accent: Color = when (health.status) {
        AuxBatteryStatus.Good -> StatusGreen
        AuxBatteryStatus.Low -> StatusAmber
        AuxBatteryStatus.Critical -> scheme.error
        AuxBatteryStatus.Unknown -> scheme.outline
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer)
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when (health.status) {
                    AuxBatteryStatus.Good -> "HEALTHY"
                    AuxBatteryStatus.Low -> "LOW"
                    AuxBatteryStatus.Critical -> "CRITICAL"
                    AuxBatteryStatus.Unknown -> "NO DATA"
                },
                style = MaterialTheme.typography.labelLarge,
                color = accent
            )
            // Same reason as the dashboard hero: an em-dash at display size reads as
            // a stray rule, not as "no value".
            if (health.latestVolts == null) {
                Text(
                    "no readings yet",
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.outline,
                    modifier = Modifier.padding(vertical = 18.dp)
                )
            } else {
                Text(
                    String.format(Locale.US, "%.2f", health.latestVolts),
                    style = MaterialTheme.typography.displayLarge,
                    fontFamily = FontFamily.Monospace,
                    color = accent
                )
                Text(
                    "volts",
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurfaceVariant
                )
            }

            health.latestAtEpochMs?.let {
                Text(
                    "last read ${dateTimeFormatter.format(Instant.ofEpochMilli(it))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            health.trendVoltsPerWeek?.let {
                Text(
                    String.format(Locale.US, "%+.3f V per week across %d sessions", it, health.sessionStartCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (health.isDeclining) StatusAmber else scheme.onSurfaceVariant
                )
            }
        }
    }
}

private val dateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault())
private val dateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault())
