package com.berke.ioniqscope.ui.screens.chargers

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.berke.ioniqscope.ServiceLocator
import com.berke.ioniqscope.charging.BoundingBox
import com.berke.ioniqscope.charging.ChargerRepository
import com.berke.ioniqscope.charging.ChargerSource
import com.berke.ioniqscope.charging.Route
import com.berke.ioniqscope.charging.RouteService
import com.berke.ioniqscope.charging.SyncState
import com.berke.ioniqscope.data.AppSettings
import com.berke.ioniqscope.data.ChargingStationEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One of the nearest places, and how the road gets there. */
data class SiteRoute(val site: ChargerSite, val route: Route)

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

    /**
     * The same records collapsed to one entry per physical place.
     *
     * Both the map and the list use this rather than the raw rows: the sources
     * publish a separate record per socket, so a single car park would otherwise
     * appear as five map markers and five identical list entries.
     */
    val sites: StateFlow<List<ChargerSite>> = _visible
        .map { items -> groupIntoSites(items).sortedBy { it.distanceMetres ?: Double.MAX_VALUE } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _location = MutableStateFlow<LocationState>(LocationState.Unknown)
    val location: StateFlow<LocationState> = _location.asStateFlow()

    /** True while the map is keeping itself centred on the user. */
    private val _following = MutableStateFlow(false)
    val following: StateFlow<Boolean> = _following.asStateFlow()

    private val _routes = MutableStateFlow<List<SiteRoute>>(emptyList())
    val routes: StateFlow<List<SiteRoute>> = _routes.asStateFlow()

    private val _searchResults = MutableStateFlow<List<ChargerSite>>(emptyList())
    val searchResults: StateFlow<List<ChargerSite>> = _searchResults.asStateFlow()

    private val routeService = RouteService()
    private var followJob: Job? = null
    private var routeJob: Job? = null
    private var searchJob: Job? = null

    /** Where the routes currently on screen were computed from. */
    private var routedFrom: Pair<Double, Double>? = null

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
     * "Sadece DC", because with OSM tagging DC on a small minority of Turkish
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
     * Starts keeping the map on the user.
     *
     * Positions arrive for as long as this is on and stop the moment it is off —
     * there is no background tracking and nothing is written down. Panning the map
     * by hand calls [stopFollowing], which is what every map app does and what
     * anyone who just dragged the map away expects.
     */
    fun startFollowing(context: Context) {
        val finder = LocationFinder(context)
        if (!finder.hasPermission()) {
            _location.value = LocationState.PermissionMissing
            return
        }

        followJob?.cancel()
        _following.value = true
        if (_location.value !is LocationState.Known) _location.value = LocationState.Requesting

        followJob = viewModelScope.launch {
            finder.stream().collect { fix ->
                _location.value = fix
                reloadVisible()
                refreshRoutesIfMoved(fix.lat, fix.lon)
            }
        }
    }

    fun stopFollowing() {
        followJob?.cancel()
        followJob = null
        _following.value = false
    }

    override fun onCleared() {
        stopFollowing()
        super.onCleared()
    }

    /**
     * Routes to the nearest few places, recomputed only once the user has actually
     * gone somewhere.
     *
     * Every fix would otherwise fire a handful of requests a second, which would be
     * both rude to a free service and a stream of position reports rather than the
     * occasional one. Well under [ROUTE_REFRESH_M] the existing lines are still
     * accurate enough to read.
     */
    private fun refreshRoutesIfMoved(lat: Double, lon: Double) {
        val previous = routedFrom
        if (previous != null &&
            ChargerRepository.distanceMetres(previous.first, previous.second, lat, lon) <
            ROUTE_REFRESH_M
        ) return
        loadRoutes(lat, lon)
    }

    /**
     * Draws the way to the nearest few places.
     *
     * Each route is fetched on its own and applied as it arrives, so a service that
     * is slow or refuses one of them still leaves the others drawn. A route that
     * cannot be fetched is simply absent — there is nothing useful to say about it,
     * and a straight line pretending to be a road would be worse than no line.
     */
    fun loadRoutes(lat: Double, lon: Double) {
        routeJob?.cancel()
        routedFrom = lat to lon
        routeJob = viewModelScope.launch {
            val current = settings.first()
            val targets = groupIntoSites(
                repo.nearest(lat, lon, limit = NEAREST_LIMIT)
                    .filter { passesFilters(it, current) }
                    .map { it.withDistance(lat to lon) }
            ).sortedBy { it.distanceMetres ?: Double.MAX_VALUE }
                .take(ROUTE_COUNT)

            _routes.value = emptyList()
            val found = mutableListOf<SiteRoute>()
            for (site in targets) {
                val route = routeService.route(lat, lon, site.lat, site.lon) ?: continue
                found += SiteRoute(site, route)
                _routes.value = found.toList()
            }
        }
    }

    fun clearRoutes() {
        routeJob?.cancel()
        routedFrom = null
        _routes.value = emptyList()
    }

    /**
     * Looks up stations by name, operator or address.
     *
     * Anchored on wherever the user is, or on the middle of the country when that is
     * unknown, so "zes" returns the nearest ZES sites rather than an arbitrary forty
     * of the hundreds that exist.
     */
    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            val anchor = here ?: DEFAULT_ANCHOR
            _searchResults.value = groupIntoSites(
                repo.search(query, anchor.first, anchor.second)
                    .map { it.withDistance(here) }
            )
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchResults.value = emptyList()
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
            (_location.value as? LocationState.Known)?.let {
                reloadVisible()
                refreshRoutesIfMoved(it.lat, it.lon)
            }
        }
    }

    companion object {
        private const val NEAREST_LIMIT = 300

        /** How many places to draw the way to. More than this and the map is lines. */
        private const val ROUTE_COUNT = 4

        /** Routes are only redrawn once the user has gone this far. */
        private const val ROUTE_REFRESH_M = 500.0

        /** Roughly the middle of Türkiye, for ordering searches before a fix. */
        private val DEFAULT_ANCHOR = 39.0 to 35.0

        fun hasLocationPermission(context: Context): Boolean =
            LocationFinder(context).hasPermission()
    }
}
