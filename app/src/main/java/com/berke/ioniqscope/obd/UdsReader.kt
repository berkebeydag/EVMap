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
        val byLine = framesFromLines(raw)
        val payload = extract(byLine, identifier)
        if (payload != null) return payload

        // Some adapters — the one measured here among them — return the whole
        // multi-frame answer as a single unbroken run of hex with no separator between
        // frames at all:
        //
        //   7EC103E620101EFFBE77EC21EF5200000000007EC22001212EA1F1D1E...
        //
        // Split on line breaks that is one line, and the first eight bytes of it are
        // the id and the first frame's header — so a line-based reader either sees
        // nonsense or, having dropped everything past eight bytes, sees the tail of
        // the last frame and nothing else. Every frame is exactly the same width,
        // though, so the run can be cut back into frames by measuring one.
        return extract(framesFromRun(raw), identifier)
    }

    /** The frames as the ELM printed them, one per line, when it prints lines at all. */
    private fun framesFromLines(raw: String): List<List<Int>> {
        val lines = raw.uppercase()
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != ">" }
        return framesOf(lines)
    }

    /**
     * The frames recovered from one unbroken run of hex.
     *
     * Only the shapes that can be checked are accepted: every frame carries the same
     * CAN id and the same width, so the run divides exactly by that width and each
     * block starts with that id. An 11-bit id makes a 19-character frame and a 29-bit
     * id a 24-character one — both because the ELM pads every CAN frame out to its
     * full eight bytes. If neither divides the run cleanly, this returns nothing
     * rather than guessing where the boundaries are.
     */
    private fun framesFromRun(raw: String): List<List<Int>> {
        val hex = raw.uppercase().filter { it.isDigit() || it in 'A'..'F' }
        for ((idChars, width) in listOf(3 to 19, 8 to 24)) {
            if (hex.length < width || hex.length % width != 0) continue
            val id = hex.take(idChars)
            val blocks = (hex.indices step width).map { hex.substring(it, it + width) }
            if (blocks.any { !it.startsWith(id) }) continue
            return framesOf(blocks.map { it.drop(idChars) })
        }
        return emptyList()
    }

    private fun framesOf(lines: List<String>): List<List<Int>> {
        val out = ArrayList<List<Int>>(lines.size)

        for (line in lines) {
            // "SEARCHING...", "NO DATA", "CAN ERROR" and the echoed command all reach
            // here; anything that is not hex is not an answer.
            var hex = line.filter { it.isDigit() || it in 'A'..'F' }

            // The 11-bit CAN id has to come off before anything counts bytes, because
            // it is three hex characters and three plus any number of byte pairs is
            // always odd. The parity check below therefore threw away every line the
            // ELM printed with headers on — which is every line, since the engine sets
            // ATH1. This reader never saw a single frame: a perfectly good
            // "7EC102E6201051FFB74" was discarded as malformed, and the screen said the
            // car had not answered with the identifier it had in fact answered with.
            if (hex.length % 2 == 1 && hex.length >= 3) hex = hex.drop(3)

            if (hex.length < 2 || hex.length % 2 != 0) continue
            var frame = hex.chunked(2).mapNotNull { it.toIntOrNull(16) }
            if (frame.isEmpty()) continue

            // A 29-bit id is eight hex characters, so it survives the parity check and
            // has to be dropped by length instead. A CAN frame is never more than eight
            // bytes, so anything beyond that is addressing.
            if (frame.size > 8) frame = frame.drop(frame.size - 8)
            out += frame
        }
        return out
    }

    /** Reassembles frames into the payload of [identifier], or null if it is not there. */
    private fun extract(frames: List<List<Int>>, identifier: String): IntArray? {
        val bytes = ArrayList<Int>(64)
        var seenFirstFrame = false

        for (frame in frames) {
            val pci = frame.first()
            when {
                // Single frame: the low nibble is the length.
                pci in 0x00..0x07 && !seenFirstFrame -> {
                    bytes += frame.drop(1).take(pci)
                    seenFirstFrame = true
                }
                // First frame of a multi-frame answer; the length spans two nibbles and
                // is not needed here, because the caller reads fixed offsets.
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
