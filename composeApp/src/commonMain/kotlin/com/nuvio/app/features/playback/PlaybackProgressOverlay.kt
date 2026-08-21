package com.nuvio.app.features.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.playback_progress_attempt
import nuvio.composeapp.generated.resources.playback_progress_choosing
import nuvio.composeapp.generated.resources.playback_progress_finding
import nuvio.composeapp.generated.resources.playback_progress_resolving
import nuvio.composeapp.generated.resources.playback_progress_source_failed
import nuvio.composeapp.generated.resources.playback_progress_source_failed_reason
import nuvio.composeapp.generated.resources.playback_progress_starting
import nuvio.composeapp.generated.resources.playback_quality_checking_connection
import nuvio.composeapp.generated.resources.playback_quality_manual
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

    /**
     * Instant only: the candidates are in but the connection measurement has not settled.
     *
     * Instant picks a quality *from* that measurement, so choosing before it lands would be
     * choosing from the unmeasured platform guess - the fault the mode was withdrawn for the
     * first time. The wait is bounded by `NetworkStrengthProbe.PROBE_DEADLINE_MS` and is
     * usually invisible, because the probe runs alongside the fetch and the fetch is slower.
     * It gets its own step for the case where it is not, because "Choosing a source" while the
     * app is measuring a line is the same small lie the connection gauge work spent three
     * passes removing.
     */
    CheckingConnection,

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
    /**
     * Instant only: `!connectionSettled`, the same signal the quality sheet withholds its
     * figure on.
     *
     * Deliberately not passed by the remembered-band path, which does not need an estimate -
     * its band is exact - and must not claim to be waiting for one.
     */
    val isMeasuringConnection: Boolean = false,
)

object PlaybackProgress {

    /**
     * The retry budget the failure chain runs to, so the overlay and the chain cannot disagree
     * about how many tries the user is being told about.
     *
     * Defined in `StreamRouteSurface.kt` and aliased here. That file has no imports and is the
     * one thing `scripts/run-pure-suites.sh` can actually execute, so the budget and the
     * function that spends it ([playbackChain]) are covered by a test that runs without Gradle -
     * which is how the drift this fixes would have been caught.
     */
    const val MAX_ATTEMPTS: Int = PLAYBACK_MAX_ATTEMPTS

    /**
     * Resolving is checked first because it is the only step with a real, observable wait: a
     * debrid mint can take seconds while `isLoadingSources` is still true for a slow addon
     * that nothing is waiting on any more.
     */
    fun step(inputs: PlaybackProgressInputs): PlaybackProgressStep = when {
        inputs.isResolvingLink -> PlaybackProgressStep.ResolvingLink
        inputs.isLoadingSources -> PlaybackProgressStep.FindingSources
        // Below the fetch, because the two run concurrently and the fetch is nearly always the
        // longer of the two; above the choice, because Instant genuinely cannot choose yet.
        inputs.isMeasuringConnection && !inputs.hasChosenSource ->
            PlaybackProgressStep.CheckingConnection
        !inputs.hasChosenSource -> PlaybackProgressStep.ChoosingSource
        else -> PlaybackProgressStep.StartingPlayback
    }

    // `isVisible` used to live here and answered only "does the overlay cover the list?".
    // That was half the question: the route also paints an opaque hand-off surface under the
    // overlay, and hiding the overlay while that surface stayed up traded a blank screen for
    // a blank screen one layer down - which is what backing out of the player actually did.
    // The whole stack is decided by `streamRouteSurface` in StreamRouteSurface.kt, so the two
    // cannot disagree - and that file has no imports, so unlike this one it actually runs.
}

/**
 * The source an automatic path has just given up on, for the overlay to name.
 *
 * [label] comes from `PlaybackSourceSelector.describe` - `1080p · WEB-DL · TorBox` - falling
 * back to the stream's own label when nothing is known about it. [reason] is the provider's
 * words when it gave any, and null when it simply failed.
 */
data class PlaybackProgressFailure(
    val label: String,
    val reason: String? = null,
)

/**
 * The full-bleed progress surface.
 *
 * It **covers** `StreamsScreen` rather than replacing it, because `StreamsScreen` owns the
 * fetch (`LaunchedEffect { StreamsRepository.load(...) }`). Composing it away would cancel the
 * very load this overlay is reporting on.
 *
 * **It is also the only thing on screen**, and it consumes pointer input, so anything the user
 * needs to be able to do while an automatic start is running has to be here. That is why
 * [onChooseManually] exists: failures used to be reported by toast over this surface while the
 * only exit was Back, which abandoned the play rather than dropping to the source list. One
 * surface now says what went wrong and offers the way out, and it appears on
 * [shouldOfferManualEscape]'s terms rather than from the first frame.
 */
@Composable
fun PlaybackProgressOverlay(
    step: PlaybackProgressStep,
    modifier: Modifier = Modifier,
    attempt: Int = 1,
    maxAttempts: Int = PlaybackProgress.MAX_ATTEMPTS,
    failure: PlaybackProgressFailure? = null,
    onChooseManually: (() -> Unit)? = null,
) {
    // Wall-clock since this surface appeared, so a start that is merely slow eventually offers
    // the same way out a failed one does. Reset whenever the overlay is recomposed into place
    // rather than kept across launches - a stale elapsed time would put the escape hatch up on
    // the first frame of the next play.
    var isPastEscapeDelay by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(MANUAL_ESCAPE_DELAY_MS)
        isPastEscapeDelay = true
    }
    val elapsedMs = if (isPastEscapeDelay) MANUAL_ESCAPE_DELAY_MS else 0L

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
                    // Belt and braces. `entry<StreamRoute>` now seeds at most [MAX_ATTEMPTS]
                    // candidates, so the chain cannot outrun this on its own - but the counter
                    // is also bumped by the player-retry path, which advances independently of
                    // the seed. Coercing keeps "Attempt 5 of 3" unreachable either way.
                    text = stringResource(
                        Res.string.playback_progress_attempt,
                        attempt.coerceAtMost(maxAttempts),
                        maxAttempts,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            // Named, not toasted. A toast over this surface restated the attempt line above it
            // and could stack once per dead candidate; worse, it outlived the overlay, so the
            // last thing a user saw after a successful third attempt was a complaint about the
            // second. Here it is scoped to the wait it belongs to.
            failure?.let { failed ->
                Text(
                    text = if (failed.reason.isNullOrBlank()) {
                        stringResource(Res.string.playback_progress_source_failed, failed.label)
                    } else {
                        stringResource(
                            Res.string.playback_progress_source_failed_reason,
                            failed.label,
                            failed.reason,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            if (onChooseManually != null && shouldOfferManualEscape(attempt, elapsedMs)) {
                TextButton(onClick = onChooseManually) {
                    Text(stringResource(Res.string.playback_quality_manual))
                }
            }
        }
    }
}

@Composable
private fun stepLabel(step: PlaybackProgressStep): String = when (step) {
    PlaybackProgressStep.FindingSources -> stringResource(Res.string.playback_progress_finding)
    // The quality sheet's own wording, reused rather than duplicated: the two surfaces are
    // waiting on the same probe and must not describe it differently.
    PlaybackProgressStep.CheckingConnection ->
        stringResource(Res.string.playback_quality_checking_connection)
    PlaybackProgressStep.ChoosingSource -> stringResource(Res.string.playback_progress_choosing)
    PlaybackProgressStep.ResolvingLink -> stringResource(Res.string.playback_progress_resolving)
    PlaybackProgressStep.StartingPlayback -> stringResource(Res.string.playback_progress_starting)
}
