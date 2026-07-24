package com.berke.ioniqscope.connection

import android.content.Context
import com.berke.ioniqscope.data.PidCatalog
import com.berke.ioniqscope.data.SettingsRepository
import com.berke.ioniqscope.service.TripLoggingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Starts and stops trip logging from vehicle speed instead of a button press.
 *
 * Having to remember to tap "start logging" before pulling away is the surest way
 * to end up with no data from the drive you actually wanted, so when the user opts
 * in, moving is the trigger.
 *
 * Stopping is deliberately lazy: a stop is only called after the car has been
 * stationary for a few minutes, because traffic lights and junctions are not the
 * end of a journey.
 */
class DriveDetector(
    private val appContext: Context,
    private val manager: ObdConnectionManager,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis
) {

    private var movingSinceMs: Long? = null
    private var stationarySinceMs: Long? = null
    private var startedByUs = false

    fun start() {
        scope.launch {
            manager.samples.collect { snapshot ->
                if (!settings.settings.first().autoLogTrips) {
                    reset()
                    return@collect
                }
                val speed = snapshot[PidCatalog.speed.key]?.value ?: return@collect
                evaluate(speed)
            }
        }

        // A dropped link should not leave a trip recording forever.
        scope.launch {
            manager.connectionState.collect { state ->
                if (state !is ConnectionState.Connected && startedByUs) {
                    stop()
                }
            }
        }
    }

    private fun evaluate(speedKmh: Double) {
        val timestamp = now()
        val logging = TripLoggingService.activeTripId.value != null

        if (speedKmh >= MOVING_KMH) {
            stationarySinceMs = null
            val since = movingSinceMs ?: timestamp.also { movingSinceMs = it }
            if (!logging && timestamp - since >= MOVING_CONFIRM_MS) {
                TripLoggingService.start(appContext)
                startedByUs = true
            }
        } else {
            movingSinceMs = null
            val since = stationarySinceMs ?: timestamp.also { stationarySinceMs = it }
            if (logging && startedByUs && timestamp - since >= STATIONARY_STOP_MS) {
                stop()
            }
        }
    }

    private fun stop() {
        TripLoggingService.stop(appContext)
        startedByUs = false
        reset()
    }

    private fun reset() {
        movingSinceMs = null
        stationarySinceMs = null
    }

    private companion object {
        /** Above walking pace — a rolling car, not sensor noise at a standstill. */
        const val MOVING_KMH = 5.0
        /** Sustained movement before committing, so a nudge in a car park does nothing. */
        const val MOVING_CONFIRM_MS = 10_000L
        /** Long enough that traffic lights and junctions do not end the trip. */
        const val STATIONARY_STOP_MS = 3 * 60 * 1000L
    }
}
