package com.berke.ioniqscope.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** A single live reading. Value is deliberately the largest thing on the card. */
@Composable
fun GaugeCard(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    stale: Boolean = false,
    /**
     * How far along its range the value sits, for the ones that have a range.
     *
     * Null for the ones that do not, and drawn as nothing rather than as an empty bar:
     * a coolant temperature has no natural full, and a bar sitting at zero would be
     * read as a reading rather than as the absence of a scale.
     */
    fraction: Float? = null,
    /** A word under the value where a bar would say less — "Sağlıklı", say. */
    note: String? = null,
    noteColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier
    ) {
        Column(Modifier.padding(13.dp)) {
            Text(
                text = label.uppercase(textLocale()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (stale) MaterialTheme.colorScheme.outline else accent,
                    maxLines = 1
                )
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (fraction != null) {
                Box(
                    Modifier
                        .padding(top = 9.dp)
                        .fillMaxWidth()
                        .height(5.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            RoundedCornerShape(3.dp)
                        )
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(accent, RoundedCornerShape(3.dp))
                    )
                }
            } else if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = noteColor,
                    modifier = Modifier.padding(top = 9.dp)
                )
            }
        }
    }

}

/** Non-blocking inline message — info, warning or error depending on [tone]. */
enum class BannerTone { Info, Warning, Error, Success }

@Composable
fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.Info,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    // Composited over the surface rather than left translucent: a banner drawn on
    // top of the map would otherwise be unreadable against the tiles behind it.
    val container = when (tone) {
        BannerTone.Info -> scheme.surfaceContainerHigh
        BannerTone.Warning -> scheme.tertiary.copy(alpha = 0.16f).compositeOver(scheme.surface)
        BannerTone.Error -> scheme.error.copy(alpha = 0.16f).compositeOver(scheme.surface)
        BannerTone.Success -> scheme.primary.copy(alpha = 0.14f).compositeOver(scheme.surface)
    }
    val accent = when (tone) {
        BannerTone.Info -> scheme.onSurfaceVariant
        BannerTone.Warning -> scheme.tertiary
        BannerTone.Error -> scheme.error
        BannerTone.Success -> scheme.primary
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(Modifier.padding(14.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = accent
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface
            )
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                ) {
                    Text(actionLabel, color = accent)
                }
            }
        }
    }
}

/**
 * The active locale, read through Compose so a language change recomposes.
 * Matters here because Turkish uppercasing is not the same as root uppercasing.
 */
@Composable
private fun textLocale(): Locale =
    Locale.forLanguageTag(androidx.compose.ui.text.intl.Locale.current.toLanguageTag())

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    // In the accent rather than the muted grey, and tracked out.
    //
    // A section label sitting in the same colour as the text under it is not doing the
    // one job it has — saying where one group of settings stops and the next begins.
    // The design puts them in the accent, which is the only place in the app that
    // colour is used for something that is not interactive, and that is why it works:
    // nothing else on the screen looks like a heading.
    Text(
        text = text.uppercase(textLocale()),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 0.8.sp,
        modifier = modifier
    )
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(24.dp)
    )
}

// ------------------------------------------------------------------ formatting

/** "8.42 s" — the format the whole app uses for split times. */
fun formatSeconds(ms: Long): String = String.format(Locale.US, "%.2f s", ms / 1000.0)

/** Bare seconds with 2dp, for use next to a separate unit label. */
fun formatSecondsBare(ms: Long): String = String.format(Locale.US, "%.2f", ms / 1000.0)

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatReading(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format(Locale.US, "%.1f", value)
