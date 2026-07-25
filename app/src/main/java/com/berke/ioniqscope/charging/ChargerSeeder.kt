package com.berke.ioniqscope.charging

import android.content.Context
import com.berke.ioniqscope.data.ChargingStationDao
import com.berke.ioniqscope.data.ChargingStationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Loads the charging stations bundled with the app the first time it runs.
 *
 * Making the user find a refresh button before the map shows anything is a bad
 * first launch: the screen that is supposed to help you find a charger starts out
 * empty, and it needs a connection to stop being empty — which is exactly what you
 * may not have. Shipping the dataset means the map is useful the moment the app
 * opens, offline, with no button to discover.
 *
 * The bundled file is built by `tools/build_charger_bundle.py`, which merges three
 * sources — no single one covers Türkiye — and writes them already normalised. It
 * is therefore no longer a raw Overpass response and is parsed here rather than by
 * [OsmChargerSource]. Refreshing later replaces it from whichever single source the
 * user picks.
 */
class ChargerSeeder(
    private val appContext: Context,
    private val dao: ChargingStationDao,
    private val scope: CoroutineScope
) {

    fun seedIfEmpty() {
        scope.launch {
            // Only ever seeds an empty table: a user who has refreshed, or who
            // deliberately cleared the data, must not have it reappear underneath them.
            if (dao.observeCount().first() > 0) return@launch

            runCatching {
                withContext(Dispatchers.IO) {
                    val json = appContext.assets.open(ASSET).bufferedReader().use { it.readText() }
                    parse(json)
                }
            }.onSuccess { stations ->
                if (stations.isNotEmpty()) dao.upsertAll(stations)
            }
        }
    }

    private fun parse(json: String): List<ChargingStationEntity> {
        val array = JSONObject(json).optJSONArray("stations") ?: return emptyList()
        val now = System.currentTimeMillis()
        val out = ArrayList<ChargingStationEntity>(array.length())

        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            // A row with no coordinate cannot be placed on a map; skipping it is
            // the only honest option, and the builder should never emit one.
            if (o.isNull("lat") || o.isNull("lon")) continue
            val sourceId = o.optStringOrNull("sourceId") ?: continue

            out += ChargingStationEntity(
                sourceId = sourceId,
                source = o.optString("source", "bundled"),
                name = o.optStringOrNull("name"),
                operator = o.optStringOrNull("operator"),
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
                connectors = o.optStringOrNull("connectors"),
                // Absent means unknown, which stays null — never a plausible zero.
                maxPowerKw = if (o.isNull("maxPowerKw")) null else o.optDouble("maxPowerKw")
                    .takeIf { !it.isNaN() },
                isDc = if (o.isNull("isDc")) null else o.optBoolean("isDc"),
                address = o.optStringOrNull("address"),
                chargePoints = if (o.isNull("chargePoints")) null
                else o.optInt("chargePoints").takeIf { it > 0 },
                fetchedAtEpochMs = now
            )
        }
        return out
    }

    /** `optString` turns a JSON null into the string "null"; this does not. */
    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private companion object {
        const val ASSET = "chargers_tr.json"
    }
}
