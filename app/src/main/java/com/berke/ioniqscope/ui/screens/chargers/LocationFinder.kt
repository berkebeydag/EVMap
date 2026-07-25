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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
 * Where the user is: one fix on demand, or a stream while the map is following them.
 *
 * The first implementation only read [LocationManager.getLastKnownLocation], which
 * returns null whenever nothing has asked the system for a fix recently — so on a
 * phone that had not been navigating, the button did nothing at all and said
 * nothing about why. This asks for a real fix when the cache is empty or stale, and
 * every failure path ends in a state the UI can show.
 *
 * [stream] exists because a map that follows you needs a position every few seconds,
 * not once. It runs only while something is collecting it, so nothing is tracked
 * while the map is not on screen or following is switched off.
 */
@SuppressLint("MissingPermission")
class LocationFinder(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Whether GPS-grade positioning was granted.
     *
     * Coarse location is a cell-tower or wifi estimate, good to somewhere between a
     * few hundred metres and a couple of kilometres. That is fine for sorting a list
     * by distance and useless for showing where you are on a road, so following
     * asks for fine and says so.
     */
    fun hasPrecisePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Positions for as long as this is collected, then nothing.
     *
     * Prefers GPS: the network provider updates too rarely and too coarsely for a
     * map to follow, and would make the marker jump between cell towers rather than
     * move along the road.
     */
    fun stream(): Flow<LocationState.Known> = callbackFlow {
        if (!hasPermission()) {
            close()
            return@callbackFlow
        }
        val manager = context.getSystemService(LocationManager::class.java) ?: run {
            close()
            return@callbackFlow
        }

        val providers = manager.getProviders(true)
        val provider = when {
            hasPrecisePermission() && LocationManager.GPS_PROVIDER in providers ->
                LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            providers.isNotEmpty() -> providers.first()
            else -> null
        } ?: run {
            close()
            return@callbackFlow
        }

        // Seed from the cache so the map moves immediately rather than sitting still
        // until the first fix arrives, which outdoors can take several seconds.
        cachedFix(manager, providers, MAX_CACHE_AGE_MS)?.let {
            trySend(LocationState.Known(it.latitude, it.longitude, fromCache = true))
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(LocationState.Known(location.latitude, location.longitude, false))
            }

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }

        runCatching {
            manager.requestLocationUpdates(
                provider, FOLLOW_INTERVAL_MS, FOLLOW_DISTANCE_M, listener,
                Looper.getMainLooper()
            )
        }.onFailure { close(it) }

        awaitClose { runCatching { manager.removeUpdates(listener) } }
    }

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

        /** Roughly one fix per second at speed, without asking for every last one. */
        const val FOLLOW_INTERVAL_MS = 1_000L
        const val FOLLOW_DISTANCE_M = 5f
    }
}
