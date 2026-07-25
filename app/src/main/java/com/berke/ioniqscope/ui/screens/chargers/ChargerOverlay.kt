package com.berke.ioniqscope.ui.screens.chargers

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.view.MotionEvent
import org.osmdroid.util.BoundingBox
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
        val userRing: Int,
        val label: Int,
        val labelHalo: Int,
        val routeCasing: Int
    )

    var sites: List<ChargerSite> = emptyList()
        set(value) {
            field = value
            singles = emptyList()
            clusters = emptyList()
        }

    /** Where the user is, drawn distinctly from the stations. */
    var userLocation: Pair<Double, Double>? = null

    /** Routes to the nearest few sites, each in its own colour. Empty draws nothing. */
    var routes: List<DrawnRoute> = emptyList()

    class DrawnRoute(val points: List<Pair<Double, Double>>, val color: Int)

    /** Screen positions from the last draw, reused for hit-testing. */
    private var singles: List<Pair<Point, ChargerSite>> = emptyList()
    private var clusters: List<Cluster> = emptyList()

    /**
     * A drawn bubble: where it is on screen, and the ground it covers.
     *
     * The bounds are kept because tapping a bubble should open it up to show what is
     * inside, and the only way to frame that correctly is to zoom to the extent of
     * its members.
     */
    private class Cluster(
        val centre: Point,
        val count: Int,
        val operator: String?,
        val north: Double,
        val south: Double,
        val east: Double,
        val west: Double
    )

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

    /**
     * Operator names are drawn over map tiles, not over a flat surface, so they get
     * a halo. Without one a name crossing a road or a park boundary becomes
     * unreadable exactly where the map is busiest.
     */
    private val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10f * density
    }
    private val brandHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10f * density
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }

    private val routeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val routeCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val path = Path()
    private val reusablePoint = Point()
    private val takenLabels = ArrayList<RectF>()

    override fun draw(canvas: Canvas, projection: Projection) {
        drawRoutes(canvas, projection)
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
        val clusterTargets = mutableListOf<Cluster>()

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

                // Only when every member is the same network. A bubble covering a
                // ZES, a Trugo and an Eşarj has no single brand, and picking one of
                // them to print would be inventing information.
                val operators = bucket.mapNotNull { it.second.operator }.distinct()
                val unanimous = operators.singleOrNull()
                    ?.takeIf { bucket.all { site -> site.second.operator != null } }

                clusterTargets += Cluster(
                    centre = Point(cx, cy),
                    count = bucket.size,
                    operator = unanimous,
                    north = bucket.maxOf { it.second.lat },
                    south = bucket.minOf { it.second.lat },
                    east = bucket.maxOf { it.second.lon },
                    west = bucket.minOf { it.second.lon }
                )
            }
        }

        drawBrands(canvas, singleTargets, clusterTargets)

        singles = singleTargets
        clusters = clusterTargets
    }

    /**
     * Operator names, drawn after every marker so a name is never painted over by a
     * marker drawn later.
     *
     * Two limits keep this readable rather than a wall of text: a name is skipped if
     * it would overlap one already placed, and only so many are drawn at all. Both
     * mean the labels thin out on their own as you zoom out, with no per-zoom-level
     * tuning to get wrong.
     */
    private fun drawBrands(
        canvas: Canvas,
        singleTargets: List<Pair<Point, ChargerSite>>,
        clusterTargets: List<Cluster>
    ) {
        takenLabels.clear()
        var drawn = 0

        // Bubbles first: they stand for more places, so their name is worth more of
        // the limited room than any single marker's.
        for (cluster in clusterTargets) {
            if (drawn >= MAX_LABELS) return
            val name = cluster.operator ?: continue
            val radius = clusterRadius *
                (1f + (cluster.count.coerceAtMost(200) / 200f) * 0.6f)
            if (place(canvas, name, cluster.centre.x.toFloat(),
                    cluster.centre.y + radius + brand.textSize)) drawn++
        }

        for ((point, site) in singleTargets) {
            if (drawn >= MAX_LABELS) return
            val name = site.operator ?: site.name ?: continue
            if (place(canvas, name, point.x.toFloat(),
                    point.y + dotRadius + brand.textSize)) drawn++
        }
    }

    /** Draws [text] centred at the point if nothing is there already. */
    private fun place(canvas: Canvas, text: String, x: Float, y: Float): Boolean {
        val shown = if (text.length > MAX_LABEL_CHARS) {
            text.take(MAX_LABEL_CHARS - 1).trimEnd() + "…"
        } else text

        val halfWidth = brand.measureText(shown) / 2f
        val box = RectF(x - halfWidth, y - brand.textSize, x + halfWidth, y + brand.textSize / 3f)
        if (takenLabels.any { RectF.intersects(it, box) }) return false
        takenLabels += box

        brandHalo.color = colors.labelHalo
        canvas.drawText(shown, x, y, brandHalo)
        brand.color = colors.label
        canvas.drawText(shown, x, y, brand)
        return true
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
     * Routes to the nearest sites, under everything else so a marker is never hidden
     * by a line running through it.
     *
     * Each route is drawn twice: a dark casing, then the colour on top. That is what
     * every map app does, and the reason is that a plain coloured line disappears
     * wherever it crosses a road of a similar shade.
     *
     * Points that project within a couple of pixels of the previous one are skipped.
     * A country-scale route is several thousand coordinates and rebuilding the whole
     * path every frame would cost more than everything else on this overlay put
     * together, while looking identical.
     */
    private fun drawRoutes(canvas: Canvas, projection: Projection) {
        if (routes.isEmpty()) return

        for (route in routes) {
            path.rewind()
            var lastX = Float.NaN
            var lastY = Float.NaN
            var started = false

            for ((lat, lon) in route.points) {
                projection.toPixels(GeoPoint(lat, lon), reusablePoint)
                val x = reusablePoint.x.toFloat()
                val y = reusablePoint.y.toFloat()

                if (started &&
                    kotlin.math.abs(x - lastX) < ROUTE_MIN_STEP_PX &&
                    kotlin.math.abs(y - lastY) < ROUTE_MIN_STEP_PX
                ) continue

                if (started) path.lineTo(x, y) else path.moveTo(x, y)
                started = true
                lastX = x
                lastY = y
            }
            if (!started) continue

            routeCasing.color = colors.routeCasing
            canvas.drawPath(path, routeCasing)
            routeLine.color = route.color
            canvas.drawPath(path, routeLine)
        }
    }

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
     * A single site opens its details; a bubble opens itself up.
     *
     * Zooming to the bubble's own extent rather than by a fixed number of levels:
     * a fixed step overshoots a tight group into empty streets and undershoots a
     * loose one, so a tap often either went nowhere useful or had to be repeated,
     * and the combined pan-and-zoom animation osmdroid runs for it visibly snapped.
     * Framing the members instead lands in one move and always separates them.
     */
    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val threshold = 24f * density

        val site = singles.nearestWithin(e.x, e.y, threshold) { it.first }
        if (site != null) {
            onSelect(site.second)
            return true
        }

        val cluster = clusters.nearestWithin(e.x, e.y, clusterRadius * 1.6f) { it.centre }
            ?: return false

        val height = cluster.north - cluster.south
        val width = cluster.east - cluster.west
        if (height < DEGENERATE_SPAN_DEG && width < DEGENERATE_SPAN_DEG) {
            // Everything in the bubble is on one spot — there is no extent to frame,
            // so step in instead and let the next tap decide.
            mapView.controller.animateTo(
                GeoPoint(cluster.north, cluster.east),
                mapView.zoomLevelDouble + 2.0,
                CLUSTER_ZOOM_MS
            )
            return true
        }

        // Padded so the outermost members do not land against the screen edge.
        val pad = maxOf(height, width) * BOUNDS_PADDING
        mapView.zoomToBoundingBox(
            BoundingBox(
                cluster.north + pad, cluster.east + pad,
                cluster.south - pad, cluster.west - pad
            ),
            true,
            0,
            mapView.maxZoomLevel,
            CLUSTER_ZOOM_MS
        )
        return true
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
        const val BOUNDS_PADDING = 0.25
        /** Below this the members are effectively one point and have no extent. */
        const val DEGENERATE_SPAN_DEG = 1e-5
        const val MAX_LABELS = 40
        const val MAX_LABEL_CHARS = 16

        /** Below this a route point is visually identical to the last one. */
        const val ROUTE_MIN_STEP_PX = 2f
    }
}
