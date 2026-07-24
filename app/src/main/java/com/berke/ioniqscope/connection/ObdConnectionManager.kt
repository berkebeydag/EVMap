package com.berke.ioniqscope.connection

import android.content.Context
import android.os.SystemClock
import com.berke.ioniqscope.data.AdapterType
import com.berke.ioniqscope.obd.BleScanner
import com.berke.ioniqscope.obd.BleTransport
import com.berke.ioniqscope.obd.ClassicBtTransport
import com.berke.ioniqscope.obd.DtcReader
import com.berke.ioniqscope.obd.Elm327
import com.berke.ioniqscope.obd.ObdEngine
import com.berke.ioniqscope.obd.Pid
import com.berke.ioniqscope.obd.Transport
import com.berke.ioniqscope.obd.VehicleState
import com.berke.ioniqscope.performance.PerfState
import com.berke.ioniqscope.performance.PerformanceMeter
import com.berke.ioniqscope.performance.SpeedSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val step: String, val attempt: Int = 1) : ConnectionState
    data class Connected(
        val deviceName: String,
        val address: String,
        /** e.g. "service FFE0 · notify FFE1 · write FFE1" */
        val linkDetail: String
    ) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

/** What the poll loop is currently doing. Restored after an exclusive command. */
private sealed interface PollMode {
    data object Idle : PollMode
    data class Dashboard(val pids: List<Pid>, val intervalMs: Long) : PollMode
    data class Performance(val pid: Pid) : PollMode
}

/**
 * Single owner of the adapter: [Transport] + [Elm327] + [ObdEngine] + [PerformanceMeter].
 *
 * Everything else in the app observes its StateFlows; nothing else is allowed to
 * touch the transport, because an ELM327 link is strictly one command at a time.
 * One-off commands (DTC read/clear) go through [exclusive], which parks the poll
 * loop first and restores it afterwards.
 */
