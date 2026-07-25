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
        SectionLabel("Güncellemeler", Modifier.padding(top = 16.dp))
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
        SectionLabel("Birimler")
        SpeedUnit.entries.forEach { unit ->
            ChoiceRow(
                selected = settings.speedUnit == unit,
                title = unit.label,
                subtitle = "${unit.suffix} olarak gösterilir",
                onClick = { vm.setUnit(unit) }
            )
        }

        HorizontalDivider()
        SectionLabel("Adaptör")
        AdapterType.entries.forEach { type ->
            ChoiceRow(
                selected = settings.adapterType == type,
                title = type.label,
                subtitle = type.description,
                onClick = { vm.setAdapter(type) }
            )
        }

        HorizontalDivider()
        SectionLabel("Otomasyon")
        SwitchRow(
            checked = settings.autoConnect,
            title = "Açılışta bağlan",
            subtitle = "Uygulama açıldığında son adaptöre yeniden bağlanır.",
            onChange = vm::setAutoConnect
        )
        SwitchRow(
            checked = settings.autoLogTrips,
            title = "Seferleri otomatik kaydet",
            subtitle = "Araç hareket edince kaydı başlatır, üç dakika durunca bitirir. " +
                "Düğmeyi hatırlamana gerek kalmaz.",
            onChange = vm::setAutoLog
        )

        HorizontalDivider()
        SectionLabel("Şarj istasyonları")
        SwitchRow(
            checked = settings.chargersDcOnly,
            title = "Sadece DC",
            subtitle = "AC olarak kayıtlı istasyonları gizler. Akım tipi belirtilmemiş " +
                "olanlar görünür kalır — Türkiye'deki OSM kayıtlarının çoğu bunu hiç " +
                "yazmıyor, dışlamak gerçek hızlı şarjları gizlerdi.",
            onChange = vm::setDcOnly
        )

        var minPower by remember(settings.chargersMinPowerKw) {
            mutableFloatStateOf(settings.chargersMinPowerKw.toFloat())
        }
        Text(
            if (minPower < 1) "Alt güç sınırı yok" else "En az ${minPower.toInt()} kW",
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
            "Elimizdeki 3.988 yerin yalnızca 1.863'ünde güç bilgisi var, yani buradaki " +
                "alt sınır belirtilmemiş olanların hepsini gizler. Open Charge Map " +
                "verisiyle işe yarar, OpenStreetMap verisiyle körlemesine keser.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary
        )

        var ocmKey by remember(settings.ocmApiKey) { mutableStateOf(settings.ocmApiKey) }
        OutlinedTextField(
            value = ocmKey,
            onValueChange = { ocmKey = it },
            label = { Text("Open Charge Map API anahtarı") },
            placeholder = { Text("paste your own free key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.setOcmKey(ocmKey) }) { Text("Anahtarı kaydet") }
            if (settings.ocmApiKey.isNotBlank()) {
                TextButton(onClick = { ocmKey = ""; vm.setOcmKey("") }) { Text("Clear") }
            }
        }
        Text(
            "İsteğe bağlı. Open Charge Map elektrikli araca özel olduğu için soket ve güç " +
                "verisi ham OpenStreetMap'ten çok daha iyi. Anahtar ücretsiz ama " +
                "openchargemap.org'dan kendin almalısın — uygulama senin adına hesap " +
                "açmaz. Anahtar yalnızca bu telefonda saklanır.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()
        SectionLabel("Gösterge PID'leri")
        Text(
            "Göstergenin hangi değerleri sorgulayacağı. Az PID, her birinin daha sık güncellenmesi demek.",
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
        SectionLabel("Sorgu aralığı")

        var sliderValue by remember(settings.pollIntervalMs) {
            mutableFloatStateOf(settings.pollIntervalMs.toFloat())
        }
        Text(
            "tam sorgu turları arasında ${sliderValue.toInt()} ms",
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
            "Performans ekranı bunu her zaman geçersiz kılar; 50 ms'de yalnızca hız sorgular.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()
        SectionLabel("Ioniq 6 batarya verisi")
        Banner(
            title = "Bilerek eklenmedi",
            text = "Şarj durumu, HV batarya voltajı/akımı, kW cinsinden güç ve hücre " +
                "sıcaklıkları standart OBD-II PID'leri değil; üreticiye özel UDS " +
                "istekleri gerektiriyorlar. Hiçbiri gömülü gelmiyor, çünkü bir DID " +
                "tahmin edip yanıtı yanlış çözmek sana inandırıcı ama yanlış sayılar " +
                "gösterirdi. Doğrulanmış değerleri (EVNotify, Car Scanner Ioniq " +
                "profili) ver, EgmpPids'e düşsünler.",
            tone = BannerTone.Info
        )

        HorizontalDivider()
        SectionLabel("Gizlilik")
        Text(
            "Analitik yok, çökme raporu yok, hesap yok. Seferler, ölçümler ve ayarlar " +
                "yalnızca bu telefonda duruyor. İnternet üç şey için kullanılıyor: " +
                "harita karoları, şarj istasyonu listesi ve rota çizimi. Araçtan " +
                "okunan hiçbir veri telefondan çıkmıyor.",
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
