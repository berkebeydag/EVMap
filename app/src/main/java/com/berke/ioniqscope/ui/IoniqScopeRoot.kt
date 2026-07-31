package com.berke.ioniqscope.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.berke.ioniqscope.R
import com.berke.ioniqscope.ServiceLocator
import com.berke.ioniqscope.connection.ConnectionState
import com.berke.ioniqscope.ui.nav.Destination
import com.berke.ioniqscope.ui.screens.battery.AuxBatteryScreen
import com.berke.ioniqscope.ui.screens.chargers.ChargerMapScreen
import com.berke.ioniqscope.ui.screens.connect.ConnectScreen
import com.berke.ioniqscope.ui.screens.console.RawConsoleScreen
import com.berke.ioniqscope.ui.screens.obd.AUX_BATTERY
import com.berke.ioniqscope.ui.screens.obd.ObdScreen
import com.berke.ioniqscope.ui.screens.settings.SettingsScreen
import com.berke.ioniqscope.ui.screens.trips.TripDetailScreen
import com.berke.ioniqscope.ui.screens.trips.TripLogScreen
import com.berke.ioniqscope.ui.theme.StatusAmber
import com.berke.ioniqscope.ui.theme.StatusGreen

/** Builds a ViewModel that needs the app's object graph, without a DI framework. */
@Composable
inline fun <reified VM : ViewModel> serviceViewModel(
    services: ServiceLocator,
    crossinline create: (ServiceLocator) -> VM
): VM = viewModel(
    factory = viewModelFactory { initializer { create(services) } }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IoniqScopeRoot(services: ServiceLocator) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = Destination.fromRoute(backStackEntry?.destination?.route) ?: Destination.Chargers
    val connection by services.connectionManager.connectionState.collectAsStateWithLifecycle()

    val isDetail = current in Destination.detail

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(current.labelRes)) },
                navigationIcon = {
                    if (isDetail) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    }
                },
                actions = {
                    // Connection lives here rather than in the bottom bar: it is a
                    // one-off action at the start of a drive, and the dot means the
                    // status is legible from every screen without spending a tab.
                    ConnectionAction(
                        state = connection,
                        selected = current == Destination.Connect,
                        onClick = { navController.navigateTo(Destination.Connect) }
                    )
                    IconButton(onClick = { navController.navigateTo(Destination.Settings) }) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.nav_settings)
                        )
                    }
                }
            )
        },
        bottomBar = {
            // Shorter than the 80dp default — three items do not need the height five
            // did, and this bar sits under a map that wants every row it can get.
            //
            // Added to the gesture bar's own inset rather than replacing it. A flat
            // 64dp is the height of *everything*, insets included, so on a phone with
            // gesture navigation the labels were squeezed down into the system bar's
            // space and ended up sitting on the line itself. This is 60dp of bar plus
            // however much the system is reserving underneath it.
            val systemBar = WindowInsets.navigationBars.asPaddingValues()
                .calculateBottomPadding()
            NavigationBar(Modifier.height(60.dp + systemBar)) {
                Destination.bottomBar.forEach { destination ->
                    NavigationBarItem(
                        selected = current == destination,
                        onClick = { navController.navigateTo(destination) },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = {
                            Text(
                                stringResource(destination.labelRes),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            // The map opens the app. It is the screen that says something before
            // anything is plugged in, which is most of the time.
            startDestination = Destination.Chargers.route,
            modifier = Modifier.padding(padding)
        ) {
            val toConnect: () -> Unit = { navController.navigateTo(Destination.Connect) }

            composable(Destination.Chargers.route) { ChargerMapScreen(services) }
            composable(Destination.Obd.route) {
                ObdScreen(
                    services = services,
                    onConnect = toConnect,
                    onOpen = { target ->
                        navController.navigateTo(
                            if (target == AUX_BATTERY) Destination.AuxBattery
                            else Destination.RawConsole
                        )
                    }
                )
            }
            composable(Destination.Trips.route) {
                TripLogScreen(
                    services = services,
                    onConnect = toConnect,
                    onOpenTrip = { tripId ->
                        navController.navigate(Destination.tripDetailRoute(tripId))
                    }
                )
            }
            composable(Destination.Connect.route) { ConnectScreen(services) }
            composable(Destination.Settings.route) { SettingsScreen(services) }
            composable(Destination.RawConsole.route) { RawConsoleScreen(services) }
            composable(Destination.AuxBattery.route) { AuxBatteryScreen(services) }
            composable(
                route = Destination.TripDetail.route,
                arguments = listOf(navArgument("tripId") { type = NavType.LongType })
            ) { entry ->
                TripDetailScreen(
                    services = services,
                    tripId = entry.arguments?.getLong("tripId") ?: 0L
                )
            }
        }
    }
}

/**
 * Bluetooth icon carrying a status dot: green connected, amber connecting (pulsing),
 * red failed, grey idle. Tapping it opens the Connect screen.
 */
@Composable
private fun ConnectionAction(
    state: ConnectionState,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme

    val targetDot = when (state) {
        is ConnectionState.Connected -> StatusGreen
        is ConnectionState.Connecting -> StatusAmber
        is ConnectionState.Failed -> scheme.error
        ConnectionState.Disconnected -> scheme.outline
    }
    val dotColor by animateColorAsState(targetDot, label = "connectionDot")

    // Only the connecting state pulses; a steady dot must not draw the eye while driving.
    val pulse = rememberInfiniteTransition(label = "connectionPulse")
    val dotAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (state is ConnectionState.Connecting) 0.25f else 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "connectionDotAlpha"
    )

    val label = when (state) {
        is ConnectionState.Connected -> stringResource(R.string.status_connected, state.deviceName)
        is ConnectionState.Connecting -> stringResource(R.string.status_connecting)
        is ConnectionState.Failed -> stringResource(R.string.status_failed)
        ConnectionState.Disconnected -> stringResource(R.string.status_disconnected)
    }

    IconButton(onClick = onClick) {
        Box {
            Icon(
                imageVector = if (state is ConnectionState.Connected) {
                    Icons.Filled.BluetoothConnected
                } else Icons.Filled.Bluetooth,
                contentDescription = label,
                tint = if (selected) scheme.primary else scheme.onSurface
            )
            // Ring in the bar's own colour so the dot reads against the icon behind it.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-3).dp)
                    .size(13.dp)
                    .background(scheme.surface, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(9.dp)
                        .alpha(dotAlpha)
                        .background(dotColor, CircleShape)
                )
            }
        }
    }
}

private fun NavHostController.navigateTo(destination: Destination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
