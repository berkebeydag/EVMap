package com.berke.ioniqscope.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.berke.ioniqscope.connection.ConnectionState
import com.berke.ioniqscope.ui.theme.StatusAmber
import com.berke.ioniqscope.ui.theme.StatusGreen

/**
 * A screen's own title, in place of one bar shared by all of them.
 *
 * The app used to carry a single TopAppBar with a small title and two icons, which
 * made every screen open the same way regardless of what it was. The design gives each
 * one a heading of its own, at a size that says which screen you are on before you read
 * it, with only the chrome that screen actually needs beside it — a connection state on
 * the ones that need a car, nothing on the ones that do not.
 *
 * The map has none of this at all: it floats its controls over the tiles instead, so
 * the map is the whole screen rather than the part left over under a bar.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f)
        )
        actions()
    }
}

/**
 * Whether the adapter is talking, as a word rather than a dot alone.
 *
 * The dot on its own was a colour the user had to have learned. On the screens that
 * need the car this says which state it is in, and on the ones that do not it is simply
 * absent rather than permanently grey.
 */
@Composable
fun ConnectionPill(state: ConnectionState, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (tint, label) = when (state) {
        is ConnectionState.Connected -> StatusGreen to "Bağlı"
        is ConnectionState.Connecting -> StatusAmber to "Bağlanıyor"
        is ConnectionState.Failed -> scheme.error to "Bağlanamadı"
        ConnectionState.Disconnected -> scheme.outline to "Bağlı değil"
    }

    // Only the connecting state pulses; a steady badge must not draw the eye while
    // the car is moving.
    val pulse = rememberInfiniteTransition(label = "connectionPulse")
    val dotAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (state is ConnectionState.Connecting) 0.25f else 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "connectionDotAlpha"
    )

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = tint.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
        contentColor = tint,
        onClick = onClick
    ) {
        Row(
            Modifier.height(28.dp).padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(Modifier.size(7.dp).alpha(dotAlpha).background(tint, CircleShape))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** The way to Settings, kept the same shape on every screen that offers it. */
@Composable
fun SettingsAction(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Filled.Settings, contentDescription = "Ayarlar")
    }
}

/** The way to the adapter, for screens with no room for the full pill. */
@Composable
fun ConnectionAction(state: ConnectionState, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dot = when (state) {
        is ConnectionState.Connected -> StatusGreen
        is ConnectionState.Connecting -> StatusAmber
        is ConnectionState.Failed -> scheme.error
        ConnectionState.Disconnected -> scheme.outline
    }
    IconButton(onClick = onClick) {
        Box {
            Icon(
                if (state is ConnectionState.Connected) Icons.Filled.BluetoothConnected
                else Icons.Filled.Bluetooth,
                contentDescription = "Adaptör"
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(9.dp)
                    .background(dot, CircleShape)
            )
        }
    }
}

/** Nothing at all, for a screen whose chrome floats over its own content. */
val NoActions: @Composable RowScope.() -> Unit = {}

/** Kept so callers can pass a plain colour where a tint is optional. */
val Transparent: Color = Color.Transparent
