package com.berke.ioniqscope.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** One adapter seen during a BLE scan. */
data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "(isimsiz)"

    /** Adapters that advertise a name matching the usual ELM327 clone patterns. */
    val looksLikeObdAdapter: Boolean
        get() = name?.let { n ->
            OBD_NAME_HINTS.any { n.contains(it, ignoreCase = true) }
        } == true

    private companion object {
        val OBD_NAME_HINTS = listOf("vgate", "icar", "obd", "elm", "vlink", "viecar")
    }
}

sealed interface BluetoothAvailability {
    data object Ready : BluetoothAvailability
    data object NoAdapter : BluetoothAvailability
    data object Disabled : BluetoothAvailability
}

/**
 * Thin wrapper over [android.bluetooth.le.BluetoothLeScanner].
 *
 * Deliberately does *not* filter by service UUID: BLE ELM327 clones advertise
 * inconsistently, and filtering on a guessed UUID would hide the very device we
 * are trying to identify. Filtering is presentational only ([DiscoveredDevice.looksLikeObdAdapter]).
 */
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {

    private val manager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val adapter: BluetoothAdapter? get() = manager?.adapter

    fun availability(): BluetoothAvailability {
        val a = adapter ?: return BluetoothAvailability.NoAdapter
        return if (a.isEnabled) BluetoothAvailability.Ready else BluetoothAvailability.Disabled
    }

    fun deviceFor(address: String): BluetoothDevice? =
        runCatching { adapter?.getRemoteDevice(address) }.getOrNull()

    /** Bonded classic-BT adapters, for the Classic transport option in Settings. */
    fun bondedDevices(): List<DiscoveredDevice> =
        runCatching {
            adapter?.bondedDevices.orEmpty().map { DiscoveredDevice(it.address, it.name, 0) }
        }.getOrDefault(emptyList())

    /**
     * Emits the accumulated result set (deduplicated by address, best RSSI wins)
     * every time it changes. Stops scanning when collection stops.
     *
     * @throws IllegalStateException if Bluetooth is off or missing.
     */
    fun scan(): Flow<List<DiscoveredDevice>> = callbackFlow {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: throw IllegalStateException("Bluetooth kapalı ya da kullanılamıyor")

        val found = linkedMapOf<String, DiscoveredDevice>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = DiscoveredDevice(
                    address = result.device.address,
                    // scanRecord name is present even before a GATT connection
                    name = result.device.name ?: result.scanRecord?.deviceName,
                    rssi = result.rssi
                )
                val existing = found[device.address]
                if (existing == null || existing.name == null || existing.rssi < device.rssi) {
                    found[device.address] = device
                    trySend(found.values.sortedByDescending { it.rssi })
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE taraması başarısız (hata $errorCode)"))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(emptyList(), settings, callback)
        trySend(emptyList())

        awaitClose { runCatching { scanner.stopScan(callback) } }
    }
}
