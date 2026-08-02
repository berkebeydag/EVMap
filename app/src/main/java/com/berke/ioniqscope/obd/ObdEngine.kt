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

    // Explicit `: Unit` — without it the block's value is `output?.flush()`, i.e.
    // `Unit?`, which does not satisfy Transport.write's `Unit` return type.
    override suspend fun write(bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
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

/**
 * The standard OBD-II PIDs, which is to say the ones every car has to answer.
 *
 * This is the part of the app that works on any car, and it is worth being clear about
 * what "any car" buys: these are SAE J1979 mode 01 PIDs, defined by the standard rather
 * than by a manufacturer, so a Zoe and an Ioniq and a diesel Passat all answer them the
 * same way. Everything richer than this — cell temperatures, pack voltage, real state of
 * health — is behind manufacturer-specific UDS identifiers and lives in VehicleProfile.
 *
 * Which of these a given car actually answers varies, and is not guessable: an EV has no
 * coolant loop to report and a petrol car has no hybrid battery. That is not worked
 * around with a list of assumptions per brand — the car is asked, once, at connect. See
 * [SupportedPidReader].
 */
object StandardPids {
    val speed        = Pid("speed", "Hız", "010D", "km/h") { d -> d[0].toDouble() }
    val rpm          = Pid("rpm", "Devir", "010C", "rpm") { d -> (256 * d[0] + d[1]) / 4.0 }
    val coolant      = Pid("coolant", "Soğutma sıvısı", "0105", "°C") { d -> d[0] - 40.0 }
    // 12V yardımcı akü — EV'de de OKUNUR, işe yarar:
    val moduleVolt   = Pid("mvolt", "Modül voltajı (12V)", "0142", "V") { d -> (256 * d[0] + d[1]) / 1000.0 }
    val ambientTemp  = Pid("ambient", "Dış sıcaklık", "0146", "°C") { d -> d[0] - 40.0 }

    /**
     * The one standard PID that is about the traction battery.
     *
     * J1979 calls it "hybrid battery pack remaining life" and it is the closest thing to
     * a manufacturer-independent state of charge that exists. Cars disagree about what
     * they put in it — some report charge, some report health — so it is labelled for
     * what the standard calls it rather than for what we would like it to be.
     */
    val hybridBattery = Pid("hvbatt", "Batarya (standart)", "015B", "%") { d -> d[0] * 100.0 / 255.0 }

    val load         = Pid("load", "Motor yükü", "0104", "%") { d -> d[0] * 100.0 / 255.0 }
    val intakeTemp   = Pid("intake", "Emme havası", "010F", "°C") { d -> d[0] - 40.0 }
    val throttle     = Pid("throttle", "Gaz kelebeği", "0111", "%") { d -> d[0] * 100.0 / 255.0 }
    val pedal        = Pid("pedal", "Gaz pedalı", "0149", "%") { d -> d[0] * 100.0 / 255.0 }
    val runTime      = Pid("runtime", "Çalışma süresi", "011F", "s") { d -> (256 * d[0] + d[1]).toDouble() }
    val distanceMil  = Pid("dist_mil", "Arıza ışığıyla yol", "0121", "km") { d -> (256 * d[0] + d[1]).toDouble() }
    val distanceClr  = Pid("dist_clr", "Kod silmeden beri", "0131", "km") { d -> (256 * d[0] + d[1]).toDouble() }
    val baro         = Pid("baro", "Hava basıncı", "0133", "kPa") { d -> d[0].toDouble() }
    val oilTemp      = Pid("oil", "Yağ sıcaklığı", "015C", "°C") { d -> d[0] - 40.0 }
    val fuelLevel    = Pid("fuel", "Yakıt seviyesi", "012F", "%") { d -> d[0] * 100.0 / 255.0 }

    /** Every standard PID the app knows how to decode, in the order they read best. */
    val all = listOf(
        speed, hybridBattery, moduleVolt, ambientTemp, pedal, throttle, load,
        rpm, runTime, distanceClr, distanceMil, baro, intakeTemp, coolant,
        oilTemp, fuelLevel
    )

    val defaultSet = listOf(speed, hybridBattery, moduleVolt, ambientTemp)

    /** The mode-01 PID number a definition asks for, e.g. 0x0D for `010D`. */
    fun numberOf(pid: Pid): Int? =
        pid.request.takeIf { it.length == 4 && it.startsWith("01") }
            ?.substring(2)
            ?.toIntOrNull(16)
}

/**
 * Asks the car which of the standard PIDs it actually answers.
 *
 * Mode 01 has four PIDs whose entire job is this: 0100, 0120, 0140 and 0160 each return
 * a 32-bit mask saying which of the next 32 PIDs are supported, and the top bit of each
 * says whether it is worth asking for the next range. So one to four questions replace
 * every assumption about what a given car supports.
 *
 * This is the difference between an app that works on the cars somebody thought to write
 * a profile for and one that works on whatever is plugged in. A car that reports no
 * hybrid battery PID simply does not offer it; one that does gets it whether or not
 * anyone has heard of the model.
 *
 * A car that refuses the question at all returns an empty set, and the caller is
 * expected to treat that as "unknown", not as "supports nothing" — a wrong empty answer
 * that hid the speed reading would be worse than asking for a PID and getting NO DATA.
 */
class SupportedPidReader(private val elm: Elm327) {

    suspend fun read(): Set<Int> {
        val supported = mutableSetOf<Int>()
        var base = 0x00
        while (base <= 0x60) {
            val mask = queryMask(base) ?: break
            for (bit in 0 until 32) {
                // Bit 31 is PID base+1, bit 0 is PID base+32 — the mask is written
                // most-significant-first, which is the opposite of how it reads.
                if ((mask shr (31 - bit)) and 1 == 1) supported += base + bit + 1
            }
            // The last bit of each range says whether the next range exists at all.
            if (mask and 1 == 0) break
            base += 0x20
        }
        return supported
    }

    private suspend fun queryMask(base: Int): Int? {
        val command = "01" + base.toString(16).uppercase().padStart(2, '0')
        val raw = elm.command(command).uppercase()
        if (raw.contains("NO DATA") || raw.contains("UNABLE") || raw.contains("?")) return null
        val hex = raw.filter { it.isDigit() || it in 'A'..'F' }
        // The reply echoes 41 then the PID number, then four bytes of mask.
        val marker = "41" + base.toString(16).uppercase().padStart(2, '0')
        val at = hex.indexOf(marker)
        if (at < 0) return null
        val payload = hex.drop(at + 4).take(8)
        if (payload.length < 8) return null
        return payload.toLongOrNull(16)?.toInt()
    }
}

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
