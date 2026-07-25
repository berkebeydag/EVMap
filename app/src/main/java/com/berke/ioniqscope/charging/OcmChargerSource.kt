package com.berke.ioniqscope.charging

import com.berke.ioniqscope.data.ChargingStationEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Charging stations from Open Charge Map.
 *
 * EV-specific, so its connector and power data is considerably better than raw
 * OSM. It needs an API key, which is free but has to be registered by the user —
 * the app cannot and does not create an account on anyone's behalf. Paste the key
 * in Settings and this source turns itself on.
 *
 * Data is licensed CC-BY-SA / ODbL depending on contribution; attribution is shown
 * on the map screen.
 */
class OcmChargerSource(
    private val apiKeyProvider: () -> String?,
    private val now: () -> Long = System::currentTimeMillis
) : ChargerSource {

    override val id = "ocm"
    override val displayName = "Open Charge Map (needs a free key)"
    override fun isAvailable() = !apiKeyProvider().isNullOrBlank()

    @Suppress("UNUSED_PARAMETER")
    override suspend fun fetch(box: BoundingBox): FetchResult {
        val key = apiKeyProvider()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No Open Charge Map API key set")

        // Asked for by country rather than by the viewport box.
        //
        // A box around Türkiye also returns Greece, Bulgaria, Cyprus and part of the
        // Caucasus, and asking per-viewport means the dataset is only ever as complete
        // as the places the user happened to pan over. One country request, measured
        // at about 2,100 stations, is both cheaper and complete.
        //
        // `compact`/`verbose=false` are deliberately not set: they replace the nested
        // ConnectionType, CurrentType and OperatorInfo objects with bare reference
        // ids, which the parser below reads as "not stated". That silently emptied
        // the operator and connector fields on every station.
        val url = "https://api.openchargemap.io/v3/poi/?output=json&countrycode=TR" +
            "&maxresults=$MAX_RESULTS"

        val body = Http.get(url, mapOf("X-API-Key" to key))
        val stations = parse(JSONArray(body))
        // A single request either returns the area or throws; if it comes back at
        // the cap, more exist than were served, so this is not the whole picture.
        return FetchResult(stations, complete = stations.size < MAX_RESULTS)
    }

    private fun parse(array: JSONArray): List<ChargingStationEntity> {
        val timestamp = now()
        val out = mutableListOf<ChargingStationEntity>()

        for (i in 0 until array.length()) {
            val poi = array.optJSONObject(i) ?: continue
            val address = poi.optJSONObject("AddressInfo") ?: continue
            val lat = address.optDouble("Latitude", Double.NaN)
            val lon = address.optDouble("Longitude", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue

            val connections = poi.optJSONArray("Connections")
            val connectorNames = mutableSetOf<String>()
            var maxKw: Double? = null
            var sawDc: Boolean? = null
            var sockets = 0

            if (connections != null) {
                for (c in 0 until connections.length()) {
                    val connection = connections.optJSONObject(c) ?: continue

                    connection.optJSONObject("ConnectionType")
                        ?.optString("Title")
                        ?.takeIf { it.isNotBlank() }
                        ?.let(connectorNames::add)

                    val kw = connection.optDouble("PowerKW", Double.NaN)
                    if (!kw.isNaN() && kw > 0) {
                        maxKw = maxOf(maxKw ?: 0.0, kw)
                    }

                    // CurrentType id 30 is DC in the OCM reference data; anything
                    // else known is AC. Unknown leaves the flag alone.
                    val currentTypeId = connection.optJSONObject("CurrentType")
                        ?.optInt("ID", -1)
                        ?: -1
                    when {
                        currentTypeId == OCM_CURRENT_TYPE_DC -> sawDc = true
                        currentTypeId > 0 && sawDc == null -> sawDc = false
                    }

                    // Quantity is how many identical sockets this entry stands for.
                    sockets += connection.optInt("Quantity", 1).coerceAtLeast(1)
                }
            }

            val sourceId = "ocm:${poi.optLong("ID")}"
            out += ChargingStationEntity(
                sourceId = sourceId,
                source = id,
                name = address.optString("Title").ifBlank { null },
                operator = poi.optJSONObject("OperatorInfo")
                    ?.optString("Title")
                    ?.ifBlank { null },
                lat = lat,
                lon = lon,
                connectors = connectorNames.takeIf { it.isNotEmpty() }?.joinToString(", "),
                maxPowerKw = maxKw,
                isDc = sawDc,
                address = listOfNotNull(
                    address.optString("AddressLine1").ifBlank { null },
                    address.optString("Town").ifBlank { null },
                    address.optString("StateOrProvince").ifBlank { null }
                ).takeIf { it.isNotEmpty() }?.joinToString(" "),
                chargePoints = sockets.takeIf { it in 1..PLAUSIBLE_MAX_CHARGE_POINTS },
                fetchedAtEpochMs = timestamp
            )
        }
        return out
    }

    private companion object {
        const val MAX_RESULTS = 10_000
        const val OCM_CURRENT_TYPE_DC = 30
    }
}
