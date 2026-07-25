package com.berke.ioniqscope.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.berke.ioniqscope.ServiceLocator
import com.berke.ioniqscope.data.AdapterType
import com.berke.ioniqscope.data.AppSettings
import com.berke.ioniqscope.data.PidCatalog
import com.berke.ioniqscope.data.SettingsRepository
import com.berke.ioniqscope.data.SpeedUnit
import com.berke.ioniqscope.ui.components.Banner
import com.berke.ioniqscope.ui.components.BannerTone
import com.berke.ioniqscope.ui.components.SectionLabel
import com.berke.ioniqscope.ui.serviceViewModel
import com.berke.ioniqscope.update.UpdateState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val services: ServiceLocator) : ViewModel() {

    private val repo = services.settings

    val settings: StateFlow<AppSettings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setUnit(unit: SpeedUnit) = viewModelScope.launch { repo.setSpeedUnit(unit) }
    fun setAutoConnect(enabled: Boolean) = viewModelScope.launch { repo.setAutoConnect(enabled) }
    fun setAutoLog(enabled: Boolean) = viewModelScope.launch { repo.setAutoLogTrips(enabled) }
    val updateState: StateFlow<UpdateState> = services.updateChecker.state

    fun setOcmKey(key: String) = viewModelScope.launch { repo.setOcmApiKey(key) }
    fun setUpdateLink(link: String) = viewModelScope.launch { repo.setUpdateShareLink(link) }
    fun setAutoCheck(enabled: Boolean) = viewModelScope.launch { repo.setAutoCheckUpdates(enabled) }

    fun checkForUpdate() = viewModelScope.launch { services.updateChecker.check() }
    fun dismissUpdate() = services.updateChecker.reset()

    fun downloadUpdate() = viewModelScope.launch {
        (updateState.value as? UpdateState.Available)?.let {
            services.updateChecker.download(it.update)
        }
    }
    fun setDcOnly(enabled: Boolean) = viewModelScope.launch { repo.setChargersDcOnly(enabled) }
    fun setMinPower(kw: Int) = viewModelScope.launch { repo.setChargersMinPower(kw) }
    fun setAdapter(type: AdapterType) = viewModelScope.launch { repo.setAdapterType(type) }
    fun setPollInterval(ms: Int) = viewModelScope.launch { repo.setPollInterval(ms) }

    fun togglePid(key: String, enabled: Boolean) = viewModelScope.launch {
        val current = settings.value.dashboardPidKeys
        val next = if (enabled) current + key else current - key
        repo.setDashboardPids(next)
    }
}

