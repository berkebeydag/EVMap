package com.berke.ioniqscope.charging

import android.content.Context
import com.berke.ioniqscope.BuildConfig
import com.berke.ioniqscope.data.ChargingStationDao
import com.berke.ioniqscope.data.ChargingStationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Loads the charging stations bundled with the app.
 *
 * Making the user find a refresh button before the map shows anything is a bad
 * first launch: the screen that is supposed to help you find a charger starts out
 * empty, and it needs a connection to stop being empty — which is exactly what you
 * may not have. Shipping the dataset means the map is useful the moment the app
 * opens, offline, with no button to discover.
 *
 * The bundled file is built by `tools/build_bundle_from_sweep.py` from a full TomTom
 * sweep of Türkiye, already normalised: 16,104 stations, every one of them carrying
 * its power, its connector types and an address. The five-source merge it replaced
 * found 6,102 and could state the power on under a third of them; it is still built
 * by `tools/build_charger_bundle.py` and kept at `data/chargers_tr.merged.json`.
 *
 * Re-seeded whenever the installed build changes, not only when the table is empty.
 * Seeding once was silently wrong: every improvement to the dataset — a thousand
 * stations added, four spellings of one operator folded into one — reached only
 * people installing for the first time, while anyone who already had the app kept
 * the list they were first given and had no way to know it was stale.
 */
class ChargerSeeder(
    private val appContext: Context,
    private val dao: ChargingStationDao,
    private val scope: CoroutineScope
) {

    private val marks =
        appContext.getSharedPreferences("charger_seed", Context.MODE_PRIVATE)

    fun seedIfStale() {
        scope.launch {
            if (marks.getInt(KEY_SEEDED_BUILD, 0) == BuildConfig.VERSION_CODE) return@launch

            runCatching {
                withContext(Dispatchers.IO) {
                    val json = appContext.assets.open(ASSET).bufferedReader().use { it.readText() }
                    parse(json)
                }
            }.onSuccess { stations ->
                if (stations.isEmpty()) return@onSuccess
                val sources = stations.map { it.source }.distinct()

                // Replaced rather than merged: rows from an older bundle carry the
                // old identifiers and the old operator spellings, so upserting on top
                // of them would leave both versions on the map.
                //
                // What the *previous* bundle seeded has to go too, not just the
                // sources this one happens to contain. When the bundle moved from the
                // five-source merge to a TomTom sweep it stopped containing the source
                // names it used to, so nothing claimed the old rows and they simply
                // stayed: 16,104 new stations landed on top of 6,102 orphaned ones and
                // the app reported 22,206. Every station in the country appeared to
                // have acquired a duplicate.
                //
                // Which means the last seed has to say what it put there. Installs
                // predating that record fall back to the sources the merge used, which
                // is what every such install has.
                val previous = marks.getStringSet(KEY_SEEDED_SOURCES, null) ?: LEGACY_SOURCES
                dao.replaceSources((previous + sources).distinct(), stations)

                marks.edit()
                    .putInt(KEY_SEEDED_BUILD, BuildConfig.VERSION_CODE)
                    .putStringSet(KEY_SEEDED_SOURCES, sources.toSet())
                    .apply()
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
                fetchedAtEpochMs = now,
                chargePoints = if (o.isNull("chargePoints")) null
                else o.optInt("chargePoints").takeIf { it > 0 }
            )
        }
        return out
    }

    /** `optString` turns a JSON null into the string "null"; this does not. */
    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private companion object {
        const val ASSET = "chargers_tr.json"
        const val KEY_SEEDED_BUILD = "seeded_build"
        const val KEY_SEEDED_SOURCES = "seeded_sources"

        /**
         * What the bundle contained before the seed started writing down what it put
         * there — the five sources of `build_charger_bundle.py`.
         *
         * Clearing these can take an OSM sync the user ran themselves down with it,
         * since the bundle's OSM rows and theirs are the same source under the same
         * ids and are genuinely indistinguishable. That costs them one tap of a free,
         * keyless source, against a duplicated map for everyone who does not.
         */
        val LEGACY_SOURCES = setOf("ocm", "zes", "trugo", "ibb", "osm")
    }
}
