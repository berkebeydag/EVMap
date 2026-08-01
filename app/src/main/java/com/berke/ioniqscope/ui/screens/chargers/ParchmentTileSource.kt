package com.berke.ioniqscope.ui.screens.chargers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.osmdroid.tileprovider.tilesource.XYTileSource
import java.io.InputStream

/**
 * Positron, repainted as parchment while each tile is decoded.
 *
 * A colour filter was tried first and cannot do this. It applies one linear transform
 * to every pixel, and the design asks for three different things at once: land warmed
 * to cream, water cooled to blue, and labels left a warm brown. Solving the first two
 * exactly gives blue a negative slope — which is what those opposite requirements
 * need — and every dark pixel on the tile, meaning all of the type, comes out a
 * saturated blue. Solving all of them together by least squares fits none of them:
 * water stays grey, which is the part that was complained about.
 *
 * So the recolour happens per pixel instead, where a decision can be made about what
 * each one *is* before deciding what it should become. Water is the only saturated
 * cyan on a Positron tile and parks the only green; everything else is neutral and
 * differs only in brightness, which is exactly what separates a label from a road from
 * open ground.
 *
 * Done once per tile as it is decoded, on osmdroid's own tile thread, and the result
 * is what gets cached — so a pan costs nothing and the work never touches a frame.
 */
class ParchmentTileSource(
    name: String,
    baseUrls: Array<String>,
    attribution: String
) : XYTileSource(name, 0, 20, 512, "@2x.png", baseUrls, attribution) {

    override fun getDrawable(aFilePath: String): Drawable? =
        repaint(super.getDrawable(aFilePath))

    override fun getDrawable(aFileInputStream: InputStream): Drawable? {
        // Decoded here rather than handed to super, because super returns a drawable
        // whose bitmap may be immutable and shared with the disk cache.
        val decoded = BitmapFactory.decodeStream(aFileInputStream) ?: return null
        return BitmapDrawable(null, recolour(decoded))
    }

    private fun repaint(drawable: Drawable?): Drawable? {
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return drawable
        return BitmapDrawable(null, recolour(bitmap))
    }

    private fun recolour(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val argb = pixels[i]
            val alpha = argb ushr 24
            if (alpha == 0) continue
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF

            val chroma = maxOf(r, g, b) - minOf(r, g, b)
            val out = when {
                // Type first, and told apart from water by how saturated it is rather
                // than by hue or brightness, which both overlap. Positron's labels are
                // #AFBAC2 over #73909C — chroma 19 and 41 — while its water sits at 8
                // to 13. The gap is clean and it is the only thing that separates them.
                chroma >= LABEL_CHROMA && b > r -> label(maxOf(r, g, b))
                near(r, g, b, WATER_SRC, WATER_TOLERANCE) -> WATER
                near(r, g, b, PARK_SRC, PARK_TOLERANCE) -> PARK
                // Everything else is neutral or type, and separated by brightness —
                // which is what tells a label from a road from open ground.
                else -> ramp[maxOf(r, g, b)]
            }
            pixels[i] = (alpha shl 24) or out
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * A label pixel in the design's colours, keeping its own weight.
     *
     * Positron draws type from a dark core out to a lighter body, and mapping the whole
     * range onto one colour would flatten the anti-aliasing and leave every name looking
     * bitmapped. The two ends map to the design's two; everything between follows.
     */
    private fun label(level: Int): Int {
        val t = ((level - LABEL_DARK) / (LABEL_LIGHT - LABEL_DARK).toFloat()).coerceIn(0f, 1f)
        return blend(0x5F5749, 0xA39A88, t)
    }

    /** True when a pixel is within the given tolerance of a Positron fill, per channel. */
    private fun near(r: Int, g: Int, b: Int, target: Triple<Int, Int, Int>, tolerance: Int) =
        Math.abs(r - target.first) <= tolerance &&
            Math.abs(g - target.second) <= tolerance &&
            Math.abs(b - target.third) <= tolerance

    private companion object {
        /**
         * Water and parks are matched against the colours Positron actually fills them
         * with, rather than by hue.
         *
         * Classifying by hue looked reasonable and was wrong: Positron sets its labels
         * in a blue-grey — #AFBAC2 for the body of the type, #73909C for its core —
         * which passes every test water does and is only separated from it by
         * brightness, and the two ranges overlap. Every street name on the map came out
         * the colour of the sea. Measured on a real tile, the fills are flat and
         * distinct, so proximity to them is exact where a hue test is a guess.
         */
        val WATER_SRC = Triple(0xD2, 0xDB, 0xDE)
        val PARK_SRC = Triple(0xEE, 0xF2, 0xEE)

        /**
         * Tight, and tighter for parks, because the fills are close together.
         *
         * Positron's park green is #EEF2EE and its land #FAFAF8 — eight to twelve
         * apart per channel. A tolerance wide enough to be generous swallowed the land
         * into the parks and turned the whole map green. Water sits further from
         * anything else and can afford a little more.
         */
        const val WATER_TOLERANCE = 10
        const val PARK_TOLERANCE = 6

        /** Above this a blue-grey pixel is type. Water never reaches it. */
        const val LABEL_CHROMA = 16

        /** The brightness of the darkest and lightest type Positron draws. */
        const val LABEL_DARK = 150
        const val LABEL_LIGHT = 200

        val WATER = 0xB9D9EB and 0xFFFFFF
        val PARK = 0xDCE8D0 and 0xFFFFFF

        /**
         * Brightness to parchment, precomputed for all 256 levels.
         *
         * The stops are placed where Positron actually puts things rather than spread
         * evenly, because it uses a narrow band near the top for almost everything: its
         * land is 250, its roads 253, and its type sits between 156 and 194. A straight
         * ramp across the whole range put all of that within a few shades of cream and
         * left the street names barely visible. These stops map each of those bands
         * onto the design's own colour for it, and keep the tile's ordering — a label
         * stays darker than the road it sits on, a road lighter than the ground beside.
         */
        val stops = arrayOf(
            0 to 0x4A4238,     // darker than anything Positron draws; an anchor
            160 to 0x8A8172,   // the core of the type
            200 to 0xA39A88,   // the body of the type
            235 to 0xE6DFCE,   // roads
            255 to 0xF6F1E6    // open ground
        )

        val ramp: IntArray = IntArray(256) { level ->
            val upper = stops.indexOfFirst { it.first >= level }.coerceAtLeast(1)
            val (lowLevel, lowColour) = stops[upper - 1]
            val (highLevel, highColour) = stops[upper]
            val span = (highLevel - lowLevel).toFloat()
            blend(lowColour, highColour, if (span <= 0f) 0f else (level - lowLevel) / span)
        }

        fun blend(from: Int, to: Int, t: Float): Int {
            fun mix(shift: Int): Int {
                val a = (from shr shift) and 0xFF
                val b = (to shr shift) and 0xFF
                return (a + (b - a) * t).toInt().coerceIn(0, 255)
            }
            return Color.rgb(mix(16), mix(8), mix(0))
        }
    }
}
