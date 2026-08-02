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
        /** Honest caveat shown in Settings; null means "the car's own answer decides". */
        val caveat: String? = null
    )

    /**
     * Every standard PID the app can decode.
     *
     * These used to be five, hand-picked, each with a written guess about whether an
     * EV would answer it. The guesses are gone: the car is asked at connect and its own
     * answer is what Settings shows, so the list can be the whole standard set without
     * filling the screen with things that will silently return nothing.
     */
    private val standard = StandardPids.all.map { Entry(it) }

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

    /**
     * Whether the connected car said it answers this PID.
     *
     * Null means nobody has asked yet — which is not the same as "no", and is drawn
     * differently for exactly that reason.
     */
    fun supportedBy(pid: Pid, reported: Set<String>): Boolean? {
        if (reported.isEmpty()) return null
        val number = StandardPids.numberOf(pid) ?: return null
        return number.toString() in reported
    }
}
