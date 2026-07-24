package com.berke.ioniqscope.ui.screens.trips

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.berke.ioniqscope.ServiceLocator
import com.berke.ioniqscope.connection.ConnectionState
import com.berke.ioniqscope.data.TripEntity
import com.berke.ioniqscope.data.defaultTripFileName
import com.berke.ioniqscope.service.TripLoggingService
import com.berke.ioniqscope.ui.components.Banner
import com.berke.ioniqscope.ui.components.BannerTone
import com.berke.ioniqscope.ui.components.EmptyState
import com.berke.ioniqscope.ui.components.SectionLabel
import com.berke.ioniqscope.ui.components.formatDuration
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

class TripLogViewModel(private val services: ServiceLocator) : ViewModel() {

    private val dao = services.database.tripDao()

    val connectionState: StateFlow<ConnectionState> = services.connectionManager.connectionState
    val activeTripId: StateFlow<Long?> = TripLoggingService.activeTripId

    val trips: StateFlow<List<TripEntity>> = dao.observeTrips()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _exportResult = MutableStateFlow<String?>(null)
    val exportResult: StateFlow<String?> = _exportResult.asStateFlow()

    /** Trip awaiting a destination from the system file picker. */
    var pendingExport: TripEntity? = null
        private set

    fun start(context: Context) = TripLoggingService.start(context)
    fun stop(context: Context) = TripLoggingService.stop(context)

    fun beginExport(trip: TripEntity) {
        pendingExport = trip
    }

    fun completeExport(uri: Uri?) {
        val trip = pendingExport
        pendingExport = null
        if (uri == null || trip == null) return

        viewModelScope.launch {
            services.csvExporter.exportTrip(trip.id, uri).fold(
                onSuccess = { rows -> _exportResult.value = "Exported $rows rows." },
                onFailure = { e -> _exportResult.value = "Export failed: ${e.message}" }
            )
        }
    }

    fun dismissExportResult() { _exportResult.value = null }

    fun deleteTrip(id: Long) = viewModelScope.launch { dao.deleteTrip(id) }
}

@Composable
fun TripLogScreen(
    services: ServiceLocator,
    onConnect: () -> Unit,
    onOpenTrip: (Long) -> Unit
) {
    val vm = serviceViewModel(services) { TripLogViewModel(it) }
    val context = LocalContext.current

    val connection by vm.connectionState.collectAsStateWithLifecycle()
    val activeTripId by vm.activeTripId.collectAsStateWithLifecycle()
    val trips by vm.trips.collectAsStateWithLifecycle()
    val exportResult by vm.exportResult.collectAsStateWithLifecycle()

    val connected = connection is ConnectionState.Connected
    val logging = activeTripId != null

    // ACTION_CREATE_DOCUMENT — the user picks the location and filename themselves.
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> vm.completeExport(uri) }

    // On API 33+ the ongoing notification needs permission, otherwise the service
    // still runs but silently. Ask before the first start.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.start(context) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!connected && !logging) {
            Banner(
                title = "Not connected",
                text = "Connect to your adapter before starting a trip log.",
                tone = BannerTone.Warning,
                modifier = Modifier.padding(top = 12.dp),
                actionLabel = "Connect",
                onAction = onConnect
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (logging) "Recording" else "Not recording",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (logging) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (logging) {
                        "Logging every reading the Dashboard is polling. Keeps running with the screen off."
                    } else {
                        "Records whatever the Dashboard is polling, timestamped, to this phone only."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (logging) {
                    Button(
                        onClick = { vm.stop(context) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("Stop logging") }
                } else {
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                vm.start(context)
                            }
                        },
                        enabled = connected
                    ) { Text("Start logging") }
                }
            }
        }

        exportResult?.let {
            Banner(
                text = it,
                tone = if (it.startsWith("Export failed")) BannerTone.Error else BannerTone.Success
            )
        }

        HorizontalDivider()
        SectionLabel("Saved trips")

        if (trips.isEmpty()) {
            EmptyState("No trips recorded yet.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(trips, key = { it.id }) { trip ->
                    TripRow(
                        trip = trip,
                        isActive = trip.id == activeTripId,
                        onOpen = { onOpenTrip(trip.id) },
                        onExport = {
                            vm.dismissExportResult()
                            vm.beginExport(trip)
                            saveLauncher.launch(defaultTripFileName(trip.startedAtEpochMs))
                        },
                        onDelete = { vm.deleteTrip(trip.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TripRow(
    trip: TripEntity,
    isActive: Boolean,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        // Tapping a finished trip opens its detail view; an in-progress one has
        // nothing settled to show yet.
        onClick = { if (!isActive) onOpen() }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    tripFormatter.format(Instant.ofEpochMilli(trip.startedAtEpochMs)),
                    style = MaterialTheme.typography.titleSmall
                )
                val duration = trip.endedAtEpochMs?.let { it - trip.startedAtEpochMs }
                Text(
                    buildString {
                        if (isActive) append("recording…")
                        else if (duration != null) append(formatDuration(duration))
                        else append("ended unexpectedly")
                        if (trip.sampleCount > 0) append("  ·  ${trip.sampleCount} readings")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onExport, enabled = !isActive) {
                Icon(Icons.Filled.Download, contentDescription = "Export CSV")
            }
            IconButton(onClick = onDelete, enabled = !isActive) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete trip")
            }
        }
    }
}

private val tripFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())
