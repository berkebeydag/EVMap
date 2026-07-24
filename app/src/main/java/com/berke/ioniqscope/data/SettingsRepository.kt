package com.berke.ioniqscope.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class SpeedUnit(val label: String, val suffix: String) {
    KMH("Kilometres per hour", "km/h"),
    MPH("Miles per hour", "mph");

    /** OBD speed always arrives in km/h; convert only for display. */
    fun fromKmh(kmh: Double): Double = if (this == KMH) kmh else kmh * 0.621371
}

enum class AdapterType(val label: String, val description: String) {
    BLE("Bluetooth LE", "Vgate iCar Pro BLE 4.0 and other GATT adapters"),
    CLASSIC("Classic Bluetooth", "RFCOMM / SPP adapters — must be paired in system settings first")
}

data class AppSettings(
    val speedUnit: SpeedUnit = SpeedUnit.KMH,
    val adapterType: AdapterType = AdapterType.BLE,
    val dashboardPidKeys: Set<String> = PidCatalog.defaultKeys,
    val pollIntervalMs: Int = 250,
    val lastDeviceAddress: String? = null,
    val lastDeviceName: String? = null,
    /** Reconnect to the last adapter when the app opens. */
    val autoConnect: Boolean = false,
    /** Start and stop trip logging from vehicle speed rather than a button. */
    val autoLogTrips: Boolean = false
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** All user preferences. Local file only — nothing syncs anywhere. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val speedUnit = stringPreferencesKey("speed_unit")
        val adapterType = stringPreferencesKey("adapter_type")
        val dashboardPids = stringSetPreferencesKey("dashboard_pids")
        val pollInterval = intPreferencesKey("poll_interval_ms")
        val lastAddress = stringPreferencesKey("last_device_address")
        val lastName = stringPreferencesKey("last_device_name")
        val autoConnect = booleanPreferencesKey("auto_connect")
        val autoLogTrips = booleanPreferencesKey("auto_log_trips")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            speedUnit = p[Keys.speedUnit]?.let { runCatching { SpeedUnit.valueOf(it) }.getOrNull() }
                ?: SpeedUnit.KMH,
            adapterType = p[Keys.adapterType]?.let { runCatching { AdapterType.valueOf(it) }.getOrNull() }
                ?: AdapterType.BLE,
            dashboardPidKeys = p[Keys.dashboardPids]?.takeIf { it.isNotEmpty() }
                ?: PidCatalog.defaultKeys,
            pollIntervalMs = p[Keys.pollInterval] ?: 250,
            lastDeviceAddress = p[Keys.lastAddress],
            lastDeviceName = p[Keys.lastName],
            autoConnect = p[Keys.autoConnect] ?: false,
            autoLogTrips = p[Keys.autoLogTrips] ?: false
        )
    }

    suspend fun setSpeedUnit(unit: SpeedUnit) =
        edit { it[Keys.speedUnit] = unit.name }

    suspend fun setAdapterType(type: AdapterType) =
        edit { it[Keys.adapterType] = type.name }

    suspend fun setDashboardPids(keys: Set<String>) =
        edit { it[Keys.dashboardPids] = keys }

    suspend fun setPollInterval(ms: Int) =
        edit { it[Keys.pollInterval] = ms.coerceIn(POLL_MIN_MS, POLL_MAX_MS) }

    suspend fun setAutoConnect(enabled: Boolean) = edit { it[Keys.autoConnect] = enabled }

    suspend fun setAutoLogTrips(enabled: Boolean) = edit { it[Keys.autoLogTrips] = enabled }

    suspend fun setLastDevice(address: String, name: String?) = edit {
        it[Keys.lastAddress] = address
        if (name != null) it[Keys.lastName] = name
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    companion object {
        const val POLL_MIN_MS = 50
        const val POLL_MAX_MS = 2000
    }
}
