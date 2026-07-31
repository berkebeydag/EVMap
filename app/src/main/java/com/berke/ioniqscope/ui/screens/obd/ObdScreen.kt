package com.berke.ioniqscope.ui.screens.obd

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.berke.ioniqscope.ServiceLocator
import com.berke.ioniqscope.ui.nav.ObdSection
import com.berke.ioniqscope.ui.screens.dashboard.DashboardScreen
import com.berke.ioniqscope.ui.screens.diagnostics.DiagnosticsScreen
import com.berke.ioniqscope.ui.screens.performance.PerformanceScreen

/**
 * The three screens that need the adapter, behind one tab.
 *
 * They were three of five entries in the bottom bar, which left the two screens that
 * work with no car at all — the map and the trip log — outnumbered in their own
 * navigation. They belong together for a simpler reason than tidiness, though: all
 * three answer nothing until something is plugged in, and none of the others ever ask.
 *
 * A tab row rather than routes. Moving between the gauges and the fault list is not
 * going somewhere; it is looking at the same connection a different way. Routes gave
 * each one a back-stack entry, so the system back button walked backwards through
 * screens the user had only glanced at.
 *
 * [rememberSaveable] so the section survives rotation and coming back from Settings —
 * a tab that resets itself to the first one every time you leave is a tab you stop
 * using.
 */
@Composable
fun ObdScreen(services: ServiceLocator, onConnect: () -> Unit, onOpen: (String) -> Unit) {
    var section by rememberSaveable { mutableStateOf(ObdSection.Dashboard) }

    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = section.ordinal) {
            ObdSection.entries.forEach { entry ->
                Tab(
                    selected = section == entry,
                    onClick = { section = entry },
                    text = {
                        Text(
                            stringResource(entry.labelRes),
                            style = MaterialTheme.typography.labelLarge
                        )
                    },
                    icon = { Icon(entry.icon, contentDescription = null) }
                )
            }
        }

        when (section) {
            ObdSection.Dashboard -> DashboardScreen(services, onConnect)
            ObdSection.Performance -> PerformanceScreen(services, onConnect)
            ObdSection.Diagnostics -> DiagnosticsScreen(
                services = services,
                onConnect = onConnect,
                onOpenConsole = { onOpen(CONSOLE) },
                onOpenAuxBattery = { onOpen(AUX_BATTERY) }
            )
        }
    }
}

/** What the diagnostics section can push, named here so the host does the navigating. */
const val CONSOLE = "console"
const val AUX_BATTERY = "aux_battery"
