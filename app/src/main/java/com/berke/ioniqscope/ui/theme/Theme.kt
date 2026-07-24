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

// Instrument-cluster palette: near-black background, cyan accent for live data,
// magenta for the performance timer, amber/red reserved for faults.
private val Cyan = Color(0xFF4CC9F0)
private val CyanDim = Color(0xFF2A9DBE)
private val Magenta = Color(0xFFF72585)
private val Amber = Color(0xFFFFB74D)
private val Surface0 = Color(0xFF0B1220)
private val Surface1 = Color(0xFF131C2B)
private val Surface2 = Color(0xFF1C2739)
private val OnSurface = Color(0xFFE8F1FF)
private val OnSurfaceMuted = Color(0xFF9AAAC4)
private val Danger = Color(0xFFFF6B6B)

/** Connection-status dot colours. Kept outside the scheme — these mean one thing only. */
val StatusGreen = Color(0xFF34D399)
val StatusAmber = Color(0xFFFFB74D)

private val DarkColors = darkColorScheme(
    primary = Cyan,
    onPrimary = Color(0xFF00232F),
    primaryContainer = CyanDim,
    onPrimaryContainer = Color(0xFF02141B),
    secondary = Magenta,
    onSecondary = Color(0xFF2B0013),
    tertiary = Amber,
    onTertiary = Color(0xFF2B1A00),
    background = Surface0,
    onBackground = OnSurface,
    surface = Surface0,
    onSurface = OnSurface,
    surfaceVariant = Surface2,
    onSurfaceVariant = OnSurfaceMuted,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    outline = Color(0xFF3A4A63),
    outlineVariant = Color(0xFF27334A),
    error = Danger,
    onError = Color(0xFF2B0000)
)

// Light scheme exists so the app is usable in daylight, but dark is the default
// and the one the layout is tuned for.
private val LightColors = lightColorScheme(
    primary = Color(0xFF00658F),
    secondary = Color(0xFFB0005E),
    tertiary = Color(0xFF7A5900),
    background = Color(0xFFFBFCFF),
    surface = Color(0xFFFBFCFF),
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
