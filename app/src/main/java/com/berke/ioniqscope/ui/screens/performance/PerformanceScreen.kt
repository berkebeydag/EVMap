package com.berke.ioniqscope.ui.screens.performance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.berke.ioniqscope.R
import com.berke.ioniqscope.ServiceLocator
import com.berke.ioniqscope.connection.ConnectionState
import com.berke.ioniqscope.connection.PerfRunRecorder
import com.berke.ioniqscope.data.AppSettings
import com.berke.ioniqscope.data.PerfRunEntity
import com.berke.ioniqscope.performance.PerfState
import com.berke.ioniqscope.ui.components.Banner
import com.berke.ioniqscope.ui.components.BannerTone
import com.berke.ioniqscope.ui.components.EmptyState
import com.berke.ioniqscope.ui.components.SectionLabel
import com.berke.ioniqscope.ui.components.formatReading
import com.berke.ioniqscope.ui.components.formatSeconds
import com.berke.ioniqscope.ui.components.formatSecondsBare
import com.berke.ioniqscope.ui.serviceViewModel
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class PerformanceViewModel(services: ServiceLocator) : ViewModel() {

    private val manager = services.connectionManager
    private val dao = services.database.perfRunDao()

    val perfState: StateFlow<PerfState> = manager.perfState
    val connectionState: StateFlow<ConnectionState> = manager.connectionState

    val settings: StateFlow<AppSettings> = services.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val runs: StateFlow<List<PerfRunEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val best0To100: StateFlow<Long?> = dao.observeBest0To100()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Speed only, ~20 Hz — see ObdConnectionManager.startPerformanceMode. */
    fun claimPolling() = manager.startPerformanceMode()

    fun resetMeter() = manager.performanceMeter.reset()

    fun deleteRun(id: Long) = viewModelScope.launch { dao.delete(id) }
}

@Composable
fun PerformanceScreen(services: ServiceLocator, onConnect: () -> Unit) {
    val vm = serviceViewModel(services) { PerformanceViewModel(it) }
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    val perf by vm.perfState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val runs by vm.runs.collectAsStateWithLifecycle()
    val best by vm.best0To100.collectAsStateWithLifecycle()

    val connected = connection is ConnectionState.Connected

    LaunchedEffect(connected) {
        if (connected) vm.claimPolling()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Banner(
                title = "Track use only",
                text = stringResource(R.string.perf_disclaimer),
                tone = BannerTone.Warning,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        if (!connected) {
            item {
                Banner(
                    title = "Not connected",
                    text = "Connect to your adapter to arm the timer.",
                    tone = BannerTone.Error,
                    actionLabel = "Connect",
                    onAction = onConnect
                )
            }
        }

        item { LiveRunPanel(perf, settings) }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = vm::resetMeter) { Text("Reset timer") }
                Text(
                    "Launch is detected automatically — just come to a stop and go.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            HorizontalDivider()
            SectionLabel("Run history", Modifier.padding(top = 8.dp))
        }

        if (runs.isEmpty()) {
            item { EmptyState("No runs saved yet. A run is stored once it crosses at least one target.") }
        } else {
            items(runs, key = { it.id }) { run ->
                RunRow(
                    run = run,
                    isBest = best != null && run.zeroTo100Ms == best,
                    onDelete = { vm.deleteRun(run.id) }
                )
            }
        }
    }
}

/** The in-motion view: two numbers big enough to read at a glance, then detail. */
@Composable
private fun LiveRunPanel(perf: PerfState, settings: AppSettings) {
    val scheme = MaterialTheme.colorScheme
    val running = perf.phase == PerfState.Phase.RUNNING
    val accent = when (perf.phase) {
        PerfState.Phase.RUNNING -> scheme.secondary
        PerfState.Phase.DONE -> scheme.primary
        PerfState.Phase.IDLE -> scheme.outline
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                when (perf.phase) {
                    PerfState.Phase.IDLE -> "READY"
                    PerfState.Phase.RUNNING -> "RUNNING"
                    PerfState.Phase.DONE -> "DONE"
                },
                style = MaterialTheme.typography.labelLarge,
                color = accent
            )

            // 0-100 is the headline number; while running it counts up live.
            val headline = perf.splits[PerfRunRecorder.SPLIT_100]
            Text(
                text = headline?.let { formatSecondsBare(it) }
                    ?: if (running) formatSecondsBare(perf.elapsedMs) else "0.00",
                style = MaterialTheme.typography.displayLarge,
                fontFamily = FontFamily.Monospace,
                color = accent,
                textAlign = TextAlign.Center
            )
            Text(
                if (headline != null) "seconds · 0-100 km/h" else "seconds elapsed",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                BigStat(
                    "Speed",
                    formatReading(settings.speedUnit.fromKmh(perf.currentKmh)),
                    settings.speedUnit.suffix
                )
                BigStat(
                    "Max",
                    formatReading(settings.speedUnit.fromKmh(perf.maxKmh)),
                    settings.speedUnit.suffix
                )
                BigStat("Distance", formatReading(perf.distanceM), "m")
            }

            if (perf.splits.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                perf.splits.forEach { (label, ms) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            formatSeconds(ms),
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                            color = scheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BigStat(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Monospace
        )
        Text(
            "$label · $unit",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RunRow(run: PerfRunEntity, isBest: Boolean, onDelete: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                if (isBest) scheme.primary.copy(alpha = 0.12f) else scheme.surfaceContainer,
                RoundedCornerShape(12.dp)
            )
            .padding(12.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        run.zeroTo100Ms?.let { formatSeconds(it) } ?: "did not reach 100",
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Monospace,
                        color = if (isBest) scheme.primary else scheme.onSurface
                    )
                    Text(
                        timestampFormatter.format(Instant.ofEpochMilli(run.recordedAtEpochMs)) +
                            if (isBest) "  ·  best" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete run")
                }
            }

            val details = buildList {
                run.zeroTo50Ms?.let { add("0-50 ${formatSeconds(it)}") }
                run.zeroTo120Ms?.let { add("0-120 ${formatSeconds(it)}") }
                run.zeroTo100mMs?.let { add("100 m ${formatSeconds(it)}") }
                run.zeroTo402mMs?.let { add("402 m ${formatSeconds(it)}") }
                add("max ${formatReading(run.maxKmh)} km/h")
            }
            Text(
                details.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private val timestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())
