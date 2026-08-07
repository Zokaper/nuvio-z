package com.nuvio.app.features.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.playback_progress_attempt
import nuvio.composeapp.generated.resources.playback_progress_choosing
import nuvio.composeapp.generated.resources.playback_progress_finding
import nuvio.composeapp.generated.resources.playback_progress_resolving
import nuvio.composeapp.generated.resources.playback_progress_starting
import nuvio.composeapp.generated.resources.streams_finding_source
import org.jetbrains.compose.resources.stringResource

/**
 * What the automatic playback path is doing, for the overlay Streamlined and Instant show
 * instead of the source list.
 *
 * Every value maps to state that already exists in `entry<StreamRoute>` - see
 * [PlaybackProgress.step]. Nothing here is a timed or faked sequence: a step that cannot be
 * observed is a step that lies about what the app is waiting for.
 */
enum class PlaybackProgressStep {
    /** Addons and plugins are still returning candidates. */
    FindingSources,

    /** Candidates are in; `PlaybackSourceSelector` is ranking them. */
    ChoosingSource,

    /** A debrid link is being minted for the chosen candidate. Usually the real wait. */
    ResolvingLink,

    /** Chosen and resolved; handing off to the player. */
    StartingPlayback,
}

/**
 * Everything the overlay's state depends on, gathered by the caller.
 *
 * Plain data on purpose - the route entry gathers, this decides, and a test can cover the
 * whole table without a Compose runtime.
 */
data class PlaybackProgressInputs(
    /** `streamsUiState.isAnyLoading`, or the request token not yet matching. */
    val isLoadingSources: Boolean,
    /** `instantSelectionHandled` for Instant, the tier pick for Streamlined. */
    val hasChosenSource: Boolean,
    /** The existing `resolvingDebridStream` flag. */
    val isResolvingLink: Boolean,
    /** 1-based. Above 1 means the failure chain has moved on from a dead candidate. */
    val attempt: Int = 1,
)

object PlaybackProgress {

    /**
     * The retry budget Instant's failure chain runs to, mirrored from the plan so the overlay
     * and the chain cannot disagree about how many tries the user is being told about.
     */
    const val MAX_ATTEMPTS: Int = 3

    /**
     * Resolving is checked first because it is the only step with a real, observable wait: a
     * debrid mint can take seconds while `isLoadingSources` is still true for a slow addon
     * that nothing is waiting on any more.
     */
    fun step(inputs: PlaybackProgressInputs): PlaybackProgressStep = when {
        inputs.isResolvingLink -> PlaybackProgressStep.ResolvingLink
        inputs.isLoadingSources -> PlaybackProgressStep.FindingSources
        !inputs.hasChosenSource -> PlaybackProgressStep.ChoosingSource
        else -> PlaybackProgressStep.StartingPlayback
    }

    /**
     * Whether the source list should be covered at all.
     *
     * The bail-outs are what matter here. Every path that gives up on automatic selection sets
     * `manualSourceListRequested`, and the metered sheet needs an answer from the user - so
     * both have to uncover the list rather than leave a spinner over a screen the user is
     * meant to be reading or answering.
     */
    fun isVisible(
        isAutoPickRoute: Boolean,
        isStreamlinedPlaybackStarting: Boolean,
        manualSourceListRequested: Boolean,
        awaitingMeteredChoice: Boolean,
        hasNavigatedAway: Boolean,
    ): Boolean {
        if (manualSourceListRequested || awaitingMeteredChoice || hasNavigatedAway) return false
        return isAutoPickRoute || isStreamlinedPlaybackStarting
    }
}

/**
 * The full-bleed progress surface.
 *
 * It **covers** `StreamsScreen` rather than replacing it, because `StreamsScreen` owns the
 * fetch (`LaunchedEffect { StreamsRepository.load(...) }`). Composing it away would cancel the
 * very load this overlay is reporting on.
 */
@Composable
fun PlaybackProgressOverlay(
    step: PlaybackProgressStep,
    modifier: Modifier = Modifier,
    attempt: Int = 1,
    maxAttempts: Int = PlaybackProgress.MAX_ATTEMPTS,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.nuvio.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.nuvio.spacing.cardPadding),
        ) {
            NuvioLoadingIndicator(color = MaterialTheme.nuvio.colors.accent)
            Text(
                text = stringResource(Res.string.streams_finding_source),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stepLabel(step),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (attempt > 1) {
                Text(
                    text = stringResource(Res.string.playback_progress_attempt, attempt, maxAttempts),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun stepLabel(step: PlaybackProgressStep): String = when (step) {
    PlaybackProgressStep.FindingSources -> stringResource(Res.string.playback_progress_finding)
    PlaybackProgressStep.ChoosingSource -> stringResource(Res.string.playback_progress_choosing)
    PlaybackProgressStep.ResolvingLink -> stringResource(Res.string.playback_progress_resolving)
    PlaybackProgressStep.StartingPlayback -> stringResource(Res.string.playback_progress_starting)
}
