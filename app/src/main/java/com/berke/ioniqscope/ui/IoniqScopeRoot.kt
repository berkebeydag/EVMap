package com.berke.ioniqscope.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.berke.ioniqscope.R
import com.berke.ioniqscope.ServiceLocator
import com.berke.ioniqscope.connection.ConnectionState
import com.berke.ioniqscope.ui.nav.Destination
import com.berke.ioniqscope.ui.screens.connect.ConnectScreen
import com.berke.ioniqscope.ui.screens.dashboard.DashboardScreen
import com.berke.ioniqscope.ui.screens.diagnostics.DiagnosticsScreen
import com.berke.ioniqscope.ui.screens.performance.PerformanceScreen
import com.berke.ioniqscope.ui.screens.settings.SettingsScreen
import com.berke.ioniqscope.ui.screens.trips.TripLogScreen

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
    val current = Destination.fromRoute(backStackEntry?.destination?.route) ?: Destination.Connect
    val connection by services.connectionManager.connectionState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ConnectionDot(connection)
                        Text(stringResource(current.labelRes))
                    }
                },
                actions = {
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
            NavigationBar {
                Destination.bottomBar.forEach { destination ->
                    NavigationBarItem(
                        selected = current == destination,
                        onClick = { navController.navigateTo(destination) },
                        icon = {
                            Icon(destination.icon, contentDescription = null)
                        },
                        label = { Text(stringResource(destination.labelRes)) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Connect.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Destination.Connect.route) { ConnectScreen(services) }
            composable(Destination.Dashboard.route) { DashboardScreen(services) }
            composable(Destination.Performance.route) { PerformanceScreen(services) }
            composable(Destination.Diagnostics.route) { DiagnosticsScreen(services) }
            composable(Destination.Trips.route) { TripLogScreen(services) }
            composable(Destination.Settings.route) { SettingsScreen(services) }
        }
    }
}

/** Always-visible connection indicator — the one thing worth knowing on every screen. */
@Composable
private fun ConnectionDot(state: ConnectionState) {
    val color = when (state) {
        is ConnectionState.Connected -> MaterialTheme.colorScheme.primary
        is ConnectionState.Connecting -> MaterialTheme.colorScheme.tertiary
        is ConnectionState.Failed -> MaterialTheme.colorScheme.error
        ConnectionState.Disconnected -> MaterialTheme.colorScheme.outline
    }
    Surface(
        modifier = Modifier.size(10.dp),
        shape = CircleShape,
        color = color,
        content = {}
    )
}

private fun NavHostController.navigateTo(destination: Destination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
