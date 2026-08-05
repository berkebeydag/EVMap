package com.berke.ioniqscope.charging

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.cos

/** A province, district or neighbourhood the map can be sent to. */
data class Place(
    val name: String,
    /** "il", or the district and province it sits in. */
    val detail: String,
    val kind: String,
    val lat: Double,
    val lon: Double,
    /** How many stations are filed under it — the ranking, and worth showing. */
    val count: Int,
    /** Pre-folded name, matched against a folded query. */
    val key: String
)

/**
 * Somewhere to go, as opposed to something to plug into.
 *
 * The search box could only find stations, so planning a trip meant knowing the name of
 * a charger at the other end. A driver knows where they are going, not what is there —
 * that is the thing they are trying to find out.
 *
 * The places are derived from the stations' own addresses at build time, which gives
 * the list a property worth keeping: every place in it is somewhere the app can say
 * something about. There is no district here that would take you to an empty map.
 */
class PlaceIndex(private val context: Context) {

    @Volatile private var places: List<Place>? = null

    private suspend fun load(): List<Place> = places ?: withContext(Dispatchers.IO) {
        val loaded = runCatching {
            val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            val array = JSONObject(text).getJSONArray("places")
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Place(
                    name = o.getString("name"),
                    detail = o.optString("detail"),
                    kind = o.optString("kind"),
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                    count = o.optInt("count"),
                    key = o.optString("key")
                )
            }
        }.getOrDefault(emptyList())
        places = loaded
        loaded
    }

    /**
     * Places whose name matches [query], best first.
     *
     * A name that starts with what was typed beats one that merely contains it — "Bal"
     * should reach Balçova before Karabalçık. Among equals it is the nearest that wins,
     * not the one with the most chargers: Turkey has an Alsancak in Kayseri and one in
     * İzmir, and somebody standing in İzmir who types it means theirs. Ranking by
     * station count put Kayseri's seven above İzmir's one, seven hundred kilometres
     * away.
     *
     * With no position to measure from, the count decides — it is the only signal left,
     * and the bigger place is the one more people mean.
     */
    suspend fun search(
        query: String,
        near: Pair<Double, Double>? = null,
        limit: Int = 6
    ): List<Place> {
        val needle = fold(query)
        if (needle.length < MIN_QUERY) return emptyList()
        return load()
            .mapNotNull { place ->
                val at = place.key.indexOf(needle)
                if (at < 0) null else place to at
            }
            .sortedWith(
                if (near == null) compareBy({ it.second }, { -it.first.count })
                else compareBy({ it.second }, { roughDistance(near, it.first) })
            )
            .take(limit)
            .map { it.first }
    }

    /** Good enough to order by; nothing here needs a real great-circle distance. */
    private fun roughDistance(from: Pair<Double, Double>, place: Place): Double {
        val dLat = place.lat - from.first
        val dLon = (place.lon - from.second) * cos(Math.toRadians(from.first))
        return dLat * dLat + dLon * dLon
    }

    private companion object {
        const val ASSET = "places_tr.json"
        const val MIN_QUERY = 2

        /** The same folding the builder used: dotted i first, then accents away. */
        fun fold(text: String): String {
            val turkish = text.replace('I', 'ı').replace('İ', 'i').lowercase()
            return Normalizer.normalize(turkish, Normalizer.Form.NFD)
                .filter { it.code !in 0x0300..0x036F }
        }
    }
}
