package com.berke.ioniqscope.data

/**
 * Interpretation of the 12V trend.
 *
 * The voltage bands are the ordinary lead-acid open-circuit rule of thumb
 * (~12.6 V rested and full, ~12.2 V around half, below 12.0 V in trouble). They
 * are a guide, not a Hyundai specification, and they only mean anything on a
 * reading taken before the car has been driven — hence [AuxVoltageEntity.atSessionStart].
 */
enum class AuxBatteryStatus { Unknown, Good, Low, Critical }

data class AuxBatteryHealth(
    val status: AuxBatteryStatus,
    val latestVolts: Double?,
    val latestAtEpochMs: Long?,
    /** Volts per week, from session-start readings. Negative means it is fading. */
    val trendVoltsPerWeek: Double?,
    val sessionStartCount: Int
) {
    val isDeclining: Boolean
        get() = trendVoltsPerWeek != null && trendVoltsPerWeek < DECLINE_THRESHOLD

    companion object {
        const val GOOD_V = 12.4
        const val LOW_V = 12.2
        const val CRITICAL_V = 12.0

        /** Losing more than this per week is worth mentioning. */
        const val DECLINE_THRESHOLD = -0.05

        val empty = AuxBatteryHealth(AuxBatteryStatus.Unknown, null, null, null, 0)

        /**
         * @param sessionStarts rested-ish readings, oldest first.
         * @param latest the most recent reading of any kind.
         */
        fun evaluate(
            sessionStarts: List<AuxVoltageEntity>,
            latest: AuxVoltageEntity?
        ): AuxBatteryHealth {
            if (latest == null) return empty

            // Judge the level on a rested reading if we have one; a reading taken
            // mid-drive is sitting on the DC-DC converter's output, not the battery's.
            val judged = sessionStarts.lastOrNull() ?: latest
            val status = when {
                judged.volts < CRITICAL_V -> AuxBatteryStatus.Critical
                judged.volts < LOW_V -> AuxBatteryStatus.Low
                judged.volts < GOOD_V && sessionStarts.size >= 2 -> AuxBatteryStatus.Low
                else -> AuxBatteryStatus.Good
            }

            return AuxBatteryHealth(
                status = status,
                latestVolts = latest.volts,
                latestAtEpochMs = latest.atEpochMs,
                trendVoltsPerWeek = trend(sessionStarts),
                sessionStartCount = sessionStarts.size
            )
        }

        /** Least-squares slope over session-start readings, converted to volts/week. */
        private fun trend(samples: List<AuxVoltageEntity>): Double? {
            if (samples.size < MIN_SAMPLES_FOR_TREND) return null

            val week = 7.0 * 24 * 60 * 60 * 1000
            val t0 = samples.first().atEpochMs
            val xs = samples.map { (it.atEpochMs - t0) / week }
            val ys = samples.map { it.volts }

            val meanX = xs.average()
            val meanY = ys.average()
            var num = 0.0
            var den = 0.0
            for (i in xs.indices) {
                val dx = xs[i] - meanX
                num += dx * (ys[i] - meanY)
                den += dx * dx
            }
            // All readings on effectively the same day — no slope to speak of yet.
            if (den < 1e-9) return null
            return num / den
        }

        private const val MIN_SAMPLES_FOR_TREND = 4
    }
}
