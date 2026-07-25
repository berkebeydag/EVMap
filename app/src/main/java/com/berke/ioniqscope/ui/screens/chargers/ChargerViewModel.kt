package com.berke.ioniqscope.ui.screens.chargers

import android.content.Context
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

    private val _location = MutableStateFlow<LocationState>(LocationState.Unknown)
    val location: StateFlow<LocationState> = _location.asStateFlow()

    private val here: Pair<Double, Double>?
        get() = (_location.value as? LocationState.Known)?.let { it.lat to it.lon }

    /** True while the list should be showing nearest-first rather than the viewport. */
    private var listMode = false

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
        if (listMode && here != null) loadNearest()
        else lastBounds?.let { loadForBounds(it) } ?: loadNearest()
    }

    /**
     * The list wants "closest to me", which is not the same question as "what is on
     * screen" — the nearest charger may well be off the current viewport. The map
     * stays bound to its viewport; the list, once a position is known, does not.
     */
    fun setListMode(enabled: Boolean) {
        listMode = enabled
        reloadVisible()
    }

    /** Reloads the visible set for a viewport, applying the user's filters. */
    fun loadForBounds(box: BoundingBox) {
        lastBounds = box
        // While the list is showing nearest-first, panning the map underneath must
        // not quietly replace it with viewport results.
        if (listMode && here != null) return
        viewModelScope.launch {
            val current = settings.first()
            val anchor = here
            _visible.value = repo.inBounds(box)
                .filter { passesFilters(it, current) }
                .map { station -> station.withDistance(anchor) }
                .sortedBy { it.distanceMetres ?: Double.MAX_VALUE }
        }
    }

    fun loadNearest() {
        val anchor = here ?: return
        viewModelScope.launch {
            val current = settings.first()
            _visible.value = repo.nearest(anchor.first, anchor.second, limit = NEAREST_LIMIT)
                .filter { passesFilters(it, current) }
                .map { it.withDistance(anchor) }
                .sortedBy { it.distanceMetres }
        }
    }

    private fun ChargingStationEntity.withDistance(anchor: Pair<Double, Double>?) =
        ChargerListItem(
            station = this,
            distanceMetres = anchor?.let { (lat, lon) ->
                ChargerRepository.distanceMetres(lat, lon, this.lat, this.lon)
            }
        )

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
     * Asks for one coarse fix. Every outcome — including the failures — lands in
     * [location] so the UI can say what happened instead of appearing to ignore
     * the tap.
     */
    fun refreshLocation(context: Context) {
        val finder = LocationFinder(context)
        if (!finder.hasPermission()) {
            _location.value = LocationState.PermissionMissing
            return
        }
        _location.value = LocationState.Requesting
        viewModelScope.launch {
            _location.value = finder.find()
            if (_location.value is LocationState.Known) reloadVisible()
        }
    }

    companion object {
        private const val NEAREST_LIMIT = 300

        fun hasLocationPermission(context: Context): Boolean =
            LocationFinder(context).hasPermission()
    }
}
