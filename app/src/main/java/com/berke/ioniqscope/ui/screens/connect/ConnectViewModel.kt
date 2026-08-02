package com.berke.ioniqscope.ui.screens.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.berke.ioniqscope.ServiceLocator
import com.berke.ioniqscope.connection.ConnectionState
import com.berke.ioniqscope.data.AdapterType
import com.berke.ioniqscope.data.AppSettings
import com.berke.ioniqscope.obd.BluetoothAvailability
import com.berke.ioniqscope.obd.DiscoveredDevice
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ScanUiState(
    val isScanning: Boolean = false,
    val devices: List<DiscoveredDevice> = emptyList(),
    val error: String? = null
)

class ConnectViewModel(private val services: ServiceLocator) : ViewModel() {

    private val manager = services.connectionManager
    private val scanner = manager.bleScanner()

    val connectionState: StateFlow<ConnectionState> = manager.connectionState
    val adapterLog: StateFlow<List<String>> = manager.adapterLog

    val settings: StateFlow<AppSettings> = services.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _scan = MutableStateFlow(ScanUiState())
    val scan: StateFlow<ScanUiState> = _scan.asStateFlow()

    private var scanJob: Job? = null

    fun bluetoothAvailability(): BluetoothAvailability = scanner.availability()

    /**
     * Looks everywhere at once.
     *
     * The app used to make the user choose an adapter type first, and then looked in
     * exactly one place. That is the wrong way round: which of the three a dongle is
     * happens to be a fact about the hardware, and the app can find that out by looking
     * rather than by asking somebody who bought whatever was cheapest.
     *
     * So all three run together. Paired classic devices and the WiFi address are known
     * immediately, so they appear at once; the BLE scan streams in on top of them over
     * the next few seconds. Each result carries its own kind, and connecting to one
     * sets the adapter type as a side effect — so the choice still gets made, just not
     * by the user and not before there is anything to choose between.
     */
    fun startScan() {
        if (_scan.value.isScanning) return
        scanJob?.cancel()

        val immediate = buildList {
            // The WiFi dongle cannot be discovered — it is an address, and either
            // something answers there or it does not. Offering it unconditionally is
            // honest: it says "this is where I would look", which is the only thing
            // that can be said before trying.
            val s = settings.value
            add(
                DiscoveredDevice(
                    address = "${s.adapterHost}:${s.adapterPort}",
                    name = "ELM327 WiFi",
                    rssi = 0,
                    kind = AdapterType.WIFI
                )
            )
            if (scanner.availability() == BluetoothAvailability.Ready) {
                addAll(runCatching { scanner.bondedDevices() }.getOrDefault(emptyList()))
            }
        }

        if (scanner.availability() != BluetoothAvailability.Ready) {
            // No Bluetooth is not an error here any more: the WiFi row is still usable,
            // and a red banner over a screen that still works reads as a dead end.
            _scan.value = ScanUiState(isScanning = false, devices = immediate)
            return
        }

        _scan.value = ScanUiState(isScanning = true, devices = immediate)
        scanJob = viewModelScope.launch {
            scanner.scan()
                .catch { e ->
                    _scan.value = _scan.value.copy(isScanning = false, error = e.message)
                }
                .collect { found ->
                    // Bonded and scanned lists overlap: a paired adapter that is also
                    // advertising would otherwise appear twice, once per way of seeing it.
                    val merged = (found + immediate).distinctBy { it.address }
                    _scan.value = _scan.value.copy(isScanning = true, devices = merged)
                }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _scan.value = _scan.value.copy(isScanning = false)
    }

    fun setEndpoint(host: String, port: Int) = viewModelScope.launch {
        services.settings.setAdapterEndpoint(host, port)
    }

    /**
     * Connects to a WiFi adapter, which has nothing to scan for.
     *
     * The address doubles as host and port because that is what the connection manager
     * remembers and reconnects to — one field, so auto-connect works the same way for
     * every adapter type rather than WiFi being a special case that forgets itself.
     */
    fun connectWifi() {
        val s = settings.value
        manager.connect("${s.adapterHost}:${s.adapterPort}", "ELM327 WiFi", AdapterType.WIFI)
    }

    fun connect(device: DiscoveredDevice) {
        stopScan()
        // The row knows what it is, so the stored adapter type follows from the choice
        // rather than having to be set before it.
        viewModelScope.launch { services.settings.setAdapterType(device.kind) }
        viewModelScope.launch {
            services.settings.setLastDevice(device.address, device.name)
        }
        manager.connect(device.address, device.displayName, device.kind)
    }

    fun reconnectLast() {
        val s = settings.value
        val address = s.lastDeviceAddress ?: return
        manager.connect(address, s.lastDeviceName, s.adapterType)
    }

    fun disconnect() = manager.disconnect()

    override fun onCleared() {
        stopScan()
    }
}
