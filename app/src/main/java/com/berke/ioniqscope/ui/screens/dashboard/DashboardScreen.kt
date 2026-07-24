package com.berke.ioniqscope.ui.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.berke.ioniqscope.ServiceLocator
import com.berke.ioniqscope.connection.ConnectionState
import com.berke.ioniqscope.data.AppSettings
import com.berke.ioniqscope.data.PidCatalog
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
fun DashboardScreen(services: ServiceLocator) {
    val vm = serviceViewModel(services) { DashboardViewModel(it) }
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.vehicleState.collectAsStateWithLifecycle()

    val connected = connection is ConnectionState.Connected

    LaunchedEffect(connected, settings.dashboardPidKeys, settings.pollIntervalMs) {
        if (connected) vm.claimPolling(settings)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!connected) {
            Banner(
                title = "Not connected",
                text = "Connect to your adapter on the Connect tab to see live data.",
                tone = BannerTone.Warning,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        val selected = PidCatalog.resolve(settings.dashboardPidKeys)
        if (selected.isEmpty()) {
            EmptyState("No PIDs selected. Pick some in Settings.")
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        ) {
            items(selected, key = { it.key }) { pid ->
                val reading: Reading? = state[pid.key]
                GaugeCard(
                    label = pid.label,
                    value = reading?.let { formatDisplayValue(pid.key, it, settings) } ?: "—",
                    unit = reading?.let { displayUnit(pid.key, it, settings) } ?: pid.unit,
                    stale = reading == null
                )
            }
        }

        // Any PID the vehicle simply does not answer shows as "—" above; call it out
        // rather than letting it look like a bug.
        val silent = selected.filter { state[it.key] == null }
        if (connected && silent.isNotEmpty() && state.isNotEmpty()) {
            Text(
                "No response for: ${silent.joinToString { it.label }}. " +
                    "Not every standard PID is supported on an EV.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatDisplayValue(key: String, reading: Reading, settings: AppSettings): String =
    if (key == PidCatalog.speed.key) {
        formatReading(settings.speedUnit.fromKmh(reading.value))
    } else {
        formatReading(reading.value)
    }

private fun displayUnit(key: String, reading: Reading, settings: AppSettings): String =
    if (key == PidCatalog.speed.key) settings.speedUnit.suffix else reading.unit
