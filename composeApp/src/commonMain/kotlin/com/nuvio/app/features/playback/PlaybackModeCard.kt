package com.nuvio.app.features.playback

// This file was `PlaybackModeSelectorScreen.kt` until 0.5.0-beta, when the standalone
// first-launch selector it held was replaced by `features/setup/SetupWizardScreen.kt`. The
// card survived the screen because two places still describe the modes - the wizard's playback
// step and `PlaybackModeDialog` in `PlaybackSettingsPage` - and they must not drift.

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.playback_mode_classic
import nuvio.composeapp.generated.resources.playback_mode_classic_download
import nuvio.composeapp.generated.resources.playback_mode_classic_stream_1
import nuvio.composeapp.generated.resources.playback_mode_classic_stream_2
import nuvio.composeapp.generated.resources.playback_mode_classic_tagline
import nuvio.composeapp.generated.resources.playback_mode_instant
import nuvio.composeapp.generated.resources.playback_mode_instant_download
import nuvio.composeapp.generated.resources.playback_mode_instant_stream_1
import nuvio.composeapp.generated.resources.playback_mode_instant_stream_2
import nuvio.composeapp.generated.resources.playback_mode_instant_tagline
import nuvio.composeapp.generated.resources.playback_mode_section_downloading
import nuvio.composeapp.generated.resources.playback_mode_section_streaming
import nuvio.composeapp.generated.resources.playback_mode_streamlined
import nuvio.composeapp.generated.resources.playback_mode_streamlined_download
import nuvio.composeapp.generated.resources.playback_mode_streamlined_stream_1
import nuvio.composeapp.generated.resources.playback_mode_streamlined_stream_2
import nuvio.composeapp.generated.resources.playback_mode_streamlined_tagline
import nuvio.composeapp.generated.resources.playback_mode_unavailable
import org.jetbrains.compose.resources.stringResource

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
 *
 * [enabled] comes from [PlaybackMode.isSelectable] and from nowhere else. A card that is
 * greyed must also be un-tappable and un-ticked: greyed *and* selected reads as a bug rather
 * than as a mode being withheld.
 */
@Composable
fun PlaybackModeCard(
    mode: PlaybackMode,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .clickable(enabled = enabled, onClick = onClick)
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
                    if (!enabled) {
                        // Say why, on the card itself. A greyed row with no explanation is
                        // the thing users report as broken.
                        Text(
                            text = stringResource(Res.string.playback_mode_unavailable),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                        )
                    }
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
 * Marker for the contract the wizard depends on, kept as documentation rather than an
 * interface because `PlayerSettingsRepository` is an object.
 *
 * Finishing setup must call **both** `markSetupWizardCompleted` and
 * `markPlaybackModeSelectorSeen`. Choosing Classic is a no-op for the mode - it is the
 * default - so the mode alone can never mean "answered", which is why the flag exists at all.
 * `SetupWizardScreen.complete()` writes both.
 */
private interface PlaybackModeRepositoryContract
