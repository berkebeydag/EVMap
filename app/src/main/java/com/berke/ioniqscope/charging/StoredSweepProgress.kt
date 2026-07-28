package com.berke.ioniqscope.charging

import android.content.Context
import org.json.JSONArray

/**
 * Remembers where a TomTom sweep got to, across app restarts.
 *
 * A full sweep of Türkiye measured at over 1,200 requests against a free allowance
 * of 2,500 a month, and it will not always finish in one go — the allowance can run
 * out, the phone can lose signal, the user can close the app. Starting again from
 * the top each time would spend the following month re-fetching what is already on
 * the phone, which on a budget that tight means the sweep would never finish at all.
 *
 * Stored as plain JSON in the app's own preferences. Four numbers and a depth per
 * box, a few thousand of them at worst.
 */
class StoredSweepProgress(context: Context) : TomTomChargerSource.SweepProgress {

    private val prefs =
        context.getSharedPreferences("tomtom_sweep", Context.MODE_PRIVATE)

    override fun load(): List<Pair<BoundingBox, Int>> {
        val raw = prefs.getString(KEY_REMAINING, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONArray(i) ?: return@mapNotNull null
                if (o.length() < 5) return@mapNotNull null
                BoundingBox(
                    minLat = o.getDouble(0),
                    minLon = o.getDouble(1),
                    maxLat = o.getDouble(2),
                    maxLon = o.getDouble(3)
                ) to o.getInt(4)
            }
        }.getOrDefault(emptyList())
    }

    override fun save(remaining: List<Pair<BoundingBox, Int>>) {
        if (remaining.isEmpty()) {
            // Finished. Clearing rather than storing an empty list means the next run
            // starts a fresh sweep, which is what "do it again" should do.
            prefs.edit().remove(KEY_REMAINING).apply()
            return
        }
        val array = JSONArray()
        remaining.forEach { (box, depth) ->
            array.put(
                JSONArray().apply {
                    put(box.minLat); put(box.minLon)
                    put(box.maxLat); put(box.maxLon)
                    put(depth)
                }
            )
        }
        prefs.edit().putString(KEY_REMAINING, array.toString()).apply()
    }

    private companion object {
        const val KEY_REMAINING = "remaining_boxes"
    }
}
