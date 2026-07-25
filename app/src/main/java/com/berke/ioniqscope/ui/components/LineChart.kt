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
    valueFormatter: (Double) -> String = { String.format(Locale.US, "%.1f", it) }
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

    Column(modifier) {
        // Stacked above and below the plot, not side by side: these are the vertical
        // extremes, and putting them left and right reads as a horizontal axis.
        Text(
            valueFormatter(dataMax),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .padding(vertical = 4.dp)
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
            }
        }

        Text(
            valueFormatter(dataMin),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
