package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.p2p.P2pLoadingStatus
import com.nuvio.app.features.playback.PlaybackLoadingState
import com.nuvio.app.features.playback.PlaybackProgressStep
import com.nuvio.app.features.playback.PlaybackSourceSelector
import com.nuvio.app.features.player.skip.NextEpisodeCard
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.player.skip.SkipIntroButton
import com.nuvio.app.features.player.skip.SkipInterval

@Composable
internal fun BoxScope.PlayerPlaybackOverlays(
    playerControlsLocked: Boolean,
    lockedOverlayVisible: Boolean,
    playbackSnapshot: PlayerPlaybackSnapshot,
    displayedPositionMs: Long,
    metrics: PlayerLayoutMetrics,
    horizontalSafePadding: Dp,
    onUnlock: () -> Unit,
    showOpeningOverlay: Boolean,
    backdropArtwork: String?,
    logo: String?,
    title: String,
    onBackWithProgress: () -> Unit,
    p2pInitialLoadingMessage: String?,
    p2pInitialLoadingProgress: Float?,
    showP2pRebufferStats: Boolean,
    p2pRebufferMessage: String?,
    p2pRebufferProgress: Float?,
    currentGestureFeedback: GestureFeedbackState?,
    renderedGestureFeedback: GestureFeedbackState?,
    initialLoadCompleted: Boolean,
    pausedOverlayVisible: Boolean,
    watchPartyBanner: String?,
    activeSkipInterval: SkipInterval?,
    skipIntervalDismissed: Boolean,
    controlsVisible: Boolean,
    onSkipInterval: (SkipInterval) -> Unit,
    onDismissSkipInterval: () -> Unit,
    sliderEdgePadding: Dp,
    overlayBottomPadding: Dp,
    isSeries: Boolean,
    nextEpisodeInfo: NextEpisodeInfo?,
    showNextEpisodeCard: Boolean,
    nextEpisodeResolving: Boolean,
    nextEpisodeSourceName: String?,
    nextEpisodeCountdown: Int?,
    nextEpisodeStarting: Boolean,
    nextEpisodeActionEnabled: Boolean,
    nextEpisodeShowDismiss: Boolean,
    blurUnwatchedEpisodes: Boolean,
    onPlayNextEpisode: () -> Unit,
    onDismissNextEpisode: () -> Unit,
    errorMessage: String?,
    onDismissError: () -> Unit,
    /** What the route chose, carried across so the band survives the hand-off unchanged. */
    loadingState: PlaybackLoadingState = PlaybackLoadingState(
        step = PlaybackProgressStep.StartingPlayback,
    ),
    formatSize: (Long) -> String = { it.toString() },
    /** Puts the source label and the engine's message on the clipboard together. */
    onCopyErrorDetails: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = playerControlsLocked && lockedOverlayVisible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        LockedPlayerOverlay(
            playbackSnapshot = playbackSnapshot,
            displayedPositionMs = displayedPositionMs,
            metrics = metrics,
            horizontalSafePadding = horizontalSafePadding,
            onUnlock = onUnlock,
            modifier = Modifier.fillMaxSize(),
        )
    }

    AnimatedVisibility(
        visible = showOpeningOverlay,
        // **No enter transition, deliberately.** This overlay is the continuation of the one
        // the stream route was already drawing, so fading it in cross-fades a screen over an
        // identical screen - the flicker at the hand-off that Phase 2 exists to remove. The
        // exit fade stays: that one is the loading screen giving way to the first frame, which
        // is a real transition between two different things.
        enter = EnterTransition.None,
        exit = fadeOut(),
    ) {
        OpeningOverlay(
            artwork = backdropArtwork,
            logo = logo,
            title = title,
            onBack = onBackWithProgress,
            horizontalSafePadding = horizontalSafePadding,
            modifier = Modifier.fillMaxSize(),
            message = p2pInitialLoadingMessage,
            progress = p2pInitialLoadingProgress,
            state = loadingState,
            formatSize = formatSize,
        )
    }

    P2pLoadingStatus(
        visible = showP2pRebufferStats && errorMessage == null,
        message = p2pRebufferMessage,
        progress = p2pRebufferProgress,
        modifier = Modifier
            .align(Alignment.Center)
            .padding(top = 58.dp),
    )

    AnimatedVisibility(
        visible = currentGestureFeedback != null,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            renderedGestureFeedback?.let { feedback ->
                GestureFeedbackPill(
                    feedback = feedback,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Top))
                        .padding(horizontal = horizontalSafePadding)
                        .padding(top = 40.dp),
                )
            }
        }
    }

    // Deliberately independent of `controlsVisible`: a player held still because it is waiting on
    // somebody else has to say so even after the chrome fades, or it reads as one that has broken.
    if (watchPartyBanner != null) {
        Surface(
            color = Color.Black.copy(alpha = 0.72f),
            contentColor = Color.White,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Top))
                .padding(horizontal = horizontalSafePadding)
                .padding(top = 96.dp),
        ) {
            Text(
                text = watchPartyBanner,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    if (!playerControlsLocked) {
        SkipIntroButton(
            interval = if (!initialLoadCompleted || pausedOverlayVisible) null else activeSkipInterval,
            dismissed = skipIntervalDismissed,
            controlsVisible = controlsVisible,
            onSkip = {
                activeSkipInterval?.let(onSkipInterval)
            },
            onDismiss = onDismissSkipInterval,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = sliderEdgePadding, bottom = overlayBottomPadding),
        )
    }

    if (isSeries && !playerControlsLocked) {
        NextEpisodeCard(
            nextEpisode = nextEpisodeInfo,
            visible = showNextEpisodeCard,
            isResolving = nextEpisodeResolving,
            sourceName = nextEpisodeSourceName,
            countdownSeconds = nextEpisodeCountdown,
            isStarting = nextEpisodeStarting,
            actionEnabled = nextEpisodeActionEnabled,
            showDismiss = nextEpisodeShowDismiss,
            blurred = blurUnwatchedEpisodes && nextEpisodeInfo?.isWatched == false,
            onPlayNext = onPlayNextEpisode,
            onDismiss = onDismissNextEpisode,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = sliderEdgePadding, bottom = overlayBottomPadding),
        )
    }

    if (errorMessage != null) {
        ErrorModal(
            message = errorMessage,
            onDismiss = onDismissError,
            // The same words the loading screen and the failure chain use, from the same
            // `SourceFacts`, so the three surfaces cannot disagree about what just died.
            sourceLabel = PlaybackSourceSelector.describe(loadingState.facts)
                .takeIf { it.isNotBlank() },
            onCopyDetails = onCopyErrorDetails,
        )
    }
}
