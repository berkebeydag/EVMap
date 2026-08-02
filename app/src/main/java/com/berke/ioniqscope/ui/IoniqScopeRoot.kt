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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
            val toSettings: () -> Unit = { navController.navigateTo(Destination.Settings) }

            composable(Destination.Chargers.route) {
                ChargerMapScreen(services, toSettings, toConnect)
            }
            composable(Destination.Obd.route) {
                ObdScreen(
                    services = services,
                    onConnect = toConnect,
                    onSettings = toSettings,
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
                    onSettings = toSettings,
                    onOpenTrip = { tripId ->
                        navController.navigate(Destination.tripDetailRoute(tripId))
                    }
                )
            }
            composable(Destination.Connect.route) {
                DetailScreen("Bağlan", navController) { ConnectScreen(services) }
            }
            composable(Destination.Settings.route) {
                DetailScreen("Ayarlar", navController) { SettingsScreen(services) }
            }
            composable(Destination.RawConsole.route) {
                DetailScreen("Komut konsolu", navController) { RawConsoleScreen(services) }
            }
            composable(Destination.AuxBattery.route) {
                DetailScreen("12V akü", navController) { AuxBatteryScreen(services) }
            }
            composable(
                route = Destination.TripDetail.route,
                arguments = listOf(navArgument("tripId") { type = NavType.LongType })
            ) { entry ->
                DetailScreen("Yolculuk", navController) {
                    TripDetailScreen(
                        services = services,
                        tripId = entry.arguments?.getLong("tripId") ?: 0L
                    )
                }
            }
        }
    }
}

/**
 * A pushed screen's heading, with the way back.
 *
 * Pushed screens used to borrow the shared bar's back arrow. With that gone they carry
 * their own, which also puts the title at the same size as every other screen's rather
 * than at the smaller one a bar imposes.
 */
@Composable
private fun DetailScreen(
    title: String,
    navController: NavHostController,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 6.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back)
                )
            }
            Text(title, style = MaterialTheme.typography.headlineMedium)
        }
        content()
    }
}

private fun NavHostController.navigateTo(destination: Destination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