@Composable
fun SettingsScreen(services: ServiceLocator) {
    val vm = serviceViewModel(services) { SettingsViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val updateState by vm.updateState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionLabel("Updates", Modifier.padding(top = 16.dp))
        UpdateSection(
            state = updateState,
            shareLink = settings.updateShareLink,
            autoCheck = settings.autoCheckUpdates,
            onShareLinkChange = vm::setUpdateLink,
            onAutoCheckChange = vm::setAutoCheck,
            onCheck = vm::checkForUpdate,
            onDownload = vm::downloadUpdate,
            onInstall = { services.apkDownloader.install(it) },
            onDismiss = vm::dismissUpdate
        )

        HorizontalDivider()
        SectionLabel("Units")
        SpeedUnit.entries.forEach { unit ->
            ChoiceRow(
                selected = settings.speedUnit == unit,
                title = unit.label,
                subtitle = "Displayed as ${unit.suffix}",
                onClick = { vm.setUnit(unit) }
            )
        }

        HorizontalDivider()
        SectionLabel("Adapter")
        AdapterType.entries.forEach { type ->
            ChoiceRow(
                selected = settings.adapterType == type,
                title = type.label,
                subtitle = type.description,
                onClick = { vm.setAdapter(type) }
            )
        }

        HorizontalDivider()
        SectionLabel("Automation")
        SwitchRow(
            checked = settings.autoConnect,
            title = "Connect on launch",
            subtitle = "Reconnect to the last adapter when the app opens.",
            onChange = vm::setAutoConnect
        )
        SwitchRow(
            checked = settings.autoLogTrips,
            title = "Log trips automatically",
            subtitle = "Start recording once the car is moving, stop after three minutes " +
                "stationary. No need to remember the button.",
            onChange = vm::setAutoLog
        )

        HorizontalDivider()
        SectionLabel("Chargers")
        SwitchRow(
            checked = settings.chargersDcOnly,
            title = "DC only",
            subtitle = "Hides stations recorded as AC. Stations with no current type " +
                "recorded stay visible — most OSM entries in Türkiye never say, so " +
                "excluding them would hide real fast chargers.",
            onChange = vm::setDcOnly
        )

        var minPower by remember(settings.chargersMinPowerKw) {
            mutableFloatStateOf(settings.chargersMinPowerKw.toFloat())
        }
        Text(
            if (minPower < 1) "No minimum power" else "At least ${minPower.toInt()} kW",
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace
        )
        Slider(
            value = minPower,
            onValueChange = { minPower = it },
            onValueChangeFinished = { vm.setMinPower(minPower.toInt()) },
            valueRange = 0f..350f
        )
        Text(
            "Only 54 of the 654 Turkish OpenStreetMap entries record their power, so a " +
                "minimum here hides everything that never said. Useful with Open Charge " +
                "Map data, blunt with OSM data.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary
        )

        var ocmKey by remember(settings.ocmApiKey) { mutableStateOf(settings.ocmApiKey) }
        OutlinedTextField(
            value = ocmKey,
            onValueChange = { ocmKey = it },
            label = { Text("Open Charge Map API key") },
            placeholder = { Text("paste your own free key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.setOcmKey(ocmKey) }) { Text("Save key") }
            if (settings.ocmApiKey.isNotBlank()) {
                TextButton(onClick = { ocmKey = ""; vm.setOcmKey("") }) { Text("Clear") }
            }
        }
        Text(
            "Optional. Open Charge Map is EV-specific, so its connector and power data " +
                "is far better than raw OpenStreetMap. The key is free but you have to " +
                "register it yourself at openchargemap.org — the app will not create an " +
                "account for you. It is stored only on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()
        SectionLabel("Dashboard PIDs")
        Text(
            "Which values the Dashboard polls. Fewer PIDs means each one updates faster.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        PidCatalog.all.forEach { entry ->
            val checked = entry.pid.key in settings.dashboardPidKeys
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.togglePid(entry.pid.key, !checked) }
                    .padding(vertical = 4.dp)
            ) {
                Checkbox(checked = checked, onCheckedChange = { vm.togglePid(entry.pid.key, it) })
                Column(Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(entry.pid.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            entry.pid.request,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    entry.caveat?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
        }

        HorizontalDivider()
        SectionLabel("Poll interval")

        var sliderValue by remember(settings.pollIntervalMs) {
            mutableFloatStateOf(settings.pollIntervalMs.toFloat())
        }
        Text(
            "${sliderValue.toInt()} ms between full poll cycles",
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace
        )
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { vm.setPollInterval(sliderValue.toInt()) },
            valueRange = SettingsRepository.POLL_MIN_MS.toFloat()..
                SettingsRepository.POLL_MAX_MS.toFloat()
        )
        Text(
            "The Performance screen always overrides this with speed-only polling at 50 ms.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()
        SectionLabel("Ioniq 6 battery data")
        Banner(
            title = "Not implemented — deliberately",
            text = "State of charge, HV battery voltage/current, power in kW and cell " +
                "temperatures are not standard OBD-II PIDs; they need manufacturer-specific " +
                "UDS requests. None are shipped, because guessing a DID and mis-parsing the " +
                "response would show you convincing numbers that are wrong. Supply verified " +
                "values (EVNotify, Car Scanner Ioniq profile) and they drop into EgmpPids.",
            tone = BannerTone.Info
        )

        HorizontalDivider()
        SectionLabel("Privacy")
        Text(
            "IoniqScope holds no INTERNET permission. There is no analytics, no crash " +
                "reporting and no account. Trips, runs and settings live only on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp)
        )
    }
}

/** Shared with [UpdateSection] so the two read identically. */
@Composable
fun SettingsSwitchRow(
    checked: Boolean,
    title: String,
    subtitle: String,
    onChange: (Boolean) -> Unit
) = SwitchRow(checked, title, subtitle, onChange)

@Composable
private fun SwitchRow(
    checked: Boolean,
    title: String,
    subtitle: String,
    onChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 4.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChoiceRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
