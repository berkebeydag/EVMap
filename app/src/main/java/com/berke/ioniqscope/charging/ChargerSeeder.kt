package com.berke.ioniqscope.charging

import android.content.Context
import com.berke.ioniqscope.data.ChargingStationDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loads the charging stations bundled with the app the first time it runs.
 *
 * Making the user find a refresh button before the map shows anything is a bad
 * first launch: the screen that is supposed to help you find a charger starts out
 * empty, and it needs a connection to stop being empty — which is exactly what you
 * may not have. Shipping the dataset means the map is useful the moment the app
 * opens, offline, with no button to discover.
 *
 * The bundled file is an Overpass response parsed by [OsmChargerSource] itself, so
 * the seed and a live refresh cannot drift apart. Refreshing later replaces it.
 */
class ChargerSeeder(
    private val appContext: Context,
    private val dao: ChargingStationDao,
    private val source: OsmChargerSource,
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
                    source.parseBundled(json)
                }
            }.onSuccess { stations ->
                if (stations.isNotEmpty()) dao.upsertAll(stations)
            }
        }
    }

    private companion object {
        const val ASSET = "chargers_tr.json"
    }
}
