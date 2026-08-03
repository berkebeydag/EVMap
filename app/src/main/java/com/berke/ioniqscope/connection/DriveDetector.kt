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

    /**
     * Whether this car has ever reported a speed.
     *
     * Latched, not per-sample: a car that answers 010D can still miss one poll, and
     * treating that single gap as "no speed available" would start a trip in the
     * middle of a stop.
     */
    private var sawSpeed = false

    fun start() {
        scope.launch {
            manager.samples.collect { snapshot ->
                if (!settings.settings.first().autoLogTrips) {
                    reset()
                    return@collect
                }
                val speed = snapshot[PidCatalog.speed.key]?.value
                if (speed != null) {
                    sawSpeed = true
                    evaluate(speed)
                } else if (!sawSpeed && snapshot.isNotEmpty()) {
                    // The car answers something, just not speed. Measured on an Ioniq
                    // 6: 010D returns NO DATA, so this collector used to return here on
                    // every single sample and no trip was ever recorded on that car —
                    // the log was empty and looked broken rather than inapplicable.
                    //
                    // With nothing to time the start against, the connection is the
                    // start: the adapter is plugged into a car that is switched on, and
                    // it stops answering when the car is switched off. That is coarser
                    // than waiting for movement — a trip begun while parked on the
                    // driveway includes the parking — but it is the difference between
                    // a log and no log.
                    startIfIdle()
                }
            }
        }

        // A dropped link ends the trip, whichever way it was started. On a car with no
        // speed reading this is also the only thing that ends it.
        scope.launch {
            manager.connectionState.collect { state ->
                if (state !is ConnectionState.Connected) {
                    // Cleared with the link, so plugging into a different car does not
                    // inherit the last one's answer about whether it reports speed.
                    sawSpeed = false
                    if (startedByUs) stop()
                }
            }
        }
    }

    private fun startIfIdle() {
        if (TripLoggingService.activeTripId.value != null) return
        TripLoggingService.start(appContext)
        startedByUs = true
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