class ObdConnectionManager(
    private val appContext: Context,
    private val scope: CoroutineScope
) {

    private val scanner = BleScanner(appContext)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _vehicleState = MutableStateFlow<VehicleState>(emptyMap())
    val vehicleState: StateFlow<VehicleState> = _vehicleState.asStateFlow()

    /** Adapter diagnostics — including the discovered GATT profile. Shown on Connect. */
    private val _adapterLog = MutableStateFlow<List<String>>(emptyList())
    val adapterLog: StateFlow<List<String>> = _adapterLog.asStateFlow()

    /** Every poll snapshot, for the trip logger. Dropped if nobody is listening. */
    private val _samples = MutableSharedFlow<VehicleState>(extraBufferCapacity = 64)
    val samples: SharedFlow<VehicleState> = _samples.asSharedFlow()

    val performanceMeter = PerformanceMeter()
    val perfState: StateFlow<PerfState> get() = performanceMeter.state

    private var transport: Transport? = null
    private var elm: Elm327? = null
    private var engine: ObdEngine? = null
    private var dtcReader: DtcReader? = null

    private var pollMode: PollMode = PollMode.Idle
    private val commandMutex = Mutex()

    private var connectJob: Job? = null
    private var lastRequest: ConnectRequest? = null
    private var userInitiatedDisconnect = false

    private data class ConnectRequest(
        val address: String,
        val name: String?,
        val type: AdapterType
    )

    val isConnected: Boolean get() = _connectionState.value is ConnectionState.Connected

    // ------------------------------------------------------------------ connect

    fun connect(address: String, name: String?, type: AdapterType) {
        connectJob?.cancel()
        lastRequest = ConnectRequest(address, name, type)
        userInitiatedDisconnect = false
        connectJob = scope.launch { attemptConnect(attempt = 1) }
    }

    private suspend fun attemptConnect(attempt: Int) {
        val request = lastRequest ?: return
        tearDown()
        _adapterLog.value = emptyList()

        val device = scanner.deviceFor(request.address)
        if (device == null) {
            _connectionState.value = ConnectionState.Failed(
                "Bluetooth is unavailable, or ${request.address} is not a valid address."
            )
            return
        }

        _connectionState.value = ConnectionState.Connecting("Opening Bluetooth link…", attempt)

        val newTransport: Transport = when (request.type) {
            AdapterType.BLE -> BleTransport(
                context = appContext,
                device = device,
                onLog = ::appendLog,
                onDisconnected = ::onUnexpectedDisconnect
            )
            AdapterType.CLASSIC -> ClassicBtTransport(device)
        }
        transport = newTransport

        val newElm = Elm327(newTransport)
        elm = newElm

        try {
            // Elm327.initialize() opens the transport itself, then runs the AT sequence.
            newElm.initialize()
        } catch (e: Throwable) {
            appendLog("Initialisation failed: ${e.message}")
            tearDown()
            if (!scheduleReconnect(attempt, e.message ?: e::class.simpleName ?: "unknown error")) {
                _connectionState.value = ConnectionState.Failed(
                    e.message ?: "Could not initialise the adapter."
                )
            }
            return
        }

        _connectionState.value = ConnectionState.Connecting("Initialising ELM327…", attempt)
        appendLog("ELM327 AT sequence completed")

        dtcReader = DtcReader(newElm)
        engine = ObdEngine(newElm, scope, onSample = ::onSample)

        _connectionState.value = ConnectionState.Connected(
            deviceName = request.name ?: device.address,
            address = device.address,
            linkDetail = (newTransport as? BleTransport)?.descriptor ?: "classic BT · SPP"
        )
    }

    /** @return true if a retry was scheduled. */
    private suspend fun scheduleReconnect(attempt: Int, reason: String): Boolean {
        if (userInitiatedDisconnect || attempt >= MAX_CONNECT_ATTEMPTS) return false
        val backoffMs = RECONNECT_BACKOFF_MS * (1L shl (attempt - 1))
        _connectionState.value = ConnectionState.Connecting(
            "Retrying after: $reason", attempt + 1
        )
        appendLog("Retrying in ${backoffMs}ms (attempt ${attempt + 1}/$MAX_CONNECT_ATTEMPTS)")
        delay(backoffMs)
        attemptConnect(attempt + 1)
        return true
    }

    private fun onUnexpectedDisconnect() {
        if (userInitiatedDisconnect) return
        scope.launch {
            appendLog("Link dropped — attempting to reconnect")
            _vehicleState.value = emptyMap()
            if (!scheduleReconnect(1, "link dropped")) {
                _connectionState.value = ConnectionState.Failed("The adapter disconnected.")
            }
        }
    }

    fun disconnect() {
        userInitiatedDisconnect = true
        connectJob?.cancel()
        connectJob = null
        scope.launch {
            tearDown()
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private suspend fun tearDown() = withContext(Dispatchers.IO) {
        engine?.stop()
        pollMode = PollMode.Idle
        runCatching { elm?.close() }
        engine = null
        elm = null
        dtcReader = null
        transport = null
        _vehicleState.value = emptyMap()
        performanceMeter.reset()
    }

    // ------------------------------------------------------------------ polling

    fun startDashboardPolling(pids: List<Pid>, intervalMs: Long) {
        val e = engine ?: return
        if (pids.isEmpty()) {
            stopPolling()
            return
        }
        pollMode = PollMode.Dashboard(pids, intervalMs)
        e.startPolling(pids, intervalMs)
    }

    /**
     * Performance mode: speed only, ~20 Hz. Nothing else is polled, because every
     * extra PID in the loop costs another ELM round-trip and blunts the timing.
     */
    fun startPerformanceMode() {
        val e = engine ?: return
        performanceMeter.reset()
        val pid = com.berke.ioniqscope.data.PidCatalog.speed
        pollMode = PollMode.Performance(pid)
        e.startPolling(listOf(pid), PERF_POLL_INTERVAL_MS)
    }

    fun stopPolling() {
        engine?.stop()
        pollMode = PollMode.Idle
    }

    private fun onSample(snapshot: VehicleState) {
        _vehicleState.value = snapshot
        _samples.tryEmit(snapshot)

        // Speed drives the performance meter regardless of which mode is polling,
        // so a run started from the Dashboard is still timed (just more coarsely).
        snapshot[com.berke.ioniqscope.data.PidCatalog.speed.key]?.let { reading ->
            performanceMeter.onSpeed(
                SpeedSample(timeMs = SystemClock.elapsedRealtime(), speedKmh = reading.value)
            )
        }
    }

    // ---------------------------------------------------------------- one-shots

    /**
     * Runs a single command with the poll loop parked. The ELM link carries one
     * request at a time; interleaving would splice two responses together.
     */
    private suspend fun <T> exclusive(block: suspend (Elm327) -> T): Result<T> =
        commandMutex.withLock {
            val e = elm ?: return Result.failure(IllegalStateException("Not connected"))
            val resumeMode = pollMode
            engine?.stop()
            // Let an in-flight command unwind before taking over the link.
            delay(IN_FLIGHT_SETTLE_MS)
            try {
                Result.success(block(e))
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                when (val m = resumeMode) {
                    is PollMode.Dashboard -> engine?.startPolling(m.pids, m.intervalMs)
                    is PollMode.Performance -> engine?.startPolling(listOf(m.pid), PERF_POLL_INTERVAL_MS)
                    PollMode.Idle -> Unit
                }
            }
        }

    suspend fun readDtcs(): Result<List<String>> =
        exclusive { dtcReader?.readCodes() ?: throw IllegalStateException("Not connected") }

    /**
     * Irreversible. The UI must confirm with the user before calling this.
     */
    suspend fun clearDtcs(): Result<Boolean> =
        exclusive { dtcReader?.clearCodes() ?: throw IllegalStateException("Not connected") }

    // -------------------------------------------------------------------- misc

    fun bleScanner(): BleScanner = scanner

    private fun appendLog(line: String) {
        _adapterLog.value = (_adapterLog.value + line).takeLast(MAX_LOG_LINES)
    }

    private companion object {
        const val PERF_POLL_INTERVAL_MS = 50L      // ~20 Hz, per the spec
        const val MAX_CONNECT_ATTEMPTS = 3
        const val RECONNECT_BACKOFF_MS = 2_000L
        const val IN_FLIGHT_SETTLE_MS = 150L
        const val MAX_LOG_LINES = 300
    }
}
