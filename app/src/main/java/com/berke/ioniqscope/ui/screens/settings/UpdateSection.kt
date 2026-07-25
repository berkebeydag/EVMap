package com.berke.ioniqscope.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.berke.ioniqscope.BuildConfig
import com.berke.ioniqscope.ui.components.Banner
import com.berke.ioniqscope.ui.components.BannerTone
import com.berke.ioniqscope.update.UpdateState
import java.io.File
import java.util.Locale

/**
 * Update controls.
 *
 * States the limit plainly rather than implying a silent auto-update: Android
 * always shows its own installer confirmation for a sideloaded package, so the
 * honest promise is "you get told, and it is one tap", not "it updates itself".
 */
@Composable
fun UpdateSection(
    state: UpdateState,
    shareLink: String,
    autoCheck: Boolean,
    onShareLinkChange: (String) -> Unit,
    onAutoCheckChange: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: (File) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var link by remember(shareLink) { mutableStateOf(shareLink) }

    Text(
        "Yüklü: ${BuildConfig.VERSION_NAME} (yapı ${BuildConfig.VERSION_CODE})",
        style = MaterialTheme.typography.bodyMedium
    )

    when (state) {
        is UpdateState.Available -> Banner(
            title = "Güncelleme var — ${state.update.versionName}",
            text = buildString {
                append("Yapı ${state.update.versionCode}")
                if (state.update.sizeBytes > 0) {
                    append(String.format(Locale.US, ", %.1f MB", state.update.sizeBytes / 1048576.0))
                }
                state.update.notes?.let { append("\n$it") }
            },
            tone = BannerTone.Success,
            actionLabel = "İndir",
            onAction = onDownload
        )

        is UpdateState.Downloading -> Column(Modifier.fillMaxWidth()) {
            Text("İndiriliyor… ${state.percent}%", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(
                progress = { state.percent / 100f },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }

        is UpdateState.ReadyToInstall -> Banner(
            title = "Kuruluma hazır",
            text = "Android onayını isteyecek — yandan yüklenen bir uygulama kendini " +
                "sessizce kuramaz, bu da kurabiliyormuş gibi yapmıyor.",
            tone = BannerTone.Success,
            actionLabel = "Kur",
            onAction = { onInstall(File(state.path)) }
        )

        is UpdateState.Failed -> Banner(
            title = "Güncelleme kontrolü başarısız",
            text = state.message,
            tone = BannerTone.Error,
            actionLabel = "Kapat",
            onAction = onDismiss
        )

        UpdateState.UpToDate -> Banner(
            text = "Bu en güncel sürüm.",
            tone = BannerTone.Info,
            actionLabel = "Kapat",
            onAction = onDismiss
        )

        UpdateState.Checking -> Text(
            "Kontrol ediliyor…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        UpdateState.Idle -> Unit
    }

    OutlinedTextField(
        value = link,
        onValueChange = { link = it },
        label = { Text("latest.json adresi") },
        placeholder = { Text("https://…/latest.json") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { onShareLinkChange(link) }) { Text("Adresi kaydet") }
        Button(
            onClick = onCheck,
            enabled = link.isNotBlank() && state !is UpdateState.Downloading
        ) { Text("Şimdi kontrol et") }
    }

    SettingsSwitchRow(
        checked = autoCheck,
        title = "Açılışta kontrol et",
        subtitle = "Uygulama açıldığında yeni sürüm var mı diye bakar.",
        onChange = onAutoCheckChange
    )

    Text(
        "Zaten projenin kendi yayın akışına bakıyor, yani normalde dokunman gerekmez. " +
            "latest.json'u düz HTTP üzerinden veren her adres çalışır — GitHub raw " +
            "dosyası, bir release dosyası, herhangi bir web sunucusu. OneDrive ya da " +
            "Drive paylaşım bağlantısı çalışmaz: onlar doğrudan indirme değil tarayıcı " +
            "oturumu ister. Kutuyu boşaltmak güncelleme kontrolünü kapatır. Hiçbir şey " +
            "yüklenmiyor; uygulama yalnızca o adresi okuyor.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (!com.berke.ioniqscope.update.ApkDownloader(context).canInstallPackages()) {
        Text(
            "Android'e IoniqScope'tan uygulama kurma izni verilmemiş. İlk güncellemeyi " +
                "kurarken kendisi soracak.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary
        )
    }
}
