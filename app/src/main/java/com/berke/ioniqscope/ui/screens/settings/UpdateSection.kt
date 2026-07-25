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
        "Installed: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
        style = MaterialTheme.typography.bodyMedium
    )

    when (state) {
        is UpdateState.Available -> Banner(
            title = "Update available — ${state.update.versionName}",
            text = buildString {
                append("Build ${state.update.versionCode}")
                if (state.update.sizeBytes > 0) {
                    append(String.format(Locale.US, ", %.1f MB", state.update.sizeBytes / 1048576.0))
                }
                state.update.notes?.let { append("\n$it") }
            },
            tone = BannerTone.Success,
            actionLabel = "Download",
            onAction = onDownload
        )

        is UpdateState.Downloading -> Column(Modifier.fillMaxWidth()) {
            Text("Downloading… ${state.percent}%", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(
                progress = { state.percent / 100f },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }

        is UpdateState.ReadyToInstall -> Banner(
            title = "Ready to install",
            text = "Android will ask you to confirm — a sideloaded app cannot install " +
                "itself silently, and this one does not pretend to.",
            tone = BannerTone.Success,
            actionLabel = "Install",
            onAction = { onInstall(File(state.path)) }
        )

        is UpdateState.Failed -> Banner(
            title = "Update check failed",
            text = state.message,
            tone = BannerTone.Error,
            actionLabel = "Dismiss",
            onAction = onDismiss
        )

        UpdateState.UpToDate -> Banner(
            text = "This is the newest build.",
            tone = BannerTone.Info,
            actionLabel = "Dismiss",
            onAction = onDismiss
        )

        UpdateState.Checking -> Text(
            "Checking…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        UpdateState.Idle -> Unit
    }

    OutlinedTextField(
        value = link,
        onValueChange = { link = it },
        label = { Text("latest.json address") },
        placeholder = { Text("https://…/latest.json") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { onShareLinkChange(link) }) { Text("Save link") }
        Button(
            onClick = onCheck,
            enabled = link.isNotBlank() && state !is UpdateState.Downloading
        ) { Text("Check now") }
    }

    SettingsSwitchRow(
        checked = autoCheck,
        title = "Check on launch",
        subtitle = "Looks for a newer build when the app opens.",
        onChange = onAutoCheckChange
    )

    Text(
        "Already pointed at the project's own build feed, so this normally needs no " +
            "attention. Any address serving a latest.json over plain HTTP works — a " +
            "GitHub raw file, a release asset, any web host. A OneDrive or Drive share " +
            "link will not: those need a browser session, not a direct fetch. Clearing " +
            "the box turns update checks off. Nothing is ever uploaded; the app only " +
            "reads that address.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (!com.berke.ioniqscope.update.ApkDownloader(context).canInstallPackages()) {
        Text(
            "Android has not been given permission to install apps from IoniqScope. " +
                "It will ask the first time you install an update.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary
        )
    }
}
