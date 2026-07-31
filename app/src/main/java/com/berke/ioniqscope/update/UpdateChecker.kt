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
            val json = JSONObject(Http.get(freshest(manifestUrl)))
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

    /**
     * The same manifest, at an address that cannot be served stale.
     *
     * GitHub's raw host puts `Cache-Control: max-age=300` on a branch URL, so for five
     * minutes after a release the check reads the *previous* manifest and reports,
     * correctly for what it was given, that there is nothing new. Which is exactly
     * when someone who has just been told a build is out goes looking for it. Neither
     * a cache-busting query string nor a `no-cache` request header shifts it —
     * measured, both come back `X-Cache: HIT` on the old copy — because the branch
     * path is the whole cache key.
     *
     * A commit URL is a different key, and a new release is always a new commit, so
     * asking the API which commit the branch is on and reading the manifest from
     * *that* is never a hit on a previous release. The API answers with a 60-second
     * cache of its own, which is the residual staleness and a twentieth of what it
     * replaces.
     *
     * Entirely an optimisation: any failure — rate limit, no network, a URL that is
     * not GitHub, a branch that is already a commit — falls back to the address as
     * given, which still works and is merely slower to notice. That keeps the
     * arrangement what it was meant to be, a plain file at a plain address, with no
     * vendor API in the path that anything depends on.
     */
    private suspend fun freshest(manifestUrl: String): String {
        val match = RAW_BRANCH.matchEntire(manifestUrl) ?: return manifestUrl
        val (owner, repo, ref, path) = match.destructured
        if (COMMIT_SHA.matches(ref)) return manifestUrl
        return runCatching {
            val sha = JSONObject(
                Http.get("https://api.github.com/repos/$owner/$repo/commits/$ref")
            ).optString("sha")
            if (COMMIT_SHA.matches(sha)) {
                "https://raw.githubusercontent.com/$owner/$repo/$sha/$path"
            } else manifestUrl
        }.getOrDefault(manifestUrl)
    }

    private companion object {
        val RAW_BRANCH =
            Regex("""https://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.+)""")
        val COMMIT_SHA = Regex("""[0-9a-f]{40}""")
    }
}
