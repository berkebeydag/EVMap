package com.berke.ioniqscope.obd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * ELM327 over WiFi.
 *
 * The cheap adapters that are not Bluetooth are almost all this: the dongle runs an
 * access point, the phone joins it, and the ELM sits on a plain TCP socket speaking
 * exactly the same text protocol. So this is the whole of the difference — a socket
 * instead of a GATT characteristic — and the engine above it does not change.
 *
 * [DEFAULT_HOST] and [DEFAULT_PORT] are what the overwhelming majority of these
 * adapters ship with. A few use 192.168.4.1 or port 23, which is why both are settings
 * rather than constants in the code.
 *
 * A connect timeout is mandatory here in a way it is not for Bluetooth: if the phone
 * is on mobile data, or on the wrong network, the socket does not refuse — it hangs
 * until the system gives up, which can be over a minute of a spinner and no
 * explanation. Ten seconds is long enough for a dongle that is there and short enough
 * to say so when it is not.
 */
class WifiTransport(
    private val host: String,
    private val port: Int
) : Transport {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        } catch (e: IOException) {
            runCatching { s.close() }
            // The platform's own message is "failed to connect to /192.168.0.10 (port
            // 35000) from /10.0.2.16 (port 46334) after 10000ms", which is accurate,
            // English, and tells a driver nothing they can act on. There are only two
            // things that are ever wrong here and both are worth naming.
            throw IOException(
                "$host:$port adresinde adaptör bulunamadı. Telefonun adaptörün WiFi " +
                    "ağına bağlı olduğundan ve adresin doğru olduğundan emin ol."
            )
        }
        // Nagle batches small writes, and every ELM command is a small write followed
        // by waiting for the reply — exactly the pattern it delays.
        s.tcpNoDelay = true
        s.soTimeout = READ_TIMEOUT_MS
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
    }

    override fun disconnect() {
        runCatching { input?.close(); output?.close(); socket?.close() }
        socket = null; input = null; output = null
    }

    override suspend fun write(bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        output?.write(bytes); output?.flush()
    }

    override suspend fun readUntilPrompt(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val buf = ByteArray(256)
        val stream = input ?: return@withContext ""
        while (isActive) {
            val n = stream.read(buf)
            if (n <= 0) break
            sb.append(String(buf, 0, n, Charsets.US_ASCII))
            if (sb.contains('>')) break
        }
        sb.toString()
    }

    val descriptor: String get() = "WiFi · $host:$port"

    companion object {
        const val DEFAULT_HOST = "192.168.0.10"
        const val DEFAULT_PORT = 35000
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000
    }
}
