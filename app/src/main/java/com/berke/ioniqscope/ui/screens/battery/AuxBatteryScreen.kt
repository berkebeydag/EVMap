package com.berke.ioniqscope.ui.screens.battery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.berke.ioniqscope.ServiceLocator
import com.berke.ioniqscope.data.AuxBatteryHealth
import com.berke.ioniqscope.data.AuxBatteryStatus
import com.berke.ioniqscope.data.AuxVoltageEntity
import com.berke.ioniqscope.ui.components.Banner
import com.berke.ioniqscope.ui.components.BannerTone
import com.berke.ioniqscope.ui.components.ChartPoint
import com.berke.ioniqscope.ui.components.EmptyState
import com.berke.ioniqscope.ui.components.LineChart
import com.berke.ioniqscope.ui.components.SectionLabel
import com.berke.ioniqscope.ui.serviceViewModel
import com.berke.ioniqscope.ui.theme.StatusAmber
import com.berke.ioniqscope.ui.theme.StatusGreen
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class AuxBatteryViewModel(services: ServiceLocator) : ViewModel() {

    private val dao = services.database.auxVoltageDao()

    val sessionStarts: StateFlow<List<AuxVoltageEntity>> = dao.observeSessionStarts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val health: StateFlow<AuxBatteryHealth> =
        combine(dao.observeSessionStarts(), dao.observeLatest()) { starts, latest ->
            AuxBatteryHealth.evaluate(starts, latest)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AuxBatteryHealth.empty)

    fun clearHistory() = viewModelScope.launch { dao.deleteAll() }
}

@Composable
fun AuxBatteryScreen(services: ServiceLocator) {
    val vm = serviceViewModel(services) { AuxBatteryViewModel(it) }
    val health by vm.health.collectAsStateWithLifecycle()
    val starts by vm.sessionStarts.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusCard(health)

        when (health.status) {
            AuxBatteryStatus.Critical -> Banner(
                title = "Dinlenmede 12,0 V altında",
                text = "Bu, 12V akünün aracı uyandırmakta zorlanmaya başladığı aralık. " +
                    "Ioniq'te aynı zamanda ICCU sorununun imzası. Marş vermemesini " +
                    "beklemek yerine kontrol ettirmeye değer.",
                tone = BannerTone.Error
            )
            AuxBatteryStatus.Low -> Banner(
                title = "Olması gerekenden düşük",
                text = "Dinlenmiş bir 12V akü normalde 12,6 V civarında durur. Sürekli " +
                    "12,2 V altında olması tam şarj olmadığına işaret eder.",
                tone = BannerTone.Warning
            )
            AuxBatteryStatus.Good -> if (health.isDeclining) {
                Banner(
                    title = "Seviye iyi ama düşüyor",
                    text = "Seviye hâlâ sağlıklı, ancak oturum başı ölçümleri düşüyor. " +
                        "Göz ucuyla takip etmeye değer.",
                    tone = BannerTone.Warning
                )
            }
            AuxBatteryStatus.Unknown -> Banner(
                text = "Henüz ölçüm yok. Sorgulanan PID'ler arasında “Modül voltajı (12V)” " +
                    "varken adaptöre bağlan, ölçüm kendiliğinden kaydedilir.",
                tone = BannerTone.Info
            )
        }

        HorizontalDivider()
        SectionLabel("Oturum başı eğilimi")
        Text(
            "Yalnızca oturum başlarken alınan ölçümler çiziliyor. Sürüş sırasında alınan " +
                "bir ölçüm akünün kendi durumunu değil DC-DC çeviricinin çıkışını gösterir; " +
                "ikisini karıştırmak tam da aradığımız eğilimi düzleştirirdi.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (starts.size < 2) {
            EmptyState("Eğilim çizilebilmesi için en az iki oturum gerekiyor.")
        } else {
            LineChart(
                points = starts.map { ChartPoint(it.atEpochMs.toDouble(), it.volts) },
                reference = AuxBatteryHealth.LOW_V,
                valueFormatter = { String.format(Locale.US, "%.2f V", it) }
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    dateFormatter.format(Instant.ofEpochMilli(starts.first().atEpochMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    dateFormatter.format(Instant.ofEpochMilli(starts.last().atEpochMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "Kesikli çizgi ${AuxBatteryHealth.LOW_V} V.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider()
        Text(
            "IoniqScope, park hâlindeki aracı izlemek için adaptörü uyanık tutmuyor. Takılı " +
                "ve bağlı bırakılan bir dongle'ın kendisi bir tüketim kaynağıdır; bilinen " +
                "bir 12V sorunu olan bir araçta bu, uygulamayı sorunun parçası yapardı.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (starts.isNotEmpty()) {
            TextButton(onClick = vm::clearHistory) { Text("12V geçmişini sil") }
        }
    }
}

@Composable
private fun StatusCard(health: AuxBatteryHealth) {
    val scheme = MaterialTheme.colorScheme
    val accent: Color = when (health.status) {
        AuxBatteryStatus.Good -> StatusGreen
        AuxBatteryStatus.Low -> StatusAmber
        AuxBatteryStatus.Critical -> scheme.error
        AuxBatteryStatus.Unknown -> scheme.outline
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer)
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when (health.status) {
                    AuxBatteryStatus.Good -> "SAĞLIKLI"
                    AuxBatteryStatus.Low -> "DÜŞÜK"
                    AuxBatteryStatus.Critical -> "KRİTİK"
                    AuxBatteryStatus.Unknown -> "VERİ YOK"
                },
                style = MaterialTheme.typography.labelLarge,
                color = accent
            )
            // Same reason as the dashboard hero: an em-dash at display size reads as
            // a stray rule, not as "no value".
            if (health.latestVolts == null) {
                Text(
                    "henüz ölçüm yok",
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.outline,
                    modifier = Modifier.padding(vertical = 18.dp)
                )
            } else {
                Text(
                    String.format(Locale.US, "%.2f", health.latestVolts),
                    style = MaterialTheme.typography.displayLarge,
                    fontFamily = FontFamily.Monospace,
                    color = accent
                )
                Text(
                    "volts",
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurfaceVariant
                )
            }

            health.latestAtEpochMs?.let {
                Text(
                    "son okuma ${dateTimeFormatter.format(Instant.ofEpochMilli(it))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            health.trendVoltsPerWeek?.let {
                Text(
                    String.format(Locale.US, "%d oturumda haftada %+.3f V", it, health.sessionStartCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (health.isDeclining) StatusAmber else scheme.onSurfaceVariant
                )
            }
        }
    }
}

private val dateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault())
private val dateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault())
