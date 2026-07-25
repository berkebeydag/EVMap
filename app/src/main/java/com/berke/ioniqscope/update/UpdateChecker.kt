package com.berke.ioniqscope.update

import android.content.Context
import com.berke.ioniqscope.BuildConfig
import com.berke.ioniqscope.charging.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.Base64

/** What the update source says the newest build is. */
data class AvailableUpdate(
    val versionCode: Int,
    val versionName: String,
    val notes: String?,
    val downloadUrl: String,
    val sizeBytes: Long
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AvailableUpdate) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data class ReadyToInstall(val path: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Checks a shared folder for a newer build.
 *
 * Android will not let a sideloaded app install an update silently — the system
 * installer always asks — so the most this can do is notice and offer. That is
 * still the difference between hunting for a file and tapping "install".
 *
 * The source is a OneDrive share link rather than anything bespoke: it is already
 * syncing, so a new build appears there without a separate publish step. The
 * anonymous shares API turns a share link into a folder listing without a login,
 * which is why no account or token is needed on the phone.
 */
class UpdateChecker(
    private val appContext: Context,
    private val shareLinkProvider: () -> String?
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun reset() { _state.value = UpdateState.Idle }

    suspend fun check(silent: Boolean = false) {
        val link = shareLinkProvider()?.takeIf { it.isNotBlank() } ?: run {
            if (!silent) _state.value = UpdateState.Failed("No update source set.")
            return
        }

        if (!silent) _state.value = UpdateState.Checking
        try {
            val children = listShare(link)
            val manifest = children[MANIFEST_NAME]
                ?: throw IllegalStateException("$MANIFEST_NAME not found in the shared folder")

            val json = JSONObject(Http.get(manifest.downloadUrl))
            val versionCode = json.optInt("versionCode", -1)
            if (versionCode <= BuildConfig.VERSION_CODE) {
                _state.value = if (silent) UpdateState.Idle else UpdateState.UpToDate
                return
            }

            val apkName = json.optString("apk", DEFAULT_APK_NAME)
            val apk = children[apkName]
                ?: throw IllegalStateException("$apkName not found in the shared folder")

            _state.value = UpdateState.Available(
                AvailableUpdate(
                    versionCode = versionCode,
                    versionName = json.optString("versionName", "?"),
                    notes = json.optString("notes").ifBlank { null },
                    downloadUrl = apk.downloadUrl,
                    sizeBytes = apk.size
                )
            )
        } catch (e: Exception) {
            // A silent check that fails must stay silent: there is no point telling
            // someone their update check failed when they did not ask for one.
            _state.value = if (silent) UpdateState.Idle
            else UpdateState.Failed(e.message ?: "Update check failed.")
        }
    }

    suspend fun download(update: AvailableUpdate) {
        try {
            val file = ApkDownloader(appContext).download(update.downloadUrl) { percent ->
                _state.value = UpdateState.Downloading(percent)
            }
            _state.value = UpdateState.ReadyToInstall(file.absolutePath)
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: "Download failed.")
        }
    }

    private data class ShareItem(val downloadUrl: String, val size: Long)

    /**
     * Lists an anonymously shared folder.
     *
     * The link is encoded the way the shares API expects: base64url of the URL,
     * unpadded, prefixed with `u!`.
     */
    private suspend fun listShare(link: String): Map<String, ShareItem> {
        val encoded = "u!" + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(link.trim().toByteArray(Charsets.UTF_8))

        val body = Http.get("https://api.onedrive.com/v1.0/shares/$encoded/root/children")
        val items = JSONObject(body).optJSONArray("value") ?: return emptyMap()

        val out = mutableMapOf<String, ShareItem>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val name = item.optString("name").ifBlank { continue }
            val url = item.optString("@content.downloadUrl").ifBlank { continue }
            out[name] = ShareItem(url, item.optLong("size"))
        }
        return out
    }

    private companion object {
        const val MANIFEST_NAME = "latest.json"
        const val DEFAULT_APK_NAME = "IoniqScope.apk"
    }
}
