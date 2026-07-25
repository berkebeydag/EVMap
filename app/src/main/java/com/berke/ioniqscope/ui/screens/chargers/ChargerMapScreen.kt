package com.berke.ioniqscope.ui.screens.chargers

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.berke.ioniqscope.ServiceLocator
import com.berke.ioniqscope.charging.BoundingBox
import com.berke.ioniqscope.charging.Http
import com.berke.ioniqscope.ui.components.Banner
import com.berke.ioniqscope.ui.components.BannerTone
import com.berke.ioniqscope.ui.components.EmptyState
import com.berke.ioniqscope.ui.serviceViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.util.Locale

/** Roughly the middle of Türkiye, so the first open shows the country. */
private val TURKEY_CENTRE = GeoPoint(39.0, 35.0)

@Composable
fun ChargerMapScreen(services: ServiceLocator) {
    val vm = serviceViewModel(services) { ChargerViewModel(it) }
    val context = LocalContext.current

    val count by vm.stationCount.collectAsStateWithLifecycle()
    val sites by vm.sites.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val following by vm.following.collectAsStateWithLifecycle()
    val routes by vm.routes.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()

    var showList by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ChargerSite?>(null) }
    /** Set when something outside the map asks it to move somewhere. */
    var moveTo by remember { mutableStateOf<GeoPoint?>(null) }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted -> if (granted.values.any { it }) vm.startFollowing(context) }

    LaunchedEffect(Unit) {
        if (ChargerViewModel.hasLocationPermission(context)) vm.refreshLocation(context)
    }

    // Markers come from the cache, so a finished load has to nudge the query.
    LaunchedEffect(count, settings.chargersDcOnly, settings.chargersMinPowerKw) {
        if (count > 0) vm.reloadVisible()
    }

    val requestLocation: () -> Unit = {
        if (ChargerViewModel.hasLocationPermission(context)) {
            if (following) vm.stopFollowing() else vm.startFollowing(context)
        } else {
            locationLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    if (showList) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(onClick = {
                    showList = false
                    vm.setListMode(false)
                }) {
                    Icon(Icons.Filled.Map, contentDescription = "Haritayı göster")
                }
                Text("$count şarj noktası kayıtlı", style = MaterialTheme.typography.bodySmall)
            }
            ChargerList(sites) { openInMaps(context, it) }
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        ChargerMap(
            sites = sites,
            routes = routes,
            onBoundsChanged = vm::loadForBounds,
            onSelect = { selected = it },
            onUserPan = vm::stopFollowing,
            userLocation = (location as? LocationState.Known)?.let { it.lat to it.lon },
            following = following,
            moveTo = moveTo,
            onMoved = { moveTo = null },
            context = context
        )

        Column(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (searching) {
                SearchPanel(
                    results = searchResults,
                    onQuery = vm::search,
                    onPick = { site ->
                        searching = false
                        vm.clearSearch()
                        selected = site
                        moveTo = GeoPoint(site.lat, site.lon)
                    },
                    onClose = {
                        searching = false
                        vm.clearSearch()
                    }
                )
            }

            if (count == 0) {
                Banner(
                    title = "İstasyon yok",
                    text = "İstasyon listesi uygulamanın içinde geliyor, yani burası normalde " +
                        "hiç boş kalmaz. En son sürümü yeniden kurmak listeyi geri getirir.",
                    tone = BannerTone.Warning
                )
            }

            locationNote(location, following)?.let {
                Banner(text = it, tone = BannerTone.Warning)
            }
        }

        // Everything the user can press lives in one stack in the corner nearest the
        // thumb, over the map rather than above it — the earlier row along the top
        // pushed the map down and put the controls where a right-handed grip cannot
        // comfortably reach.
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End
        ) {
            MapButton(
                icon = Icons.Filled.Search,
                description = "İstasyon ara",
                active = searching,
                onClick = {
                    searching = !searching
                    if (!searching) vm.clearSearch()
                }
            )
            MapButton(
                icon = Icons.Filled.MyLocation,
                description = if (following) "Takibi bırak" else "Konumumu takip et",
                active = following,
                busy = location is LocationState.Requesting,
                onClick = requestLocation
            )
            MapButton(
                icon = Icons.AutoMirrored.Filled.List,
                description = "Listeyi göster",
                onClick = {
                    showList = true
                    vm.setListMode(true)
                }
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 12.dp)
                .widthIn(max = 220.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (routes.isNotEmpty()) {
                RouteLegend(routes) { openInMaps(context, it) }
            }
            Text(
                CARTO_ATTRIBUTION,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        selected?.let { site ->
            SelectedSiteCard(
                site = site,
                onNavigate = { openInMaps(context, site) },
                onDismiss = { selected = null },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 76.dp, bottom = 12.dp)
            )
        }
    }
}

