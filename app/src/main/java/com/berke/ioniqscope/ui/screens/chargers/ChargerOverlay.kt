package com.berke.ioniqscope.ui.screens.chargers

import android.graphics.Bitmap
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
 * Sites within [cellPx] of each other are merged into one dot with a count under it.
 * The count is how many *places* are in there — the sockets at a single place were
 * already folded together by [groupIntoSites], so a 12 means twelve car parks, not
 * twelve plugs.
 *
 * A site's colour is its network. That is what the colour is for, so AC-versus-DC
 * moves to dot size: a marker can only carry so many variables before it carries
 * none, and which network a charger belongs to decides whether you can use it at all.
 */
class ChargerOverlay(
    private val colors: Colors,
    private val brandColors: Map<String, Int>,
    private val density: Float,
    private val onSelect: (ChargerSite) -> Unit
) : Overlay() {

    /** Theme colours, passed in so the overlay does not reach into Compose. */
    data class Colors(
        /** Groups, which span several networks and so belong to none of them. */
        val cluster: Int,
        /** A network with no colour of its own, and sites with no operator at all. */
        val otherBrand: Int,
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

    class DrawnRoute(
        val points: List<Pair<Double, Double>>,
        val color: Int,
        /** Where the route is actually going, so the pin lands on the station. */
        val destinationLat: Double,
        val destinationLon: Double
    )

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
        val north: Double,
        val south: Double,
        val east: Double,
        val west: Double
    )

    /**
     * How close two sites have to be on screen before they are drawn as one bubble.
     *
     * Down from 56dp, which clustered far too eagerly: at street zoom, with whole
     * streets between them, two stations were still arriving as a numbered blob — and
     * a bubble reading "2" over a neighbourhood you can see every building in tells
     * you strictly less than two dots would. This is a hair over the widest marker, so
     * sites merge only when they would otherwise be drawn on top of each other, and
     * they separate roughly two zoom levels earlier than before.
     *
     * This is close to the floor. The widest marker is 13dp across, so below about
     * 16dp a "2" stops meaning "two sites near each other" and starts meaning "two
     * sites that would be drawn as one dot anyway" — at which point splitting them
     * shows the user the same single blob, minus the number telling them it is two.
     */
    private val cellPx = 16f * density
    private val dotRadius = 5f * density

    /** How long each chevron's tail is. Small enough to sit inside the line's width. */
    private val arrowSize = 3.4f * density
    private val arrowSpacingPx = 30f * density

    /**
     * Groups get a dot only a little larger than a site's.
     *
     * They used to be filled bubbles scaled by how many they held, which at country
     * zoom put a wall of large discs over the whole map and buried the coastline and
     * the road network under them. The number underneath carries the size
     * information; the marker does not have to shout it as well.
     */
    private val clusterRadius = 7f * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
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

    /**
     * Route width, cut back from 5dp.
     *
     * Four routes at 5dp with an 8dp casing is 52dp of paint over a city street grid,
     * and at that weight the lines stop reading as routes on a map and start reading
     * as a scribble over one — the street names and junctions they run along, which
     * are the thing that makes a route legible, disappear underneath them.
     */
    private val routeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val routeCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /**
     * The chevrons that say which way the route runs.
     *
     * Thin, round-capped and half transparent on purpose. A route with no direction on
     * it is ambiguous at both ends, and the usual fix — solid filled triangles — turns
     * the line into a row of arrowheads and costs more legibility than the ambiguity
     * did. Two short strokes at a quarter of the line's weight are enough to read the
     * direction from and quiet enough to ignore while reading anything else.
     */
    private val routeArrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xB8FFFFFF.toInt()
    }
    private val arrowPath = Path()
    private val pinPath = Path()

    /** Half the pin's height; the tip sits on the site and the body above it. */
    private val pinBody = 8f * density
    private val shadowDrop = 1.6f * density

    /**
     * The teardrop: a circle a body's height up plus a tail down to the tip. The
     * overlap is deliberate — one fill with non-zero winding makes them a single
     * silhouette with no seam where they meet.
     */
    /**
     * One rendered pin per colour and size. Bounded by the palette, not by the map:
     * twenty brands plus the neutral, times two sizes, is the whole cache.
     */
    private val pinCache = HashMap<Pair<Int, Boolean>, Bitmap>()

    /**
     * Sized against the markers: clearly the larger thing without burying them.
     *
     * Every site is a pin now, so a destination that was merely a pin no longer says
     * anything. Half again as wide is what separates "you are being sent here" from
     * "there is a charger here" once the shape is shared.
     */
    private val pinRadius = 11.5f * density
    private val pinHeight = 26f * density

    private val path = Path()
    private val reusablePoint = Point()
    private val takenLabels = ArrayList<RectF>()

    override fun draw(canvas: Canvas, projection: Projection) {
        drawRoutes(canvas, projection)
        drawUser(canvas, projection)
        if (sites.isEmpty()) return

        val width = canvas.width
        val height = canvas.height
        // Two cells of slack. One covers a group sitting on the screen edge whose
        // members are just past it. The second covers zooming: osmdroid animates a
        // zoom by drawing this overlay through a scaled canvas, so canvas.width stops
        // matching what is really on screen and a tight margin culls markers that are
        // still visible — which looked like markers flickering out mid-zoom.
        val margin = cellPx * 2f + clusterRadius * 2f

        val pixelsPerDegree = quantise(pixelsPerDegree(projection, width))
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
                drawSitePin(
                    canvas, point.x.toFloat(), point.y.toFloat(),
                    big = site.isDc == true, color = brandColor(site.operator)
                )
                singleTargets += point to site
            } else {
                // Centroid keeps the marker over the group rather than on one member.
                val cx = bucket.sumOf { it.first.x } / bucket.size
                val cy = bucket.sumOf { it.first.y } / bucket.size

                // A group spans several networks, so it gets the neutral colour: a
                // brand colour here would claim the whole group belongs to one.
                drawDot(canvas, cx.toFloat(), cy.toFloat(), clusterRadius, colors.cluster)

                clusterTargets += Cluster(
                    centre = Point(cx, cy),
                    count = bucket.size,
                    north = bucket.maxOf { it.second.lat },
                    south = bucket.minOf { it.second.lat },
                    east = bucket.maxOf { it.second.lon },
                    west = bucket.minOf { it.second.lon }
                )
            }
        }

        drawLabels(canvas, singleTargets, clusterTargets)
        // Last, over everything. A suggestion's end point is the one marker on this
        // screen the user is being pointed at, and drawn in with the rest it was a dot
        // among five hundred dots — indistinguishable from the stations it was being
        // recommended over.
        drawDestinations(canvas, projection)

        singles = singleTargets
        clusters = clusterTargets
    }

    /**
     * A pin at the end of each route, in that route's own colour.
     *
     * A pin rather than a bigger dot: the shape is what separates "this is where you
     * are being sent" from "this is a charging station", and the tip gives it a exact
     * point on the map, which a circle does not have. The colour is what ties it to
     * its line and to its row in the panel.
     */
    private fun drawDestinations(canvas: Canvas, projection: Projection) {
        for (route in routes) {
            projection.toPixels(
                GeoPoint(route.destinationLat, route.destinationLon), reusablePoint
            )
            val x = reusablePoint.x.toFloat()
            val y = reusablePoint.y.toFloat()
            if (x < -pinHeight || y < -pinHeight ||
                x > canvas.width + pinHeight || y > canvas.height + pinHeight
            ) continue

            val centreY = y - pinHeight
            pinPath.rewind()
            pinPath.addCircle(x, centreY, pinRadius, Path.Direction.CW)
            // The tail, from the circle's lower flanks down to the point. Overlapping
            // the circle is fine and deliberate — one fill, non-zero winding, so the
            // two shapes come out as one silhouette with no seam between them.
            pinPath.moveTo(x - pinRadius * 0.7f, centreY + pinRadius * 0.72f)
            pinPath.lineTo(x, y)
            pinPath.lineTo(x + pinRadius * 0.7f, centreY + pinRadius * 0.72f)
            pinPath.close()

            fill.color = route.color
            canvas.drawPath(pinPath, fill)
            stroke.color = colors.outline
            canvas.drawPath(pinPath, stroke)
            // A hole in the middle, so the pin reads as a pin at a glance rather than
            // as a coloured blob, and so the outline is not the only thing shaping it.
            fill.color = colors.outline
            canvas.drawCircle(x, centreY, pinRadius * 0.34f, fill)
        }
    }

    /** One dot with a ring, kept for the groups, which are not places. */
    private fun drawDot(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        fill.color = color
        canvas.drawCircle(x, y, radius, fill)
        stroke.color = colors.outline
        canvas.drawCircle(x, y, radius, stroke)
    }

    /**
     * A site, drawn as a pin rather than a dot.
     *
     * The dot was honest and flat: it marked a coordinate without saying it was a place
     * you go to. A teardrop has a tip, so it points at its own spot instead of covering
     * it, and it reads as somewhere on a map rather than as data plotted over one —
     * which is most of what makes the design's map look calmer than ours did.
     *
     * Drawn from a cached bitmap rather than a path. Measured: three paths per marker
     * — shadow, body, hole — through a scaled canvas took panning from 6% janky frames
     * to 66%, and the 99th percentile frame from 19 ms to 101 ms, because a scaled path
     * is re-tessellated every time it is drawn and there are hundreds on screen. A
     * bitmap is one blit, the tessellation happens once per colour, and there are only
     * about twenty colours in the whole palette.
     */
    private fun drawSitePin(canvas: Canvas, x: Float, y: Float, big: Boolean, color: Int) {
        val bitmap = pinCache.getOrPut(color to big) { buildPin(color, big) }
        canvas.drawBitmap(bitmap, x - bitmap.width / 2f, y - bitmap.height + shadowDrop, null)
    }

    /** One pin, rendered once. Tip at the bottom centre, body and shadow above it. */
    private fun buildPin(color: Int, big: Boolean): Bitmap {
        val body = if (big) pinBody * 1.15f else pinBody
        val width = Math.ceil((body * 2 + 2 * density).toDouble()).toInt()
        val height = Math.ceil((body * 2 + shadowDrop + 2 * density).toDouble()).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val into = Canvas(bitmap)

        val shape = Path().apply {
            addCircle(0f, -body, body, Path.Direction.CW)
            moveTo(-body * 0.68f, -body * 0.72f)
            lineTo(0f, 0f)
            lineTo(body * 0.68f, -body * 0.72f)
            close()
        }

        into.translate(width / 2f, height - 1f - shadowDrop)
        // A soft drop, as an offset copy: shadow layers fall off the hardware path.
        fill.color = SHADOW
        into.translate(0f, shadowDrop)
        into.drawPath(shape, fill)
        into.translate(0f, -shadowDrop)

        fill.color = color
        into.drawPath(shape, fill)
        // The hole reads as a socket at any size and keeps the silhouette from
        // becoming a solid blob when several sit together.
        fill.color = colors.outline
        into.drawCircle(0f, -body, body * 0.30f, fill)
        return bitmap
    }

    /**
     * Sites that can fast-charge are drawn slightly larger.
     *
     * Colour is spoken for by the network, and a marker can only carry so many
     * variables before it carries none. Size is the weaker channel, which suits the
     * weaker question: which network it is decides whether you have the app and the
     * subscription, AC-or-DC decides how long you sit there.
     */
    private fun radiusFor(site: ChargerSite): Float =
        if (site.isDc == true) dotRadius * 1.3f else dotRadius

    private fun brandColor(operator: String?): Int =
        operator?.let { brandColors[it] } ?: colors.otherBrand

    /**
     * Labels, drawn after every marker so a name is never painted over by a marker
     * drawn later.
     *
     * A group's number sits under its dot exactly as a network's name sits under
     * theirs, so the two read as one family rather than as a bubble and a pin. Groups
     * go first and are not subject to the label budget: a group dot with no number on
     * it says nothing at all, whereas an unlabelled site still shows its colour and
     * its position.
     *
     * Names are skipped where they would overlap one already placed, and capped in
     * number, so they thin out on their own as you zoom out with no per-zoom-level
     * tuning to get wrong.
     */
    private fun drawLabels(
        canvas: Canvas,
        singleTargets: List<Pair<Point, ChargerSite>>,
        clusterTargets: List<Cluster>
    ) {
        takenLabels.clear()

        for (cluster in clusterTargets) {
            place(
                canvas, cluster.count.toString(), cluster.centre.x.toFloat(),
                cluster.centre.y + clusterRadius + brand.textSize, bold = true
            )
        }

        var drawn = 0
        for ((point, site) in singleTargets) {
            if (drawn >= MAX_LABELS) return
            val name = labelFor(site) ?: continue
            if (place(canvas, name, point.x.toFloat(),
                    point.y + brand.textSize * 0.9f)) drawn++
        }
    }

    /**
     * What a marker says: the network, and how fast it charges.
     *
     * The power is the thing that decides whether a site is worth stopping at — 180 kW
     * and 11 kW are twenty minutes and a whole evening — and it was only visible after
     * tapping. Sites that never published a figure keep the bare name rather than
     * being given a made-up one.
     */
    private fun labelFor(site: ChargerSite): String? {
        val name = site.operator ?: site.name ?: return null
        val kw = site.maxPowerKw?.takeIf { it > 0 } ?: return name
        // Whole numbers for whole ratings: "22 kW", not "22.0 kW". The halves that do
        // occur (7.4, 3.7) are real and stay.
        val power = if (kw % 1.0 == 0.0) kw.toInt().toString() else String.format("%.1f", kw)
        return "$name $power kW"
    }

    /** Draws [text] centred at the point if nothing is there already. */
    private fun place(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        bold: Boolean = false
    ): Boolean {
        brand.isFakeBoldText = bold
        brandHalo.isFakeBoldText = bold
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
     * The scale the grid is cut at, snapped to whole zoom levels.
     *
     * osmdroid zooms continuously, and this overlay is redrawn at every intermediate
     * scale. Cutting the grid at the live scale meant it was re-cut on every frame of
     * a zoom, so for the whole length of the animation groups were visibly splitting,
     * merging and swapping numbers — a step change happening sixty times instead of
     * once. Snapping to whole levels means the grid changes twice on a two-level zoom,
     * which is the honest number of times the answer actually changes.
     *
     * Scale doubles per zoom level, so the snap is a power of two.
     */
    private fun quantise(pixelsPerDegree: Double): Double {
        if (pixelsPerDegree <= 0.0 || !pixelsPerDegree.isFinite()) return pixelsPerDegree
        val level = floor(ln(pixelsPerDegree) / LN_2)
        return Math.pow(2.0, level)
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
            arrowPath.rewind()
            var lastX = Float.NaN
            var lastY = Float.NaN
            var started = false
            // Distance walked since the last chevron, so they are spaced along the
            // road rather than one per coordinate — a motorway stretch is one long
            // segment and a roundabout is thirty short ones.
            var sinceArrow = arrowSpacingPx * 0.5f

            for ((lat, lon) in route.points) {
                projection.toPixels(GeoPoint(lat, lon), reusablePoint)
                val x = reusablePoint.x.toFloat()
                val y = reusablePoint.y.toFloat()

                if (started &&
                    kotlin.math.abs(x - lastX) < ROUTE_MIN_STEP_PX &&
                    kotlin.math.abs(y - lastY) < ROUTE_MIN_STEP_PX
                ) continue

                if (started) {
                    path.lineTo(x, y)
                    sinceArrow = appendArrows(lastX, lastY, x, y, sinceArrow)
                } else {
                    path.moveTo(x, y)
                }
                started = true
                lastX = x
                lastY = y
            }
            if (!started) continue

            routeCasing.color = colors.routeCasing
            canvas.drawPath(path, routeCasing)
            routeLine.color = route.color
            canvas.drawPath(path, routeLine)
            canvas.drawPath(arrowPath, routeArrow)
        }
    }

    /**
     * Chevrons along one segment, evenly spaced, pointing the way the route runs.
     *
     * Returns how far past the last chevron the segment ended, so the next segment
     * carries on from there and the spacing stays even across the whole route rather
     * than restarting at every coordinate.
     */
    private fun appendArrows(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        carried: Float
    ): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val length = kotlin.math.hypot(dx, dy)
        if (length < 0.001f) return carried

        val ux = dx / length
        val uy = dy / length
        // Perpendicular, for the two tails.
        val px = -uy
        val py = ux

        val spread = arrowSize * 0.62f
        var at = arrowSpacingPx - carried
        while (at <= length) {
            val tipX = x1 + ux * at
            val tipY = y1 + uy * at
            val backX = tipX - ux * arrowSize
            val backY = tipY - uy * arrowSize

            arrowPath.moveTo(backX + px * spread, backY + py * spread)
            arrowPath.lineTo(tipX, tipY)
            arrowPath.lineTo(backX - px * spread, backY - py * spread)

            at += arrowSpacingPx
        }
        // `at` now points past the end, one spacing beyond the last chevron drawn —
        // or, if none was, one spacing beyond where it would have gone. Either way
        // the gap left behind is what the next segment starts with.
        return length - (at - arrowSpacingPx)
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
        val LN_2 = ln(2.0)
        const val CLUSTER_ZOOM_MS = 350L
        const val BOUNDS_PADDING = 0.25
        /** Below this the members are effectively one point and have no extent. */
        const val DEGENERATE_SPAN_DEG = 1e-5
        const val MAX_LABELS = 40
        /** Up from 16, so the power is not what gets cut off the end of a name. */
        const val MAX_LABEL_CHARS = 22

        /** Below this a route point is visually identical to the last one. */
        const val ROUTE_MIN_STEP_PX = 2f

        /** The design's drop, soft enough to read as depth rather than as an edge. */
        const val SHADOW = 0x38000000
    }
}
