package com.berke.ioniqscope.obd

/** What can be said about a fault code from its own structure, and nothing more. */
data class DtcMeaning(
    /** Powertrain, chassis, body or network. */
    val system: String,
    /** Whether a generic table can be trusted for it. */
    val scope: String,
    /** The one thing the range itself states, where a range states one. Often null. */
    val note: String? = null
)

/**
 * Reads a fault code's structure, which is standardised, and refuses to guess the rest.
 *
 * There is a table of SAE definitions and it is tempting. It is also wrong here. The
 * codes this car reports include P0243, whose SAE definition is a turbocharger
 * wastegate solenoid, on a vehicle with no turbocharger — Hyundai reuses the numbering
 * in an E-GMP context and the generic meaning does not survive the trip. Printing it
 * would be a confident, plausible, wrong sentence next to a code somebody is about to
 * spend money on.
 *
 * What is true regardless is the shape of the code, defined by SAE J2012: the letter
 * says which system reported it, and the first digit says whether anybody outside the
 * manufacturer defined what it means. That is worth saying, it is never wrong, and it
 * is more than "look it up" — a U code that is manufacturer-specific and a P code that
 * is SAE-standard call for different next steps.
 */
object DtcInfo {

    fun describe(code: String): DtcMeaning? {
        val text = code.trim().uppercase()
        if (text.length < 3) return null

        val system = when (text[0]) {
            'P' -> "Motor / güç aktarma"
            'C' -> "Şasi"
            'B' -> "Gövde"
            'U' -> "Ağ / iletişim"
            else -> return null
        }

        val standard = isStandard(text)
        val scope = if (standard) {
            "SAE standardı — genel tabloda karşılığı var"
        } else {
            "Üreticiye özel — anlamını yalnızca Hyundai tanımlar"
        }

        return DtcMeaning(system, scope, noteFor(text))
    }

    /**
     * Whether the code sits in a range somebody other than the manufacturer defined.
     *
     * P0 and P2 are SAE, P1 is the manufacturer's, and P3 is split down the middle:
     * P30xx-P33xx belong to the manufacturer and P34xx upwards to SAE. For C, B and U
     * only the 0 range is standard.
     */
    private fun isStandard(text: String): Boolean {
        val group = text.getOrNull(1) ?: return false
        return when (text[0]) {
            'P' -> when (group) {
                '0', '2' -> true
                '3' -> (text.getOrNull(2) ?: '0') >= '4'
                else -> false
            }
            else -> group == '0'
        }
    }

    /**
     * The few ranges that state their meaning as a range.
     *
     * Deliberately short. Each of these is defined at the level of the range itself, so
     * saying it does not require knowing the individual code — which is the line
     * between reading a code and guessing at it.
     */
    private fun noteFor(text: String): String? = when {
        text.startsWith("U0") ->
            "Bu aralık bir kontrol ünitesiyle iletişimin kesilmesini anlatır. " +
                "Çoğu zaman arızanın kendisi değil, arızalanan başka bir şeyin belirtisidir."
        text.startsWith("P0A") || text.startsWith("P0B") || text.startsWith("P0C") ->
            "Bu aralık hibrit ve elektrikli güç aktarma organları için ayrılmıştır."
        else -> null
    }
}
