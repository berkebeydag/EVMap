package com.berke.ioniqscope.update

import android.content.Context
import com.berke.ioniqscope.BuildConfig
import com.berke.ioniqscope.charging.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.URI

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
 * Checks a plain URL for a newer build.
 *
 * Android will not let a sideloaded app install an update silently — the system
 * installer always asks — so the most this can do is notice and offer. That is
 * still the difference between hunting for a file and tapping "install".
 *
 * The source is deliberately just an address returning JSON, not any provider's
 * API. The first attempt used OneDrive's anonymous shares API and that turned out
 * to be closed: accounts migrated to SharePoint answer 401 to the API and 403 to
 * the share link itself. A static file works anywhere — GitHub raw, a release
 * asset, any web host — and cannot be withdrawn by one vendor's policy change.
 *
 * Expected shape, with `url` absolute or relative to the manifest:
 * ```
 * {"versionCode": 15, "versionName": "0.1.15", "url": "IoniqScope.apk",
 *  "sizeBytes": 21207175, "notes": "…"}
 * ```
 */
class UpdateChecker(
    private val appContext: Context,
    private val manifestUrlProvider: () -> String?
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun reset() { _state.value = UpdateState.Idle }

    suspend fun check(silent: Boolean = false) {
        val manifestUrl = manifestUrlProvider()?.takeIf { it.isNotBlank() } ?: run {
            if (!silent) _state.value = UpdateState.Failed("No update source set.")
            return
        }

        if (!silent) _state.value = UpdateState.Checking
        try {
            val json = JSONObject(Http.get(manifestUrl))
            val versionCode = json.optInt("versionCode", -1)
            if (versionCode <= BuildConfig.VERSION_CODE) {
                _state.value = if (silent) UpdateState.Idle else UpdateState.UpToDate
                return
            }

            val raw = json.optString("url").ifBlank { "IoniqScope.apk" }
            _state.value = UpdateState.Available(
                AvailableUpdate(
                    versionCode = versionCode,
                    versionName = json.optString("versionName", "?"),
                    notes = json.optString("notes").ifBlank { null },
                    downloadUrl = resolve(manifestUrl, raw),
                    sizeBytes = json.optLong("sizeBytes")
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

    /** Lets the manifest name the APK relative to itself, so moving hosts is a one-line change. */
    private fun resolve(manifestUrl: String, target: String): String =
        runCatching { URI(manifestUrl).resolve(target).toString() }.getOrDefault(target)
}
