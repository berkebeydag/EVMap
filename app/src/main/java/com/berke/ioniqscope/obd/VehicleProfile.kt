package com.berke.ioniqscope.obd

/**
 * What a particular car can be asked beyond the standard OBD-II set.
 *
 * Standard PIDs are an ISO specification and work on anything with a socket, which is
 * why speed, the 12V rail and ambient temperature never needed a profile. Everything a
 * driver actually wants from an electric car — charge, health, pack voltage, how many
 * kW are going in — is manufacturer-specific: a different ECU address, a different
 * identifier, and a different byte at a different offset for every platform.
 *
 * So the car is a setting rather than an assumption. The app opens on a generic
 * profile that reads the standard set and nothing else, which is what every car
 * supports; picking a platform adds its battery readings on top.
 *
 * The offsets are the whole risk here. A wrong one does not fail — it produces a
 * number, and a plausible one, which is worse than an error. Every profile therefore
 * names its source, and nothing reaches the gauges until it has been checked against
 * what the car itself displays.
 */
data class VehicleProfile(
    val id: String,
    val name: String,
    /** Empty for cars whose extra readings are not known. */
    val battery: BatteryQueries?
) {
    /**
     * One ECU, one identifier, and where the values sit inside the answer.
     *
     * Grouped rather than listed one query per value because they arrive together: a
     * single read of 0x0101 carries the current, the pack voltage and the temperatures
     * at once, and asking three times for the same sixty bytes would treble the
     * traffic on a bus that answers slowly.
     */
    data class BatteryQueries(
        val ecuHeader: String,
        val reads: List<Read>
    )

    data class Read(
        val identifier: String,
        val values: List<Value>
    )

    data class Value(
        val key: String,
        val label: String,
        val unit: String,
        /** Byte offset into the payload that follows the echoed identifier. */
        val at: Int,
        val length: Int = 1,
        val signed: Boolean = false,
        val scale: Double = 1.0
    )

    companion object {
        /** What an unrecognised car gets: the standard set, and no invented extras. */
        val Generic = VehicleProfile(
            id = "generic",
            name = "Diğer / bilinmiyor",
            battery = null
        )

        /**
         * Hyundai and Kia's E-GMP platform — Ioniq 5, Ioniq 6, EV6, EV9.
         *
         * The battery ECU answers on 7E4 and the identifiers, offsets and scalings are
         * the ones the community settled on for the Ioniq 5 and published as a Torque
         * profile. They apply here because it is the same computer: the Ioniq 6 runs
         * the same platform, the same 77.4 kWh pack and the same BMS, which is what
         * the Ioniq forum says in as many words.
         *
         * Offsets are counted from the first byte after the echoed identifier, so
         * Torque's "a" is [at] 0. Where Torque writes `e/2` for the state of charge,
         * that is the fifth byte halved, which is offset 4 at a scale of 0.5.
         *
         * Source: github.com/Esprit1st/Hyundai-Ioniq-5-Torque-Pro-PIDs and the
         * "OBD2 PIDs for Ioniq 5/6" thread on ioniqforum.com.
         */
        val Egmp = VehicleProfile(
            id = "egmp",
            name = "Hyundai / Kia E-GMP (Ioniq 5-6, EV6)",
            battery = BatteryQueries(
                ecuHeader = "7E4",
                reads = listOf(
                    Read(
                        identifier = "220101",
                        values = listOf(
                            Value("soc_bms", "Şarj (BMS)", "%", at = 4, scale = 0.5),
                            Value("hv_current", "HV akım", "A", at = 10, length = 2,
                                signed = true, scale = 0.1),
                            Value("hv_voltage", "HV voltaj", "V", at = 12, length = 2,
                                scale = 0.1),
                            Value("batt_temp_max", "Batarya en yüksek", "°C", at = 14,
                                signed = true),
                            Value("batt_temp_min", "Batarya en düşük", "°C", at = 15,
                                signed = true),
                            Value("aux_voltage", "12V akü (BMS)", "V", at = 29, scale = 0.1)
                        )
                    ),
                    Read(
                        identifier = "220105",
                        values = listOf(
                            Value("soh", "Batarya sağlığı (SOH)", "%", at = 25, length = 2,
                                scale = 0.1),
                            Value("soc_display", "Şarj (gösterge)", "%", at = 31, scale = 0.5)
                        )
                    )
                )
            )
        )

        val all = listOf(Generic, Egmp)

        fun byId(id: String?): VehicleProfile =
            all.firstOrNull { it.id == id } ?: Generic
    }
}

/**
 * One pass over a profile's battery identifiers.
 *
 * [raw] is kept because [values] cannot be trusted on its own — see the note on
 * `readBattery`. A screen showing the numbers without the bytes behind them is asking
 * to be believed rather than checked.
 */
data class BatteryReading(
    val values: Map<String, Double>,
    val raw: String
)
