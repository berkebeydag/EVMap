package com.berke.ioniqscope.obd

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/* =========================================================================
 *  1) TRANSPORT — fiziksel bağlantı soyutlaması
 *     Klasik BT / BLE / WiFi hepsi bu arayüzü uygular. Motor bunun
 *     hangisi olduğunu bilmez → tek yerden değiştirilir.
 * ========================================================================= */
interface Transport {
    suspend fun connect()
    fun disconnect()
    /** Ham byte gönderir (komut sonuna \r biz ekleriz). */
    suspend fun write(bytes: ByteArray)
    /** '>' prompt'una kadar okur, ham metni döner. */
    suspend fun readUntilPrompt(): String
    val isConnected: Boolean
}

/**
 * Vgate iCar'ın KLASİK BT sürümü için (RFCOMM / SPP).
 * SPP standart UUID'i sabittir; Vgate dahil tüm ELM327 klasik BT
 * adaptörleri bunu kullanır.
 */
class ClassicBtTransport(private val device: BluetoothDevice) : Transport {

    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override val isConnected: Boolean get() = socket?.isConnected == true

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val s = device.createRfcommSocketToServiceRecord(sppUuid)
        s.connect()                 // bağlanana kadar bloklar
        socket = s
        input = s.inputStream
        output = s.outputStream
    }

    override fun disconnect() {
        runCatching { input?.close(); output?.close(); socket?.close() }
        socket = null; input = null; output = null
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        output?.write(bytes); output?.flush()
    }

    /**
     * ELM327 her cevabı '>' ile bitirir. Onu görene kadar okuyoruz.
     * WiFi/BLE transport'ta okuma mantığı değişir ama sözleşme aynı kalır.
     */
    override suspend fun readUntilPrompt(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val buf = ByteArray(256)
        val stream = input ?: return@withContext ""
        while (isActive) {
            val n = stream.read(buf)
            if (n <= 0) break
            sb.append(String(buf, 0, n, Charsets.US_ASCII))
            if (sb.contains('>')) break        // prompt geldi
        }
        sb.toString()
    }
}

/* BLE sürümü (iCar Pro) için iskelet:
class BleTransport(...) : Transport {
    // GATT servis/karakteristik UUID'leri Vgate BLE'de genelde FFF0/FFF1/FFF2
    // ya da FFE0/FFE1 olur — cihazı taratıp doğrulamak gerekir.
    // write → karakteristiğe yaz, readUntilPrompt → notification'ları biriktir.
}
*/

/* =========================================================================
 *  2) ELM327 — AT init dizisi + komut/cevap katmanı
 * ========================================================================= */
class Elm327(private val transport: Transport) {

    /** Bağlan + adaptörü hazırla. Bir kere çağrılır. */
    suspend fun initialize() {
        transport.connect()
        // Cevapları temizlemek için init komutları:
        command("ATZ")    // reset
        delay(1000)
        command("ATE0")   // echo kapat (cevaplarda komutu tekrar etmesin)
        command("ATL0")   // satır sonlarını kapat
        command("ATS0")   // boşlukları kapat → parse kolaylaşır
        command("ATH1")   // header'ları AÇ (UDS/çok-frame'de gerekli)
        command("ATSP0")  // protokolü otomatik seç (standart PID'ler için)
    }

    /**
     * Tek komut gönder, ham cevabı döndür.
     * Örn command("010C") → "410C1AF8"
     */
    suspend fun command(cmd: String): String {
        transport.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        return transport.readUntilPrompt()
            .replace(">", "")
            .replace("\r", "")
            .replace("\n", "")
            .replace(" ", "")
            .trim()
    }

    /** PSA EV gibi belirli bir ECU'ya sormak için header ayarla. */
    suspend fun setHeader(ecuHex: String) = command("ATSH$ecuHex")
    suspend fun forceCanProtocol() = command("ATSP6") // ISO 15765-4 CAN 11bit/500k

    fun close() = transport.disconnect()
}

/* =========================================================================
 *  3) PID TANIMLARI — istek + cevabı değere çeviren parser
 * ========================================================================= */
data class Pid(
    val key: String,
    val label: String,
    val request: String,           // ELM'e gönderilen (ör. "010C")
    val unit: String,
    val parse: (IntArray) -> Double // header sonrası data byte'ları → değer
)

/** "410C1AF8" ham cevabından data byte'larını (0x1A, 0xF8) çıkarır. */
fun extractDataBytes(raw: String, request: String): IntArray {
    // Beklenen cevap prefix'i = 0x40 + mode, sonra PID byte(lar)ı
    val clean = raw.uppercase().filter { it.isDigit() || it in 'A'..'F' }
    // "41" + PID'i bulup sonrasını al (basit ve pratik yaklaşım)
    val modeEcho = (request.substring(0, 2).toInt(16) + 0x40).toString(16).uppercase().padStart(2, '0')
    val idx = clean.indexOf(modeEcho + request.substring(2))
    if (idx < 0) return IntArray(0)
    val dataHex = clean.substring(idx + request.length)
    return dataHex.chunked(2).mapNotNull { it.toIntOrNull(16) }.toIntArray()
}

