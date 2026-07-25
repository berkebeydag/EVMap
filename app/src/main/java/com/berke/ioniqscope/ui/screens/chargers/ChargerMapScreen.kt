package com.berke.ioniqscope.ui.screens.chargers

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.berke.ioniqscope.ServiceLocator
import com.berke.ioniqscope.charging.BoundingBox
import com.berke.ioniqscope.charging.Http
import com.berke.ioniqscope.charging.SyncState
import com.berke.ioniqscope.data.ChargingStationEntity
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Roughly the middle of Türkiye, so the first open shows the country. */
private val TURKEY_CENTRE = GeoPoint(39.0, 35.0)

@Composable
fun ChargerMapScreen(services: ServiceLocator) {
    val vm = serviceViewModel(services) { ChargerViewModel(it) }
    val context = LocalContext.current

    val sync by vm.syncState.collectAsStateWithLifecycle()
    val count by vm.stationCount.collectAsStateWithLifecycle()
    val lastSync by vm.lastSync.collectAsStateWithLifecycle()
    val visible by vm.visible.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()

    var showList by remember { mutableStateOf(false) }
    var showSourceMenu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ChargingStationEntity?>(null) }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.refreshLocation(context) }

    LaunchedEffect(Unit) {
        if (ChargerViewModel.hasLocationPermission(context)) vm.refreshLocation(context)
    }

    // Whatever went wrong with locating, say so — the button used to fail silently.
    val locationMessage = when (location) {
        LocationState.PermissionMissing ->
            "Location permission not granted, so the list cannot be sorted by distance."
        LocationState.Disabled ->
            "Location is switched off on this device. Turn it on to sort by distance."
        LocationState.TimedOut ->
            "Could not get a position. Under cover or indoors this can take a while — " +
                "try again with a clearer view of the sky."
        is LocationState.Known ->
            if ((location as LocationState.Known).fromCache) {
                "Sorted by your last known position — no fresh fix available here."
            } else null
        else -> null
    }

    // Markers come from the cache, so a finished download has to nudge the query.
    LaunchedEffect(count, settings.chargersDcOnly, settings.chargersMinPowerKw) {
        if (count > 0) vm.reloadVisible()
    }

    // The map gets the controls floated over it — that is how map apps are laid
    // out anyway, and osmdroid draws past its Compose bounds when it is a sibling
    // in a Column rather than the backdrop. The list has no such problem, so it
    // stacks normally and never has to guess how tall the overlay happens to be.
    val controls: @Composable () -> Unit = {
        if (count == 0 && sync !is SyncState.Running) {
            Banner(
                title = "No stations downloaded yet",
                text = "Tap refresh to download the charging-station list once. After that " +
                    "the map works offline — which is when you actually need it.",
                tone = BannerTone.Info,
                modifier = Modifier.padding(12.dp)
            )
        }

        when (val state = sync) {
            is SyncState.Running -> Banner(
                text = "Downloading from ${state.sourceName}… this can take a minute.",
                tone = BannerTone.Info,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
            is SyncState.Failed -> Banner(
                title = "Refresh failed",
                text = state.message,
                tone = BannerTone.Error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                actionLabel = "Dismiss",
                onAction = vm::dismissSyncMessage
            )
            is SyncState.Done -> Banner(
                title = if (state.partial) "Partial refresh" else null,
                text = if (state.partial) {
                    "${state.added} stations from ${state.sourceName}, but part of the " +
                        "country did not answer. What arrived was added to what you " +
                        "already had rather than replacing it. Try again later for a " +
                        "full refresh."
                } else {
                    "${state.added} stations from ${state.sourceName}."
                },
                tone = if (state.partial) BannerTone.Warning else BannerTone.Success,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                actionLabel = "Dismiss",
                onAction = vm::dismissSyncMessage
            )
            SyncState.Idle -> Unit
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                FilledTonalIconButton(
                    onClick = { showSourceMenu = true },
                    enabled = sync !is SyncState.Running
                ) {
                    if (sync is SyncState.Running) {
                        CircularProgressIndicator(Modifier.padding(4.dp))
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh stations")
                    }
                }
                DropdownMenu(
                    expanded = showSourceMenu,
                    onDismissRequest = { showSourceMenu = false }
                ) {
                    vm.sources().forEach { source ->
                        DropdownMenuItem(
                            text = { Text(source.displayName) },
                            enabled = source.isAvailable(),
                            onClick = {
                                showSourceMenu = false
                                vm.refresh(source)
                            }
                        )
                    }
                }
            }

            FilledTonalIconButton(
                onClick = {
                    if (ChargerViewModel.hasLocationPermission(context)) vm.refreshLocation(context)
                    else locationLauncher.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                },
                enabled = location !is LocationState.Requesting
            ) {
                if (location is LocationState.Requesting) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                } else {
                    Icon(
                        Icons.Filled.MyLocation,
                        contentDescription = "Use my location",
                        tint = if (location is LocationState.Known) {
                            MaterialTheme.colorScheme.primary
                        } else LocalContentColor.current
                    )
                }
            }

            FilledTonalIconButton(onClick = {
                showList = !showList
                vm.setListMode(showList)
            }) {
                Icon(
                    if (showList) Icons.Filled.Map else Icons.AutoMirrored.Filled.List,
                    contentDescription = if (showList) "Show map" else "Show list"
                )
            }


            // Readable whichever tile happens to be underneath.
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(
                        "$count stations cached",
                        style = MaterialTheme.typography.bodySmall
                    )
                    lastSync?.let {
                        Text(
                            "updated ${syncFormatter.format(Instant.ofEpochMilli(it))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        locationMessage?.let {
            Banner(
                text = it,
                tone = BannerTone.Warning,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        if (settings.chargersDcOnly || settings.chargersMinPowerKw > 0) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                Text(
                    buildString {
                        append("Filter: ")
                        if (settings.chargersDcOnly) append("DC only")
                        if (settings.chargersDcOnly && settings.chargersMinPowerKw > 0) append(" · ")
                        if (settings.chargersMinPowerKw > 0) append("≥${settings.chargersMinPowerKw} kW")
                        append(". Stations with no current type recorded are kept — with OSM " +
                            "data most Turkish entries never say, so excluding them would hide " +
                            "real chargers.")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }

    if (showList) {
        Column(Modifier.fillMaxSize()) {
            controls()
            ChargerList(visible)
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            ChargerMap(
                visible = visible,
                onBoundsChanged = vm::loadForBounds,
                onSelect = { selected = it },
                darkTiles = isSystemInDarkTheme(),
                context = context
            )
            Column(Modifier.fillMaxWidth().align(Alignment.TopStart)) { controls() }

            selected?.let { station ->
                SelectedStationCard(
                    station = station,
                    onNavigate = { openInMaps(context, station) },
                    onDismiss = { selected = null },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                )
            }

            Text(
                CARTO_ATTRIBUTION,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
            )
        }
    }
}

/** Details for the tapped station, over the map rather than on a new screen. */
@Composable
private fun SelectedStationCard(
    station: ChargingStationEntity,
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
                station.name ?: station.operator ?: "Charging station",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                describe(station),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onNavigate) { Text("Navigate") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

@Composable
private fun ChargerMap(
    visible: List<ChargerListItem>,
    onBoundsChanged: (BoundingBox) -> Unit,
    onSelect: (ChargingStationEntity) -> Unit,
    darkTiles: Boolean,
    context: Context
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current.density

    val overlay = remember {
        ChargerOverlay(
            colors = ChargerOverlay.Colors(
                dc = scheme.primary.toArgb(),
                ac = scheme.tertiary.toArgb(),
                unknown = scheme.outline.toArgb(),
                cluster = scheme.primaryContainer.toArgb(),
                clusterText = scheme.onPrimaryContainer.toArgb(),
                outline = scheme.surface.toArgb()
            ),
            density = density,
            onTap = onSelect
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
            setTileSource(if (darkTiles) CARTO_DARK else CARTO_LIGHT)
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

    LaunchedEffect(darkTiles) {
        mapView.setTileSource(if (darkTiles) CARTO_DARK else CARTO_LIGHT)
        mapView.invalidate()
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }

    AndroidView(
        factory = { mapView },
        modifier = Modifier.fillMaxSize(),
        update = { map ->
            // Stations are clustered in the overlay, so every one of them stays
            // represented at any zoom — no threshold below which the map silently
            // looks empty, and no cap that would cut by database order rather than
            // by geography.
            overlay.items = visible
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
private fun ChargerList(items: List<ChargerListItem>) {
    if (items.isEmpty()) {
        EmptyState("No stations here. Pan the map, refresh the list, or relax the filters.")
        return
    }
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items, key = { it.station.id }) { item ->
            ChargerRow(item) { openInMaps(context, item.station) }
        }
    }
}

@Composable
private fun ChargerRow(item: ChargerListItem, onNavigate: () -> Unit) {
    val station = item.station
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        onClick = onNavigate
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                station.name ?: station.operator ?: "Charging station",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                describe(station),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            item.distanceMetres?.let {
                Text(
                    formatDistance(it),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** Honest about gaps: "unknown" is shown as unknown, never as a plausible default. */
private fun describe(station: ChargingStationEntity): String = buildString {
    append(station.operator ?: "operator unknown")
    append(" · ")
    append(station.maxPowerKw?.let { String.format(Locale.US, "%.0f kW", it) } ?: "power unknown")
    when (station.isDc) {
        true -> append(" · DC")
        false -> append(" · AC")
        null -> append(" · type unknown")
    }
    station.connectors?.let { append("\n$it") }
    station.address?.let { append("\n$it") }
}

private fun formatDistance(metres: Double): String =
    if (metres < 1000) String.format(Locale.US, "%.0f m", metres)
    else String.format(Locale.US, "%.1f km", metres / 1000)

/** Hands off to whatever navigation app the user actually uses. */
private fun openInMaps(context: Context, station: ChargingStationEntity) {
    val label = Uri.encode(station.name ?: station.operator ?: "Charging station")
    val intent = Intent(
        Intent.ACTION_VIEW,
        "geo:${station.lat},${station.lon}?q=${station.lat},${station.lon}($label)".toUri()
    )
    runCatching { context.startActivity(intent) }
}

private fun String.toUri(): Uri = Uri.parse(this)

/**
 * CARTO basemaps, in both a dark and a light style.
 *
 * Standard OSM tiles come in one bright style only, and filtering them into
 * something dark does not work: inverting flips the map's own colour logic (sea
 * turns brown, roads turn black), while merely dimming leaves a washed-out grey
 * with no contrast. A basemap actually designed dark is the only version that
 * reads properly next to a dark UI.
 *
 * The `@2x` variants are 512px retina tiles, which is the other half of why the
 * map used to look like a low-resolution image.
 *
 * Free to use with attribution, which is rendered on the map. No API key and no
 * account, keeping the project buildable by anyone who clones it.
 */
private const val CARTO_ATTRIBUTION = "© OpenStreetMap contributors © CARTO"

private fun cartoSource(style: String) = XYTileSource(
    "carto-$style",
    0, 20, 512, "@2x.png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/$style/",
        "https://b.basemaps.cartocdn.com/$style/",
        "https://c.basemaps.cartocdn.com/$style/"
    ),
    CARTO_ATTRIBUTION
)

private val CARTO_DARK = cartoSource("dark_all")
private val CARTO_LIGHT = cartoSource("light_all")

private val syncFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault())
