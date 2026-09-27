package com.nuvio.app.features.playback

// This file was `PlaybackModeSelectorScreen.kt` until 0.5.0-beta, when the standalone
// first-launch selector it held was replaced by `features/setup/SetupWizardScreen.kt`. The
// card survived the screen because two places still describe the modes - the wizard's playback
// step and `PlaybackModeDialog` in `PlaybackSettingsPage` - and they must not drift.

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.NuvioChoiceCard
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.playback_mode_classic
import nuvio.composeapp.generated.resources.playback_mode_classic_detail
import nuvio.composeapp.generated.resources.playback_mode_classic_tagline
import nuvio.composeapp.generated.resources.playback_mode_instant
import nuvio.composeapp.generated.resources.playback_mode_instant_detail
import nuvio.composeapp.generated.resources.playback_mode_instant_tagline
import nuvio.composeapp.generated.resources.playback_mode_streamlined
import nuvio.composeapp.generated.resources.playback_mode_streamlined_detail
import nuvio.composeapp.generated.resources.playback_mode_streamlined_tagline
import nuvio.composeapp.generated.resources.playback_mode_unavailable
import org.jetbrains.compose.resources.stringResource

/**
 * One mode as a selectable card: radio, name, a short tagline and one sentence of what pressing
 * play does.
 *
 * **Shared deliberately.** Two places describe the modes - the setup wizard and
 * `PlaybackModeDialog` in `PlaybackSettingsPage` - and the last time mode-descriptive logic
 * was duplicated across those two files, one copy kept captioning Instant "Not ready yet"
 * after the other had been fixed. One composable, so they cannot drift again.
 *
 * **Playback only.** Until the wizard polish pass (2026-09-27) each card also had a "Downloading"
 * section naming the Download Mode it implies. Downloads have their own step and their own
 * settings now, so that line only made the playback question longer and harder to read; the
 * derivation itself is unchanged (`DownloadPolicy.derivedMode`, pinned by `DownloadPolicyTest`).
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
    NuvioChoiceCard(
        title = playbackModeName(mode),
        selected = isSelected && enabled,
        onClick = onClick,
        modifier = modifier,
        tagline = playbackModeTagline(mode),
        description = playbackModeDetail(mode),
        enabled = enabled,
        // Say why, on the card itself. A greyed row with no explanation is the thing users
        // report as broken.
        note = if (enabled) null else stringResource(Res.string.playback_mode_unavailable),
    )
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

/**
 * Must stay in step with what each mode actually does on the playback path. Copy that
 * contradicts the router is worse than no copy at all: Streamlined once went on offering
 * *"Pin a release to reuse it for the rest of a season"* for a whole release after the pin was
 * withdrawn (see [PlaybackModeRouter]).
 *
 * What each sentence maps to today:
 *  - **Classic** - `PlaybackRouteDecision.ShowSourceList`: every source, the user picks one.
 *  - **Streamlined** - `ShowQualitySheet` into [PlaybackSourceSelector]: a quality, then Nuvio's
 *    pick of the release inside it.
 *  - **Instant** - `AutoPick`: tier and source from the measured connection, bounded by the
 *    quality limit.
 */
@Composable
private fun playbackModeDetail(mode: PlaybackMode): String = when (mode) {
    PlaybackMode.CLASSIC -> stringResource(Res.string.playback_mode_classic_detail)
    PlaybackMode.STREAMLINED -> stringResource(Res.string.playback_mode_streamlined_detail)
    PlaybackMode.INSTANT -> stringResource(Res.string.playback_mode_instant_detail)
}

/**
 * Marker for the contract the wizard depends on, kept as documentation rather than an
 * interface because `PlayerSettingsRepository` is an object.
 *
 * Finishing setup must call `markSetupWizardCompleted`. It used to have to call
 * `markPlaybackModeSelectorSeen` as well, because choosing Classic is a no-op for the mode - it
 * is the default - so the mode alone could never mean "answered". That distinction belongs to the
 * standalone first-launch selector, which the wizard replaced; the wizard's own completion
 * revision answers the same question, so the second flag was deleted.
 * `SetupWizardScreen.complete()` writes the revision.
 */
private interface PlaybackModeRepositoryContract
