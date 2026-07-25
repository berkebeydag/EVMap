package com.berke.ioniqscope.ui.screens.chargers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/** Where the user is, or why we do not know. */
sealed interface LocationState {
    data object Unknown : LocationState
    data object Requesting : LocationState
    data class Known(val lat: Double, val lon: Double, val fromCache: Boolean) : LocationState
    data object PermissionMissing : LocationState
    data object Disabled : LocationState
    data object TimedOut : LocationState
}

/**
 * A single coarse position, on demand.
 *
 * The first implementation only read [LocationManager.getLastKnownLocation], which
 * returns null whenever nothing has asked the system for a fix recently — so on a
 * phone that had not been navigating, the button did nothing at all and said
 * nothing about why. This asks for a real fix when the cache is empty or stale, and
 * every failure path ends in a state the UI can show.
 *
 * Deliberately one-shot rather than a subscription: the charger list needs an
 * anchor to sort by, not continuous tracking, and continuous tracking would cost
 * battery and be a privacy claim the app does not need to make.
 */
@SuppressLint("MissingPermission")
class LocationFinder(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    suspend fun find(): LocationState {
        if (!hasPermission()) return LocationState.PermissionMissing
        val manager = context.getSystemService(LocationManager::class.java)
            ?: return LocationState.Disabled

        val providers = manager.getProviders(true)
        if (providers.isEmpty()) return LocationState.Disabled

        cachedFix(manager, providers, MAX_CACHE_AGE_MS)?.let {
            return LocationState.Known(it.latitude, it.longitude, fromCache = false)
        }

        requestSingleFix(manager, providers)?.let {
            return LocationState.Known(it.latitude, it.longitude, fromCache = false)
        }

        // No fresh fix — in a garage or indoors that is the normal case. An old
        // position still sorts the list roughly right, which beats refusing to sort
        // it at all, so fall back to it and let the UI say it is stale.
        cachedFix(manager, providers, maxAgeMs = Long.MAX_VALUE)?.let {
            return LocationState.Known(it.latitude, it.longitude, fromCache = true)
        }

        return LocationState.TimedOut
    }

    /** Most recent cached fix no older than [maxAgeMs]. */
    private fun cachedFix(
        manager: LocationManager,
        providers: List<String>,
        maxAgeMs: Long
    ): Location? = runCatching {
        providers
            .mapNotNull { manager.getLastKnownLocation(it) }
            .filter { maxAgeMs == Long.MAX_VALUE || System.currentTimeMillis() - it.time < maxAgeMs }
            .maxByOrNull { it.time }
    }.getOrNull()

    private suspend fun requestSingleFix(
        manager: LocationManager,
        providers: List<String>
    ): Location? {
        // Network provider first when present: for sorting a list by distance a
        // coarse fix arrives far sooner than a GPS lock, and is accurate enough.
        val provider = when {
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            else -> providers.first()
        }

        return try {
            withTimeout(FIX_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            runCatching { manager.removeUpdates(this) }
                            if (cont.isActive) cont.resume(location)
                        }

                        // Present for API levels whose LocationListener still declares them.
                        override fun onProviderEnabled(provider: String) = Unit
                        override fun onProviderDisabled(provider: String) = Unit
                    }

                    runCatching {
                        manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                    }.onFailure {
                        if (cont.isActive) cont.resume(null)
                    }

                    cont.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
                }
            }
        } catch (e: TimeoutCancellationException) {
            null
        }
    }

    private companion object {
        /** Older than this and it may be a different city entirely. */
        const val MAX_CACHE_AGE_MS = 10 * 60 * 1000L
        const val FIX_TIMEOUT_MS = 15_000L
    }
}
