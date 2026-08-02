package com.berke.ioniqscope.ui.screens.connect

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.BluetoothConnected
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.berke.ioniqscope.R
import com.berke.ioniqscope.ServiceLocator
import com.berke.ioniqscope.connection.ConnectionState
import com.berke.ioniqscope.data.AdapterType
import com.berke.ioniqscope.obd.BluetoothAvailability
import com.berke.ioniqscope.obd.DiscoveredDevice
import com.berke.ioniqscope.obd.WifiTransport
import com.berke.ioniqscope.ui.components.Banner
import com.berke.ioniqscope.ui.components.BannerTone
import com.berke.ioniqscope.ui.components.EmptyState
import com.berke.ioniqscope.ui.components.SectionLabel
import com.berke.ioniqscope.ui.serviceViewModel
import com.berke.ioniqscope.ui.theme.StatusAmber
import com.berke.ioniqscope.ui.theme.StatusGreen

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
            adapterType = settings.adapterType,
            onDisconnect = vm::disconnect,
            onReconnectLast = vm::reconnectLast,
            lastDeviceName = settings.lastDeviceName ?: settings.lastDeviceAddress
        )

        when (availability) {
            BluetoothAvailability.NoAdapter -> Banner(
                title = "Bluetooth donanımı yok",
                text = "Bu cihazda Bluetooth yok. WiFi adaptörü yine de kullanılabilir.",
                tone = BannerTone.Warning
            )
            BluetoothAvailability.Disabled -> Banner(
                title = "Bluetooth kapalı",
                text = "Bluetooth adaptörlerini görmek için aç. WiFi adaptörü açmadan da çalışır.",
                tone = BannerTone.Warning
            )
            BluetoothAvailability.Ready -> Unit
        }

        // One button, because there is now one thing to do. Scanning and listing the
        // paired devices used to be two, which made the user pick the method before
        // they had picked a device — a question about how the app works, asked of
        // somebody who wants to know whether their dongle is there.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (scan.isScanning) {
                Button(
                    onClick = vm::stopScan,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).height(50.dp)
                ) { Text("Aramayı durdur") }
            } else {
                Button(
                    onClick = vm::startScan,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).height(50.dp)
                ) { Text("Adaptör ara") }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Bulunan adaptörler",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            if (scan.isScanning) {
                Text(
                    "Taranıyor…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        scan.error?.let {
            Banner(title = "Tarama başarısız", text = it, tone = BannerTone.Error)
        }

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
    adapterType: AdapterType,
    lastDeviceName: String?,
    onDisconnect: () -> Unit,
    onReconnectLast: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val accent = when (state) {
        is ConnectionState.Connected -> StatusGreen
        is ConnectionState.Connecting -> StatusAmber
        is ConnectionState.Failed -> scheme.error
        ConnectionState.Disconnected -> scheme.outline
    }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The state as a mark before it is a sentence: this screen is looked at
                // while waiting, and a colour and a shape resolve before words do.
                Box(
                    Modifier
                        .size(56.dp)
                        .background(accent.copy(alpha = 0.14f), RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        when {
                            adapterType == AdapterType.WIFI -> Icons.Filled.Wifi
                            state is ConnectionState.Connected -> Icons.Filled.BluetoothConnected
                            else -> Icons.Filled.Bluetooth
                        },
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(
                        when (state) {
                            is ConnectionState.Connected -> "Bağlı"
                            is ConnectionState.Connecting -> "Bağlanıyor…"
                            is ConnectionState.Failed -> "Bağlanamadı"
                            ConnectionState.Disconnected -> "Bağlı değil"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        when (state) {
                            is ConnectionState.Connected -> "${state.deviceName} · ${state.linkDetail}"
                            is ConnectionState.Connecting -> state.step
                            is ConnectionState.Failed -> state.message
                            // The instruction has to match the adapter: telling
                            // somebody with a WiFi dongle to scan for it sends them
                            // looking for a button that is not on the screen.
                            ConnectionState.Disconnected -> lastDeviceName
                                ?.let { "Son kullanılan: $it" }
                                ?: if (adapterType == AdapterType.WIFI) {
                                    "Telefonu adaptörün WiFi ağına bağla, sonra Bağlan'a bas."
                                } else {
                                    "Başlamak için tarama yap ve OBD-II adaptörünü seç."
                                }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            if (state is ConnectionState.Connecting) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 14.dp))
            }

            when {
                state is ConnectionState.Connected ->
                    TextButton(
                        onClick = onDisconnect,
                        modifier = Modifier.padding(top = 6.dp)
                    ) { Text("Bağlantıyı kes") }
                state !is ConnectionState.Connecting && lastDeviceName != null ->
                    TextButton(
                        onClick = onReconnectLast,
                        modifier = Modifier.padding(top = 6.dp)
                    ) { Text("$lastDeviceName ile tekrar dene") }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: DiscoveredDevice, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val kindIcon = when (device.kind) {
        AdapterType.WIFI -> Icons.Filled.Wifi
        else -> Icons.Filled.Bluetooth
    }
    // A device that looks like an adapter is the one you came here for, so it is drawn
    // as the answer and the rest are dimmed rather than merely listed alongside.
    val likely = device.looksLikeObdAdapter
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (likely) scheme.surfaceContainerLow else scheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            if (likely) scheme.primary.copy(alpha = 0.45f) else scheme.outline
        ),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().alpha(if (likely) 1f else 0.72f)
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .background(
                        if (likely) scheme.primary.copy(alpha = 0.14f)
                        else scheme.surfaceContainerHigh,
                        RoundedCornerShape(13.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    kindIcon,
                    contentDescription = null,
                    tint = if (likely) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(device.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    buildString {
                        // The kind first, because it is the thing the user no longer
                        // has to choose and therefore the thing they should be told.
                        append(
                            when (device.kind) {
                                AdapterType.WIFI -> "WiFi"
                                AdapterType.CLASSIC -> "Klasik BT · eşleşmiş"
                                AdapterType.BLE -> "Bluetooth LE"
                            }
                        )
                        if (likely) append(" · OBD adaptörüne benziyor")
                        append(" · ")
                        append(device.address)
                        if (device.rssi != 0) append(" · ${device.rssi} dBm")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (likely) scheme.primary else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

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

/**
 * Where the WiFi adapter is, and a button to go there.
 *
 * The defaults are what nearly every one of these dongles ships with, so for most
 * people this is a screen they read once and never touch. It is editable because the
 * ones that differ — 192.168.4.1, port 23 — are otherwise unusable, and because a
 * hardcoded address that happens to be wrong looks identical to a broken app.
 */
@Composable
private fun WifiEndpointCard(
    host: String,
    port: Int,
    connecting: Boolean,
    onSave: (String, Int) -> Unit,
    onConnect: () -> Unit
) {
    @Suppress("UNUSED_PARAMETER")
    val scheme = MaterialTheme.colorScheme
    var hostText by remember(host) { mutableStateOf(host) }
    var portText by remember(port) { mutableStateOf(port.toString()) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainer,
        border = BorderStroke(1.dp, scheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Adaptör adresi", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = hostText,
                    onValueChange = { hostText = it },
                    label = { Text("IP") },
                    singleLine = true,
                    modifier = Modifier.weight(2f)
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        onSave(hostText, portText.toIntOrNull() ?: WifiTransport.DEFAULT_PORT)
                        onConnect()
                    },
                    enabled = !connecting && hostText.isNotBlank(),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).height(50.dp)
                ) { Text(if (connecting) "Bağlanılıyor…" else "Bağlan") }
            }
            Text(
                "Telefonun bu adaptörün WiFi ağına bağlı olması gerekiyor. Çoğu dongle " +
                    "192.168.0.10:35000 kullanır; bazıları 192.168.4.1 ya da 23. portta " +
                    "olur. Adaptörün ağındayken mobil veri kapanmadığı için harita ve " +
                    "güncellemeler çalışmayabilir — o normal.",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant
            )
        }
    }
}
