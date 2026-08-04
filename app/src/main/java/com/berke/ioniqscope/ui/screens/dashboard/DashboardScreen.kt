package com.berke.ioniqscope.ui.screens.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.berke.ioniqscope.ServiceLocator
import com.berke.ioniqscope.connection.ConnectionState
import com.berke.ioniqscope.connection.GPS_SPEED_KEY
import com.berke.ioniqscope.data.AppSettings
import com.berke.ioniqscope.data.PidCatalog
import com.berke.ioniqscope.obd.Pid
import com.berke.ioniqscope.obd.Reading
import com.berke.ioniqscope.obd.VehicleProfile
import com.berke.ioniqscope.obd.VehicleState
import com.berke.ioniqscope.ui.components.Banner
import com.berke.ioniqscope.ui.components.BannerTone
import com.berke.ioniqscope.ui.components.EmptyState
import com.berke.ioniqscope.ui.components.GaugeCard
import com.berke.ioniqscope.ui.components.formatReading
import com.berke.ioniqscope.ui.screens.chargers.LocationFinder
import com.berke.ioniqscope.ui.serviceViewModel
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DashboardViewModel(private val services: ServiceLocator) : ViewModel() {

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
    val profile = VehicleProfile.byId(settings.vehicleProfileId)
    // Published by the connection manager alongside everything else, so the trip
    // logger and the drive detector see the same number this dial does.
    val gpsSpeed = state[GPS_SPEED_KEY]?.value

    LaunchedEffect(connected, settings.dashboardPidKeys, settings.pollIntervalMs) {
        if (connected) vm.claimPolling(settings)
    }

    val selected = PidCatalog.resolve(settings.dashboardPidKeys)
    val profileSpecs = profile.battery?.reads.orEmpty().flatMap { it.values }
        .filter { state.containsKey(it.key) }

    if (selected.isEmpty() && profileSpecs.isEmpty()) {
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
                    gpsSpeedKmh = gpsSpeed,
                    settings = settings
                )
            }
        }

        // The car's own battery figures, ahead of the standard PIDs, because on an EV
        // they are the ones that answer. Labels and units come from the profile, so
        // nothing here has to know what an E-GMP is.
        if (profileSpecs.isNotEmpty()) {
            items(profileSpecs, key = { "batt_" + it.key }) { spec ->
                GaugeCard(
                    label = spec.label,
                    value = state[spec.key]?.let { String.format(Locale.US, "%.1f", it.value) } ?: "—",
                    unit = spec.unit,
                    stale = false
                )
            }
            // Power is not read; it is the product of two things that were, and it is
            // the number a driver watches while charging.
            val amps = state["hv_current"]?.value
            val volts = state["hv_voltage"]?.value
            if (amps != null && volts != null) {
                item(key = "batt_power") {
                    GaugeCard(
                        label = "Güç",
                        value = String.format(Locale.US, "%.1f", amps * volts / 1000.0),
                        unit = "kW",
                        stale = false
                    )
                }
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

        // Speed drops out of this list once the receiver is answering for it: telling
        // somebody a reading is missing while showing it to them is just wrong.
        val silent = selected.filter {
            state[it.key] == null && !(it.key == PidCatalog.speed.key && gpsSpeed != null)
        }
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

/**
 * Speed, as a dial rather than a number in a box.
 *
 * It is the one value read at a glance from a moving car, and a glance takes in a
 * shape faster than it reads a figure — how far round the arc has swept says "fast" or
 * "slow" before the digits resolve. The number is still the content; the arc is what
 * makes it answerable without focusing on it.
 *
 * The top of the scale is fixed rather than following the reading. A dial that
 * rescales itself has no shape to learn, and the whole point of the arc is that the
 * same angle means the same speed every time.
 */
@Composable
private fun SpeedHeroCard(reading: Reading?, gpsSpeedKmh: Double?, settings: AppSettings) {
    val scheme = MaterialTheme.colorScheme
    // The wire first, the sky second. A car that reports its own speed is telling you
    // what its wheels are doing; the receiver is telling you what the whole car is
    // doing over the ground, which is the same number until it is not — a wheelspin,
    // a tunnel, a hill of trees.
    val fromGps = reading == null && gpsSpeedKmh != null
    val kmh = reading?.value ?: gpsSpeedKmh ?: 0.0
    val haveSpeed = reading != null || gpsSpeedKmh != null
    val shown = settings.speedUnit.fromKmh(kmh)

    // The fastest seen since the screen opened. Remembered rather than stored: it is
    // a fact about this drive, and a top speed carried over from last week would be
    // read as this one's.
    var peak by remember { mutableStateOf(0.0) }
    if (kmh > peak) peak = kmh

    val fraction by animateFloatAsState(
        targetValue = (kmh / SPEED_FULL_SCALE).coerceIn(0.0, 1.0).toFloat(),
        label = "speedFill"
    )

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = scheme.surfaceContainer,
        border = BorderStroke(1.dp, scheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.size(232.dp), contentAlignment = Alignment.Center) {
                val track = scheme.surfaceContainerHigh
                val accent = scheme.primary
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 14.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    // Open at the bottom, which is where a dial's scale is read from
                    // and the one part of the circle the eye does not need.
                    drawArc(
                        color = track,
                        startAngle = ARC_START,
                        sweepAngle = ARC_SWEEP,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                    if (fraction > 0f) {
                        drawArc(
                            color = accent,
                            startAngle = ARC_START,
                            sweepAngle = ARC_SWEEP * fraction,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        // Named for where it came from. A speed off the satellites and
                        // a speed off the car are different measurements, and a driver
                        // comparing this against the dashboard in front of them should
                        // know which one they are holding.
                        if (fromGps) "HIZ · GPS" else PidCatalog.speed.label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (fromGps) scheme.tertiary else scheme.onSurfaceVariant
                    )
                    if (!haveSpeed) {
                        Text(
                            "veri bekleniyor",
                            style = MaterialTheme.typography.titleMedium,
                            color = scheme.outline,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 14.dp)
                        )
                    } else {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                formatReading(shown),
                                style = MaterialTheme.typography.displayLarge,
                                fontFamily = FontFamily.Monospace,
                                color = scheme.onSurface
                            )
                            Text(
                                settings.speedUnit.suffix,
                                style = MaterialTheme.typography.titleSmall,
                                color = scheme.primary,
                                modifier = Modifier.padding(start = 6.dp, bottom = 10.dp)
                            )
                        }
                        if (peak > 0) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = scheme.surfaceContainerHigh
                            ) {
                                Text(
                                    "maks ${formatReading(settings.speedUnit.fromKmh(peak))} " +
                                        settings.speedUnit.suffix,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(
                                        horizontal = 10.dp,
                                        vertical = 4.dp
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // The scale, so the arc means something the first time it is seen.
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                listOf(0.0, SPEED_FULL_SCALE / 2, SPEED_FULL_SCALE).forEach {
                    Text(
                        formatReading(settings.speedUnit.fromKmh(it)),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

/** Where the dial starts, and how far it runs. Open at the bottom by 90 degrees. */
private const val ARC_START = 135f
private const val ARC_SWEEP = 270f

/** The top of the dial, in km/h. Fixed, so the same angle always means the same speed. */
private const val SPEED_FULL_SCALE = 180.0

private fun displayUnit(pid: Pid, settings: AppSettings): String =
    if (pid.key == PidCatalog.speed.key) settings.speedUnit.suffix else pid.unit