/** One round map control. Filled while its mode is on, tonal otherwise. */
@Composable
private fun MapButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean = false,
    busy: Boolean = false,
    onClick: () -> Unit
) {
    val content: @Composable () -> Unit = {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(icon, contentDescription = description)
    }
    if (active) {
        FilledIconButton(onClick = onClick, shape = CircleShape) { content() }
    } else {
        FilledTonalIconButton(
            onClick = onClick,
            shape = CircleShape,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                // Opaque: a translucent control over a busy map is unreadable.
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) { content() }
    }
}

/** Search by name, operator or district, over the stations already on the device. */
@Composable
private fun SearchPanel(
    results: List<ChargerSite>,
    onQuery: (String) -> Unit,
    onPick: (ChargerSite) -> Unit,
    onClose: () -> Unit
) {
    var query by remember { mutableStateOf("") }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp
    ) {
        Column(Modifier.padding(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    onQuery(it)
                },
                singleLine = true,
                label = { Text("İstasyon ara") },
                placeholder = { Text("ZES, Trugo, bir ilçe…") },
                trailingIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Aramayı kapat")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            if (query.isNotBlank() && results.isEmpty()) {
                Text(
                    "Eşleşen bir şey yok. Arama, cihazdaki istasyonlar üzerinde çalışıyor; " +
                        "haritanın tamamında değil.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)
                )
            }

            LazyColumn(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(results.take(SEARCH_RESULTS_SHOWN), key = { it.id }) { site ->
                    TextButton(
                        onClick = { onPick(site) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                site.name ?: site.operator ?: "Şarj istasyonu",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                listOfNotNull(
                                    site.operator,
                                    site.distanceMetres?.let { formatDistance(it) },
                                    site.address
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Which coloured line goes where.
 *
 * Without this the lines are decoration: four colours leaving your position and no
 * way to tell which one ends at which charger, or whether the shortest is also the
 * quickest. Tapping a row hands that one to the navigation app.
 */
@Composable
private fun RouteLegend(routes: List<SiteRoute>, onNavigate: (ChargerSite) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
    ) {
        Column(Modifier.padding(8.dp)) {
            routes.forEachIndexed { index, entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(Color(ROUTE_COLOURS[index % ROUTE_COLOURS.size]), CircleShape)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.site.operator ?: entry.site.name ?: "Şarj istasyonu",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                        Text(
                            "${formatDistance(entry.route.metres)} · " +
                                formatMinutes(entry.route.seconds),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onNavigate(entry.site) }) {
                        Icon(
                            Icons.Filled.Navigation,
                            contentDescription = "Yol tarifi",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Text(
                "Rotalar dış bir servisten geliyor, yani konumun oraya gönderiliyor.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Details for the tapped site, over the map rather than on a new screen. */
@Composable
private fun SelectedSiteCard(
    site: ChargerSite,
    onNavigate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                site.name ?: site.operator ?: "Şarj istasyonu",
                style = MaterialTheme.typography.titleMedium
            )
            // The socket count belongs here, not on the map: the map answers "where
            // can I charge", this answers "what will I find when I get there".
            chargePointSummary(site)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                describe(site),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onNavigate) {
                    Icon(Icons.Filled.Navigation, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Text("Yol tarifi", modifier = Modifier.padding(start = 6.dp))
                }
                TextButton(onClick = onDismiss) { Text("Kapat") }
            }
        }
    }
}

/**
 * How many sockets are here, phrased as what the data actually supports.
 *
 * Says "listed" because this is what the sources published, not a live count of
 * what is free. Where nobody published a figure it returns null and the card says
 * nothing at all, rather than showing a made-up "1".
 */
private fun chargePointSummary(site: ChargerSite): String? = when (site.chargePoints) {
    null -> null
    1 -> "1 şarj noktası kayıtlı"
    else -> "${site.chargePoints} şarj noktası kayıtlı"
}

/** Whatever went wrong with locating, said plainly rather than failing silently. */
private fun locationNote(location: LocationState, following: Boolean): String? = when {
    location is LocationState.PermissionMissing ->
        "Konum izni verilmedi, bu yüzden harita seni gösteremiyor ve takip edemiyor."
    location is LocationState.Disabled ->
        "Bu cihazda konum kapalı."
    location is LocationState.TimedOut ->
        "Konum alınamadı. Kapalı alanda ya da bina içinde bu uzun sürebilir."
    following && location is LocationState.Known && location.fromCache ->
        "Bilinen son konumun takip ediliyor — yeni sinyal bekleniyor."
    else -> null
}

@Composable
private fun ChargerMap(
    sites: List<ChargerSite>,
    routes: List<SiteRoute>,
    onBoundsChanged: (BoundingBox) -> Unit,
    onSelect: (ChargerSite) -> Unit,
    onUserPan: () -> Unit,
    userLocation: Pair<Double, Double>?,
    following: Boolean,
    moveTo: GeoPoint?,
    onMoved: () -> Unit,
    context: Context
) {
    val density = LocalDensity.current.density

    val overlay = remember {
        ChargerOverlay(colors = MAP_MARKERS, density = density, onSelect = onSelect)
    }

    val mapView = remember {
        // osmdroid needs its cache configured and a real User-Agent before any
        // tile request; the public tile servers reject the default one.
        Configuration.getInstance().apply {
            // osmdroid's own preference store; androidx PreferenceManager is a
            // different library and the platform one is deprecated, so use a
            // plain SharedPreferences of our own.
            load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = Http.USER_AGENT
            osmdroidBasePath = context.filesDir
            osmdroidTileCache = context.cacheDir.resolve("osmdroid")
        }
        MapView(context).apply {
            setTileSource(CARTO_VOYAGER)
            setMultiTouchControls(true)
            // Without this the raster tiles are blitted 1:1 onto a ~3x density
            // screen, which is why the map read as a low-resolution image pasted in.
            isTilesScaledToDpi = true
            zoomController.setVisibility(
                org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
            )
            controller.setZoom(6.2)
            controller.setCenter(TURKEY_CENTRE)
            overlays.add(overlay)
        }
    }

    // Following means the map goes wherever the user goes. Without following the
    // marker still moves, but the viewport is left exactly where it was put — a map
    // that drags itself back while you are looking at somewhere else is infuriating.
    LaunchedEffect(userLocation, following) {
        val (lat, lon) = userLocation ?: return@LaunchedEffect
        overlay.userLocation = lat to lon
        if (following) {
            mapView.controller.animateTo(
                GeoPoint(lat, lon),
                maxOf(mapView.zoomLevelDouble, USER_ZOOM),
                null
            )
        }
        mapView.invalidate()
    }

    LaunchedEffect(moveTo) {
        val target = moveTo ?: return@LaunchedEffect
        mapView.controller.animateTo(target, PICKED_ZOOM, null)
        onMoved()
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }

    AndroidView(
        factory = { mapView },
        modifier = Modifier.fillMaxSize(),
        update = { map ->
            // Sites are clustered in the overlay, so every one of them stays
            // represented at any zoom — no threshold below which the map silently
            // looks empty, and no cap that would cut by database order rather than
            // by geography.
            overlay.sites = sites
            overlay.routes = routes.mapIndexed { index, entry ->
                ChargerOverlay.DrawnRoute(
                    entry.route.points,
                    ROUTE_COLOURS[index % ROUTE_COLOURS.size]
                )
            }
            map.invalidate()
        }
    )

    LaunchedEffect(mapView) {
        // Debounced so a pan does not fire a query per frame.
        mapView.addMapListener(
            DelayedMapListener(
                object : MapListener {
                    override fun onScroll(event: ScrollEvent?): Boolean {
                        emitBounds(mapView, onBoundsChanged); return true
                    }
                    override fun onZoom(event: ZoomEvent?): Boolean {
                        emitBounds(mapView, onBoundsChanged); return true
                    }
                },
                400L
            )
        )
        emitBounds(mapView, onBoundsChanged)
    }

    // A drag is the user saying "look here instead", so it releases the follow lock.
    // Checked on touch rather than on scroll events, because the map scrolls itself
    // while following and that must not be mistaken for the user taking over.
    DisposableEffect(mapView) {
        mapView.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_MOVE) onUserPan()
            false
        }
        onDispose { mapView.setOnTouchListener(null) }
    }
}

private fun emitBounds(map: MapView, onBoundsChanged: (BoundingBox) -> Unit) {
    val box = map.boundingBox ?: return
    onBoundsChanged(
        BoundingBox(
            minLat = box.latSouth,
            minLon = box.lonWest,
            maxLat = box.latNorth,
            maxLon = box.lonEast
        )
    )
}

@Composable
private fun ChargerList(sites: List<ChargerSite>, onNavigate: (ChargerSite) -> Unit) {
    if (sites.isEmpty()) {
        EmptyState("Burada istasyon yok. Haritayı kaydır ya da filtreleri gevşet.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(sites, key = { it.id }) { site ->
            ChargerRow(site) { onNavigate(site) }
        }
    }
}

@Composable
private fun ChargerRow(site: ChargerSite, onNavigate: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        onClick = onNavigate
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                site.name ?: site.operator ?: "Şarj istasyonu",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                describe(site),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                site.distanceMetres?.let {
                    Text(
                        formatDistance(it),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                site.chargePoints?.takeIf { it > 1 }?.let {
                    Text(
                        "$it şarj noktası",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Honest about gaps: "unknown" is shown as unknown, never as a plausible default. */
private fun describe(site: ChargerSite): String = buildString {
    append(site.operator ?: "işletmeci bilinmiyor")
    append(" · ")
    append(site.maxPowerKw?.let { String.format(Locale.US, "%.0f kW", it) } ?: "güç bilinmiyor")
    when (site.isDc) {
        true -> append(" · DC")
        false -> append(" · AC")
        null -> append(" · tip bilinmiyor")
    }
    site.connectors?.let { append("\n$it") }
    site.address?.let { append("\n$it") }
}

private fun formatDistance(metres: Double): String =
    if (metres < 1000) String.format(Locale.US, "%.0f m", metres)
    else String.format(Locale.US, "%.1f km", metres / 1000)

private fun formatMinutes(seconds: Double): String {
    val minutes = (seconds / 60).toInt()
    return if (minutes < 60) "$minutes dk"
    else String.format(Locale.US, "%d sa %02d dk", minutes / 60, minutes % 60)
}

/** Hands off to whatever navigation app the user actually uses. */
private fun openInMaps(context: Context, site: ChargerSite) {
    val label = Uri.encode(site.name ?: site.operator ?: "Şarj istasyonu")
    val intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("geo:${site.lat},${site.lon}?q=${site.lat},${site.lon}($label)")
    )
    runCatching { context.startActivity(intent) }
}

/**
 * CARTO Voyager, used whichever theme the app itself is in.
 *
 * The map used to follow the app's dark theme, and CARTO's dark basemap is built
 * for maps that are mostly empty: the road network is drawn in near-black on
 * black, so at anything above city zoom the roads simply were not legible. Voyager
 * keeps a light base with a properly graded road hierarchy — motorways, trunk
 * roads and streets are all separable — which is what you actually need when the
 * question is "how do I drive to that charger".
 *
 * A bright map under a dark UI is a deliberate trade: legibility of the thing you
 * are reading beats matching the chrome around it. The markers below are given
 * fixed, map-tuned colours so they do not depend on the app theme either.
 *
 * The `@2x` variant is 512px retina tiles, which is why the map no longer looks
 * like a low-resolution image pasted in.
 *
 * Free to use with attribution, which is rendered on the map. No API key and no
 * account, keeping the project buildable by anyone who clones it.
 */
private const val CARTO_ATTRIBUTION = "© OpenStreetMap contributors © CARTO"

private val CARTO_VOYAGER = XYTileSource(
    "carto-voyager",
    0, 20, 512, "@2x.png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://c.basemaps.cartocdn.com/rastertiles/voyager/"
    ),
    CARTO_ATTRIBUTION
)

/**
 * Marker colours, fixed rather than taken from the Material scheme.
 *
 * The scheme's colours are chosen to sit on the app's own surfaces; on a light
 * basemap the dark theme's pastel primary all but disappears. These are picked
 * against the tiles instead, with a white ring on every marker — the standard map
 * treatment, and the reason a pin stays visible over both a grey motorway and a
 * green park.
 */
private val MAP_MARKERS = ChargerOverlay.Colors(
    dc = 0xFF1B8E3C.toInt(),          // green: fast charging
    ac = 0xFF2563EB.toInt(),          // blue: AC only
    unknown = 0xFF64748B.toInt(),     // slate: the source never said
    cluster = 0xFF0F766E.toInt(),
    clusterText = 0xFFFFFFFF.toInt(),
    outline = 0xFFFFFFFF.toInt(),
    user = 0xFF1A73E8.toInt(),
    userRing = 0xFFFFFFFF.toInt(),
    label = 0xFF1F2937.toInt(),
    labelHalo = 0xE6FFFFFF.toInt(),
    routeCasing = 0x66000000
)

/**
 * One colour per drawn route, in descending order of prominence.
 *
 * Chosen to be distinguishable from each other, from the marker colours, and from
 * the roads underneath — the last of which rules out the obvious greys and blues.
 */
private val ROUTE_COLOURS = intArrayOf(
    0xFFE8590C.toInt(),   // orange: the nearest
    0xFF7048E8.toInt(),   // violet
    0xFF0CA678.toInt(),   // teal
    0xFFD6336C.toInt()    // pink
)

/** Close enough to see the streets around you, without losing nearby towns. */
private const val USER_ZOOM = 14.0

/** Close enough to see the forecourt of the place that was picked. */
private const val PICKED_ZOOM = 16.0

private const val SEARCH_RESULTS_SHOWN = 8
