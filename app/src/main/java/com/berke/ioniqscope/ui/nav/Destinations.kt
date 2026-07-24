package com.berke.ioniqscope.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import com.berke.ioniqscope.R

enum class Destination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector
) {
    Connect("connect", R.string.nav_connect, Icons.Filled.Bluetooth),
    Dashboard("dashboard", R.string.nav_dashboard, Icons.Filled.Speed),
    Performance("performance", R.string.nav_performance, Icons.Filled.Timer),
    Diagnostics("diagnostics", R.string.nav_diagnostics, Icons.Filled.Warning),
    Trips("trips", R.string.nav_trips, Icons.Filled.Route),
    Settings("settings", R.string.nav_settings, Icons.Filled.Settings);

    companion object {
        /**
         * Settings is deliberately not in the bottom bar — six items crowd the labels
         * off a phone-width NavigationBar. It is reachable from the gear in the top bar.
         */
        val bottomBar = listOf(Connect, Dashboard, Performance, Diagnostics, Trips)

        fun fromRoute(route: String?): Destination? = entries.firstOrNull { it.route == route }
    }
}
