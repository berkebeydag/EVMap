package com.berke.ioniqscope.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.berke.ioniqscope.R
import com.berke.ioniqscope.ServiceLocator
import com.berke.ioniqscope.connection.ConnectionState
import com.berke.ioniqscope.data.AuxBatteryHealth
import com.berke.ioniqscope.data.AuxBatteryStatus
import com.berke.ioniqscope.obd.ReadinessReport
import com.berke.ioniqscope.ui.components.Banner
import com.berke.ioniqscope.ui.components.BannerTone
import com.berke.ioniqscope.ui.components.EmptyState
import com.berke.ioniqscope.ui.components.SectionLabel
import com.berke.ioniqscope.ui.serviceViewModel
import com.berke.ioniqscope.ui.theme.StatusAmber
import com.berke.ioniqscope.ui.theme.StatusGreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

data class DiagnosticsUiState(
    val busy: Boolean = false,
    /** null = never read this session; empty list = read, none stored. */
    val codes: List<String>? = null,
    val readiness: ReadinessReport? = null,
    val error: String? = null,
    val message: String? = null
)

class DiagnosticsViewModel(services: ServiceLocator) : ViewModel() {

    private val manager = services.connectionManager
    private val auxDao = services.database.auxVoltageDao()

    val connectionState: StateFlow<ConnectionState> = manager.connectionState

    val auxHealth: StateFlow<AuxBatteryHealth> =
        combine(auxDao.observeSessionStarts(), auxDao.observeLatest()) { starts, latest ->
            AuxBatteryHealth.evaluate(starts, latest)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AuxBatteryHealth.empty)

    private val _ui = MutableStateFlow(DiagnosticsUiState())
    val ui: StateFlow<DiagnosticsUiState> = _ui.asStateFlow()

    fun readCodes() {
        _ui.value = _ui.value.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            manager.readDtcs().fold(
                onSuccess = { codes -> _ui.value = _ui.value.copy(busy = false, codes = codes) },
                onFailure = { e ->
                    _ui.value = _ui.value.copy(
                        busy = false,
                        error = e.message ?: "Could not read trouble codes."
                    )
                }
            )
        }
    }

    fun runInspectionCheck() {
        _ui.value = _ui.value.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            manager.readReadiness().fold(
                onSuccess = { report ->
                    _ui.value = _ui.value.copy(busy = false, readiness = report)
                },
                onFailure = { e ->
                    _ui.value = _ui.value.copy(
                        busy = false,
                        error = e.message ?: "Could not read readiness status."
                    )
                }
            )
        }
    }

    /** Only ever called after the user confirms in the dialog. */
    fun clearCodes() {
        _ui.value = _ui.value.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            manager.clearDtcs().fold(
                onSuccess = { acknowledged ->
                    _ui.value = DiagnosticsUiState(
                        busy = false,
                        codes = if (acknowledged) emptyList() else _ui.value.codes,
                        message = if (acknowledged) {
                            "Codes cleared. Re-read to confirm nothing has come straight back. " +
                                "Readiness monitors are also reset, so an inspection check will " +
                                "report incomplete until the car has been driven again."
                        } else {
                            "The ECU did not acknowledge the clear request (no 44 response)."
                        }
                    )
                },
                onFailure = { e ->
                    _ui.value = _ui.value.copy(
                        busy = false,
                        error = e.message ?: "Could not clear trouble codes."
                    )
                }
            )
        }
    }
}

