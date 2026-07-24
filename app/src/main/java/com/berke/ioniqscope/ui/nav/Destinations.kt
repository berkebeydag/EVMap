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
    @param:StringRes val labelRes: Int,
    val icon: ImageVector
) {
    Dashboard("dashboard", R.string.nav_dashboard, Icons.Filled.Speed),
    Performance("performance", R.string.nav_performance, Icons.Filled.Timer),
    Diagnostics("diagnostics", R.string.nav_diagnostics, Icons.Filled.Warning),
    Trips("trips", R.string.nav_trips, Icons.Filled.Route),

    // Reached from the top bar, not the bottom bar.
    Connect("connect", R.string.nav_connect, Icons.Filled.Bluetooth),
    Settings("settings", R.string.nav_settings, Icons.Filled.Settings);

    companion object {
        /**
         * The four places you actually spend time. Connect and Settings live in the
         * top bar instead: Connect is something you do once at the start of a drive,
         * not a destination you return to, and six items crowd the labels off a
         * phone-width NavigationBar.
         */
        val bottomBar = listOf(Dashboard, Performance, Diagnostics, Trips)

        /** Pushed detail screens — these get a back arrow rather than a tab. */
        val detail = listOf(Connect, Settings)

        fun fromRoute(route: String?): Destination? = entries.firstOrNull { it.route == route }
    }
}
