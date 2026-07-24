package com.berke.ioniqscope.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Transport implementation (GATT) for the Vgate iCar Pro BLE 4.0.
 *
 * The original skeleton hard-coded FFE0/FFE1. BLE ELM327 clones are not consistent
 * about this, so instead of guessing we:
 *   1. try the known candidate service/characteristic pairs, in order;
 *   2. fall back to *structural* discovery — any service holding a NOTIFY
 *      characteristic plus a writable one (they are frequently the same
 *      characteristic on these adapters);
 *   3. log every discovered service and characteristic through [onLog] so the
 *      real UUIDs can be read off the Connect screen and pinned in [CANDIDATES];
 *   4. fail with a descriptive message rather than silently doing nothing.
 *
 * @param onLog receives human-readable adapter diagnostics (shown on the Connect screen).
 * @param onDisconnected invoked when the peripheral drops the link, so the
 *        connection manager can surface it instead of polling into the void.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val onLog: (String) -> Unit = {},
    private val onDisconnected: () -> Unit = {},
    private val connectTimeoutMs: Long = 20_000,
    private val responseTimeoutMs: Long = 6_000
) : Transport {

    /**
     * Known-good candidates for BLE ELM327 clones, most common first.
     * `write` == `notify` means a single bidirectional characteristic.
     *
     * TODO(verified-uuids): once the real Vgate iCar Pro UUIDs are confirmed from
     * the connect log, move that pair to the front of this list.
     */
    private data class Candidate(val service: UUID, val notify: UUID, val write: UUID)

    private val candidates = listOf(
        // FFE0 / FFE1 — single characteristic, read+write (very common)
        Candidate(shortUuid("FFE0"), shortUuid("FFE1"), shortUuid("FFE1")),
        // FFF0 — split: FFF1 notifies, FFF2 accepts writes
        Candidate(shortUuid("FFF0"), shortUuid("FFF1"), shortUuid("FFF2")),
        // Some units expose FFF0 as a single bidirectional characteristic
        Candidate(shortUuid("FFF0"), shortUuid("FFF1"), shortUuid("FFF1")),
        // Nordic UART Service — used by a few rebadged adapters
        Candidate(
            UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
            UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
            UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        )
    )

    private val cccdUuid = shortUuid("2902")

    private var gatt: BluetoothGatt? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    /** Notification payloads, reassembled into ELM responses by [readUntilPrompt]. */
    private val rxChannel = Channel<String>(Channel.UNLIMITED)

    private val writeMutex = Mutex()
    @Volatile private var mtuPayload = 20   // ATT_MTU 23 - 3 bytes of ATT header
    @Volatile private var connected = false
    @Volatile private var setupCont: CancellableContinuation<Unit>? = null
    @Volatile private var writeAck: CancellableContinuation<Unit>? = null

    override val isConnected: Boolean get() = connected && notifyChar != null && writeChar != null

    /** Human-readable summary of what was actually negotiated. Shown in the UI. */
    @Volatile var descriptor: String = "not connected"
        private set

    // ---------------------------------------------------------------- callbacks

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onLog("GATT connected (status=$status), requesting MTU…")
                    // Bigger MTU => fewer notification fragments per ELM response.
                    if (!g.requestMtu(247)) {
                        onLog("requestMtu rejected, continuing at default MTU")
                        g.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasConnected = connected
                    connected = false
                    onLog("GATT disconnected (status=$status)")
                    failSetup(IllegalStateException("Adapter disconnected (GATT status $status)"))
                    // Unblock anyone parked in readUntilPrompt.
                    rxChannel.close(IllegalStateException("Adapter disconnected"))
                    if (wasConnected) onDisconnected()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            mtuPayload = (mtu - 3).coerceAtLeast(20)
            onLog("MTU = $mtu (payload $mtuPayload B)")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failSetup(IllegalStateException("Service discovery failed (status $status)"))
                return
            }
            logDiscoveredProfile(g)

            val pair = resolveCharacteristics(g)
            if (pair == null) {
                failSetup(
                    IllegalStateException(
                        "No usable notify+write characteristic pair found on this adapter. " +
                            "See the adapter log above for what it actually exposes, and pin " +
                            "those UUIDs in BleTransport.candidates."
                    )
                )
                return
            }

            notifyChar = pair.first
            writeChar = pair.second
            descriptor = "service ${pair.first.service.uuid.short()} · " +
                "notify ${pair.first.uuid.short()} · write ${pair.second.uuid.short()}"
            onLog("Using $descriptor")

            if (!g.setCharacteristicNotification(pair.first, true)) {
                failSetup(IllegalStateException("Could not enable notifications on ${pair.first.uuid}"))
                return
            }

            val cccd = pair.first.getDescriptor(cccdUuid)
            if (cccd == null) {
                // Some clones notify without a CCCD present. Treat as usable.
                onLog("No CCCD (2902) on notify characteristic — proceeding anyway")
                connected = true
                completeSetup()
            } else {
                writeCccd(g, cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid != cccdUuid) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onLog("Notifications enabled")
                connected = true
                completeSetup()
            } else {
                failSetup(IllegalStateException("Enabling notifications failed (status $status)"))
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                ch.value?.let { rxChannel.trySend(it.toString(Charsets.US_ASCII)) }
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            rxChannel.trySend(value.toString(Charsets.US_ASCII))
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            val cont = writeAck ?: return
            writeAck = null
            if (status == BluetoothGatt.GATT_SUCCESS) cont.resume(Unit)
            else cont.resumeWithException(IllegalStateException("Characteristic write failed ($status)"))
        }
    }

    // ---------------------------------------------------------------- Transport

    override suspend fun connect() {
        try {
            withTimeout(connectTimeoutMs) {
                suspendCancellableCoroutine<Unit> { cont ->
                    setupCont = cont
                    onLog("Connecting to ${device.address}…")
                    gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                    if (gatt == null) {
                        setupCont = null
                        cont.resumeWithException(IllegalStateException("connectGatt returned null"))
                    }
                    cont.invokeOnCancellation { disconnect() }
                }
            }
        } catch (e: TimeoutCancellationException) {
            disconnect()
            throw IllegalStateException(
                "Timed out after ${connectTimeoutMs / 1000}s connecting to the adapter. " +
                    "Is it powered and in range?"
            )
        }
    }

    override fun disconnect() {
        connected = false
        notifyChar = null
        writeChar = null
        descriptor = "not connected"
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    override suspend fun write(bytes: ByteArray) = writeMutex.withLock {
        val g = gatt ?: throw IllegalStateException("Not connected")
        val ch = writeChar ?: throw IllegalStateException("Not connected")

        // Anything still buffered predates this command, so it is stale by definition.
        drainRx()

        val noResponse =
            ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        for (chunk in bytes.chunked(mtuPayload)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val rc = g.writeCharacteristic(ch, chunk, type)
                if (rc != BluetoothGatt.GATT_SUCCESS) {
                    throw IllegalStateException("writeCharacteristic returned $rc")
                }
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.writeType = type
                    ch.value = chunk
                    if (!g.writeCharacteristic(ch)) {
                        throw IllegalStateException("writeCharacteristic rejected")
                    }
                }
            }
            // A write-with-response must be acked before the next one is issued.
            if (!noResponse) {
                withTimeout(responseTimeoutMs) {
                    suspendCancellableCoroutine<Unit> { cont -> writeAck = cont }
                }
            }
        }
    }

    /**
     * ELM327 terminates every response with '>'. Reassemble notification fragments
     * until we see it, or give up — a hang here would stall the whole poll loop.
     */
    override suspend fun readUntilPrompt(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        try {
            withTimeout(responseTimeoutMs) {
                while (true) {
                    sb.append(rxChannel.receive())
                    if (sb.contains('>')) break
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException(
                "No '>' prompt within ${responseTimeoutMs}ms (got \"${sb.toString().trim()}\")"
            )
        }
        sb.toString()
    }

    // ---------------------------------------------------------------- internals

    private fun writeCccd(g: BluetoothGatt, cccd: BluetoothGattDescriptor) {
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { cccd.value = value; g.writeDescriptor(cccd) }
        }
        if (!ok) failSetup(IllegalStateException("Could not write CCCD to enable notifications"))
    }

    /** Dump the full GATT profile so unknown adapters can be identified from the UI log. */
    private fun logDiscoveredProfile(g: BluetoothGatt) {
        onLog("── discovered GATT profile ──")
        for (service in g.services) {
            onLog("service ${service.uuid}")
            for (ch in service.characteristics) {
                onLog("   char ${ch.uuid}  [${ch.properties.propertyNames()}]")
            }
        }
        onLog("──────────────────────────────")
    }

    /** Known candidates first; structural match as a fallback. */
    private fun resolveCharacteristics(
        g: BluetoothGatt
    ): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        for (c in candidates) {
            val service = g.getService(c.service) ?: continue
            val notify = service.getCharacteristic(c.notify) ?: continue
            val write = service.getCharacteristic(c.write) ?: continue
            if (notify.canNotify() && write.canWrite()) {
                onLog("Matched known adapter profile ${c.service.short()}")
                return notify to write
            }
        }

        onLog("No known profile matched — falling back to structural discovery")
        for (service in g.services) {
            val notify = service.characteristics.firstOrNull { it.canNotify() } ?: continue
            val write = service.characteristics.firstOrNull { it.canWrite() } ?: continue
            return notify to write
        }
        return null
    }

    private fun drainRx() {
        while (rxChannel.tryReceive().isSuccess) { /* discard */ }
    }

    private fun completeSetup() {
        val cont = setupCont ?: return
        setupCont = null
        if (cont.isActive) cont.resume(Unit)
    }

    private fun failSetup(e: Throwable) {
        val cont = setupCont ?: return
        setupCont = null
        if (cont.isActive) cont.resumeWithException(e)
    }
}

// ------------------------------------------------------------------- helpers

private fun shortUuid(hex4: String): UUID =
    UUID.fromString("0000${hex4.uppercase()}-0000-1000-8000-00805F9B34FB")

/** "0000FFE1-0000-…" -> "FFE1"; leaves non-16-bit UUIDs untouched. */
private fun UUID.short(): String {
    val s = toString().uppercase()
    return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805F9B34FB")) {
        s.substring(4, 8)
    } else s
}

private fun BluetoothGattCharacteristic.canNotify(): Boolean =
    properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
        BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

private fun BluetoothGattCharacteristic.canWrite(): Boolean =
    properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

private fun Int.propertyNames(): String = buildList {
    if (this@propertyNames and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
    if (this@propertyNames and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
    if (this@propertyNames and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("writeNR")
    if (this@propertyNames and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
    if (this@propertyNames and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
}.joinToString(",").ifEmpty { "none" }

private fun ByteArray.chunked(size: Int): List<ByteArray> =
    if (this.size <= size) listOf(this)
    else indices.step(size).map { copyOfRange(it, minOf(it + size, this.size)) }
