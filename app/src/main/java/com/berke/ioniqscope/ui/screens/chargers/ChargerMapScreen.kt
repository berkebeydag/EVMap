package com.berke.ioniqscope.ui.screens.chargers

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import com.berke.ioniqscope.charging.ChargerTariffs
import com.berke.ioniqscope.charging.ChargerTariffs.AS_OF
import com.berke.ioniqscope.data.OperatorCount
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
    val sortByPrice by vm.sortByPrice.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ChargerSite?>(null) }
    /** Where something outside the map wants it, and how close to sit when it arrives. */
    var moveTo by remember { mutableStateOf<Pair<GeoPoint, Double>?>(null) }

    /** Set while the pending request is the precise-location upgrade, not the first ask. */
    var askingForPrecise by remember { mutableStateOf(false) }
    var pickingBrands by remember { mutableStateOf(false) }
    val operators by vm.operators.collectAsStateWithLifecycle()

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
    LaunchedEffect(
        count,
        settings.chargersDcOnly,
        settings.chargersMinPowerKw,
        settings.chargersOperators
    ) {
        if (count > 0) {
            vm.reloadVisible()
            // The suggestions are filtered too, so they are as stale as the map is.
            vm.refreshRoutes()
        }
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
                // One quantity, always: how many places are in the list below. It
                // briefly said "271 DC yer" with the filter on and "16104 kayıtlı yer"
                // with it off — the nearby count and the whole database, swapping
                // places under the same label.
                Text(
                    "${sites.size} yer",
                    style = MaterialTheme.typography.bodySmall
                )
                // The reason the prices are there. Sorting by them is the difference
                // between showing a driver a price and helping them act on it.
                TextButton(
                    onClick = { vm.setSortByPrice(!sortByPrice) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (sortByPrice) "En ucuz" else "En yakın",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                // The brand filter belongs here too. Both filters apply to this list;
                // being able to change only one of them from it is the asymmetry the
                // AC/DC switch was added to fix in the first place.
                FilledTonalIconButton(
                    onClick = { pickingBrands = true },
                    colors = if (settings.chargersOperators.isNotEmpty()) {
                        IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    } else IconButtonDefaults.filledTonalIconButtonColors()
                ) {
                    Icon(Icons.Filled.FilterAlt, contentDescription = "Şarj ağını seç")
                }
                // The same switch as the map's, because the list is the same question
                // asked a different way and a filter that only exists on one of them
                // is a filter you cannot trust either of them about.
                FilledTonalIconButton(
                    onClick = { vm.setDcOnly(!settings.chargersDcOnly) },
                    colors = if (settings.chargersDcOnly) {
                        IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    } else IconButtonDefaults.filledTonalIconButtonColors()
                ) {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = if (settings.chargersDcOnly)
                            "AC istasyonları da göster" else "Yalnızca DC göster"
                    )
                }
            }
            ChargerList(sites) { openInMaps(context, it) }
        }
        if (pickingBrands) {
            BrandFilterDialog(
                operators = operators,
                selected = settings.chargersOperators,
                onApply = { vm.setOperators(it) },
                onDismiss = { pickingBrands = false }
            )
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
                    modifier = Modifier.widthIn(max = 208.dp)
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
        // Hidden while a site is open. The sheet reaches both edges now, so the stack
        // would be sitting on top of the thing the user just asked to read — and every
        // control on it acts on the map behind, which is not what is being looked at.
        if (selected == null) Surface(
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
                    icon = Icons.Filled.Bolt,
                    description = if (settings.chargersDcOnly) "AC istasyonları da göster"
                    else "Yalnızca DC göster",
                    active = settings.chargersDcOnly,
                    onClick = { vm.setDcOnly(!settings.chargersDcOnly) }
                )
                MapButton(
                    icon = Icons.Filled.FilterAlt,
                    description = "Şarj ağını seç",
                    active = settings.chargersOperators.isNotEmpty(),
                    onClick = { pickingBrands = true }
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

        if (pickingBrands) {
            BrandFilterDialog(
                operators = operators,
                selected = settings.chargersOperators,
                onApply = { vm.setOperators(it) },
                onDismiss = { pickingBrands = false }
            )
        }

        // Flush to the bottom and to both edges, as a sheet rather than a floating
        // card. It used to be inset with the button stack squeezed alongside it, which
        // left a strip of map down each side too narrow to see anything through and
        // put a column of controls over the thing the user had just asked to read.
        selected?.let { site ->
            SelectedSiteCard(
                site = site,
                onNavigate = { openInMaps(context, site) },
                onDismiss = { selected = null },
                modifier = Modifier.align(Alignment.BottomCenter)
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
        Column(Modifier.padding(vertical = 3.dp)) {
            // Nearest at the top. The order routes arrive in is the order the
            // requests happened to finish, which is no order at all to read.
            routes.sortedBy { it.route.metres }.forEachIndexed { index, entry ->
                // The whole row navigates. A separate button for it was one more
                // thing to aim at for an action the row already stands for.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onNavigate(entry.site) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(
                                Color(ROUTE_COLOURS[index % ROUTE_COLOURS.size]),
                                CircleShape
                            )
                    )
                    // Network on top, where you would actually arrive underneath.
                    // The network alone answers "can I charge here"; it does not
                    // answer "where am I going", and four rows reading ZES, ZES,
                    // Trugo, ZES are four rows you cannot choose between.
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.site.operator ?: entry.site.name ?: UNBRANDED,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // Power first, then where. The kW is what decides whether a
                        // suggestion is worth taking — 180 and 11 are twenty minutes
                        // and an evening — and it was the one thing on this panel the
                        // map markers said and it did not. On the same line as the
                        // destination so the panel does not grow a row to say it.
                        Text(
                            listOfNotNull(
                                formatPower(entry.site.maxPowerKw),
                                destinationOf(entry.site)
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "${formatDistance(entry.route.metres)} · " +
                                formatMinutes(entry.route.seconds),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Rounded to whole lira here and only here. A suggestion is a
                        // choice between five stops, and "13-16 ₺" separates them just
                        // as well as "12,99-16,49 ₺" in a third of the width; the exact
                        // figures are one tap away on the card.
                        formatTariff(entry.site, short = true)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = priceColour(entry.site)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * What a stop at this site costs per kWh, as its network published it.
 *
 * Prefixed with "~" and never presented as the price: this is the operator's own
 * tariff, and membership, campaigns, roaming and — by ZES's own admission on its
 * pricing page — the location itself can all move it. Networks with no published
 * figure return null and the UI shows nothing, rather than a guess.
 *
 * [short] rounds to whole lira for the places measured in millimetres.
 */
private fun formatTariff(site: ChargerSite, short: Boolean): String? {
    val band = ChargerTariffs.bandFor(site.operator, site.isDc) ?: return null
    fun money(value: Double) =
        if (short) String.format(Locale.US, "%.0f", value)
        else String.format(Locale.US, "%.2f", value).replace('.', ',')
    val figure = if (band.varies) "${money(band.from)}-${money(band.to)}" else money(band.from)
    return "~$figure ₺" + if (short) "" else "/kWh"
}

/**
 * Where this site's price sits against the rest of the market.
 *
 * A number without this is not a decision. Someone choosing a cheap stop cannot read
 * "13,49 ₺" unless they carry the national average around with them, and cannot read
 * "12,99-16,49 ₺" at all — which is the whole complaint the verdict answers. So the
 * ranges stay, and get a word saying what they mean.
 */
private fun priceVerdict(site: ChargerSite): String? =
    when (ChargerTariffs.levelFor(site.operator, site.isDc)) {
        ChargerTariffs.Level.Cheap -> "ucuz"
        ChargerTariffs.Level.Average -> "ortalama"
        ChargerTariffs.Level.Expensive -> "pahalı"
        // Not a hedge. ZES prints DC-1 and DC-2 and Aksa prints Tarife 1 and Tarife 2,
        // and neither says which applies where — so "it depends" is the true answer,
        // and it still separates a gamble from a stop that is certainly 12,90.
        ChargerTariffs.Level.Variable -> "değişken"
        null -> null
    }

@Composable
private fun priceColour(site: ChargerSite) =
    when (ChargerTariffs.levelFor(site.operator, site.isDc)) {
        ChargerTariffs.Level.Cheap -> MaterialTheme.colorScheme.secondary
        ChargerTariffs.Level.Expensive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

/** A charge rating as a driver reads it, or nothing when nobody published one. */
private fun formatPower(kw: Double?): String? {
    val value = kw?.takeIf { it > 0 } ?: return null
    // Whole numbers for whole ratings: "22 kW", not "22.0 kW". The halves that really
    // occur — 7.4, 3.7 — are genuine and stay.
    return if (value % 1.0 == 0.0) "${value.toInt()} kW"
    else String.format(Locale.getDefault(), "%.1f kW", value)
}

/**
 * Where a route actually ends, in as few words as fit on one line.
 *
 * The site's own name first — "Migros Balçova" is the thing you look for when you get
 * there. Failing that the address, cut back to the part that locates it: these arrive
 * as "Mithatpaşa Caddesi 1234, 35330, Balçova, İzmir", and the postcode and the
 * province are the two parts nobody standing in İzmir needs.
 */
private fun destinationOf(site: ChargerSite): String? {
    site.name?.takeIf { it.isNotBlank() && it != site.operator }?.let { return it }
    val address = site.address?.takeIf { it.isNotBlank() } ?: return null
    val parts = address.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    // Drop a bare postcode wherever it sits and keep the first part, which in a
    // Turkish address is the neighbourhood — the word that actually locates it.
    //
    // Just the one part. It used to carry the district too, and once the charge rating
    // joined it on the same line the pair no longer fit: five rows read "Etiler, Ko…",
    // "Mimarsin…", "Oğuzlar, K…". The district was the half worth losing anyway, since
    // suggestions are all within a few kilometres and it is usually the same for every
    // one of them.
    val useful = parts.filterNot { it.length == 5 && it.all(Char::isDigit) }
    return useful.firstOrNull()
}

/**
 * Picks which networks appear, listed by how many stations each one has.
 *
 * Ordered by size rather than alphabetically because that is the order the list is
 * useful in: 613 operators sorted by name buries ZES and Trugo — between them a fifth
 * of the country — under a long tail of names with a single station each. The ones
 * worth filtering by are the ones you can actually reach, and those are at the top.
 *
 * Selection is applied on dismissal rather than per tap: choosing three networks
 * would otherwise rebuild the map three times on the way there.
 */
@Composable
private fun BrandFilterDialog(
    operators: List<OperatorCount>,
    selected: Set<String>,
    onApply: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var picked by remember(selected) { mutableStateOf(selected) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Şarj ağı") },
        text = {
            Column {
                Text(
                    if (picked.isEmpty()) "Hepsi gösteriliyor."
                    else "${picked.size} ağ seçili — harita ve rota önerileri yalnızca " +
                        "bunlara bakıyor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(Modifier.padding(top = 8.dp)) {
                    items(operators, key = { it.name }) { operator ->
                        val isOn = operator.name in picked
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    picked = if (isOn) picked - operator.name
                                    else picked + operator.name
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // The same colour the map draws this network in, so the
                            // filter and the map are read as one thing.
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .background(
                                        Color(
                                            BRAND_COLOURS[operator.name]
                                                ?: MAP_MARKERS.otherBrand
                                        ),
                                        CircleShape
                                    )
                            )
                            Text(
                                operator.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                operator.stations.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Checkbox(checked = isOn, onCheckedChange = null)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(picked); onDismiss() }) { Text("Uygula") }
        },
        dismissButton = {
            // Clearing is the way back to "everything", and it is worth its own
            // button: unticking a dozen networks by hand to see the whole map again
            // is not a thing anyone should have to do.
            TextButton(onClick = { onApply(emptySet()); onDismiss() }) { Text("Hepsi") }
        }
    )
}

/**
 * The tapped site, as the design lays it out.
 *
 * Structured rather than described. It used to be a sentence — operator, power, type,
 * connectors, address, all run together — which is quick to write and slow to read: to
 * find out whether your cable fits you had to parse a paragraph. The facts are chips
 * now, in the order they are asked in: how fast, what fits, whose it is.
 *
 * Price keeps a box of its own because it is the one number here that is an estimate,
 * and the box is what stops it being read with the same confidence as the kW.
 */
@Composable
private fun SelectedSiteCard(
    site: ChargerSite,
    onNavigate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        // Square along the bottom: it is anchored to the screen edge, and rounding a
        // corner that touches nothing only shows the map through the gap.
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        color = scheme.surfaceContainer,
        shadowElevation = 12.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 18.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        site.name ?: site.operator ?: UNBRANDED,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    chargePointSummary(site)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Kapat",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            FlowRow(
                Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // The speed leads and is the only chip in the accent colour: it is the
                // one fact that decides whether this stop is twenty minutes or a whole
                // evening, and the rest only qualify it.
                powerChip(site)?.let { FactChip(it, accent = true) }
                site.connectors?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?.distinct()?.forEach { FactChip(connectorName(it)) }
                site.operator?.let { FactChip(it, muted = true) }
            }

            site.address?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            formatTariff(site, short = false)?.let { price ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = scheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, scheme.outline),
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(price, style = MaterialTheme.typography.titleSmall)
                            priceVerdict(site)?.let {
                                Spacer(Modifier.weight(1f))
                                FactChip(it, muted = true)
                            }
                        }
                        Text(
                            "${site.operator} tarifesi · $AS_OF",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Text(
                            "İşletmecinin yayımladığı fiyat. Üyelik, kampanya ve " +
                                "lokasyona göre değişebilir.",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            Button(
                onClick = onNavigate,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .height(50.dp)
            ) {
                Icon(
                    Icons.Filled.Navigation,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text("Yol tarifi", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** One fact, boxed. Accent for the speed, muted for what only qualifies it. */
@Composable
private fun FactChip(text: String, accent: Boolean = false, muted: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (accent) scheme.primary.copy(alpha = 0.12f) else scheme.surfaceContainerHigh
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                accent -> scheme.primary
                muted -> scheme.onSurfaceVariant
                else -> scheme.onSurface
            },
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
        )
    }
}

/** "180 kW DC", or nothing where the source never published a rating. */
private fun powerChip(site: ChargerSite): String? {
    val kw = site.maxPowerKw?.takeIf { it > 0 } ?: return null
    val power = if (kw % 1.0 == 0.0) "${kw.toInt()} kW"
    else String.format(Locale.US, "%.1f kW", kw)
    return when (site.isDc) {
        true -> "$power DC"
        false -> "$power AC"
        null -> power
    }
}

/**
 * The socket type as a driver would say it.
 *
 * The bundle carries IEC's own spelling — IEC62196Type2CCS — which is correct, precise,
 * and written on no cable and in no car's manual. Anything unrecognised is passed
 * through as it came rather than guessed at.
 */
private fun connectorName(raw: String): String = when (raw) {
    "IEC62196Type2CCS" -> "CCS2"
    "IEC62196Type2Outlet" -> "Tip 2"
    "IEC62196Type2CableAttached" -> "Tip 2 kablolu"
    "Chademo" -> "CHAdeMO"
    else -> raw
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

            // osmdroid's defaults are sized for a small map in a corner of a screen,
            // not a full-screen one being dragged around. Nine tiles in memory is
            // fewer than a phone screen holds, so panning evicted tiles it was about
            // to need again and re-decoded them from disk; two downloads at a time
            // then made a cold area arrive in visible waves.
            //
            // The overshoot is the one that matters most here: it keeps a ring of
            // tiles beyond the screen edge, which is precisely the ground a sideways
            // drag moves onto. Without it the first thing a pan reveals is always
            // empty. (Not measured on this machine — the emulator's tiles were long
            // since cached and its connection is not a phone's — so these are sized
            // from what the screen actually needs rather than from a before-and-after.)
            cacheMapTileCount = 64
            cacheMapTileOvershoot = 2
            tileDownloadThreads = 6
            tileDownloadMaxQueueSize = 80
        }
        MapView(context).apply {
            setTileSource(CARTO_PARCHMENT)
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
                    points = entry.route.points,
                    color = ROUTE_COLOURS[index % ROUTE_COLOURS.size],
                    // The station's own coordinate, not the route's last point: the
                    // route ends where the road does, which is the kerb outside.
                    destinationLat = entry.site.lat,
                    destinationLon = entry.site.lon
                )
            }
            map.invalidate()
        }
    )

    LaunchedEffect(mapView) {
        // Debounced so a pan does not fire a query per frame.
        //
        // 400 ms was the largest single part of the wait after letting go of the map —
        // longer than the query and the grouping put together — and it was that long
        // because every one of those queries used to be expensive and run on the UI
        // thread. Neither is true now: a query that is superseded gets cancelled, one
        // that lands inside the area already loaded does not run at all, and what does
        // run is off the main thread. So the delay can be what it was meant to be,
        // long enough not to fire mid-drag rather than long enough to hide the cost.
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
                140L
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
                // The network's published rate. Exact figures here, where there is
                // room for a range and for the "~" that says this is what the network
                // charges rather than what this socket will bill.
                formatTariff(site, short = false)?.let {
                    Text(
                        it + (priceVerdict(site)?.let { verdict -> " · $verdict" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = priceColour(site)
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
/**
 * Positron, repainted to parchment as each tile is decoded.
 *
 * The recolour is per pixel rather than a colour filter, for the reason set out in
 * [ParchmentTileSource]: one linear transform cannot warm the land, cool the water and
 * leave the type alone at the same time.
 */
private val CARTO_PARCHMENT = ParchmentTileSource(
    "carto-parchment",
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
    // Warm, to sit on parchment rather than on the slate the map used to be. The
    // halo is the basemap's own cream, so a name crossing a road reads as printed on
    // the map instead of pasted over it.
    label = 0xFF5F5749.toInt(),
    labelHalo = 0xF0F6F1E6.toInt(),
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
    // Tier one: the six largest, a third of every station in the country between
    // them. These are pushed as far apart in hue as six colours go, because these are
    // the ones a glance has to separate. The pairing that used to break this was ZES
    // and WAT Mobilite — the first and fourth largest — sitting on 00897B and 00ACC1,
    // which is the same teal twice.
    "ZES" to 0xFF2E7D32.toInt(),           // green
    "Trugo" to 0xFF283593.toInt(),         // indigo
    "Voltrun" to 0xFFEF6C00.toInt(),       // orange
    "WAT Mobilite" to 0xFF00BCD4.toInt(),  // cyan
    "Eşarj" to 0xFFD32F2F.toInt(),         // red
    "Otopriz" to 0xFF7B1FA2.toInt(),       // purple

    // Tier two fills the gaps between those hues. Ranks 7 to 15, another 14% — and
    // the tier the old palette skipped entirely: it spent colours on Toger, PowerŞarj
    // and Astor Şarj, which are 19th and below, while Otojet, En Yakıt and Otowatt sat
    // in the neutral grey with three hundred stations apiece.
    "Otojet" to 0xFFF9A825.toInt(),        // amber
    "En Yakıt" to 0xFF6D4C41.toInt(),      // brown
    "Otowatt" to 0xFF00897B.toInt(),       // teal
    "Sharz" to 0xFFC2185B.toInt(),         // magenta
    "Aksa Şarj" to 0xFF558B2F.toInt(),     // olive
    "Autel" to 0xFF5E35B1.toInt(),         // violet
    "Beefull" to 0xFFFF7043.toInt(),       // coral
    "ovolt" to 0xFF0288D1.toInt(),         // light blue
    "EPSIS" to 0xFFAD1457.toInt(),         // dark pink

    // Tier three, ranks 16 to 20. Past here the differences stop being readable on a
    // 10dp dot, so the tail keeps the neutral grey rather than being given colours
    // nobody could tell apart.
    "D-Charge" to 0xFF37474F.toInt(),      // slate
    "5 Şarj" to 0xFF8D6E63.toInt(),        // light brown
    "Oncharge" to 0xFF689F38.toInt(),      // light green
    "Astor Şarj" to 0xFF7CB342.toInt(),    // lime
    "Efish" to 0xFF26A69A.toInt()          // light teal
)

/**
 * One colour per drawn route, in descending order of prominence.
 *
 * Chosen to be distinguishable from each other, from the marker colours, and from
 * the roads underneath — the last of which rules out the obvious greys and blues.
 */
private val ROUTE_COLOURS = intArrayOf(
    0xFF22C1D6.toInt(),   // teal: the nearest, and the app's own accent
    0xFF8B5CF6.toInt(),   // violet
    0xFF1BA98A.toInt(),   // green
    0xFFE8467C.toInt(),   // pink
    0xFFF0862B.toInt()    // orange
)

/** Close enough to see the streets around you, without losing nearby towns. */
private const val USER_ZOOM = 14.0

/** Close enough to see the forecourt of the place that was picked. */
private const val PICKED_ZOOM = 16.0

private const val SEARCH_RESULTS_SHOWN = 8
