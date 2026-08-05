package com.berke.ioniqscope.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged

/** One point on a chart. [x] is usually a timestamp, [y] the measured value. */
data class ChartPoint(val x: Double, val y: Double)

/**
 * Minimal line chart drawn straight onto a Canvas.
 *
 * Deliberately not a charting library: the app pulls in no third-party UI
 * dependencies, and a single polyline with a gradient fill is not worth one.
 *
 * @param reference optional horizontal marker (e.g. the 12V low-voltage threshold).
 */
@Composable
fun LineChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    reference: Double? = null,
    referenceColor: Color = MaterialTheme.colorScheme.error,
    valueFormatter: (Double) -> String = { String.format(Locale.US, "%.1f", it) },
    /**
     * How to write an x value. Null leaves the horizontal axis unlabelled, which is
     * right for a chart whose x is an index and wrong for one whose x is a clock.
     */
    xFormatter: ((Double) -> String)? = null
) {
    if (points.size < 2) {
        EmptyState("Grafik için henüz yeterli veri yok.", modifier)
        return
    }

    // Labels describe the data. The axis additionally has to make room for the
    // reference line, but showing the threshold as if it were a measurement would
    // misreport the range.
    val dataMin = points.minOf { it.y }
    val dataMax = points.maxOf { it.y }

    val minY = minOf(dataMin, reference ?: Double.MAX_VALUE)
    val maxY = maxOf(dataMax, reference ?: -Double.MAX_VALUE)
    // A flat series would otherwise divide by zero; give it a band to sit in.
    val span = (maxY - minY).takeIf { abs(it) > 1e-9 } ?: 1.0
    val padded = span * 0.1
    val lo = minY - padded
    val hi = maxY + padded

    val minX = points.minOf { it.x }
    val maxX = points.maxOf { it.x }
    val spanX = (maxX - minX).takeIf { abs(it) > 1e-9 } ?: 1.0

    // Where the finger is, or null. A chart of six hundred points can be read at a
    // glance for its shape and not at all for its numbers; touching it is how you ask
    // what the number was at that moment.
    var touchX by remember(points) { mutableStateOf<Float?>(null) }
    var plotWidth by remember { mutableIntStateOf(0) }

    val selected = touchX?.let { x ->
        val fraction = (x / plotWidth.coerceAtLeast(1)).coerceIn(0f, 1f)
        val target = minX + fraction * spanX
        points.minByOrNull { abs(it.x - target) }
    }

    Column(modifier) {
        // What the finger is on, above the chart where it does not cover anything.
        Text(
            selected?.let { point ->
                buildString {
                    append(valueFormatter(point.y))
                    xFormatter?.let { append("  ·  ").append(it(point.x)) }
                }
            } ?: valueFormatter(dataMax),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected != null) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Stacked above and below the plot, not side by side: these are the vertical
        // extremes, and putting them left and right reads as a horizontal axis.
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .padding(vertical = 4.dp)
                .onSizeChanged { plotWidth = it.width }
                .pointerInput(points) {
                    // Press and drag both scrub; letting go clears it, so the chart
                    // goes back to describing itself rather than one moment of itself.
                    detectDragGesturesAfterLongPress(
                        onDragStart = { touchX = it.x },
                        onDragEnd = { touchX = null },
                        onDragCancel = { touchX = null },
                        onDrag = { change, _ -> touchX = change.position.x }
                    )
                }
                .pointerInput(points) {
                    detectTapGestures(
                        onPress = {
                            touchX = it.x
                            tryAwaitRelease()
                            touchX = null
                        }
                    )
                }
        ) {
            Canvas(Modifier.fillMaxWidth().height(height)) {
                fun px(p: ChartPoint) = Offset(
                    x = (((p.x - minX) / spanX) * size.width).toFloat(),
                    y = (((hi - p.y) / (hi - lo)) * size.height).toFloat()
                )

                if (reference != null) {
                    val y = (((hi - reference) / (hi - lo)) * size.height).toFloat()
                    drawLine(
                        color = referenceColor.copy(alpha = 0.7f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
                    )
                }

                val offsets = points.map(::px)

                // Fill under the curve, so the shape reads at a glance.
                val fill = Path().apply {
                    moveTo(offsets.first().x, size.height)
                    offsets.forEach { lineTo(it.x, it.y) }
                    lineTo(offsets.last().x, size.height)
                    close()
                }
                drawPath(
                    path = fill,
                    brush = Brush.verticalGradient(
                        listOf(lineColor.copy(alpha = 0.28f), Color.Transparent)
                    )
                )

                val line = Path().apply {
                    moveTo(offsets.first().x, offsets.first().y)
                    offsets.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(path = line, color = lineColor, style = Stroke(width = 3f))

                // Mark the newest sample — on a trend chart that is the one that matters.
                drawCircle(color = lineColor, radius = 5f, center = offsets.last())

                selected?.let { point ->
                    val at = px(point)
                    drawLine(
                        color = lineColor.copy(alpha = 0.55f),
                        start = Offset(at.x, 0f),
                        end = Offset(at.x, size.height),
                        strokeWidth = 2f
                    )
                    drawCircle(color = lineColor, radius = 7f, center = at)
                }
            }
        }

        Row(Modifier.fillMaxWidth()) {
            Text(
                valueFormatter(dataMin),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            // The clock, at the two ends. A chart of a drive with no time on it says
            // what happened but never when, and "when" is half of reading one back.
            xFormatter?.let { format ->
                Text(
                    "${format(minX)} – ${format(maxX)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
