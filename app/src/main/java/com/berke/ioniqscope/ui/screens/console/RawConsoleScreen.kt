package com.berke.ioniqscope.ui.screens.console

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.berke.ioniqscope.ServiceLocator
import com.berke.ioniqscope.connection.ConnectionState
import com.berke.ioniqscope.connection.ObdConnectionManager
import com.berke.ioniqscope.ui.components.Banner
import com.berke.ioniqscope.ui.components.BannerTone
import com.berke.ioniqscope.ui.components.EmptyState
import com.berke.ioniqscope.ui.serviceViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One request/response pair, exactly as it went over the wire. */
data class ConsoleEntry(
    val command: String,
    val response: String,
    val isError: Boolean
)

class RawConsoleViewModel(services: ServiceLocator) : ViewModel() {

    private val manager: ObdConnectionManager = services.connectionManager

    val connectionState: StateFlow<ConnectionState> = manager.connectionState

    private val _log = MutableStateFlow<List<ConsoleEntry>>(emptyList())
    val log: StateFlow<List<ConsoleEntry>> = _log.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Polling is parked while the console is open so a custom ATSH cannot poison it. */
    fun parkPolling() = manager.stopPolling()

    fun send(command: String) {
        if (command.isBlank() || _busy.value) return
        _busy.value = true
        viewModelScope.launch {
            manager.sendRaw(command).fold(
                onSuccess = { append(command, it.ifBlank { "(empty response)" }, false) },
                onFailure = { append(command, it.message ?: "failed", true) }
            )
            _busy.value = false
        }
    }

    fun restoreHeaderAndLeave() {
        viewModelScope.launch { manager.restoreDefaultHeader() }
    }

    fun clear() { _log.value = emptyList() }

    /** The whole session as text, for pasting somewhere it can be checked. */
    fun transcript(): String = _log.value.joinToString("\n") { "> ${it.command}\n${it.response}" }

    private fun append(command: String, response: String, isError: Boolean) {
        _log.value = _log.value + ConsoleEntry(command, response, isError)
    }
}

/**
 * Presets for the E-GMP verification pass. Order matters: the header must be set
 * before the 22-service requests, and restored afterwards.
 */
private val PRESETS = listOf(
    "0100" to "Which standard PIDs does the car support?",
    "0902" to "VIN",
    "ATSH 7E4" to "Address the BMS (required before 22…)",
    "220101" to "BMS main frame — SoC, HV V/A, temps, CED/CEC",
    "220105" to "SoC display, SOH",
    "ATSH 7DF" to "Back to broadcast (restores normal polling)"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RawConsoleScreen(services: ServiceLocator) {
    val vm = serviceViewModel(services) { RawConsoleViewModel(it) }
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    val connected = connection is ConnectionState.Connected
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var command by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // A custom ATSH would otherwise be inherited by the dashboard poll loop.
    DisposableEffect(Unit) {
        vm.parkPolling()
        onDispose { vm.restoreHeaderAndLeave() }
    }

    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Banner(
            title = "Raw — nothing is interpreted",
            text = "Responses are shown exactly as the adapter returns them. Use this to " +
                "check a manufacturer frame against something you can see on the dash " +
                "before it gets built into the app. Live polling is paused while this " +
                "screen is open.",
            tone = BannerTone.Info,
            modifier = Modifier.padding(top = 12.dp)
        )

        if (!connected) {
            Banner(
                title = "Not connected",
                text = "Connect to the adapter first.",
                tone = BannerTone.Warning
            )
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESETS.forEach { (cmd, _) ->
                AssistChip(
                    onClick = { command = cmd },
                    label = { Text(cmd, fontFamily = FontFamily.Monospace) },
                    enabled = connected && !busy
                )
            }
        }

        PRESETS.firstOrNull { it.first.equals(command.trim(), ignoreCase = true) }?.let { (_, hint) ->
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.weight(1f),
                label = { Text("Command") },
                placeholder = { Text("e.g. 220101") },
                singleLine = true,
                enabled = connected && !busy,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Monospace
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Send
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = { vm.send(command); keyboard?.hide() }
                )
            )
            if (busy) {
                CircularProgressIndicator(Modifier.padding(8.dp))
            } else {
                IconButton(
                    onClick = { vm.send(command); keyboard?.hide() },
                    enabled = connected && command.isNotBlank()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }

        if (log.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { copyToClipboard(context, vm.transcript()) }) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text("Copy transcript")
                }
                TextButton(onClick = vm::clear) { Text("Clear") }
            }
        }

        if (log.isEmpty()) {
            EmptyState("Nothing sent yet. Tap a preset above, or type a command.")
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(log) { entry -> ConsoleRow(entry, context) }
            }
        }
    }
}

@Composable
private fun ConsoleRow(entry: ConsoleEntry, context: Context) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "> ${entry.command}",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = scheme.onSurfaceVariant
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    entry.response,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    color = if (entry.isError) scheme.error else scheme.primary,
                    modifier = Modifier.weight(1f).padding(top = 4.dp)
                )
                IconButton(onClick = { copyToClipboard(context, entry.response) }) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy response",
                        tint = scheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText("IoniqScope", text))
}
