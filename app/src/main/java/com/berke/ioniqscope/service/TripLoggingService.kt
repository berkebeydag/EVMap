package com.berke.ioniqscope.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.berke.ioniqscope.MainActivity
import com.berke.ioniqscope.R
import com.berke.ioniqscope.ServiceLocator
import com.berke.ioniqscope.data.TripEntity
import com.berke.ioniqscope.data.TripSampleEntity
import com.berke.ioniqscope.obd.VehicleState
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Records every poll snapshot to Room for the duration of a trip.
 *
 * A foreground service rather than a ViewModel coroutine so logging survives the
 * screen going off and the app being backgrounded — which is the normal case for
 * a phone sitting in a cradle on a drive.
 *
 * Samples are batched: writing every reading individually would mean a database
 * transaction several times a second for the whole drive.
 */
class TripLoggingService : LifecycleService() {

    private val services by lazy { ServiceLocator.get(this) }
    private val tripDao by lazy { services.database.tripDao() }

    private val queue = Channel<Pair<Long, VehicleState>>(Channel.UNLIMITED)
    private var collectJob: Job? = null
    private var writerJob: Job? = null
    private var tripId: Long = 0
    private var written = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startLogging()
            ACTION_STOP -> stopLogging()
            else -> stopLogging()
        }
        return START_NOT_STICKY
    }

    private fun startLogging() {
        if (collectJob != null) return

        goForeground(0)

        lifecycleScope.launch {
            tripId = tripDao.insertTrip(TripEntity(startedAtEpochMs = System.currentTimeMillis()))
            _activeTripId.value = tripId

            collectJob = lifecycleScope.launch {
                services.connectionManager.samples.collect { snapshot ->
                    if (snapshot.isNotEmpty()) {
                        queue.trySend(System.currentTimeMillis() to snapshot)
                    }
                }
            }

            writerJob = lifecycleScope.launch { drainQueue() }
        }
    }

    /** Batches queued snapshots into Room, flushing on size or idle timeout. */
    private suspend fun drainQueue() {
        val batch = mutableListOf<TripSampleEntity>()

        suspend fun flush() {
            if (batch.isEmpty()) return
            val toWrite = batch.toList()
            batch.clear()
            runCatching { tripDao.insertSamples(toWrite) }
            written += toWrite.size
            updateNotification(written)
        }

        while (lifecycleScope.isActive) {
            val item = withTimeoutOrNull(FLUSH_INTERVAL_MS) { queue.receive() }
            if (item == null) {
                flush()
                continue
            }
            val (at, snapshot) = item
            snapshot.forEach { (key, reading) ->
                batch += TripSampleEntity(
                    tripId = tripId,
                    atEpochMs = at,
                    pidKey = key,
                    label = reading.label,
                    value = reading.value,
                    unit = reading.unit
                )
            }
            if (batch.size >= BATCH_SIZE) flush()
        }
        flush()
    }

    private fun stopLogging() {
        collectJob?.cancel()
        collectJob = null
        val id = tripId
        val writer = writerJob
        writerJob = null

        lifecycleScope.launch {
            writer?.cancel()
            // Anything still queued belongs to this trip; write it before closing out.
            val leftovers = mutableListOf<TripSampleEntity>()
            while (true) {
                val item = queue.tryReceive().getOrNull() ?: break
                val (at, snapshot) = item
                snapshot.forEach { (key, reading) ->
                    leftovers += TripSampleEntity(
                        tripId = id, atEpochMs = at, pidKey = key,
                        label = reading.label, value = reading.value, unit = reading.unit
                    )
                }
            }
            if (leftovers.isNotEmpty()) runCatching { tripDao.insertSamples(leftovers) }

            if (id != 0L) {
                val count = runCatching { tripDao.sampleCount(id) }.getOrDefault(0)
                runCatching { tripDao.finishTrip(id, System.currentTimeMillis(), count) }
            }
            _activeTripId.value = null
            ServiceCompat.stopForeground(this@TripLoggingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        collectJob?.cancel()
        writerJob?.cancel()
        _activeTripId.value = null
        super.onDestroy()
    }

    // --------------------------------------------------------------- notification

    private fun goForeground(sampleCount: Int) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(sampleCount), type)
    }

    /** Best-effort: if POST_NOTIFICATIONS was denied the count simply stops updating. */
    @SuppressLint("MissingPermission")
    private fun updateNotification(sampleCount: Int) {
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, buildNotification(sampleCount))
        }
    }

    private fun buildNotification(sampleCount: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TripLoggingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.trip_notification_title))
            .setContentText(
                if (sampleCount == 0) "Waiting for data…" else "$sampleCount readings recorded"
            )
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.trip_notification_stop), stop)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "trip_logging"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.berke.ioniqscope.START_TRIP"
        private const val ACTION_STOP = "com.berke.ioniqscope.STOP_TRIP"
        private const val BATCH_SIZE = 120
        private const val FLUSH_INTERVAL_MS = 2_000L

        private val _activeTripId = MutableStateFlow<Long?>(null)

        /** Non-null while a trip is being recorded. Observed by the Trip Log screen. */
        val activeTripId: StateFlow<Long?> = _activeTripId.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, TripLoggingService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TripLoggingService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
