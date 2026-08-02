package com.berke.ioniqscope.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
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
        // Two mutually exclusive options with nothing to explain: a segmented control
        // says "one of these" in the shape of the control, where two rows with radio
        // buttons and a subtitle each say it in four lines of text.
        Surface(
            shape = RoundedCornerShape(13.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SpeedUnit.entries.forEach { unit ->
                    val chosen = settings.speedUnit == unit
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = if (chosen) MaterialTheme.colorScheme.primary
                        else Color.Transparent,
                        contentColor = if (chosen) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = { vm.setUnit(unit) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            Modifier.height(34.dp).fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) { Text(unit.suffix, style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
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
            "Elimizdeki 6.102 yerin yalnızca 1.779'unda güç bilgisi var, yani buradaki " +
                "alt sınır belirtilmemiş olanların hepsini gizler. Open Charge Map " +
                "verisiyle işe yarar, OpenStreetMap verisiyle körlemesine keser.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary
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
        SectionLabel("Araç")
        Text(
            "Hız, 12V ve dış sıcaklık her arabada standart OBD-II ile okunur — araç " +
                "seçmesen de çalışır. Batarya şarjı ve sağlığı standart değil; her " +
                "platformun kendi sorgusu, kendi bayt düzeni var. Seçim OBD > Batarya " +
                "sekmesinde ve orada ham cevapla birlikte doğrulanabiliyor.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        HorizontalDivider()
        SectionLabel("Gizlilik")
        Text(
            // The routing sentence used to sit on the map itself. It moved here rather
            // than being deleted: the map is not the place for a paragraph, but a
            // feature that sends the user's position to a third party has to be
            // written down somewhere they can find it.
            "Analitik yok, çökme raporu yok, hesap yok. Seferler, ölçümler ve ayarlar " +
                "yalnızca bu telefonda duruyor. Araçtan okunan hiçbir veri telefondan " +
                "çıkmıyor.\n\n" +
                "İnternet üç şey için kullanılıyor: harita karoları, şarj istasyonu " +
                "listesi ve rota çizimi. Bunlardan yalnızca rota çizimi konumunu dışarı " +
                "gönderiyor — en yakın istasyonlara yol çizmek için başlangıç ve varış " +
                "noktası bir rota servisine (OSRM) iletiliyor. Karolar sadece haritada " +
                "hangi bölgeye baktığını belli eder, istasyon listesi ise uygulamanın " +
                "içinde geldiği için hiçbir istek gerektirmez.",
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
