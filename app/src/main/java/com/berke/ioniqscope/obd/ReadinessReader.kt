package com.berke.ioniqscope.obd

/**
 * Emissions readiness, from standard OBD-II service 01 PID 01.
 *
 * The layout below is the published SAE J1979 one, the same standard the PIDs in
 * [StandardPids] come from — it is not a reverse-engineered or manufacturer-specific
 * guess. Even so, the UI shows the raw response next to the decode so it can be
 * checked rather than trusted.
 *
 * Byte A : bit 7 = MIL on, bits 0-6 = stored DTC count
 * Byte B : bits 0-2 = misfire/fuel/components supported, bits 4-6 = same, incomplete
 *          bit 3 = compression ignition (diesel) when set
 * Bytes C/D : the remaining monitors — C = supported, D = incomplete
 *
 * On a battery-electric car most monitors are simply unsupported, and that is the
 * correct answer rather than a failure: there is no catalyst or evaporative system
 * to test.
 */
data class MonitorStatus(
    val name: String,
    val supported: Boolean,
    val complete: Boolean
)

data class ReadinessReport(
    val milOn: Boolean,
    val storedDtcCount: Int,
    val monitors: List<MonitorStatus>,
    /** Codes that have not yet matured into stored ones (service 07). */
    val pendingCodes: List<String>,
    /** Exactly what the adapter returned, for verification. */
    val rawStatus: String,
    val rawPending: String
) {
    val supportedMonitors: List<MonitorStatus> get() = monitors.filter { it.supported }
    val incomplete: List<MonitorStatus> get() = supportedMonitors.filter { !it.complete }

    /**
     * The question an inspection actually asks: warning light off, nothing stored,
     * and every monitor the car does support has finished running.
     */
    val looksReady: Boolean get() = !milOn && storedDtcCount == 0 && incomplete.isEmpty()
}

class ReadinessReader(private val elm: Elm327) {

    suspend fun read(): ReadinessReport {
        val rawStatus = elm.command("0101")
        val data = extractDataBytes(rawStatus, "0101")
        if (data.size < 4) {
            throw IllegalStateException(
                "Mode 01 PID 01 returned ${data.size} data bytes, expected 4 (raw: $rawStatus)"
            )
        }

        val a = data[0]
        val b = data[1]
        val c = data[2]
        val d = data[3]

        val monitors = buildList {
            // Continuous monitors, byte B.
            add(MonitorStatus("Misfire", b and 0x01 != 0, b and 0x10 == 0))
            add(MonitorStatus("Fuel system", b and 0x02 != 0, b and 0x20 == 0))
            add(MonitorStatus("Components", b and 0x04 != 0, b and 0x40 == 0))

            // Non-continuous monitors, bytes C (supported) and D (incomplete).
            NON_CONTINUOUS.forEachIndexed { index, name ->
                val mask = 1 shl index
                add(MonitorStatus(name, c and mask != 0, d and mask == 0))
            }
        }

        val rawPending = elm.command("07")

        return ReadinessReport(
            milOn = a and 0x80 != 0,
            storedDtcCount = a and 0x7F,
            monitors = monitors,
            pendingCodes = decodePending(rawPending),
            rawStatus = rawStatus,
            rawPending = rawPending
        )
    }

    /** Service 07 uses the same DTC encoding as service 03, with a 0x47 echo. */
    private fun decodePending(raw: String): List<String> {
        val clean = raw.uppercase().filter { it.isDigit() || it in 'A'..'F' }
        val idx = clean.indexOf("47")
        if (idx < 0) return emptyList()
        return clean.substring(idx + 2)
            .chunked(4)
            .filter { it.length == 4 && it != "0000" }
            .map { decodeDtc(it.substring(0, 2).toInt(16), it.substring(2, 4).toInt(16)) }
    }

    /** Same mapping as DtcReader; duplicated rather than widening that class's API. */
    private fun decodeDtc(a: Int, b: Int): String {
        val letter = when (a shr 6) { 0 -> 'P'; 1 -> 'C'; 2 -> 'B'; else -> 'U' }
        return "%c%d%X%X%X".format(letter, (a shr 4) and 0x03, a and 0x0F, (b shr 4) and 0x0F, b and 0x0F)
    }

    private companion object {
        val NON_CONTINUOUS = listOf(
            "Catalyst",
            "Heated catalyst",
            "Evaporative system",
            "Secondary air system",
            "A/C refrigerant",
            "Oxygen sensor",
            "Oxygen sensor heater",
            "EGR system"
        )
    }
}
