package com.berke.ioniqscope.obd

/**
 * Reads a manufacturer-specific data identifier and hands back the payload bytes.
 *
 * Standard OBD-II answers in one frame, which is why [extractDataBytes] can get away
 * with finding the echoed mode and taking what follows. A UDS read cannot: asking the
 * battery ECU for 0x0101 comes back as roughly sixty bytes, and CAN carries eight at a
 * time, so the answer arrives as an ISO-TP sequence — a first frame carrying the total
 * length, then consecutive frames each carrying an index.
 *
 * With headers on, which is how the engine is configured, the ELM prints them as:
 *
 * ```
 * 7EC 10 3D 62 01 01 FF F7 E7
 * 7EC 21 FF 9E 26 14 C6 62 00
 * 7EC 22 00 00 00 00 0E 0E 0E
 * ```
 *
 * So this strips the CAN id, drops the ISO-TP index byte from every line, checks the
 * positive response (0x62 + the identifier), and returns what is left. Everything
 * after that is a matter of counting bytes, which is where getting it wrong stops
 * being obvious — hence [VehicleProfile] carrying its own byte offsets rather than
 * this trying to guess a layout.
 */
object UdsReader {

    /** A negative response, which is a refusal rather than a failure to arrive. */
    private const val NEGATIVE = 0x7F

    /** ReadDataByIdentifier's positive reply is the request mode plus 0x40. */
    private const val POSITIVE = 0x62

    /**
     * The payload of one identifier, or null when the car did not answer with one.
     *
     * Null rather than an empty array on every failure path, so a caller cannot
     * mistake "the ECU refused" for "the ECU said zero" — which for a state of charge
     * is the difference between showing nothing and showing an empty battery.
     */
    fun payloadOf(raw: String, identifier: String): IntArray? {
        val lines = raw.uppercase()
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != ">" }

        val bytes = ArrayList<Int>(64)
        var seenFirstFrame = false

        for (line in lines) {
            // "SEARCHING...", "NO DATA", "CAN ERROR" and the echoed command all reach
            // here; anything that is not hex is not an answer.
            val hex = line.filter { it.isDigit() || it in 'A'..'F' }
            if (hex.length < 2 || hex.length % 2 != 0) continue
            var frame = hex.chunked(2).mapNotNull { it.toIntOrNull(16) }
            if (frame.isEmpty()) continue

            // Drop the CAN id when the ELM is printing headers. An 11-bit id is three
            // hex characters, so the line is odd-length before the filter above pairs
            // it up — which is why the id is found by looking at what a frame can be
            // rather than by trusting the length.
            if (frame.size > 8) frame = frame.drop(frame.size - 8)

            val pci = frame.first()
            when {
                // Single frame: the low nibble is the length.
                pci in 0x00..0x07 && !seenFirstFrame -> {
                    bytes += frame.drop(1).take(pci)
                    seenFirstFrame = true
                }
                // First frame of a multi-frame answer; the length spans two nibbles
                // and is not needed here, because the caller reads fixed offsets.
                pci in 0x10..0x1F -> {
                    bytes += frame.drop(2)
                    seenFirstFrame = true
                }
                // Consecutive frame.
                pci in 0x20..0x2F && seenFirstFrame -> bytes += frame.drop(1)
            }
        }

        if (bytes.isEmpty()) return null
        if (bytes.firstOrNull() == NEGATIVE) return null

        // The reply echoes the identifier it is answering, which is the only thing
        // separating the answer we asked for from one still arriving for the last one.
        val wanted = identifier.uppercase().chunked(2).mapNotNull { it.toIntOrNull(16) }
        if (wanted.isEmpty()) return null
        val expected = listOf(POSITIVE) + wanted.drop(1)
        val start = bytes.indexOfSublist(expected)
        if (start < 0) return null

        return bytes.drop(start + expected.size).toIntArray()
    }

    private fun List<Int>.indexOfSublist(needle: List<Int>): Int {
        if (needle.isEmpty() || needle.size > size) return -1
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}

/** Reads [count] bytes big-endian starting at [at], or null if the payload is short. */
fun IntArray.uintAt(at: Int, count: Int = 1): Int? {
    if (at < 0 || at + count > size) return null
    var value = 0
    for (i in 0 until count) value = (value shl 8) or this[at + i]
    return value
}

/** The same, read as two's complement — battery current is negative while charging. */
fun IntArray.intAt(at: Int, count: Int = 1): Int? {
    val raw = uintAt(at, count) ?: return null
    val bits = count * 8
    val sign = 1 shl (bits - 1)
    return if (raw and sign != 0) raw - (1 shl bits) else raw
}
