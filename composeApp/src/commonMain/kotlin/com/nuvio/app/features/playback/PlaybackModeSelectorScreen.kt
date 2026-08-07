package com.nuvio.app.features.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import nuvio.composeapp.generated.resources.playback_mode_classic_download
import nuvio.composeapp.generated.resources.playback_mode_classic_stream_1
import nuvio.composeapp.generated.resources.playback_mode_classic_stream_2
import nuvio.composeapp.generated.resources.playback_mode_classic_tagline
import nuvio.composeapp.generated.resources.playback_mode_escape_hatch
import nuvio.composeapp.generated.resources.playback_mode_instant
import nuvio.composeapp.generated.resources.playback_mode_instant_download
import nuvio.composeapp.generated.resources.playback_mode_instant_stream_1
import nuvio.composeapp.generated.resources.playback_mode_instant_stream_2
import nuvio.composeapp.generated.resources.playback_mode_instant_tagline
import nuvio.composeapp.generated.resources.playback_mode_section_downloading
import nuvio.composeapp.generated.resources.playback_mode_section_streaming
import nuvio.composeapp.generated.resources.playback_mode_selector_confirm
import nuvio.composeapp.generated.resources.playback_mode_selector_recommendation
import nuvio.composeapp.generated.resources.playback_mode_selector_subtitle
import nuvio.composeapp.generated.resources.playback_mode_selector_title
import nuvio.composeapp.generated.resources.playback_mode_streamlined
import nuvio.composeapp.generated.resources.playback_mode_streamlined_download
import nuvio.composeapp.generated.resources.playback_mode_streamlined_stream_1
import nuvio.composeapp.generated.resources.playback_mode_streamlined_stream_2
import nuvio.composeapp.generated.resources.playback_mode_streamlined_tagline
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
                text = stringResource(Res.string.playback_mode_escape_hatch),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/**
 * One mode, described the way a plan-comparison card describes a tier.
 *
 * **Shared deliberately.** Two places describe the modes - this screen and
 * `PlaybackModeDialog` in `PlaybackSettingsPage` - and the last time mode-descriptive logic
 * was duplicated across those two files, one copy kept captioning Instant "Not ready yet"
 * after the other had been fixed. One composable, so they cannot drift again.
 *
 * The download lines are not decoration: they must keep matching
 * [PlaybackModeDownloadRouter.decide], which is what actually happens when the user presses
 * Download. Copy that contradicts the router is worse than no copy at all.
 */
@Composable
fun PlaybackModeCard(
    mode: PlaybackMode,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(16.dp),
                    )
                } else {
                    Modifier
                },
            ),
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = playbackModeName(mode),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = playbackModeTagline(mode),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }

            ModeDetailSection(
                title = stringResource(Res.string.playback_mode_section_streaming),
                lines = playbackModeStreamingLines(mode),
            )
            ModeDetailSection(
                title = stringResource(Res.string.playback_mode_section_downloading),
                lines = listOf(playbackModeDownloadLine(mode)),
            )
        }
    }
}

@Composable
private fun ModeDetailSection(title: String, lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        lines.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
fun playbackModeName(mode: PlaybackMode): String = when (mode) {
    PlaybackMode.CLASSIC -> stringResource(Res.string.playback_mode_classic)
    PlaybackMode.STREAMLINED -> stringResource(Res.string.playback_mode_streamlined)
    PlaybackMode.INSTANT -> stringResource(Res.string.playback_mode_instant)
}

@Composable
private fun playbackModeTagline(mode: PlaybackMode): String = when (mode) {
    PlaybackMode.CLASSIC -> stringResource(Res.string.playback_mode_classic_tagline)
    PlaybackMode.STREAMLINED -> stringResource(Res.string.playback_mode_streamlined_tagline)
    PlaybackMode.INSTANT -> stringResource(Res.string.playback_mode_instant_tagline)
}

@Composable
private fun playbackModeStreamingLines(mode: PlaybackMode): List<String> = when (mode) {
    PlaybackMode.CLASSIC -> listOf(
        stringResource(Res.string.playback_mode_classic_stream_1),
        stringResource(Res.string.playback_mode_classic_stream_2),
    )
    PlaybackMode.STREAMLINED -> listOf(
        stringResource(Res.string.playback_mode_streamlined_stream_1),
        stringResource(Res.string.playback_mode_streamlined_stream_2),
    )
    PlaybackMode.INSTANT -> listOf(
        stringResource(Res.string.playback_mode_instant_stream_1),
        stringResource(Res.string.playback_mode_instant_stream_2),
    )
}

/**
 * Must stay in step with [PlaybackModeDownloadRouter.decide]. `PlaybackModeDownloadCopyTest`
 * pins the two together: Classic is the only mode whose download entry point depends on
 * whether the scope is a single item, and that is what its line has to say.
 */
@Composable
private fun playbackModeDownloadLine(mode: PlaybackMode): String = when (mode) {
    PlaybackMode.CLASSIC -> stringResource(Res.string.playback_mode_classic_download)
    PlaybackMode.STREAMLINED -> stringResource(Res.string.playback_mode_streamlined_download)
    PlaybackMode.INSTANT -> stringResource(Res.string.playback_mode_instant_download)
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
