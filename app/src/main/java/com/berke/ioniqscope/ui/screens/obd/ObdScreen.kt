package com.berke.ioniqscope.ui.screens.obd

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
        // A segmented control rather than a tab row. Tabs are for moving between
        // places; these four are one place looked at four ways, and the design draws
        // that difference — a single bordered strip holding four segments, with the
        // chosen one filled, instead of four labels sharing an underline.
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
        ) {
            Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ObdSection.entries.forEach { entry ->
                    val chosen = section == entry
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (chosen) MaterialTheme.colorScheme.primary
                        else Color.Transparent,
                        contentColor = if (chosen) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = { section = entry },
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            Modifier.height(32.dp).fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(entry.labelRes),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        when (section) {
            ObdSection.Dashboard -> DashboardScreen(services, onConnect)
            ObdSection.Performance -> PerformanceScreen(services, onConnect)
            ObdSection.Battery -> BatteryScreen(services, onConnect)
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
