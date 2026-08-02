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
    KMH("Saatte kilometre", "km/h"),
    MPH("Saatte mil", "mph");

    /** OBD speed always arrives in km/h; convert only for display. */
    fun fromKmh(kmh: Double): Double = if (this == KMH) kmh else kmh * 0.621371
}

enum class AdapterType(val label: String, val description: String) {
    BLE("Bluetooth LE", "Vgate iCar Pro BLE 4.0 ve diğer GATT adaptörleri"),
    CLASSIC("Klasik Bluetooth", "RFCOMM / SPP adaptörleri — önce sistem ayarlarından eşleştirilmeli")
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
    val autoLogTrips: Boolean = false,
    /**
     * Who is signed in, as Google reported them. All null when nobody is.
     *
     * Stored rather than asked for on every launch: the sign-in sheet is a thing the
     * user chose to do once, and putting it in front of them again each time the app
     * opens would be asking them to re-decide something they already decided.
     */
    val accountName: String? = null,
    val accountEmail: String? = null,
    val accountPhotoUrl: String? = null,
    /** Hide stations not positively marked as DC. */
    /** Default on: an EV on the road wants fast charging, and it halves what is drawn. */
    val chargersDcOnly: Boolean = true,
    /**
     * Which networks to show. Empty means all of them, which is the default and the
     * only sane one — a filter nobody has touched must not be hiding anything.
     */
    val chargersOperators: Set<String> = emptySet(),
    /** Hide stations below this advertised power. 0 disables the filter. */
    val chargersMinPowerKw: Int = 0,
    /** Where the app looks for newer builds. Empty disables updates. */
    val updateShareLink: String = DEFAULT_UPDATE_MANIFEST,
    /** Look for a new build when the app opens. */
    val autoCheckUpdates: Boolean = true,
    /**
     * Which car is plugged in. Generic reads only the standard set, which is what
     * every car supports; a platform adds its own battery queries on top.
     */
    val vehicleProfileId: String = "generic"
)

/**
 * Where new builds are published.
 *
 * The `dist` branch carries only the current APK and this manifest, and is rewritten
 * on every release; the source lives on `main`. Reading a raw file needs no key, no
 * account and no API — which is the whole point, since a token shipped inside the
 * APK could be extracted from it.
 */
const val DEFAULT_UPDATE_MANIFEST =
    "https://raw.githubusercontent.com/berkebeydag/EVMap/dist/latest.json"

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
        val chargersDcOnly = booleanPreferencesKey("chargers_dc_only")
        val chargersOperators = stringSetPreferencesKey("chargers_operators")
        val chargersMinPowerKw = intPreferencesKey("chargers_min_power_kw")
        val updateShareLink = stringPreferencesKey("update_share_link")
        val autoCheckUpdates = booleanPreferencesKey("auto_check_updates")
        val vehicleProfile = stringPreferencesKey("vehicle_profile")
        val accountName = stringPreferencesKey("account_name")
        val accountEmail = stringPreferencesKey("account_email")
        val accountPhotoUrl = stringPreferencesKey("account_photo_url")
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
            autoLogTrips = p[Keys.autoLogTrips] ?: false,
            accountName = p[Keys.accountName],
            accountEmail = p[Keys.accountEmail],
            accountPhotoUrl = p[Keys.accountPhotoUrl],
            chargersDcOnly = p[Keys.chargersDcOnly] ?: true,
            chargersOperators = p[Keys.chargersOperators] ?: emptySet(),
            chargersMinPowerKw = p[Keys.chargersMinPowerKw] ?: 0,
            // Falls back to the built-in address rather than to empty, so updates
            // work on a fresh install without anyone having to paste a URL in.
            // Setting it to something else still wins; clearing it turns updates off.
            updateShareLink = p[Keys.updateShareLink] ?: DEFAULT_UPDATE_MANIFEST,
            autoCheckUpdates = p[Keys.autoCheckUpdates] ?: true,
            vehicleProfileId = p[Keys.vehicleProfile] ?: "generic"
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



    suspend fun setChargersDcOnly(enabled: Boolean) = edit { it[Keys.chargersDcOnly] = enabled }

    suspend fun setChargersOperators(operators: Set<String>) = edit {
        it[Keys.chargersOperators] = operators
    }

    suspend fun setChargersMinPower(kw: Int) = edit {
        it[Keys.chargersMinPowerKw] = kw.coerceIn(0, 400)
    }

    suspend fun setUpdateShareLink(link: String) = edit {
        it[Keys.updateShareLink] = link.trim()
    }

    suspend fun setVehicleProfile(id: String) = edit { it[Keys.vehicleProfile] = id }

    suspend fun setAccount(name: String?, email: String?, photoUrl: String?) = edit { p ->
        // Written together, because a half-written account is a state nothing else in
        // the app knows how to draw.
        if (name == null) p.remove(Keys.accountName) else p[Keys.accountName] = name
        if (email == null) p.remove(Keys.accountEmail) else p[Keys.accountEmail] = email
        if (photoUrl == null) p.remove(Keys.accountPhotoUrl) else p[Keys.accountPhotoUrl] = photoUrl
    }

    suspend fun setAutoCheckUpdates(enabled: Boolean) = edit {
        it[Keys.autoCheckUpdates] = enabled
    }

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
