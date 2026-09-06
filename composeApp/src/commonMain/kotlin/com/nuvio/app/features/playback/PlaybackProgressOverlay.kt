package com.nuvio.app.features.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nuvio.app.features.downloads.SourceFacts
import kotlinx.coroutines.delay


/**
 * The route's half of the one loading surface.
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
 *
 * Since Phase 2 it draws nothing itself: it builds a [PlaybackLoadingState] and hands it to
 * [PlaybackLoadingScreen], which the player's `OpeningOverlay` also renders. The two used to be
 * separate screens with separate spinners, so the moment playback was handed off the artwork
 * changed, the wording changed and the motion changed - on a path where nothing had gone wrong.
 * Keeping the *state* here and the *pixels* there is what makes the hand-off invisible while
 * leaving the route in charge of the chain.
 */
@Composable
fun PlaybackProgressOverlay(
    step: PlaybackProgressStep,
    modifier: Modifier = Modifier,
    attempt: Int = 1,
    maxAttempts: Int = PlaybackProgress.MAX_ATTEMPTS,
    failure: PlaybackProgressFailure? = null,
    facts: SourceFacts? = null,
    artwork: String? = null,
    logo: String? = null,
    title: String? = null,
    formatSize: (Long) -> String = { it.toString() },
    onBack: (() -> Unit)? = null,
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

    PlaybackLoadingScreen(
        state = PlaybackLoadingState(
            step = step,
            attempt = attempt,
            maxAttempts = maxAttempts,
            facts = facts,
            failure = failure,
            offerManualEscape = shouldOfferManualEscape(attempt, elapsedMs),
        ),
        artwork = artwork,
        logo = logo,
        title = title,
        formatSize = formatSize,
        modifier = modifier,
        onBack = onBack,
        onChooseManually = onChooseManually,
    )
}
