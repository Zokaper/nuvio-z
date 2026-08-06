package com.nuvio.app.features.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.playback_mode_classic
import nuvio.composeapp.generated.resources.playback_mode_classic_description
import nuvio.composeapp.generated.resources.playback_mode_instant
import nuvio.composeapp.generated.resources.playback_mode_instant_description
import nuvio.composeapp.generated.resources.playback_mode_not_ready
import nuvio.composeapp.generated.resources.playback_mode_selector_confirm
import nuvio.composeapp.generated.resources.playback_mode_selector_recommendation
import nuvio.composeapp.generated.resources.playback_mode_selector_subtitle
import nuvio.composeapp.generated.resources.playback_mode_selector_title
import nuvio.composeapp.generated.resources.playback_mode_streamlined
import nuvio.composeapp.generated.resources.playback_mode_streamlined_description
import org.jetbrains.compose.resources.stringResource

/**
 * The first-launch mode selector.
 *
 * Shown once to everyone, existing installs included, and **pre-selected to
 * [PlaybackMode.Default] (Classic)** so that dismissing it changes nothing about how the
 * app already behaves. Confirming records both the mode and the seen flag - see
 * [PlaybackModeRepositoryContract] on why the two are separate.
 */
@Composable
fun PlaybackModeSelectorScreen(
    initialMode: PlaybackMode = PlaybackMode.Default,
    onConfirm: (PlaybackMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(initialMode) }

    Box(
        modifier = modifier.background(MaterialTheme.nuvio.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(Res.string.playback_mode_selector_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(Res.string.playback_mode_selector_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PlaybackMode.entries.forEach { mode ->
                PlaybackModeCard(
                    mode = mode,
                    isSelected = mode == selected,
                    onClick = { selected = mode },
                )
            }

            Text(
                text = stringResource(Res.string.playback_mode_selector_recommendation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = { onConfirm(selected) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(Res.string.playback_mode_selector_confirm))
            }
        }
    }
}

@Composable
private fun PlaybackModeCard(
    mode: PlaybackMode,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = playbackModeName(mode),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = playbackModeSummary(mode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (mode != PlaybackMode.CLASSIC) {
                Text(
                    text = stringResource(Res.string.playback_mode_not_ready),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun playbackModeName(mode: PlaybackMode): String = when (mode) {
    PlaybackMode.CLASSIC -> stringResource(Res.string.playback_mode_classic)
    PlaybackMode.STREAMLINED -> stringResource(Res.string.playback_mode_streamlined)
    PlaybackMode.INSTANT -> stringResource(Res.string.playback_mode_instant)
}

@Composable
private fun playbackModeSummary(mode: PlaybackMode): String = when (mode) {
    PlaybackMode.CLASSIC -> stringResource(Res.string.playback_mode_classic_description)
    PlaybackMode.STREAMLINED -> stringResource(Res.string.playback_mode_streamlined_description)
    PlaybackMode.INSTANT -> stringResource(Res.string.playback_mode_instant_description)
}

/**
 * Marker for the contract the selector depends on, kept as documentation rather than an
 * interface because `PlayerSettingsRepository` is an object.
 *
 * Confirming must call **both** `setPlaybackMode` and `markPlaybackModeSelectorSeen`.
 * Choosing Classic is a no-op for the mode - it is the pre-selected default - so relying on
 * the mode alone to dismiss the selector would show it again on every launch.
 */
private interface PlaybackModeRepositoryContract
