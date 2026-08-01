package com.berke.ioniqscope.ui.screens.chargers

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.berke.ioniqscope.ServiceLocator
import com.berke.ioniqscope.charging.BoundingBox
import com.berke.ioniqscope.charging.contains
import com.berke.ioniqscope.charging.paddedBy
import com.berke.ioniqscope.charging.ChargerRepository
import com.berke.ioniqscope.charging.ChargerTariffs
import com.berke.ioniqscope.charging.ChargerSource
import com.berke.ioniqscope.charging.Route
import com.berke.ioniqscope.charging.RouteService
import com.berke.ioniqscope.charging.SyncState
import com.berke.ioniqscope.data.AppSettings
import com.berke.ioniqscope.data.ChargingStationEntity
import com.berke.ioniqscope.data.OperatorCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /**
     * Whether the list answers "what is near" or "what is cheap".
     *
     * Two different questions, and the second one is why the prices are there at all —
     * a price you cannot sort by is a price you have to compare in your head against
     * every other row.
     */
    private val _sortByPrice = MutableStateFlow(false)
    val sortByPrice: StateFlow<Boolean> = _sortByPrice.asStateFlow()

    fun setSortByPrice(enabled: Boolean) { _sortByPrice.value = enabled }

    val sites: StateFlow<List<ChargerSite>> = combine(_visible, _sortByPrice) { items, byPrice ->
        val grouped = groupIntoSites(items)
        if (byPrice) {
            // Sites with no published tariff go last rather than first: an unknown
            // price is not a cheap one, and burying the known ones under it would
            // make the sort useless exactly where it is meant to help.
            grouped.sortedWith(
                compareBy(
                    { ChargerTariffs.worstCase(it.operator, it.isDc) ?: Double.MAX_VALUE },
                    { it.distanceMetres ?: Double.MAX_VALUE }
                )
            )
        } else grouped.sortedBy { it.distanceMetres ?: Double.MAX_VALUE }
    }
        // Off the main thread. `viewModelScope` is Dispatchers.Main.immediate, so
        // without this the grouping and the sort — a full pass and an n-log-n pass
        // over as many as 16,000 stations at country zoom — ran on the UI thread
        // every time the map moved. Measured: a 916 ms frame and "Skipped 50 frames"
        // the moment a pan settled, which is exactly the pause after letting go.
        .flowOn(Dispatchers.Default)
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
    private var boundsJob: Job? = null
    /** The direct ask that runs alongside a follow, so failures still get reported. */
    private var oneShotJob: Job? = null
    private var routeJob: Job? = null
    private var searchJob: Job? = null

    /** Where the routes currently on screen were computed from. */
    private var routedFrom: Pair<Double, Double>? = null

    private val here: Pair<Double, Double>?
        get() = (_location.value as? LocationState.Known)?.let { it.lat to it.lon }

    /**
     * True while the list is up, and with it the list's nearest-first query.
     *
     * Owned here rather than remembered in the composable, because the two lifetimes
     * are not the same and the copies came apart. This survives leaving the screen;
     * a `remember` does not. So opening the list and then switching tabs reset the
     * composable's flag to false and left this one true, and from then on the map was
     * drawn from the nearest 300 stations no matter where it was pointed — the whole
     * country showing a single group of 231 over Ankara and nothing anywhere else.
     */
    private val _listMode = MutableStateFlow(false)
    val listMode: StateFlow<Boolean> = _listMode.asStateFlow()

    fun sources(): List<ChargerSource> = services.chargerSources

    fun refresh(source: ChargerSource) {
        viewModelScope.launch { repo.sync(source) }
    }

    fun dismissSyncMessage() = repo.clearSyncState()

    /**
     * The AC/DC switch, wired here as well as in Settings.
     *
     * It is the one filter a driver changes while looking at the map — an Ioniq on a
     * long run wants the DC sites and nothing else, and the same driver parked
     * overnight wants everything — so it belongs next to the map, not three taps into
     * a settings screen. Both places write the same stored setting, so they cannot
     * disagree.
     */
    fun setDcOnly(enabled: Boolean) = viewModelScope.launch {
        services.settings.setChargersDcOnly(enabled)
    }

    /** Every network with a station, most first — the brand filter's own list. */
    val operators: StateFlow<List<OperatorCount>> = repo.operators
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Empty means every network, which is what an untouched filter has to mean. */
    fun setOperators(operators: Set<String>) = viewModelScope.launch {
        services.settings.setChargersOperators(operators)
    }

    /** Last viewport asked for, so a finished sync can repopulate it. */
    private var lastBounds: BoundingBox? = null

    /** The area [_visible] actually holds, which is wider than the viewport that asked. */
    private var loadedBox: BoundingBox? = null

    /**
     * Re-runs the last query. Called when the cached count changes, because a sync
     * that finishes while the map is sitting still would otherwise leave the screen
     * empty until the user happened to pan.
     */
    fun reloadVisible() {
        invalidate()
        if (_listMode.value && here != null) loadNearest()
        else lastBounds?.let { loadForBounds(it) } ?: loadNearest()
    }

    /**
     * The list wants "closest to me", which is not the same question as "what is on
     * screen" — the nearest charger may well be off the current viewport. The map
     * stays bound to its viewport; the list, once a position is known, does not.
     */
    fun setListMode(enabled: Boolean) {
        _listMode.value = enabled
        reloadVisible()
    }

    /** Reloads the visible set for a viewport, applying the user's filters. */
    fun loadForBounds(box: BoundingBox) {
        lastBounds = box
        // While the list is showing nearest-first, panning the map underneath must
        // not quietly replace it with viewport results.
        if (_listMode.value && here != null) return

        // Panning inside what is already loaded needs no query at all. Every query
        // fetches a viewport wider than the one that asked for it, so a nudge in any
        // direction lands inside the last answer and costs nothing — which is most
        // pans. Only leaving that area goes back to the database.
        loadedBox?.let { if (it.contains(box)) return }

        val padded = box.paddedBy(VIEWPORT_MARGIN)
        // A pan that arrives while the last one is still running supersedes it: the
        // answer to a viewport nobody is looking at any more is worth nothing, and
        // dragging across the country would otherwise queue one full pass per step.
        boundsJob?.cancel()
        boundsJob = viewModelScope.launch {
            val current = settings.first()
            val anchor = here
            // Filtered in SQL, so the rows never built are the ones being hidden.
            val rows = repo.inBounds(
                padded,
                dcOnly = current.chargersDcOnly,
                minPowerKw = current.chargersMinPowerKw.toDouble(),
                operators = current.chargersOperators
            )
            val prepared = withContext(Dispatchers.Default) {
                rows.map { station -> station.withDistance(anchor) }
                    .sortedBy { it.distanceMetres ?: Double.MAX_VALUE }
            }
            loadedBox = padded
            _visible.value = prepared
        }
    }

    /**
     * Forgets what is loaded, so the next viewport actually queries.
     *
     * Anything that changes which stations belong on screen — a filter, a finished
     * sync — has to go through here, or the skip above happily serves the answer to
     * the previous question.
     */
    private fun invalidate() {
        loadedBox = null
    }

    fun loadNearest() {
        val anchor = here ?: return
        viewModelScope.launch {
            val current = settings.first()
            _visible.value = repo.nearest(
                anchor.first, anchor.second,
                dcOnly = current.chargersDcOnly,
                minPowerKw = current.chargersMinPowerKw.toDouble(),
                operators = current.chargersOperators,
                limit = NEAREST_LIMIT
            ).map { it.withDistance(anchor) }.sortedBy { it.distanceMetres }
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

        var streamed = false
        followJob = viewModelScope.launch {
            finder.stream().collect { fix ->
                streamed = true
                _location.value = fix
                // Not reloadVisible(). Following delivers a fix a second, and each one
                // would throw away the loaded area and re-run the whole viewport
                // pipeline — the exact work the padded box exists to avoid, now on a
                // timer instead of on a pan. The map moves itself while following, and
                // moving fires the viewport listener, which loads what is needed. Only
                // the list asks a question a new position genuinely changes the answer
                // to, so only the list reloads here.
                if (_listMode.value) reloadVisible()
                refreshRoutesIfMoved(fix.lat, fix.lon)
            }
        }

        // A stream alone reports success and nothing else: with the providers all
        // switched off it simply closes, and while GPS is still searching it stays
        // open and silent. Either way the screen said "Konum alınıyor" indefinitely
        // and never explained itself. One direct ask alongside it always ends in a
        // state the UI can show — a fix, or the reason there is not one.
        //
        // Applied only if the stream has not already answered, and dropped if it
        // answers first, so a slow single fix cannot overwrite a live one.
        oneShotJob?.cancel()
        oneShotJob = viewModelScope.launch {
            val outcome = finder.find()
            if (streamed) return@launch
            _location.value = outcome
            (outcome as? LocationState.Known)?.let {
                reloadVisible()
                refreshRoutesIfMoved(it.lat, it.lon)
            }
        }
    }

    fun stopFollowing() {
        followJob?.cancel()
        followJob = null
        oneShotJob?.cancel()
        oneShotJob = null
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
            // The same filters the map is showing. A suggestion to drive to a network
            // the user has just filtered off the map is a suggestion about a station
            // they have said they cannot use.
            val targets = groupIntoSites(
                repo.nearest(
                    lat, lon,
                    dcOnly = current.chargersDcOnly,
                    minPowerKw = current.chargersMinPowerKw.toDouble(),
                    operators = current.chargersOperators,
                    limit = NEAREST_LIMIT
                ).map { it.withDistance(lat to lon) }
            ).sortedBy { it.distanceMetres ?: Double.MAX_VALUE }
                .take(ROUTE_COUNT)

            _routes.value = emptyList()
            val found = mutableListOf<SiteRoute>()
            for (site in targets) {
                val route = routeService.route(lat, lon, site.lat, site.lon) ?: continue
                found += SiteRoute(site, route)
                // Sorted here, not at each place that draws them. The panel ordered by
                // distance while the map coloured by arrival order, so a route's line
                // and its row in the panel could be given different colours — the two
                // things the colour exists to tie together.
                _routes.value = found.sortedBy { it.route.metres }
            }
        }
    }

    /**
     * Recomputes the suggestions from where the user is now, whatever the distance.
     *
     * [refreshRoutesIfMoved] deliberately does nothing until the car has actually gone
     * somewhere, which is right for a position update and wrong for a filter change:
     * picking two networks left five suggestions on screen pointing at the three the
     * user had just switched off, and the lines on the map still ran to them.
     */
    fun refreshRoutes() {
        val (lat, lon) = here ?: return
        routedFrom = null
        loadRoutes(lat, lon)
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

    companion object {
        private const val NEAREST_LIMIT = 300

        /**
         * How much wider than the viewport each query reaches, as a fraction of the
         * viewport's own span.
         *
         * The point is to make small pans free. A third on each side covers roughly
         * a screen's worth of dragging before the database is asked anything, which
         * is the difference between a pause every time you nudge the map and a pause
         * only when you actually go somewhere. Wider would skip more queries and make
         * each one bigger; a third is where those stop trading well at country zoom,
         * where the answer is already the whole country.
         */
        private const val VIEWPORT_MARGIN = 0.33

        /** How many places to draw the way to. More than this and the map is lines. */
        private const val ROUTE_COUNT = 5

        /** Routes are only redrawn once the user has gone this far. */
        private const val ROUTE_REFRESH_M = 500.0

        /** Roughly the middle of Türkiye, for ordering searches before a fix. */
        private val DEFAULT_ANCHOR = 39.0 to 35.0

        fun hasLocationPermission(context: Context): Boolean =
            LocationFinder(context).hasPermission()

        /**
         * Whether the fix will be a place or a district.
         *
         * Granting "Yaklaşık" instead of "Kesin" is a single tap in the permission
         * dialog and blurs every position the app is given to a kilometre or two,
         * which reads as the map being broken rather than as a setting.
         */
        fun hasPreciseLocationPermission(context: Context): Boolean =
            LocationFinder(context).hasPrecisePermission()
    }
}
