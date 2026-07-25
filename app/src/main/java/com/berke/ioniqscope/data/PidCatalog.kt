package com.berke.ioniqscope.data

import com.berke.ioniqscope.obd.EgmpPids
import com.berke.ioniqscope.obd.Pid
import com.berke.ioniqscope.obd.StandardPids

/**
 * Everything the Dashboard is allowed to poll.
 *
 * Only PIDs that come from the vetted definitions in `ObdEngine.kt` appear here —
 * nothing is invented locally. [EgmpPids] is intentionally empty until verified
 * Ioniq 6 / E-GMP values are supplied, at which point its entries show up in this
 * list (and therefore in Settings) with no further wiring.
 */
object PidCatalog {

    data class Entry(
        val pid: Pid,
        /** Honest caveat shown in Settings; null means "expected to work". */
        val caveat: String? = null
    )

    private val standard = listOf(
        Entry(StandardPids.speed),
        Entry(StandardPids.moduleVolt),
        Entry(StandardPids.ambientTemp),
        Entry(
            StandardPids.rpm,
            "Ioniq 6'da içten yanmalı motor yok; bu PID genellikle desteklenmez."
        ),
        Entry(
            StandardPids.coolant,
            "Elektrikli araçta termal döngüler üreticiye özel PID'lerin arkasında; yanıt vermeyebilir."
        )
    )

    /** Standard PIDs plus whatever verified E-GMP PIDs have been added. */
    val all: List<Entry> get() = standard + EgmpPids.set.map { Entry(it) }

    val defaultKeys: Set<String> = StandardPids.defaultSet.map { it.key }.toSet()

    fun byKey(key: String): Pid? = all.firstOrNull { it.pid.key == key }?.pid

    /** Resolves stored keys to PIDs, preserving catalog order and dropping unknowns. */
    fun resolve(keys: Set<String>): List<Pid> =
        all.map { it.pid }.filter { it.key in keys }

    /**
     * The single PID the Performance screen polls. Kept here so there is exactly one
     * definition of "the speed PID" in the app.
     */
    val speed: Pid = StandardPids.speed
}
