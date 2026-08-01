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
import com.berke.ioniqscope.charging.ChargerSource
import com.berke.ioniqscope.charging.SyncState
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
    fun setTomTomKey(key: String) = viewModelScope.launch { repo.setTomTomApiKey(key) }

    val syncState: StateFlow<SyncState> = services.chargerRepository.syncState

    /** Only the sources the user has actually enabled by supplying a key. */
    fun availableSources(): List<ChargerSource> =
        services.chargerSources.filter { it.isAvailable() }

    fun syncChargers(source: ChargerSource) = viewModelScope.launch {
        services.chargerRepository.sync(source)
    }

    fun dismissSync() = services.chargerRepository.clearSyncState()
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
    val syncState by vm.syncState.collectAsStateWithLifecycle()

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
            "Elimizdeki 6.102 yerin yalnızca 1.779'unda güç bilgisi var, yani buradaki " +
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
            placeholder = { Text("kendi ücretsiz anahtarın") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.setOcmKey(ocmKey) }) { Text("Anahtarı kaydet") }
            if (settings.ocmApiKey.isNotBlank()) {
                TextButton(onClick = { ocmKey = ""; vm.setOcmKey("") }) { Text("Temizle") }
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

        var tomtomKey by remember(settings.tomtomApiKey) { mutableStateOf(settings.tomtomApiKey) }
        OutlinedTextField(
            value = tomtomKey,
            onValueChange = { tomtomKey = it },
            label = { Text("TomTom API anahtarı") },
            placeholder = { Text("kendi ücretsiz anahtarın") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.setTomTomKey(tomtomKey) }) { Text("Anahtarı kaydet") }
            if (settings.tomtomApiKey.isNotBlank()) {
                TextButton(onClick = { tomtomKey = ""; vm.setTomTomKey("") }) { Text("Temizle") }
            }
        }
        Text(
            "İsteğe bağlı ama en çok fark yaratan kaynak. Ölçtüm: döndürdüğünün " +
                "yaklaşık yarısı bizde yok, ve tek başına her sonuçta gücü yazıyor — " +
                "paketteki 6.102 yerin ancak 1.779'unda güç bilgisi var. Ayrıca " +
                "kendi listesini yayınlamayan ağları (Eşarj, Voltrun, Sharz, WAT) " +
                "taşıyor. " +
                "Anahtarı developer.tomtom.com'dan kendin alıyorsun; uygulama senin " +
                "adına hesap açmaz. Bu kaynak uygulamanın içinde gelen listeye " +
                "karışmıyor — TomTom verisi senin anahtarınla çekilip yalnızca bu " +
                "telefonda saklanıyor, herkese dağıtılan pakete girmiyor.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ChargerSyncSection(
            state = syncState,
            sources = vm.availableSources(),
            onSync = vm::syncChargers,
            onDismiss = vm::dismissSync
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

/**
 * Pulls fresh stations from whichever keyed sources are switched on.
 *
 * Lives here rather than on the map. The map had a refresh button and it was taken
 * off deliberately: on a screen you look at while driving, the useful action is
 * "find me a charger", not "go and re-download the country". This is a thing you do
 * once, at home, after pasting a key.
 */
@Composable
private fun ChargerSyncSection(
    state: SyncState,
    sources: List<ChargerSource>,
    onSync: (ChargerSource) -> Unit,
    onDismiss: () -> Unit
) {
    if (sources.isEmpty()) return

    when (state) {
        is SyncState.Running -> Text(
            "${state.sourceName} indiriliyor… bu bir dakika sürebilir.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        is SyncState.Failed -> Banner(
            title = "Güncelleme başarısız",
            text = state.message,
            tone = BannerTone.Error,
            actionLabel = "Kapat",
            onAction = onDismiss
        )

        is SyncState.Done -> Banner(
            title = if (state.partial) "Kısmi güncelleme" else null,
            text = if (state.partial) {
                "${state.sourceName} kaynağından ${state.added} istasyon geldi, ama " +
                    "istek bütçesi bitti. Gelenler mevcut listenin üzerine eklendi, " +
                    "listenin yerine geçmedi. Tekrar çalıştırırsan kalanı da alır."
            } else {
                "${state.sourceName} kaynağından ${state.added} istasyon."
            },
            tone = if (state.partial) BannerTone.Warning else BannerTone.Success,
            actionLabel = "Kapat",
            onAction = onDismiss
        )

        SyncState.Idle -> Unit
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sources.forEach { source ->
            TextButton(
                onClick = { onSync(source) },
                enabled = state !is SyncState.Running
            ) {
                Text("${source.displayName.substringBefore(" (")} ile güncelle")
            }
        }
    }
    Text(
        "Ülke çapında, tek seferlik bir indirme. Şarj istasyonları taşınmadığı için " +
            "bunu bir kez yapman yeter: veri telefonda kalır, uygulama güncellemeleri " +
            "silmez, yarım kalırsa kaldığı yerden devam eder. Wi-Fi'dayken yap, " +
            "birkaç dakika sürer.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
