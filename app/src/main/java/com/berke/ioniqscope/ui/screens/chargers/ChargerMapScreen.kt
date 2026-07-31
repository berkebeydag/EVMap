package com.berke.ioniqscope.ui.screens.chargers

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
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
import kotlin.math.roundToInt

/**
 * What a site with no operator and no name is called.
 *
 * About 300 of the sites have neither: the register they come from lists a licensed
 * charge point at a coordinate without saying whose it is. Calling them "charging
 * station" made them look like a network of that name sitting between ZES and
 * Trugo; this says what is actually the case.
 */
private const val UNBRANDED = "Marka belirtilmemiş"

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

    // The view model owns this. Remembering it here as well let the two disagree
    // whenever the screen left composition with the list open.
    val showList by vm.listMode.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ChargerSite?>(null) }
    /** Where something outside the map wants it, and how close to sit when it arrives. */
    var moveTo by remember { mutableStateOf<Pair<GeoPoint, Double>?>(null) }

    /** Set while the pending request is the precise-location upgrade, not the first ask. */
    var askingForPrecise by remember { mutableStateOf(false) }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val wasUpgrade = askingForPrecise
        askingForPrecise = false
        if (granted.values.any { it }) vm.startFollowing(context)
        // Asked for precise and still coarse. Android only offers that dialog once;
        // after that the permission screen is the only thing that can still change
        // it, so go there rather than leaving a button that visibly does nothing.
        if (wasUpgrade && !ChargerViewModel.hasPreciseLocationPermission(context)) {
            openAppSettings(context)
        }
    }

    val askForPrecise: () -> Unit = {
        askingForPrecise = true
        locationLauncher.launch(
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    // Re-read on every fix rather than once: the permission dialog can be answered
    // while this screen is up, and "Kesin" granted mid-session has to stop the note.
    val precisePermission = remember(location) {
        ChargerViewModel.hasPreciseLocationPermission(context)
    }

    /**
     * Opening on the whole country is the right answer exactly once — before anyone
     * has told the app where they are. After that it is a map of Türkiye when what
     * was wanted is the chargers within reach, and it took a tap on the locate button
     * every time to get there.
     *
     * Following rather than a single fix, because the other thing this screen is for
     * is being looked at from a moving car: one fix leaves the marker where you were
     * when you opened it, and by the time it matters you are a junction past it.
     * Panning by hand still hands control back, and leaving the screen stops the
     * stream — nothing is tracked while the map is not up.
     */
    LaunchedEffect(Unit) {
        if (ChargerViewModel.hasLocationPermission(context)) vm.startFollowing(context)
    }
    DisposableEffect(Unit) {
        onDispose { vm.stopFollowing() }
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
                FilledTonalIconButton(onClick = { vm.setListMode(false) }) {
                    Icon(Icons.Filled.Map, contentDescription = "Haritayı göster")
                }
                // Places, not sockets. One row was one socket back when the sources
                // were taken raw; the bundle now merges them, so a row is a place you
                // can drive to and the socket count lives on the row itself.
                Text("$count kayıtlı yer", style = MaterialTheme.typography.bodySmall)
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
                        moveTo = GeoPoint(site.lat, site.lon) to PICKED_ZOOM
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

            // Given its own banner rather than a line of text, because unlike every
            // other note here this one has something the driver can actually press.
            if (!precisePermission && location is LocationState.Known) {
                Banner(
                    text = "Yalnızca yaklaşık konum izni verilmiş, bu yüzden Android " +
                        "konumu bir-iki kilometre bulanıklaştırıyor. Uygulama bunu " +
                        "kendi başına düzeltemez.",
                    tone = BannerTone.Warning,
                    actionLabel = "Kesin konumu aç",
                    onAction = askForPrecise
                )
            }

            locationNote(location, following)?.let {
                Banner(text = it, tone = BannerTone.Warning)
            }

            // Up here with everything else that describes the map, rather than in the
            // opposite corner from it. Constrained so it never becomes a panel: it is
            // a key to four coloured lines, not a screen of its own.
            if (routes.isNotEmpty()) {
                RouteLegend(
                    routes = routes,
                    onNavigate = { openInMaps(context, it) },
                    modifier = Modifier.widthIn(max = 236.dp)
                )
            }
        }

        // Everything the user can press lives in one stack in the corner nearest the
        // thumb, over the map rather than above it — the earlier row along the top
        // pushed the map down and put the controls where a right-handed grip cannot
        // comfortably reach.
        //
        // One surface holding three buttons rather than three floating circles: it
        // reads as a single control, and it takes noticeably less of the map.
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            shadowElevation = 3.dp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 12.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                    onClick = { vm.setListMode(true) }
                )
            }
        }

        // Required by the tile licence, so it stays — but at the smallest size that
        // is still legible, in the corner nothing else uses.
        Text(
            CARTO_ATTRIBUTION,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 10.dp, bottom = 8.dp)
        )

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

/**
 * One control inside the stack.
 *
 * Transparent when off, so the surface behind it carries the shape and the three
 * read as one control; tinted when its mode is on, which is the only state that
 * needs to announce itself. Kept at the full touch target — this gets pressed in a
 * moving car, and shaving it to look tidier would be the wrong trade.
 */
