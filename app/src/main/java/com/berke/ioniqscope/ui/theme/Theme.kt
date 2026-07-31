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
import androidx.compose.ui.text.font.FontFamily
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

private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 72.sp,
        lineHeight = 76.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 26.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.6.sp
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
