package com.berke.ioniqscope.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import com.berke.ioniqscope.R

/**
 * The three screens that live inside [Destination.Obd].
 *
 * Sections rather than routes: switching between the gauges and the fault list is not
 * going somewhere, it is looking at the same connection a different way, and giving
 * each one a back-stack entry meant the system back button walked through them.
 */
enum class ObdSection(
    @param:StringRes val labelRes: Int,
    val icon: ImageVector
) {
    Dashboard(R.string.nav_dashboard, Icons.Filled.Speed),
    Performance(R.string.nav_performance, Icons.Filled.Timer),
    Diagnostics(R.string.nav_diagnostics, Icons.Filled.Warning),
    Battery(R.string.nav_battery, Icons.Filled.BatteryChargingFull)
}