/** Standart OBD-II PID'leri (bunlar ISO standardı, EV'de bir kısmı çalışır). */
object StandardPids {
    val speed        = Pid("speed", "Hız", "010D", "km/h") { d -> d[0].toDouble() }
    val rpm          = Pid("rpm", "Devir", "010C", "rpm") { d -> (256 * d[0] + d[1]) / 4.0 }
    val coolant      = Pid("coolant", "Soğutma sıvısı", "0105", "°C") { d -> d[0] - 40.0 }
    // 12V yardımcı akü — EV'de de OKUNUR, işe yarar:
    val moduleVolt   = Pid("mvolt", "Modül voltajı (12V)", "0142", "V") { d -> (256 * d[0] + d[1]) / 1000.0 }
    val ambientTemp  = Pid("ambient", "Dış sıcaklık", "0146", "°C") { d -> d[0] - 40.0 }

    val defaultSet = listOf(speed, moduleVolt, ambientTemp)
}

/*
 * Hyundai Ioniq 6 / E-GMP EV'ye özel PID'ler (SoC, HV batarya V/A, hücre sıcaklıkları)
 * BURAYA GELECEK. Bunlar standart değil — belirli ECU header'ına (ATSH...)
 * UDS sorgusu gerekiyor. Örn:
 *   engine.setHeader("6xx")
 *   engine.command("22 xxxx")   // 22 = ReadDataByIdentifier
 * Exact ECU adresi + DID'leri DOĞRULANMIŞ topluluk kaynağından (EVNotify repo, Car Scanner Ioniq profili) alınmalı (uydurma yok).
 */
object EgmpPids {
    // val stateOfCharge = Pid("soc", "Şarj (SoC)", "22XXXX", "%") { d -> ... }
    val set: List<Pid> = emptyList()
}

/* =========================================================================
 *  4) DTC OKUMA / TEMİZLEME  (Mode 03 / Mode 04)
 * ========================================================================= */
class DtcReader(private val elm: Elm327) {

    suspend fun readCodes(): List<String> {
        val raw = elm.command("03").uppercase().filter { it.isDigit() || it in 'A'..'F' }
        // Cevap "43" ile başlar, ardından 2 byte'lık DTC çiftleri gelir
        val idx = raw.indexOf("43")
        if (idx < 0) return emptyList()
        val payload = raw.substring(idx + 2)
        return payload.chunked(4)
            .filter { it.length == 4 && it != "0000" }
            .map { decodeDtc(it.substring(0,2).toInt(16), it.substring(2,4).toInt(16)) }
    }

    suspend fun clearCodes(): Boolean =
        elm.command("04").uppercase().contains("44")   // 44 = onay

    /** 2 byte → "P0301" formatı. Standart DTC çözümü. */
    private fun decodeDtc(a: Int, b: Int): String {
        val letter = when (a shr 6) { 0 -> 'P'; 1 -> 'C'; 2 -> 'B'; else -> 'U' }
        val d1 = (a shr 4) and 0x03
        val d2 = a and 0x0F
        val d3 = (b shr 4) and 0x0F
        val d4 = b and 0x0F
        return "%c%d%X%X%X".format(letter, d1, d2, d3, d4)
    }
}

/* =========================================================================
 *  5) POLL MOTORU — sürekli okuyup StateFlow yayınlar (UI buradan besler)
 * ========================================================================= */
data class Reading(val label: String, val value: Double, val unit: String)
typealias VehicleState = Map<String, Reading>

class ObdEngine(
    private val elm: Elm327,
    private val scope: CoroutineScope,
    private val onSample: (VehicleState) -> Unit = {}   // TripLogger buraya bağlanır
) {
    private val _state = MutableStateFlow<VehicleState>(emptyMap())
    val state: StateFlow<VehicleState> = _state.asStateFlow()

    private var job: Job? = null

    fun startPolling(pids: List<Pid>, intervalMs: Long = 250) {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                val snapshot = mutableMapOf<String, Reading>()
                for (pid in pids) {
                    runCatching {
                        val raw = elm.command(pid.request)
                        val data = extractDataBytes(raw, pid.request)
                        if (data.isNotEmpty()) {
                            snapshot[pid.key] = Reading(pid.label, pid.parse(data), pid.unit)
                        }
                    }
                }
                _state.value = snapshot
                onSample(snapshot)     // loglama
                delay(intervalMs)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}
