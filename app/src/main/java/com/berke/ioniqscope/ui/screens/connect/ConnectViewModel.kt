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

    fun startScan() {
        if (_scan.value.isScanning) return

        val type = settings.value.adapterType
        if (type == AdapterType.CLASSIC) {
            // Classic BT adapters are not advertised; they come from the system
            // pairing list instead.
            _scan.value = ScanUiState(isScanning = false, devices = scanner.bondedDevices())
            return
        }

        scanJob?.cancel()
        _scan.value = ScanUiState(isScanning = true)
        scanJob = viewModelScope.launch {
            scanner.scan()
                .catch { e ->
                    _scan.value = ScanUiState(isScanning = false, error = e.message)
                }
                .collect { devices ->
                    _scan.value = _scan.value.copy(isScanning = true, devices = devices)
                }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _scan.value = _scan.value.copy(isScanning = false)
    }

    fun connect(device: DiscoveredDevice) {
        stopScan()
        viewModelScope.launch {
            services.settings.setLastDevice(device.address, device.name)
        }
        manager.connect(device.address, device.displayName, settings.value.adapterType)
    }

    fun reconnectLast() {
        val s = settings.value
        val address = s.lastDeviceAddress ?: return
        manager.connect(address, s.lastDeviceName, s.adapterType)
    }

    fun disconnect() = manager.disconnect()

    override fun onCleared() {
        stopScan()
        super.onCleared()
    }
}
