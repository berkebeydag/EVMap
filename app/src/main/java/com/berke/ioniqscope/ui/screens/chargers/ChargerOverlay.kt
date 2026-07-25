package com.berke.ioniqscope.ui.screens.chargers

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Draws every site in one overlay instead of one [org.osmdroid.views.overlay.Marker]
 * per site.
 *
 * Several thousand Marker objects is several thousand overlays for osmdroid to
 * iterate, hit-test and lay out on every frame; this is a single pass over an array
 * with one projection call each, which is what makes panning stay smooth at country
 * zoom.
 *
 * Sites within [cellPx] of each other are merged into a numbered bubble. The number
 * is how many *places* are in there — the sockets at a single place were already
 * folded together by [groupIntoSites], so a bubble reading 12 means twelve car parks,
 * not twelve plugs.
 */
class ChargerOverlay(
    private val colors: Colors,
    private val density: Float,
    private val onSelect: (ChargerSite) -> Unit
) : Overlay() {

    /** Theme colours, passed in so the overlay does not reach into Compose. */
    data class Colors(
        val dc: Int,
        val ac: Int,
        val unknown: Int,
        val cluster: Int,
        val clusterText: Int,
        val outline: Int,
        val user: Int,
        val userRing: Int
    )

    var sites: List<ChargerSite> = emptyList()
        set(value) {
            field = value
            singles = emptyList()
            clusters = emptyList()
        }

    /** Where the user is, drawn distinctly from the stations. */
    var userLocation: Pair<Double, Double>? = null

    /** Screen positions from the last draw, reused for hit-testing. */
    private var singles: List<Pair<Point, ChargerSite>> = emptyList()
    private var clusters: List<Point> = emptyList()

    private val cellPx = 56f * density
    private val dotRadius = 5.5f * density
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
        drawUser(canvas, projection)
        if (sites.isEmpty()) return

        val width = canvas.width
        val height = canvas.height
        // One whole cell of slack: a bubble sitting on the edge of the screen has
        // members just past it, and dropping those was what made the number on an
        // edge bubble change — or the bubble vanish — as the map was dragged.
        val margin = cellPx + clusterRadius * 2f

        val pixelsPerDegree = pixelsPerDegree(projection, width)
        val buckets = HashMap<Long, MutableList<Pair<Point, ChargerSite>>>()

        for (site in sites) {
            projection.toPixels(GeoPoint(site.lat, site.lon), reusablePoint)
            val x = reusablePoint.x
            val y = reusablePoint.y
            if (x < -margin || y < -margin || x > width + margin || y > height + margin) continue

            buckets.getOrPut(cellKey(site, pixelsPerDegree, x, y)) { mutableListOf() }
                .add(Point(x, y) to site)
        }

        val singleTargets = mutableListOf<Pair<Point, ChargerSite>>()
        val clusterTargets = mutableListOf<Point>()

        for (bucket in buckets.values) {
            if (bucket.size == 1) {
                val (point, site) = bucket.first()
                fill.color = when (site.isDc) {
                    true -> colors.dc
                    false -> colors.ac
                    null -> colors.unknown
                }
                canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), dotRadius, fill)
                stroke.color = colors.outline
                canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), dotRadius, stroke)
                singleTargets += point to site
            } else {
                // Centroid keeps the bubble over the group rather than on one member.
                val cx = bucket.sumOf { it.first.x } / bucket.size
                val cy = bucket.sumOf { it.first.y } / bucket.size
                // Grows a little with size so a bubble of 200 reads bigger than one of 3.
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
                clusterTargets += Point(cx, cy)
            }
        }

        singles = singleTargets
        clusters = clusterTargets
    }

    /**
     * Which cluster cell a site belongs to.
     *
     * Deliberately computed from the site's position *on the map* rather than on the
     * screen. Bucketing by screen pixel re-cuts the grid every time the map is
     * dragged, so the same two sites fall in one cell at one scroll offset and two
     * cells a few pixels later: bubbles merged, split, jumped and swapped numbers
     * while the map moved. Map coordinates do not change when you pan, so a bubble
     * now stays exactly where it was and only re-forms on zoom, which is when it
     * should.
     *
     * Web Mercator is used for the vertical axis because it is what the tiles are
     * drawn in, and it is linear in pixels — plain latitude is not, so a grid built
     * on it would be coarser in the north than in the south.
     */
    private fun cellKey(site: ChargerSite, pixelsPerDegree: Double, x: Int, y: Int): Long {
        if (pixelsPerDegree <= 0.0 || !pixelsPerDegree.isFinite()) {
            // Fall back to the screen grid rather than dropping the site.
            return pack((x / cellPx).toInt(), (y / cellPx).toInt())
        }
        val worldX = site.lon * pixelsPerDegree
        val worldY = mercatorDegrees(site.lat) * pixelsPerDegree
        return pack(
            floor(worldX / cellPx).toInt(),
            floor(worldY / cellPx).toInt()
        )
    }

    /**
     * Screen pixels per degree of longitude at the current zoom.
     *
     * Read off the projection rather than assumed from the tile size, so it stays
     * right whatever tile source is in use. The value depends only on zoom — the
     * span of longitude across the screen does not change as you pan — which is
     * what makes the grid above stable.
     */
    private fun pixelsPerDegree(projection: Projection, width: Int): Double {
        if (width <= 0) return 0.0
        val left = projection.fromPixels(0, 0)
        val right = projection.fromPixels(width, 0)
        var span = right.longitude - left.longitude
        if (span <= 0) span += 360.0          // viewport crossing the antimeridian
        if (span <= 0) return 0.0
        return width / span
    }

    private fun mercatorDegrees(latitude: Double): Double {
        val clamped = latitude.coerceIn(-85.05, 85.05)
        return Math.toDegrees(ln(tan(PI / 4 + Math.toRadians(clamped) / 2)))
    }

    private fun pack(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    /**
     * The "you are here" dot.
     *
     * Drawn before the stations so a charger you are standing next to is not
     * hidden underneath it, and given a halo plus a white ring so it stays
     * distinguishable from a station at any zoom.
     */
    private fun drawUser(canvas: Canvas, projection: Projection) {
        val (lat, lon) = userLocation ?: return
        projection.toPixels(GeoPoint(lat, lon), reusablePoint)
        val x = reusablePoint.x.toFloat()
        val y = reusablePoint.y.toFloat()

        fill.color = colors.user and 0x40FFFFFF.toInt()
        canvas.drawCircle(x, y, 14f * density, fill)

        fill.color = colors.userRing
        canvas.drawCircle(x, y, 7.5f * density, fill)

        fill.color = colors.user
        canvas.drawCircle(x, y, 5.5f * density, fill)
    }

    /**
     * A single site opens its details; a bubble zooms into it.
     *
     * Tapping a bubble used to open whichever member happened to be nearest its
     * centre, which is an arbitrary pick out of a group the user cannot see inside.
     * Zooming is the answer to "what is in there".
     */
    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val threshold = 24f * density

        val site = singles.nearestWithin(e.x, e.y, threshold) { it.first }
        if (site != null) {
            onSelect(site.second)
            return true
        }

        val cluster = clusters.nearestWithin(e.x, e.y, clusterRadius * 1.6f) { it }
        if (cluster != null) {
            // Rebuilt from the coordinates rather than cast: fromPixels is declared
            // to return IGeoPoint, and casting it would be a crash on tap the day
            // osmdroid returns anything else.
            val centre = mapView.projection.fromPixels(cluster.x, cluster.y)
            mapView.controller.animateTo(
                GeoPoint(centre.latitude, centre.longitude),
                mapView.zoomLevelDouble + 2.0,
                CLUSTER_ZOOM_MS
            )
            return true
        }
        return false
    }

    private fun <T> List<T>.nearestWithin(
        x: Float,
        y: Float,
        threshold: Float,
        point: (T) -> Point
    ): T? {
        val nearest = minByOrNull {
            val dx = point(it).x - x
            val dy = point(it).y - y
            dx * dx + dy * dy
        } ?: return null
        val dx = point(nearest).x - x
        val dy = point(nearest).y - y
        return if (dx * dx + dy * dy <= threshold * threshold) nearest else null
    }

    private companion object {
        const val CLUSTER_ZOOM_MS = 350L
    }
}
