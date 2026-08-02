package com.berke.ioniqscope.ui.screens.performance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
                title = "Yalnızca pist",
                text = stringResource(R.string.perf_disclaimer),
                tone = BannerTone.Warning,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        if (!connected) {
            item {
                Banner(
                    title = "Bağlı değil",
                    text = "Kronometreyi kurmak için adaptöre bağlan.",
                    tone = BannerTone.Error,
                    actionLabel = "Bağlan",
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
                TextButton(onClick = vm::resetMeter) { Text("Kronometreyi sıfırla") }
                Text(
                    "Kalkış kendiliğinden algılanır — dur ve bas, yeter.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            HorizontalDivider()
            SectionLabel("Ölçüm geçmişi", Modifier.padding(top = 8.dp))
        }

        if (runs.isEmpty()) {
            item { EmptyState("Henüz kayıtlı ölçüm yok. Bir ölçüm, en az bir hedefi geçtiğinde kaydedilir.") }
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

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = scheme.surfaceContainer,
        border = BorderStroke(1.dp, scheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                when (perf.phase) {
                    PerfState.Phase.IDLE -> "HAZIR"
                    PerfState.Phase.RUNNING -> "ÖLÇÜYOR"
                    PerfState.Phase.DONE -> "BİTTİ"
                },
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                letterSpacing = 3.sp
            )

            // 0-100 is the headline; while running it counts up live. The unit sits
            // beside it at a fifth of the size rather than on a line of its own — it
            // is part of the reading, not a caption under it.
            val headline = perf.splits[PerfRunRecorder.SPLIT_100]
            Row(
                Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = headline?.let { formatSecondsBare(it) }
                        ?: if (running) formatSecondsBare(perf.elapsedMs) else "0,00",
                    style = MaterialTheme.typography.displayLarge,
                    color = scheme.onSurface
                )
                Text(
                    "s",
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LiveStat(
                    "ANLIK",
                    formatReading(settings.speedUnit.fromKmh(perf.currentKmh)),
                    settings.speedUnit.suffix,
                    highlight = true,
                    modifier = Modifier.weight(1f)
                )
                LiveStat(
                    "MAKS",
                    formatReading(settings.speedUnit.fromKmh(perf.maxKmh)),
                    settings.speedUnit.suffix,
                    modifier = Modifier.weight(1f)
                )
                LiveStat(
                    "MESAFE",
                    formatReading(perf.distanceM),
                    "m",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    // The split that the whole run is for, given a card of its own rather than a row
    // in a list of them: it is the number anybody quotes about a car.
    val hundred = perf.splits[PerfRunRecorder.SPLIT_100]
    if (hundred != null) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = scheme.surfaceContainer,
            border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "0-100 km/h",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            formatSecondsBare(hundred),
                            style = MaterialTheme.typography.headlineMedium,
                            color = scheme.primary
                        )
                        Text(
                            "s",
                            style = MaterialTheme.typography.titleSmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
        }
    }

    // Every other split, still shown, but under the headline rather than beside it.
    if (perf.splits.size > 1) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = scheme.surfaceContainer,
            border = BorderStroke(1.dp, scheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                perf.splits.filterKeys { it != PerfRunRecorder.SPLIT_100 }
                    .forEach { (label, ms) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant
                            )
                            Text(formatSeconds(ms), style = MaterialTheme.typography.labelMedium)
                        }
                    }
            }
        }
    }
}

/** One live figure during a run: what it is, the number, and its unit. */
@Composable
private fun LiveStat(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outline),
        modifier = modifier
    ) {
        Column(
            Modifier.padding(10.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = if (highlight) scheme.primary else scheme.onSurface,
                modifier = Modifier.padding(top = 3.dp)
            )
            Text(
                unit,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RunRow(run: PerfRunEntity, isBest: Boolean, onDelete: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            if (isBest) scheme.secondary.copy(alpha = 0.5f) else scheme.outline
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    timestampFormatter.format(Instant.ofEpochMilli(run.recordedAtEpochMs)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    run.zeroTo100Ms?.let { formatSeconds(it) } ?: "100'e ulaşmadı",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isBest) scheme.secondary else scheme.onSurface
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Ölçümü sil",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // The rest of the run, on one quiet line. It is what you look at after
            // deciding the headline is interesting, not while deciding.
            val details = buildList {
                run.zeroTo50Ms?.let { add("0-50 ${formatSeconds(it)}") }
                run.zeroTo120Ms?.let { add("0-120 ${formatSeconds(it)}") }
                run.zeroTo100mMs?.let { add("100 m ${formatSeconds(it)}") }
                run.zeroTo402mMs?.let { add("402 m ${formatSeconds(it)}") }
                add("azami ${formatReading(run.maxKmh)} km/h")
            }
            Text(
                details.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private val timestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())