@Composable
private fun MapButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean = false,
    busy: Boolean = false,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.primaryContainer
            } else Color.Transparent,
            contentColor = if (active) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else MaterialTheme.colorScheme.onSurface
        ),
        modifier = Modifier.padding(2.dp)
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp))
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
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 4.dp
    ) {
        Column(Modifier.padding(10.dp)) {
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
                                site.name ?: site.operator ?: UNBRANDED,
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
private fun RouteLegend(
    routes: List<SiteRoute>,
    onNavigate: (ChargerSite) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 3.dp,
        modifier = modifier
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            // Nearest at the top. The order routes arrive in is the order the
            // requests happened to finish, which is no order at all to read.
            routes.sortedBy { it.route.metres }.forEachIndexed { index, entry ->
                // The whole row navigates. A separate button for it was one more
                // thing to aim at for an action the row already stands for.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onNavigate(entry.site) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(
                                Color(ROUTE_COLOURS[index % ROUTE_COLOURS.size]),
                                CircleShape
                            )
                    )
                    Text(
                        entry.site.operator ?: entry.site.name ?: UNBRANDED,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${formatDistance(entry.route.metres)} · " +
                            formatMinutes(entry.route.seconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 4.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                site.name ?: site.operator ?: UNBRANDED,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
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
    location is LocationState.Known && (location.accuracyMetres ?: 0f) > COARSE_FIX_M ->
        "Konum şu an yaklaşık ${location.accuracyMetres!!.roundToInt()} m " +
            "doğrulukta — açık alanda kendiliğinden düzelir."
    else -> null
}

/** The one screen that can still grant precise location once the dialog is spent. */
private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Past this the fix is a neighbourhood, not a place, and the map should say so. */
private const val COARSE_FIX_M = 300f

@Composable
private fun ChargerMap(
    sites: List<ChargerSite>,
    routes: List<SiteRoute>,
    onBoundsChanged: (BoundingBox) -> Unit,
    onSelect: (ChargerSite) -> Unit,
    onUserPan: () -> Unit,
    userLocation: Pair<Double, Double>?,
    following: Boolean,
    moveTo: Pair<GeoPoint, Double>?,
    onMoved: () -> Unit,
    context: Context
) {
    val density = LocalDensity.current.density

    val overlay = remember {
        ChargerOverlay(
            colors = MAP_MARKERS,
            brandColors = BRAND_COLOURS,
            density = density,
            onSelect = onSelect
        )
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
            setTileSource(CARTO_POSITRON)
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
        val (target, zoom) = moveTo ?: return@LaunchedEffect
        mapView.controller.animateTo(target, zoom, null)
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
                site.name ?: site.operator ?: UNBRANDED,
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
    val label = Uri.encode(site.name ?: site.operator ?: UNBRANDED)
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

/**
 * Positron rather than Voyager.
 *
 * Voyager is a warm cream basemap with yellow roads, and it competes: every marker
 * and every route line on this screen is drawn *over* it, and the road network was
 * arriving at roughly the same weight and saturation as the data. Positron is the
 * quiet one in the same family — near-white land, pale blue-grey roads and water,
 * muted green parks, which is the palette a navigation map wants and what leaves the
 * chargers as the loudest thing on screen.
 *
 * Same provider, same terms, same attribution, and no key or account either way.
 */
private val CARTO_POSITRON = XYTileSource(
    "carto-positron",
    0, 20, 512, "@2x.png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/rastertiles/light_all/",
        "https://b.basemaps.cartocdn.com/rastertiles/light_all/",
        "https://c.basemaps.cartocdn.com/rastertiles/light_all/"
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
    cluster = 0xFF334155.toInt(),     // slate: a group belongs to no one network
    otherBrand = 0xFF94A3B8.toInt(),  // pale slate: everything outside the palette
    outline = 0xFFFFFFFF.toInt(),
    user = 0xFF1A73E8.toInt(),
    userRing = 0xFFFFFFFF.toInt(),
    label = 0xFF1F2937.toInt(),
    labelHalo = 0xE6FFFFFF.toInt(),
    routeCasing = 0x66000000
)

/**
 * A colour per network, for the ten that actually cover the country.
 *
 * Ten is close to the limit of what anyone can hold apart at a glance, and these ten
 * account for about seven in ten of the sites we have; the rest share one neutral
 * grey rather than being given colours nobody could decode. The names are matched
 * exactly, which is only workable because the bundle folds ESARJ, "Eşarj (TR)" and
 * the rest into one spelling per brand first.
 *
 * The colours are picked apart from each other, from the route colours, and from the
 * basemap — which is what rules out the obvious road-grey and motorway-blue.
 */
private val BRAND_COLOURS = mapOf(
    "ZES" to 0xFF00897B.toInt(),          // teal
    "Eşarj" to 0xFFE53935.toInt(),        // red
    "Otopriz" to 0xFF43A047.toInt(),      // green
    "Trugo" to 0xFF1E88E5.toInt(),        // blue
    "Sharz" to 0xFF8E24AA.toInt(),        // purple
    "Toger" to 0xFFF57C00.toInt(),        // orange
    "WAT Mobilite" to 0xFF00ACC1.toInt(), // cyan
    "Voltrun" to 0xFF6D4C41.toInt(),      // brown
    "PowerŞarj" to 0xFFC2185B.toInt(),    // magenta
    "Astor Şarj" to 0xFF7CB342.toInt()    // lime
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
