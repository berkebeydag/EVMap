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
    /**
     * [accuracyMetres] is the radius the system says it is confident to, or null when
     * it did not say. Carried through because "where you are" and "how well we know
     * it" are different facts, and a map that draws a 2 km cell-tower estimate exactly
     * like a 5 m GPS lock is claiming the first is the second.
     */
    data class Known(
        val lat: Double,
        val lon: Double,
        val fromCache: Boolean,
        val accuracyMetres: Float? = null
    ) : LocationState
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
        // Both, not one. Choosing up front meant that with GPS picked and the phone
        // indoors nothing ever arrived and the marker stayed on the seed fix, while
        // with the network picked it never got better than a cell tower. Listening to
        // both lets the coarse one hold the marker until the accurate one takes over.
        val wanted = providers.filter {
            it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER
        }.ifEmpty { providers.take(1) }
        if (wanted.isEmpty()) {
            close()
            return@callbackFlow
        }

        // Seed from the cache so the map moves immediately rather than sitting still
        // until the first fix arrives, which outdoors can take several seconds.
        var shown: Location? = cachedFix(manager, providers, MAX_CACHE_AGE_MS)
            ?.also { trySend(it.known(fromCache = true)) }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                // A worse fix only replaces a better one once the better one is old
                // enough to be somewhere else. Without the age test the marker jumped
                // between the GPS position and the cell tower every few seconds; with
                // no test at all, standing still, the coarse provider would drag it
                // off the road it was on.
                val current = shown
                val staleMs = if (current == null) Long.MAX_VALUE
                else location.time - current.time
                if (current != null &&
                    location.radiusMetres() > current.radiusMetres() &&
                    staleMs < STALE_FIX_MS
                ) return

                shown = location
                trySend(location.known(fromCache = false))
            }

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }

        val asked = wanted.count { provider ->
            runCatching {
                manager.requestLocationUpdates(
                    provider, FOLLOW_INTERVAL_MS, FOLLOW_DISTANCE_M, listener,
                    Looper.getMainLooper()
                )
            }.isSuccess
        }
        if (asked == 0) close()

        awaitClose { runCatching { manager.removeUpdates(listener) } }
    }

    suspend fun find(): LocationState {
        if (!hasPermission()) return LocationState.PermissionMissing
        val manager = context.getSystemService(LocationManager::class.java)
            ?: return LocationState.Disabled

        val providers = manager.getProviders(true)
        if (providers.isEmpty()) return LocationState.Disabled

        // The cache is only taken as the answer when it is both fresh and tight. It
        // used to be taken whenever it was fresh, which handed back the cell-tower
        // estimate every time and never asked the hardware anything — so the marker
        // sat a district away and no amount of waiting moved it.
        cachedFix(manager, providers, MAX_CACHE_AGE_MS)
            ?.takeIf { it.radiusMetres() <= GOOD_ENOUGH_ACCURACY_M }
            ?.let { return it.known(fromCache = false) }

        requestSingleFix(manager, providers)?.let { return it.known(fromCache = false) }

        // No fresh fix — in a garage or indoors that is the normal case. An old
        // position still sorts the list roughly right, which beats refusing to sort
        // it at all, so fall back to it and let the UI say it is stale.
        cachedFix(manager, providers, maxAgeMs = Long.MAX_VALUE)?.let {
            return it.known(fromCache = true)
        }

        return LocationState.TimedOut
    }

    /**
     * The best cached fix no older than [maxAgeMs] — best meaning tightest, not newest.
     *
     * Taking the newest was what put the marker a district away. Every phone holds a
     * cached fix per provider, and the network one is refreshed constantly by other
     * apps while the GPS one is only as recent as the last time something actually
     * held a lock. So the newest was almost always the cell-tower estimate, good to
     * somewhere between a few hundred metres and a couple of kilometres, and a GPS fix
     * from two minutes ago accurate to five metres lost to it every time.
     *
     * Age still matters, but as a filter rather than as the ranking: anything inside
     * the window is recent enough to be about where you are, and among those the
     * tightest is the closest to the truth. A fix with no stated accuracy is ranked
     * last rather than dropped — it is still better than nothing.
     */
    private fun cachedFix(
        manager: LocationManager,
        providers: List<String>,
        maxAgeMs: Long
    ): Location? = runCatching {
        providers
            .mapNotNull { manager.getLastKnownLocation(it) }
            .filter { maxAgeMs == Long.MAX_VALUE || System.currentTimeMillis() - it.time < maxAgeMs }
            .minByOrNull { it.radiusMetres() }
    }.getOrNull()

    /** Stated accuracy, or a value that sorts behind every fix that stated one. */
    private fun Location.radiusMetres(): Float =
        if (hasAccuracy() && accuracy > 0f) accuracy else UNSTATED_ACCURACY_M

    private fun Location.known(fromCache: Boolean) = LocationState.Known(
        lat = latitude,
        lon = longitude,
        fromCache = fromCache,
        accuracyMetres = if (hasAccuracy() && accuracy > 0f) accuracy else null
    )

    /**
     * One fix, asked of every provider at once and answered with the tightest.
     *
     * This used to pick a single provider up front and prefer the network one,
     * reasoning that a coarse fix arrives far sooner than a GPS lock and is accurate
     * enough to sort a list by distance. It is — and it is not good enough to draw a
     * dot on a map with, which is the other thing the answer is used for. A cell-tower
     * estimate is routinely half a kilometre out and can be several, which is exactly
     * the "I am a district away" the map was showing.
     *
     * Listening to both costs nothing: the fixes arrive in the order they arrive, and
     * the first one still ends the wait if it is good enough to stop caring. Only when
     * the coarse fix is all there is does the coarse fix get returned, and then it is
     * returned with its accuracy attached so the UI can say so.
     */
    private suspend fun requestSingleFix(
        manager: LocationManager,
        providers: List<String>
    ): Location? {
        val wanted = providers.filter {
            it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER
        }.ifEmpty { listOf(providers.first()) }

        // Held out here so a timeout can still answer with whatever did arrive. A
        // coarse fix in hand beats telling the user we found nothing at all.
        var best: Location? = null

        return try {
            withTimeout(FIX_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (!cont.isActive) return
                            if (location.radiusMetres() <
                                (best?.radiusMetres() ?: Float.MAX_VALUE)
                            ) best = location

                            // Tight enough that waiting for GPS to do better would only
                            // make the user wait. Anything coarser is kept, and the
                            // other provider gets the rest of the timeout to beat it.
                            if (location.radiusMetres() <= GOOD_ENOUGH_ACCURACY_M) {
                                runCatching { manager.removeUpdates(this) }
                                cont.resume(location)
                            }
                        }

                        // Present for API levels whose LocationListener still declares them.
                        override fun onProviderEnabled(provider: String) = Unit
                        override fun onProviderDisabled(provider: String) = Unit
                    }

                    val asked = wanted.count { provider ->
                        runCatching {
                            manager.requestLocationUpdates(
                                provider, 0L, 0f, listener, Looper.getMainLooper()
                            )
                        }.isSuccess
                    }
                    if (asked == 0 && cont.isActive) cont.resume(null)

                    // One listener registered against several providers; one removal
                    // detaches it from all of them.
                    cont.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
                }
            }
        } catch (e: TimeoutCancellationException) {
            best
        }
    }

    private companion object {
        /** Older than this and it may be a different city entirely. */
        const val MAX_CACHE_AGE_MS = 10 * 60 * 1000L
        const val FIX_TIMEOUT_MS = 15_000L

        /**
         * Close enough to stop waiting for something better.
         *
         * A GPS lock outdoors settles around 5-15 m and a good wifi fix around 20-40 m;
         * either puts the marker on the right street. A cell-tower fix is hundreds of
         * metres to a couple of kilometres, which is a district, and is what this
         * threshold exists to keep waiting past.
         */
        const val GOOD_ENOUGH_ACCURACY_M = 50f

        /**
         * Assumed radius for a fix that states none, chosen to lose to any fix that
         * does. Providers that report nothing are the oldest and vaguest sources.
         */
        const val UNSTATED_ACCURACY_M = 10_000f

        /** Past this, even a coarse fix is better than a precise one this old. */
        const val STALE_FIX_MS = 30_000L

        /** Roughly one fix per second at speed, without asking for every last one. */
        const val FOLLOW_INTERVAL_MS = 1_000L
        const val FOLLOW_DISTANCE_M = 5f
    }
}
