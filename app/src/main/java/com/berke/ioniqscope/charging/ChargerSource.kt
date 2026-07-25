package com.berke.ioniqscope.charging

import com.berke.ioniqscope.data.ChargingStationEntity

/** Geographic window to fetch. */
data class BoundingBox(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double
) {
    companion object {
        /** Türkiye, generously bounded. Used for a whole-country refresh. */
        val TURKEY = BoundingBox(35.8, 25.6, 42.2, 44.9)
    }
}

/**
 * A provider of public charging-station locations.
 *
 * Deliberately an interface with more than one implementation, because no single
 * source is good enough on its own for Türkiye:
 *
 *  - OpenStreetMap is free and needs no account, but under-reports the big
 *    networks badly (ZES shows a fraction of its real footprint) and tags DC
 *    charging on only a small minority of entries.
 *  - Open Charge Map is EV-specific and much better on connector and power data,
 *    but needs a free API key the user has to register for themselves.
 *  - EPDK publishes the authoritative national list, since every licensed operator
 *    must report to it. No public documented endpoint was found; if one surfaces,
 *    it drops in here as a third implementation with nothing else changing.
 *
 * TODO(epdk): add an EPDK-backed source if a public endpoint becomes available.
 */
/**
 * Result of a fetch.
 *
 * [complete] distinguishes "this is the whole area" from "this is what came back
 * before something failed". The difference matters: a complete fetch can replace
 * the cached set outright, a partial one must only add to it, or a flaky request
 * would quietly delete good data and leave the map emptier than before.
 */
data class FetchResult(
    val stations: List<ChargingStationEntity>,
    val complete: Boolean
)

interface ChargerSource {

    /** Stable identifier, also used as the `source` column value. */
    val id: String

    /** Shown in Settings. */
    val displayName: String

    /** True when the source is usable right now (e.g. a key has been supplied). */
    fun isAvailable(): Boolean

    /**
     * Fetches stations within [box].
     *
     * @throws Exception when nothing could be fetched at all; callers report it
     *         rather than silently showing a stale or empty map.
     */
    suspend fun fetch(box: BoundingBox): FetchResult
}
