package com.berke.ioniqscope.ui.screens.trips

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
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
import com.berke.ioniqscope.data.TripSpeedSummary
import com.berke.ioniqscope.ui.components.ConnectionPill
import com.berke.ioniqscope.ui.components.ScreenHeader
import com.berke.ioniqscope.ui.components.SettingsAction
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class TripLogViewModel(private val services: ServiceLocator) : ViewModel() {

    private val dao = services.database.tripDao()

    val connectionState: StateFlow<ConnectionState> = services.connectionManager.connectionState
    val activeTripId: StateFlow<Long?> = TripLoggingService.activeTripId

    val trips: StateFlow<List<TripEntity>> = dao.observeTrips()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Speed figures keyed by trip, so a card can be drawn without its own query. */
    val speeds: StateFlow<Map<Long, TripSpeedSummary>> = dao.observeSpeedSummaries()
        .map { rows -> rows.associateBy { it.tripId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

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
                onSuccess = { rows -> _exportResult.value = "$rows satır dışa aktarıldı." },
                onFailure = { e -> _exportResult.value = "Dışa aktarma başarısız: ${e.message}" }
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
    onSettings: () -> Unit,
    onOpenTrip: (Long) -> Unit
) {
    val vm = serviceViewModel(services) { TripLogViewModel(it) }
    val context = LocalContext.current

    val connection by vm.connectionState.collectAsStateWithLifecycle()
    val activeTripId by vm.activeTripId.collectAsStateWithLifecycle()
    val trips by vm.trips.collectAsStateWithLifecycle()
    val speeds by vm.speeds.collectAsStateWithLifecycle()
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

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Yolculuklar") {
            ConnectionPill(connection, onConnect)
            SettingsAction(onSettings)
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        if (!connected && !logging) {
            Banner(
                title = "Bağlı değil",
                text = "Yolculuk kaydına başlamadan önce adaptöre bağlan.",
                tone = BannerTone.Warning,
                modifier = Modifier.padding(top = 12.dp),
                actionLabel = "Bağlan",
                onAction = onConnect
            )
        }

        // The one action this screen exists for, given the width it deserves, with
        // what it is doing said underneath rather than in a card around it.
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (logging) {
                Button(
                    onClick = { vm.stop(context) },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.weight(1f).height(52.dp)
                ) { Text("Kaydı durdur") }
            } else {
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            vm.start(context)
                        }
                    },
                    enabled = connected,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).height(52.dp)
                ) { Text("Kaydı başlat") }
            }
        }

        Text(
            if (logging) {
                "Göstergenin sorguladığı her ölçüm kaydediliyor. Ekran kapalıyken de sürer."
            } else {
                "Göstergenin sorguladığı ne varsa, zaman damgalı olarak yalnızca bu " +
                    "telefona kaydeder."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        exportResult?.let {
            Banner(
                text = it,
                tone = if (it.startsWith("Dışa aktarma başarısız")) BannerTone.Error
                else BannerTone.Success
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Kayıtlı yolculuklar", style = MaterialTheme.typography.titleSmall)
            Text(
                "${trips.size} kayıt",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (trips.isEmpty()) {
            EmptyState("Henüz kayıtlı yolculuk yok.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(trips, key = { it.id }) { trip ->
                    TripRow(
                        trip = trip,
                        speed = speeds[trip.id],
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
}

/** A label over its value, which is how the design stacks a statistic. */
@Composable
private fun TripStat(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun TripRow(
    trip: TripEntity,
    speed: TripSpeedSummary?,
    isActive: Boolean,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = scheme.surfaceContainer,
        border = BorderStroke(1.dp, scheme.outline),
        // Tapping a finished trip opens its detail view; an in-progress one has
        // nothing settled to show yet.
        onClick = { if (!isActive) onOpen() },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tripFormatter.format(Instant.ofEpochMilli(trip.startedAtEpochMs)),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                if (isActive) {
                    Text(
                        "kaydediliyor…",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.primary
                    )
                }
            }

            val duration = trip.endedAtEpochMs?.let { it - trip.startedAtEpochMs }
            Row(
                Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                TripStat(
                    "Süre",
                    when {
                        isActive -> "sürüyor"
                        duration != null -> formatDuration(duration)
                        // A trip with no end was cut off by a crash or a kill; it is
                        // not a trip of unknown length, it is one that never finished.
                        else -> "yarım kaldı"
                    }
                )
                // Absent rather than zero: a trip logged without the speed PID selected
                // has no average speed, and 0 km/h is a different claim about it.
                speed?.let {
                    TripStat("Ort. hız", "${it.averageSpeed.roundToInt()} km/h")
                    TripStat("En yüksek", "${it.topSpeed.roundToInt()} km/h")
                }
                TripStat("Örnek", trip.sampleCount.toString())
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onExport, enabled = !isActive) {
                    Icon(Icons.Filled.Download, contentDescription = "CSV olarak dışa aktar")
                }
                IconButton(onClick = onDelete, enabled = !isActive) {
                    Icon(Icons.Filled.Delete, contentDescription = "Yolculuğu sil")
                }
            }
        }
    }
}

private val tripFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())
