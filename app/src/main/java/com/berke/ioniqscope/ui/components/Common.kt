package com.berke.ioniqscope.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import java.util.Locale

/** A single live reading. Value is deliberately the largest thing on the card. */
@Composable
fun GaugeCard(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    stale: Boolean = false
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) {
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
                    style = MaterialTheme.typography.headlineLarge,
                    fontFamily = FontFamily.Monospace,
                    color = if (stale) MaterialTheme.colorScheme.outline else accent,
                    maxLines = 1
                )
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
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
    Text(
        text = text.uppercase(textLocale()),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
