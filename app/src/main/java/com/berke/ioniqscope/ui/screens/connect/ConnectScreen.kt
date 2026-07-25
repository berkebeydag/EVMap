package com.berke.ioniqscope.ui.screens.connect

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.berke.ioniqscope.R
import com.berke.ioniqscope.ServiceLocator
import com.berke.ioniqscope.connection.ConnectionState
import com.berke.ioniqscope.obd.BluetoothAvailability
import com.berke.ioniqscope.obd.DiscoveredDevice
import com.berke.ioniqscope.ui.components.Banner
import com.berke.ioniqscope.ui.components.BannerTone
import com.berke.ioniqscope.ui.components.EmptyState
import com.berke.ioniqscope.ui.components.SectionLabel
import com.berke.ioniqscope.ui.serviceViewModel

@Composable
fun ConnectScreen(services: ServiceLocator) {
    val vm = serviceViewModel(services) { ConnectViewModel(it) }
    val context = LocalContext.current

    var granted by remember { mutableStateOf(BluetoothPermissions.allGranted(context)) }
    var permanentlyDenied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = result.values.all { it }
        permanentlyDenied = !granted
    }

    if (!granted) {
        PermissionRationale(
            permanentlyDenied = permanentlyDenied,
            onGrant = { launcher.launch(BluetoothPermissions.required.toTypedArray()) },
            onOpenSettings = {
                context.startActivity(
                    Intent(
                        AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                )
            }
        )
        return
    }

    ConnectContent(vm)
}

@Composable
private fun PermissionRationale(
    permanentlyDenied: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            Icons.AutoMirrored.Filled.BluetoothSearching,
            contentDescription = null,
            modifier = Modifier.padding(top = 24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            stringResource(R.string.perm_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            stringResource(R.string.perm_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (BluetoothPermissions.needsLocationRationale) {
            Banner(
                text = stringResource(R.string.perm_location_note),
                tone = BannerTone.Info
            )
        }
        if (permanentlyDenied) {
            Banner(
                text = stringResource(R.string.perm_denied_settings),
                tone = BannerTone.Warning
            )
            OutlinedButton(onClick = onOpenSettings) { Text("Uygulama ayarlarını aç") }
        }
        Button(onClick = onGrant) { Text(stringResource(R.string.perm_grant)) }
    }
}

@Composable
private fun ConnectContent(vm: ConnectViewModel) {
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val log by vm.adapterLog.collectAsStateWithLifecycle()

    val availability = remember(connection) { vm.bluetoothAvailability() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ConnectionCard(
            state = connection,
            onDisconnect = vm::disconnect,
            onReconnectLast = vm::reconnectLast,
            lastDeviceName = settings.lastDeviceName ?: settings.lastDeviceAddress
        )

        when (availability) {
            BluetoothAvailability.NoAdapter -> Banner(
                title = "Bluetooth donanımı yok",
                text = "Bu cihazda IoniqScope'un kullanabileceği bir Bluetooth donanımı yok.",
                tone = BannerTone.Error
            )
            BluetoothAvailability.Disabled -> Banner(
                title = "Bluetooth kapalı",
                text = "Adaptörü taramak için Bluetooth'u aç.",
                tone = BannerTone.Warning
            )
            BluetoothAvailability.Ready -> Unit
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (scan.isScanning) {
                OutlinedButton(onClick = vm::stopScan) { Text("Taramayı durdur") }
                CircularProgressIndicator(modifier = Modifier.padding(start = 4.dp))
            } else {
                Button(
                    onClick = vm::startScan,
                    enabled = availability == BluetoothAvailability.Ready
                ) {
                    Text(
                        if (settings.adapterType == com.berke.ioniqscope.data.AdapterType.CLASSIC) {
                            "Eşleşmiş adaptörleri listele"
                        } else "Adaptör tara"
                    )
                }
            }
        }

        scan.error?.let {
            Banner(title = "Tarama başarısız", text = it, tone = BannerTone.Error)
        }

        SectionLabel("Bulunan adaptörler")

        Box(Modifier.weight(1f)) {
            if (scan.devices.isEmpty()) {
                EmptyState(
                    if (scan.isScanning) "Taranıyor…"
                    else "Henüz adaptör yok. Adaptör takılı ve menzildeyken taramayı başlat."
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(scan.devices, key = { it.address }) { device ->
                        DeviceRow(device) { vm.connect(device) }
                    }
                }
            }
        }

        if (log.isNotEmpty()) {
            AdapterLogCard(log)
        }
    }
}

@Composable
private fun ConnectionCard(
    state: ConnectionState,
    lastDeviceName: String?,
    onDisconnect: () -> Unit,
    onReconnectLast: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (state) {
                is ConnectionState.Connected -> {
                    Text("Connected", style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.deviceName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        state.linkDetail,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onDisconnect) { Text("Disconnect") }
                }
                is ConnectionState.Connecting -> {
                    Text("Connecting", style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.step,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                is ConnectionState.Failed -> {
                    Text(
                        "Bağlı değil",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    if (lastDeviceName != null) {
                        TextButton(onClick = onReconnectLast) { Text("$lastDeviceName ile tekrar dene") }
                    }
                }
                ConnectionState.Disconnected -> {
                    Text("Bağlı değil", style = MaterialTheme.typography.titleMedium)
                    if (lastDeviceName != null) {
                        Text(
                            "Son kullanılan: $lastDeviceName",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = onReconnectLast) { Text("Reconnect") }
                    } else {
                        Text(
                            "Başlamak için tarama yap ve OBD-II adaptörünü seç.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: DiscoveredDevice, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(device.displayName) },
        supportingContent = {
            Text(
                buildString {
                    append(device.address)
                    if (device.rssi != 0) append("  ·  ${device.rssi} dBm")
                    if (device.looksLikeObdAdapter) append("  ·  OBD adaptörüne benziyor")
                },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        },
        trailingContent = { TextButton(onClick = onClick) { Text("Bağlan") } },
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * The discovered GATT profile is dumped here on purpose: if the adapter's UUIDs
 * differ from the known candidates, this is where you read them off to pin them
 * in BleTransport.
 */
@Composable
private fun AdapterLogCard(log: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            SectionLabel("Adaptör kaydı")
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                items(log) { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
