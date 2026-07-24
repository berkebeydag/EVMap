package com.berke.ioniqscope.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.berke.ioniqscope.ui.components.Banner
import com.berke.ioniqscope.ui.components.BannerTone
import com.berke.ioniqscope.ui.components.EmptyState
import com.berke.ioniqscope.ui.components.SectionLabel
import com.berke.ioniqscope.ui.serviceViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DiagnosticsUiState(
    val busy: Boolean = false,
    /** null = never read this session; empty list = read, none stored. */
    val codes: List<String>? = null,
    val error: String? = null,
    val message: String? = null
)

class DiagnosticsViewModel(services: ServiceLocator) : ViewModel() {

    private val manager = services.connectionManager

    val connectionState: StateFlow<ConnectionState> = manager.connectionState

    private val _ui = MutableStateFlow(DiagnosticsUiState())
    val ui: StateFlow<DiagnosticsUiState> = _ui.asStateFlow()

    fun readCodes() {
        _ui.value = _ui.value.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            manager.readDtcs().fold(
                onSuccess = { codes ->
                    _ui.value = DiagnosticsUiState(busy = false, codes = codes)
                },
                onFailure = { e ->
                    _ui.value = _ui.value.copy(
                        busy = false,
                        error = e.message ?: "Could not read trouble codes."
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
                            "Codes cleared. Re-read to confirm nothing has come straight back."
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
fun DiagnosticsScreen(services: ServiceLocator) {
    val vm = serviceViewModel(services) { DiagnosticsViewModel(it) }
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()

    val connected = connection is ConnectionState.Connected
    var showConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!connected) {
            Banner(
                title = "Not connected",
                text = "Connect to your adapter to read trouble codes.",
                tone = BannerTone.Warning,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 12.dp)
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

        ui.error?.let { Banner(title = "Error", text = it, tone = BannerTone.Error) }
        ui.message?.let { Banner(text = it, tone = BannerTone.Success) }

        SectionLabel("Stored codes")

        when (val codes = ui.codes) {
            null -> EmptyState("Tap “Read codes” to query the vehicle (OBD mode 03).")
            else -> if (codes.isEmpty()) {
                Banner(
                    title = "No stored codes",
                    text = "The vehicle reported no stored diagnostic trouble codes.",
                    tone = BannerTone.Success
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(codes) { code -> DtcRow(code) }
                }
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
