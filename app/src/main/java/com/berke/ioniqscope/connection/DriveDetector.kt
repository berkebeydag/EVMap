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
                // Either speed will do for "is the car moving". The receiver's is
                // arguably the better one for it: it measures the whole car over the
                // ground rather than a wheel, and it exists on cars that publish no
                // speed at all.
                val speed = snapshot[PidCatalog.speed.key]?.value
                    ?: snapshot[GPS_SPEED_KEY]?.value
                // Recording starts the moment the adapter is connected, without
                // waiting to see the car move. The alternative was to wait for
                // movement, and then the first minute of every drive — reversing off
                // a driveway, the queue at the gate — was missing from the log of it.
                startIfIdle()

                if (speed != null) {
                    sawSpeed = true
                    evaluate(speed)
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

    /**
     * Stops when the car stops and starts again when it moves, all without unplugging.
     *
     * Standing at a charger for forty minutes with the adapter in is not part of the
     * drive, and one trip that spans the whole afternoon because the link never dropped
     * says nothing about either half of it. So stillness ends a trip and movement
     * begins the next — the connection is only the outermost fallback.
     */
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
