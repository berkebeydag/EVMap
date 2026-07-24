package com.berke.ioniqscope.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Vgate iCar Pro BLE 4.0 için Transport implementasyonu (GATT).
 *
 * ⚠️ DOĞRULA: BLE ELM327 adaptörlerinde servis/karakteristik UUID'leri üreticiye
 * göre değişir. Vgate/çoğu klonda yaygın adaylar:
 *     • Service FFE0 → IO (notify+write) FFE1
 *     • Service FFF0 → Write FFF2 / Notify FFF1
 * Gerçek cihaza bağlanınca onServicesDiscovered'ta keşfedilen servisleri LOG'la,
 * doğru UUID'leri buraya yaz. (Alternatif: nRF Connect uygulamasıyla incele.)
 *
 * Not: API 33+ karakteristik value API'leri değişti; burada geriye dönük uyumlu
 * (deprecated) yol kullanıldı — Claude Code targetSdk'ya göre modernize edebilir.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice
) : Transport {

    // TODO: gerçek cihazda doğrula
    private val serviceUuid = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
    private val ioCharUuid  = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
    private val cccdUuid    = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    private var gatt: BluetoothGatt? = null
    private var ioChar: BluetoothGattCharacteristic? = null
    private val rxChannel = Channel<String>(Channel.UNLIMITED)

    @Volatile private var connectCont: ((Result<Unit>) -> Unit)? = null

    override val isConnected: Boolean get() = ioChar != null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(serviceUuid)?.getCharacteristic(ioCharUuid)
            if (ch != null) {
                ioChar = ch
                g.setCharacteristicNotification(ch, true)
                ch.getDescriptor(cccdUuid)?.let {
                    @Suppress("DEPRECATION")
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(it)
                }
                connectCont?.invoke(Result.success(Unit))
            } else {
                connectCont?.invoke(
                    Result.failure(IllegalStateException("IO karakteristiği yok — UUID'leri doğrula"))
                )
            }
            connectCont = null
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val chunk = ch.value?.toString(Charsets.US_ASCII) ?: return
            rxChannel.trySend(chunk)
        }
    }

    override suspend fun connect() = suspendCancellableCoroutine { cont ->
        connectCont = { result -> result.fold({ cont.resume(Unit) }, { cont.resumeWithException(it) }) }
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        cont.invokeOnCancellation { disconnect() }
    }

    override fun disconnect() {
        gatt?.disconnect(); gatt?.close(); gatt = null; ioChar = null
    }

    @Suppress("DEPRECATION")
    override suspend fun write(bytes: ByteArray) {
        val ch = ioChar ?: return
        ch.value = bytes
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        gatt?.writeCharacteristic(ch)
    }

    /** ELM327 cevabı '>' ile biter; notification parçalarını o ana kadar biriktir. */
    override suspend fun readUntilPrompt(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        while (isActive) {
            sb.append(rxChannel.receive())
            if (sb.contains('>')) break
        }
        sb.toString()
    }
}
