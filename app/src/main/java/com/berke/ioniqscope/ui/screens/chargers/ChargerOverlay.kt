package com.berke.ioniqscope.ui.screens.chargers

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.view.MotionEvent
import com.berke.ioniqscope.data.ChargingStationEntity
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

/**
 * Draws every station in one overlay instead of one [org.osmdroid.views.overlay.Marker]
 * per station.
 *
 * Six hundred Marker objects is six hundred overlays for osmdroid to iterate,
 * hit-test and lay out on every frame; this is a single pass over an array with
 * one projection call each, which is what makes panning stay smooth at country zoom.
 *
 * Points that land within [cellPx] of each other on screen are merged into a
 * numbered cluster. Clustering in *screen* space rather than by geography means it
 * declusters naturally as you zoom in, with no per-zoom-level tuning.
 */
class ChargerOverlay(
    private val colors: Colors,
    private val density: Float,
    private val onTap: (ChargingStationEntity) -> Unit
) : Overlay() {

    /** Theme colours, passed in so the overlay does not reach into Compose. */
    data class Colors(
        val dc: Int,
        val ac: Int,
        val unknown: Int,
        val cluster: Int,
        val clusterText: Int,
        val outline: Int
    )

    var items: List<ChargerListItem> = emptyList()
        set(value) {
            field = value
            hitTargets = emptyList()
        }

    /** Screen positions from the last draw, reused for hit-testing. */
    private var hitTargets: List<Pair<Point, ChargingStationEntity>> = emptyList()

    private val cellPx = 56f * density
    private val dotRadius = 5f * density
    private val clusterRadius = 15f * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11f * density
        isFakeBoldText = true
    }

    private val reusablePoint = Point()

    override fun draw(canvas: Canvas, projection: Projection) {
        if (items.isEmpty()) return

        // Bucket by screen cell. Off-screen points are dropped before any drawing,
        // so a country-wide dataset costs one projection each and nothing more.
        val width = canvas.width
        val height = canvas.height
        val margin = clusterRadius * 2

        val buckets = HashMap<Long, MutableList<Pair<Point, ChargingStationEntity>>>()

        for (item in items) {
            val station = item.station
            projection.toPixels(GeoPoint(station.lat, station.lon), reusablePoint)
            val x = reusablePoint.x
            val y = reusablePoint.y
            if (x < -margin || y < -margin || x > width + margin || y > height + margin) continue

            val key = (x / cellPx).toInt().toLong() shl 32 or
                ((y / cellPx).toInt().toLong() and 0xFFFFFFFFL)
            buckets.getOrPut(key) { mutableListOf() }
                .add(Point(x, y) to station)
        }

        val targets = mutableListOf<Pair<Point, ChargingStationEntity>>()

        for (bucket in buckets.values) {
            if (bucket.size == 1) {
                val (point, station) = bucket.first()
                fill.color = when (station.isDc) {
                    true -> colors.dc
                    false -> colors.ac
                    null -> colors.unknown
                }
                canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), dotRadius, fill)
                stroke.color = colors.outline
                canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), dotRadius, stroke)
                targets += point to station
            } else {
                // Centroid keeps the bubble over the group rather than on one member.
                val cx = bucket.sumOf { it.first.x } / bucket.size
                val cy = bucket.sumOf { it.first.y } / bucket.size
                // Grows a little with size so a cluster of 200 reads bigger than one of 3.
                val radius = clusterRadius *
                    (1f + (bucket.size.coerceAtMost(200) / 200f) * 0.6f)

                fill.color = colors.cluster
                canvas.drawCircle(cx.toFloat(), cy.toFloat(), radius, fill)
                stroke.color = colors.outline
                canvas.drawCircle(cx.toFloat(), cy.toFloat(), radius, stroke)

                label.color = colors.clusterText
                // Baseline offset so the digits sit optically centred in the bubble.
                canvas.drawText(
                    bucket.size.toString(),
                    cx.toFloat(),
                    cy + label.textSize / 3f,
                    label
                )
                // A cluster resolves to its nearest member when tapped.
                targets += bucket.minByOrNull {
                    val dx = it.first.x - cx
                    val dy = it.first.y - cy
                    dx * dx + dy * dy
                }!!
            }
        }

        hitTargets = targets
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val threshold = 24f * density
        val nearest = hitTargets.minByOrNull { (point, _) ->
            val dx = point.x - e.x
            val dy = point.y - e.y
            dx * dx + dy * dy
        } ?: return false

        val dx = nearest.first.x - e.x
        val dy = nearest.first.y - e.y
        if (dx * dx + dy * dy > threshold * threshold) return false

        onTap(nearest.second)
        return true
    }
}
