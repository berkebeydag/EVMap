package com.berke.ioniqscope.ui.screens.chargers

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.berke.ioniqscope.ServiceLocator
import com.berke.ioniqscope.charging.BoundingBox
import com.berke.ioniqscope.charging.ChargerRepository
import com.berke.ioniqscope.charging.ChargerSource
import com.berke.ioniqscope.charging.SyncState
import com.berke.ioniqscope.data.AppSettings
import com.berke.ioniqscope.data.ChargingStationEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A station plus its distance from wherever the user is, when that is known. */
data class ChargerListItem(
    val station: ChargingStationEntity,
    val distanceMetres: Double?
)

class ChargerViewModel(private val services: ServiceLocator) : ViewModel() {

    private val repo: ChargerRepository = services.chargerRepository

    val syncState: StateFlow<SyncState> = repo.syncState

    val settings: StateFlow<AppSettings> = services.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val stationCount: StateFlow<Int> = repo.stationCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val lastSync: StateFlow<Long?> = repo.lastSync
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _visible = MutableStateFlow<List<ChargerListItem>>(emptyList())
    val visible: StateFlow<List<ChargerListItem>> = _visible.asStateFlow()

    private val _userLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val userLocation: StateFlow<Pair<Double, Double>?> = _userLocation.asStateFlow()

    fun sources(): List<ChargerSource> = services.chargerSources

    fun refresh(source: ChargerSource) {
        viewModelScope.launch { repo.sync(source) }
    }

    fun dismissSyncMessage() = repo.clearSyncState()

    /** Last viewport asked for, so a finished sync can repopulate it. */
    private var lastBounds: BoundingBox? = null

    /**
     * Re-runs the last query. Called when the cached count changes, because a sync
     * that finishes while the map is sitting still would otherwise leave the screen
     * empty until the user happened to pan.
     */
    fun reloadVisible() {
        val box = lastBounds
        if (box != null) loadForBounds(box) else loadNearest()
    }

    /** Reloads the visible set for a viewport, applying the user's filters. */
    fun loadForBounds(box: BoundingBox) {
        lastBounds = box
        viewModelScope.launch {
            val current = settings.first()
            val here = _userLocation.value
            val stations = repo.inBounds(box)
                .filter { passesFilters(it, current) }
                .map { station ->
                    ChargerListItem(
                        station = station,
                        distanceMetres = here?.let { (lat, lon) ->
                            ChargerRepository.distanceMetres(lat, lon, station.lat, station.lon)
                        }
                    )
                }
                .sortedBy { it.distanceMetres ?: Double.MAX_VALUE }
            _visible.value = stations
        }
    }

    fun loadNearest() {
        val here = _userLocation.value ?: return
        viewModelScope.launch {
            val current = settings.first()
            _visible.value = repo.nearest(here.first, here.second, limit = 200)
                .filter { passesFilters(it, current) }
                .map {
                    ChargerListItem(
                        station = it,
                        distanceMetres = ChargerRepository.distanceMetres(
                            here.first, here.second, it.lat, it.lon
                        )
                    )
                }
                .sortedBy { it.distanceMetres }
        }
    }

    /**
     * `isDc == null` means the source never said. Those are kept even under
     * "DC only", because with OSM tagging DC on a small minority of Turkish
     * entries, excluding unknowns would hide most real fast chargers. The UI
     * states this rather than quietly filtering.
     */
    private fun passesFilters(station: ChargingStationEntity, settings: AppSettings): Boolean {
        if (settings.chargersDcOnly && station.isDc == false) return false
        if (settings.chargersMinPowerKw > 0) {
            val power = station.maxPowerKw ?: return false
            if (power < settings.chargersMinPowerKw) return false
        }
        return true
    }

    /**
     * Last known coarse position. Deliberately does not subscribe to updates: the
     * charger list only needs a rough anchor to sort by, and a continuous fix is
     * both a battery cost and a privacy cost for no gain.
     */
    @SuppressLint("MissingPermission")
    fun refreshLocation(context: Context) {
        if (!hasLocationPermission(context)) return
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        val location = runCatching {
            manager.getProviders(true)
                .mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        }.getOrNull() ?: return
        _userLocation.value = location.latitude to location.longitude
        loadNearest()
    }

    companion object {
        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
