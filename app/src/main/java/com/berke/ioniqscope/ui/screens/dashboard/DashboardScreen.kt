package com.berke.ioniqscope.ui.screens.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.berke.ioniqscope.ServiceLocator
import com.berke.ioniqscope.connection.ConnectionState
import com.berke.ioniqscope.data.AppSettings
import com.berke.ioniqscope.data.PidCatalog
import com.berke.ioniqscope.obd.Pid
import com.berke.ioniqscope.obd.Reading
import com.berke.ioniqscope.obd.VehicleState
import com.berke.ioniqscope.ui.components.Banner
import com.berke.ioniqscope.ui.components.BannerTone
import com.berke.ioniqscope.ui.components.EmptyState
import com.berke.ioniqscope.ui.components.GaugeCard
import com.berke.ioniqscope.ui.components.formatReading
import com.berke.ioniqscope.ui.serviceViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class DashboardViewModel(services: ServiceLocator) : ViewModel() {

    private val manager = services.connectionManager

    val vehicleState: StateFlow<VehicleState> = manager.vehicleState
    val connectionState: StateFlow<ConnectionState> = manager.connectionState

    val settings: StateFlow<AppSettings> = services.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /**
     * Takes ownership of the poll loop with the user's chosen PID set.
     * Called when the screen becomes visible; the Performance screen takes it back
     * the same way, so whichever screen you are looking at drives the adapter.
     */
    fun claimPolling(settings: AppSettings) {
        val pids = PidCatalog.resolve(settings.dashboardPidKeys)
        manager.startDashboardPolling(pids, settings.pollIntervalMs.toLong())
    }
}

@Composable
fun DashboardScreen(services: ServiceLocator, onConnect: () -> Unit) {
    val vm = serviceViewModel(services) { DashboardViewModel(it) }
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.vehicleState.collectAsStateWithLifecycle()

    val connected = connection is ConnectionState.Connected

    LaunchedEffect(connected, settings.dashboardPidKeys, settings.pollIntervalMs) {
        if (connected) vm.claimPolling(settings)
    }

    val selected = PidCatalog.resolve(settings.dashboardPidKeys)
    if (selected.isEmpty()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            NotConnectedBanner(connected, onConnect)
            EmptyState("Hiç PID seçilmemiş. Ayarlar'dan seç.")
        }
        return
    }

    // Speed gets its own hero card — it is the one value you read at a glance while
    // moving, so it should never be one tile among equals.
    val speed = selected.firstOrNull { it.key == PidCatalog.speed.key }
    val rest = selected.filter { it.key != PidCatalog.speed.key }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (!connected) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                NotConnectedBanner(connected = false, onConnect = onConnect)
            }
        }

        if (speed != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SpeedHeroCard(
                    reading = state[speed.key],
                    settings = settings
                )
            }
        }

        items(rest, key = { it.key }) { pid ->
            val reading: Reading? = state[pid.key]
            GaugeCard(
                label = pid.label,
                value = reading?.let { formatReading(it.value) } ?: "—",
                // Unit comes from the PID and the user's preference, never from the
                // reading — otherwise a disconnected card falls back to the raw unit
                // while the app is set to something else.
                unit = displayUnit(pid, settings),
                stale = reading == null
            )
        }

        val silent = selected.filter { state[it.key] == null }
        if (connected && silent.isNotEmpty() && state.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Yanıt yok: ${silent.joinToString { it.label }}. " +
                        "Standart PID'lerin hepsi elektrikli araçta desteklenmiyor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NotConnectedBanner(connected: Boolean, onConnect: () -> Unit) {
    if (connected) return
    Banner(
        title = "Bağlı değil",
        text = "Canlı veriyi görmek için adaptöre bağlan.",
        tone = BannerTone.Warning,
        actionLabel = "Bağlan",
        onAction = onConnect
    )
}

/** Full-width speed readout: large, monospaced, legible in a moving car. */
@Composable
private fun SpeedHeroCard(reading: Reading?, settings: AppSettings) {
    val scheme = MaterialTheme.colorScheme
    val kmh = reading?.value ?: 0.0
    val shown = settings.speedUnit.fromKmh(kmh)

    // Fills as speed rises. Purely a glanceable cue — the number is the real content.
    val fraction by animateFloatAsState(
        targetValue = (kmh / 200.0).coerceIn(0.0, 1.0).toFloat(),
        label = "speedFill"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(
                                scheme.primary.copy(alpha = 0.16f),
                                scheme.primary.copy(alpha = 0.02f)
                            )
                        ),
                        size = Size(size.width * fraction, size.height),
                        topLeft = Offset.Zero
                    )
                }
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = PidCatalog.speed.label.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant
            )
            if (reading == null) {
                // A 72sp em-dash reads as a stray rule across the card, so the
                // waiting state gets its own, quieter treatment.
                Text(
                    text = "waiting for data",
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 18.dp)
                )
            } else {
                Text(
                    text = formatReading(shown),
                    style = MaterialTheme.typography.displayLarge,
                    fontFamily = FontFamily.Monospace,
                    color = scheme.primary,
                    textAlign = TextAlign.Center
                )
            }
            Text(
                text = settings.speedUnit.suffix,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

private fun displayUnit(pid: Pid, settings: AppSettings): String =
    if (pid.key == PidCatalog.speed.key) settings.speedUnit.suffix else pid.unit
