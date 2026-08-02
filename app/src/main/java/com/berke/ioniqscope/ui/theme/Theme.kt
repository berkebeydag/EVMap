package com.berke.ioniqscope.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.berke.ioniqscope.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Taken from the app mark: the teal of the pin, the slate navy of the bolt and the
// road, and the green of the verge. Amber and red are left alone — they are the fault
// colours and mean one thing regardless of what the rest of the app is wearing.
//
// The blue-violet cyan and magenta this replaced were a generic instrument-cluster
// pairing that belonged to nothing in particular; a magenta timer next to a teal pin
// read as two apps sharing a screen.
private val Teal = Color(0xFF22C1D6)
private val TealDim = Color(0xFF0E8FA0)
private val TealDeep = Color(0xFF0B6B78)
private val Verge = Color(0xFF6DBE4B)
private val Amber = Color(0xFFFFB74D)

// Surfaces are the bolt's slate navy taken down to backgrounds, so the mark sits on
// the app rather than on top of it. Warmer and greener than the near-black they
// replaced, which was a blue the logo does not contain.
private val Surface0 = Color(0xFF0A161D)
private val Surface1 = Color(0xFF10222C)
private val Surface2 = Color(0xFF1A303C)
private val OnSurface = Color(0xFFE6F2F5)
private val OnSurfaceMuted = Color(0xFF93AAB4)
private val Danger = Color(0xFFFF6B6B)

/** Connection-status dot colours. Kept outside the scheme — these mean one thing only. */
val StatusGreen = Color(0xFF6DBE4B)
val StatusAmber = Color(0xFFFFB74D)

private val DarkColors = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF00232A),
    primaryContainer = TealDim,
    onPrimaryContainer = Color(0xFF02141A),
    secondary = Verge,
    onSecondary = Color(0xFF0C2408),
    // The selected tab's pill. Left unset it fell back to Material's baseline purple,
    // which is how a lilac lozenge ended up under a teal-and-green app.
    secondaryContainer = Color(0xFF1D3A15),
    onSecondaryContainer = Color(0xFFC3E8B0),
    tertiary = Amber,
    onTertiary = Color(0xFF2B1A00),
    tertiaryContainer = Color(0xFF3A2A00),
    onTertiaryContainer = Color(0xFFFFDDA6),
    background = Surface0,
    onBackground = OnSurface,
    surface = Surface0,
    onSurface = OnSurface,
    surfaceVariant = Surface2,
    onSurfaceVariant = OnSurfaceMuted,
    // Every step of the container ramp, so nothing anywhere falls through to the
    // baseline scheme. One unset role is enough to put a stray hue on screen.
    surfaceContainerLowest = Color(0xFF060F14),
    surfaceContainerLow = Color(0xFF0D1C24),
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Color(0xFF223B49),
    inverseSurface = OnSurface,
    inverseOnSurface = Surface0,
    inversePrimary = TealDeep,
    outline = Color(0xFF35525E),
    outlineVariant = Color(0xFF223A45),
    error = Danger,
    onError = Color(0xFF2B0000),
    errorContainer = Color(0xFF4A1512),
    onErrorContainer = Color(0xFFFFDAD6)
)

// Light scheme exists so the app is usable in daylight, but dark is the default
// and the one the layout is tuned for.
private val LightColors = lightColorScheme(
    primary = TealDeep,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB5EAF1),
    onPrimaryContainer = Color(0xFF00272E),
    secondary = Color(0xFF3F7A2A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCEDBB),
    onSecondaryContainer = Color(0xFF11290A),
    tertiary = Color(0xFF7A5900),
    background = Color(0xFFF7FBFC),
    surface = Color(0xFFF7FBFC),
    error = Color(0xFFBA1A1A)
)

/**
 * The design's own typeface, bundled rather than fetched.
 *
 * Plus Jakarta Sans, which is what the design is set in. Carried in the APK — 324 KB
 * across five weights — because a downloadable font arrives after the first frame, and
 * a dashboard that reflows a second after it opens is worse than one that never
 * changed. It is under the SIL Open Font Licence, so shipping it is the intended use.
 *
 * Five weights, not two. The design leans on the difference between 600 and 700 and
 * again between 700 and 800 to separate a label from a value from a heading, and
 * synthesising those from one weight is what makes text look approximately right and
 * never quite right.
 */
private val Jakarta = FontFamily(
    Font(R.font.jakarta_400, FontWeight.Normal),
    Font(R.font.jakarta_500, FontWeight.Medium),
    Font(R.font.jakarta_600, FontWeight.SemiBold),
    Font(R.font.jakarta_700, FontWeight.Bold),
    Font(R.font.jakarta_800, FontWeight.ExtraBold)
)

/**
 * The scale, taken from the design rather than from Material's defaults.
 *
 * Material's own scale is built for a different density of information: its bodyMedium
 * is 14sp where this design sets its card text at 12.5, and its titles run larger and
 * lighter than these do. Left at the defaults, every screen came out roomier and
 * softer than the thing it was copying, which is most of why they did not look alike
 * even once the colours matched.
 *
 * Sizes are in sp so they still answer to the system font setting; only the relations
 * between them are fixed.
 */
private val AppTypography = Typography(
    // The dial's readout. Tight tracking, because at this size default spacing pulls
    // three digits apart into three separate things.
    displayLarge = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.Bold,
        fontSize = 60.sp,
        lineHeight = 62.sp,
        letterSpacing = (-3).sp
    ),
    // A screen's own name.
    headlineMedium = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 21.sp,
        letterSpacing = (-0.3).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp
    ),
    // A sheet's heading.
    titleMedium = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 18.sp,
        letterSpacing = (-0.2).sp
    ),
    // A card's heading, and the value on a tile.
    titleSmall = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.Normal,
        fontSize = 14.5.sp,
        lineHeight = 20.sp
    ),
    // What a card says about itself.
    bodyMedium = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 17.sp
    ),
    // Buttons and the pills along a header.
    labelLarge = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 13.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.Bold,
        fontSize = 12.5.sp
    ),
    // Chips, counts, and anything set in capitals — which gets the tracking that
    // makes capitals readable rather than the tracking that suits lower case.
    labelSmall = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 0.4.sp
    )
)

@Composable
fun IoniqScopeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val context = LocalContext.current

    SideEffect {
        (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content
    )
}