@Composable
fun DiagnosticsScreen(
    services: ServiceLocator,
    onConnect: () -> Unit,
    onOpenConsole: () -> Unit,
    onOpenAuxBattery: () -> Unit
) {
    val vm = serviceViewModel(services) { DiagnosticsViewModel(it) }
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val aux by vm.auxHealth.collectAsStateWithLifecycle()

    val connected = connection is ConnectionState.Connected
    var showConfirm by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!connected) {
            item {
                Banner(
                    title = "Not connected",
                    text = "Connect to your adapter to read trouble codes.",
                    tone = BannerTone.Warning,
                    modifier = Modifier.padding(top = 12.dp),
                    actionLabel = "Connect",
                    onAction = onConnect
                )
            }
        }

        item { AuxBatteryCard(aux, onOpenAuxBattery) }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = vm::readCodes, enabled = connected && !ui.busy) {
                    Text("Read codes")
                }
                OutlinedButton(
                    onClick = { showConfirm = true },
                    enabled = connected && !ui.busy,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.dtc_clear_confirm))
                }
                if (ui.busy) CircularProgressIndicator()
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::runInspectionCheck, enabled = connected && !ui.busy) {
                    Text("Inspection check")
                }
                TextButton(onClick = onOpenConsole) { Text("Raw console") }
            }
        }

        ui.error?.let { item { Banner(title = "Error", text = it, tone = BannerTone.Error) } }
        ui.message?.let { item { Banner(text = it, tone = BannerTone.Success) } }

        ui.readiness?.let { report -> item { ReadinessCard(report) } }

        item { SectionLabel("Stored codes") }

        when (val codes = ui.codes) {
            null -> item {
                EmptyState("Tap “Read codes” to query the vehicle (OBD mode 03).")
            }
            else -> if (codes.isEmpty()) {
                item {
                    Banner(
                        title = "No stored codes",
                        text = "The vehicle reported no stored diagnostic trouble codes.",
                        tone = BannerTone.Success
                    )
                }
            } else {
                items(codes) { code -> DtcRow(code) }
            }
        }
    }

    if (showConfirm) {
        ClearCodesDialog(
            onConfirm = {
                showConfirm = false
                vm.clearCodes()
            },
            onDismiss = { showConfirm = false }
        )
    }
}

@Composable
private fun AuxBatteryCard(health: AuxBatteryHealth, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accent = when (health.status) {
        AuxBatteryStatus.Good -> StatusGreen
        AuxBatteryStatus.Low -> StatusAmber
        AuxBatteryStatus.Critical -> scheme.error
        AuxBatteryStatus.Unknown -> scheme.outline
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
        onClick = onClick
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = scheme.surfaceContainer),
            leadingContent = {
                Icon(Icons.Filled.BatteryAlert, contentDescription = null, tint = accent)
            },
            headlineContent = { Text("12V battery") },
            supportingContent = {
                Text(
                    when (health.status) {
                        AuxBatteryStatus.Unknown -> "No readings yet"
                        else -> buildString {
                            append(
                                health.latestVolts?.let {
                                    String.format(Locale.US, "%.2f V", it)
                                } ?: "—"
                            )
                            append(" · ")
                            append(
                                when (health.status) {
                                    AuxBatteryStatus.Good ->
                                        if (health.isDeclining) "trending down" else "healthy"
                                    AuxBatteryStatus.Low -> "low"
                                    AuxBatteryStatus.Critical -> "critical"
                                    AuxBatteryStatus.Unknown -> ""
                                }
                            )
                        }
                    }
                )
            },
            trailingContent = { Text("Trend →", color = scheme.primary) }
        )
    }
}

@Composable
private fun ReadinessCard(report: ReadinessReport) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (report.looksReady) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                    contentDescription = null,
                    tint = if (report.looksReady) StatusGreen else StatusAmber,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    if (report.looksReady) "Looks ready" else "Not ready",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (report.looksReady) StatusGreen else StatusAmber
                )
            }

            Text(
                "Warning light: ${if (report.milOn) "ON" else "off"}  ·  " +
                    "stored codes: ${report.storedDtcCount}",
                style = MaterialTheme.typography.bodyMedium
            )

            if (report.pendingCodes.isNotEmpty()) {
                Text(
                    "Pending: ${report.pendingCodes.joinToString()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StatusAmber
                )
            }

            if (report.supportedMonitors.isEmpty()) {
                Text(
                    "This car reports no emissions monitors at all. That is the expected " +
                        "answer for a battery-electric vehicle — there is no catalyst or " +
                        "fuel system to test — not a fault.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            } else {
                report.supportedMonitors.forEach { monitor ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(monitor.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (monitor.complete) "complete" else "incomplete",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (monitor.complete) StatusGreen else StatusAmber
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            // Shown so the decode above can be checked rather than taken on faith.
            Text(
                "raw 0101: ${report.rawStatus}\nraw 07: ${report.rawPending}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DtcRow(code: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                code,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.tertiary
            )
            // No description is shown: IoniqScope ships no DTC dictionary, and
            // inventing one would be worse than sending you to look the code up.
            Text(
                "Look this code up against Hyundai/E-GMP documentation before acting on it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Clearing DTCs is irreversible, so it always goes through this dialog. */
@Composable
private fun ClearCodesDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dtc_clear_title)) },
        text = { Text(stringResource(R.string.dtc_clear_body)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.dtc_clear_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
