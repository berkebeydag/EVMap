package com.berke.ioniqscope.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import com.berke.ioniqscope.R

enum class Destination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector
) {
    Chargers("chargers", R.string.nav_chargers, Icons.Filled.EvStation),
    Trips("trips", R.string.nav_trips, Icons.Filled.Route),

    /**
     * Everything that only says anything with the adapter plugged in.
     *
     * The gauges, the timer and the fault reader used to be three tabs of five, which
     * put the two screens that work without a car — the map and the trip log — in the
     * minority of their own navigation bar. They are one destination now, with the
     * three of them behind a row of tabs inside it, because "am I connected" is the
     * question they all share and none of the others ask.
     */
    Obd("obd", R.string.nav_obd, Icons.Filled.Speed),

    // Reached from the top bar, not the bottom bar.
    Connect("connect", R.string.nav_connect, Icons.Filled.Bluetooth),
    Settings("settings", R.string.nav_settings, Icons.Filled.Settings),

    // Pushed from Diagnostics.
    RawConsole("console", R.string.nav_console, Icons.Filled.Terminal),
    AuxBattery("aux_battery", R.string.nav_aux_battery, Icons.Filled.BatteryAlert),

    // Pushed from the trip list. Takes a tripId argument.
    TripDetail("trip/{tripId}", R.string.nav_trip_detail, Icons.Filled.Route);

    companion object {
        /**
         * The three places you actually spend time. Connect and Settings live in the
         * top bar instead: Connect is something you do once at the start of a drive,
         * not a destination you return to, and crowded labels are the first thing to
         * go on a phone-width NavigationBar.
         */
        val bottomBar = listOf(Chargers, Trips, Obd)

        /** Pushed detail screens — these get a back arrow rather than a tab. */
        val detail = listOf(Connect, Settings, RawConsole, AuxBattery, TripDetail)

        fun tripDetailRoute(tripId: Long) = "trip/$tripId"

        fun fromRoute(route: String?): Destination? = entries.firstOrNull { it.route == route }
    }
}
