package com.berke.ioniqscope.connection

import com.berke.ioniqscope.data.AuxVoltageDao
import com.berke.ioniqscope.data.AuxVoltageEntity
import com.berke.ioniqscope.obd.StandardPids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps a long-lived record of the 12V auxiliary battery.
 *
 * The Ioniq 5/6 has a well-documented 12V/ICCU failure mode, and the 12V module
 * voltage is one of the few things on an EV that a *standard* OBD PID reports
 * honestly. Watching it drift over weeks is the cheapest early warning available.
 *
 * Deliberately does not keep the adapter awake to poll a parked car: a BLE dongle
 * left connected is itself a parasitic load, so on a car with a known drain problem
 * that would make the app part of the disease. It samples only while you are
 * already connected and driving.
 */
class AuxBatteryMonitor(
    private val manager: ObdConnectionManager,
    private val dao: AuxVoltageDao,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis
) {

    private var sessionStartPending = false
    private var lastRecordedAt = 0L

    fun start() {
        scope.launch {
            manager.connectionState.collect { state ->
                // Arm on connect; the next voltage reading becomes the session-start
                // sample, which is the closest thing to a rested reading we can get.
                if (state is ConnectionState.Connected) sessionStartPending = true
            }
        }

        scope.launch {
            manager.samples.collect { snapshot ->
                val reading = snapshot[StandardPids.moduleVolt.key] ?: return@collect
                record(reading.value)
            }
        }
    }

    private suspend fun record(volts: Double) {
        // A disconnected or confused adapter reports 0 V; storing that would poison
        // the trend with a reading the battery never had.
        if (volts < PLAUSIBLE_MIN_V || volts > PLAUSIBLE_MAX_V) return

        val timestamp = now()
        val isSessionStart = sessionStartPending

        if (!isSessionStart && timestamp - lastRecordedAt < SAMPLE_INTERVAL_MS) return

        sessionStartPending = false
        lastRecordedAt = timestamp
        runCatching {
            dao.insert(
                AuxVoltageEntity(
                    atEpochMs = timestamp,
                    volts = volts,
                    atSessionStart = isSessionStart
                )
            )
        }
    }

    private companion object {
        /** One reading per five minutes while driving is plenty for a weeks-long trend. */
        const val SAMPLE_INTERVAL_MS = 5 * 60 * 1000L
        const val PLAUSIBLE_MIN_V = 6.0
        const val PLAUSIBLE_MAX_V = 18.0
    }
}
