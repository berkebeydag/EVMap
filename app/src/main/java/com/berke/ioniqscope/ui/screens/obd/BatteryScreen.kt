package com.berke.ioniqscope.ui.screens.obd

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.berke.ioniqscope.ServiceLocator
import com.berke.ioniqscope.connection.ConnectionState
import com.berke.ioniqscope.obd.BatteryReading
import com.berke.ioniqscope.obd.VehicleProfile
import com.berke.ioniqscope.ui.components.Banner
import com.berke.ioniqscope.ui.components.BannerTone
import com.berke.ioniqscope.ui.components.SectionLabel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The battery readings a car's own ECU holds, and the bytes they were read from.
 *
 * Everything here is manufacturer-specific, which is why it is a screen of its own
 * rather than three more tiles on the gauges. Standard OBD-II says nothing about a
 * traction battery: the state of charge, the pack voltage and the health figure all
 * come from asking one particular computer for one particular identifier and counting
 * bytes into the answer.
 *
 * And counting wrong does not fail. It returns 43.5 where the truth is 87, or 12.4
 * where the truth is 380 — numbers indistinguishable from real ones. So this screen
 * shows the raw answer next to every reading and says, in as many words, to check them
 * against the car's own display before believing them. That is also why they are not
 * on the dashboard: the gauges are for things the app is sure of.
 */
@Composable
fun BatteryScreen(services: ServiceLocator, onConnect: () -> Unit) {
    val connection by services.connectionManager.connectionState.collectAsStateWithLifecycle()
    val settings by services.settings.settings.collectAsStateWithLifecycle(
        initialValue = com.berke.ioniqscope.data.AppSettings()
    )
    val scope = rememberCoroutineScope()

    var reading by remember { mutableStateOf<BatteryReading?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val profile = VehicleProfile.byId(settings.vehicleProfileId)
    val connected = connection is ConnectionState.Connected

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!connected) {
            Banner(
                title = "Bağlı değil",
                text = "Batarya verisini okumak için adaptöre bağlan.",
                tone = BannerTone.Warning,
                actionLabel = "Bağlan",
                onAction = onConnect
            )
        }

        SectionLabel("Araç")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VehicleProfile.all.forEach { option ->
                FilterChip(
                    selected = profile.id == option.id,
                    onClick = {
                        scope.launch { services.settings.setVehicleProfile(option.id) }
                        reading = null
                    },
                    label = { Text(option.name, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        if (profile.battery == null) {
            Banner(
                text = "Bu profil yalnızca standart OBD-II değerlerini okur — hız, 12V, " +
                    "dış sıcaklık. Batarya şarjı ve sağlığı standart değil, her " +
                    "platformun kendi sorgusu var. Araban listede yoksa Komut " +
                    "konsolundan deneyip çıkanı bana getirebilirsin.",
                tone = BannerTone.Info
            )
            return@Column
        }

        Banner(
            title = "Doğrulanmadı",
            text = "Aşağıdaki değerler topluluk kaynaklarındaki bayt konumlarına göre " +
                "çözülüyor ve senin arabanda henüz doğrulanmadı. Yanlış bir konum hata " +
                "vermez — inandırıcı ama yanlış bir sayı verir. Okuduktan sonra şarj " +
                "yüzdesini arabanın kendi göstergesiyle karşılaştır; tutuyorsa gerisi " +
                "de tutuyordur, tutmuyorsa ham baytları bana getir.",
            tone = BannerTone.Warning
        )

        Button(onClick = {
            scope.launch {
                busy = true; error = null
                services.connectionManager.readBattery(profile)
                    .onSuccess { reading = it }
                    .onFailure { error = it.message ?: "Okunamadı" }
                busy = false
            }
        }, enabled = connected && !busy) {
            if (busy) {
                CircularProgressIndicator(
                    Modifier.padding(end = 8.dp),
                    strokeWidth = 2.dp
                )
            }
            Text("Bataryayı oku")
        }

        error?.let { Banner(text = it, tone = BannerTone.Error) }

        reading?.let { result ->
            SectionLabel("Değerler")
            if (result.values.isEmpty()) {
                Banner(
                    text = "Araç sorguya cevap verdi ama beklenen kimlik dönmedi. Ham " +
                        "cevap aşağıda — profil bu araca uymuyor olabilir.",
                    tone = BannerTone.Warning
                )
            }
            val labels = profile.battery.reads.flatMap { it.values }.associateBy { it.key }
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    result.values.forEach { (key, value) ->
                        val spec = labels[key]
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                spec?.label ?: key,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                String.format(Locale.US, "%.1f %s", value, spec?.unit ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    // Power is not read; it is the product of two things that were, and
                    // it is the number a driver actually watches while charging.
                    val amps = result.values["hv_current"]
                    val volts = result.values["hv_voltage"]
                    if (amps != null && volts != null) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                "Güç (hesaplanan)",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                String.format(Locale.US, "%.1f kW", amps * volts / 1000.0),
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            SectionLabel("Ham cevap")
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Text(
                    result.raw,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}
