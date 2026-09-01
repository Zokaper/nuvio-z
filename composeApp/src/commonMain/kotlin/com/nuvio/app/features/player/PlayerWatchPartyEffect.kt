package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.nuvio.app.features.watchparty.DriftCorrectionKind
import com.nuvio.app.features.watchparty.SourceResolutionState
import com.nuvio.app.features.watchparty.WatchPartyControlMode
import com.nuvio.app.features.watchparty.WatchPartyRepository
import com.nuvio.app.features.watchparty.WatchPartySnapshotIntervalMs
import com.nuvio.app.features.watchparty.WatchPartyStatus
import com.nuvio.app.features.watchparty.expectedPartyPositionMs
import com.nuvio.app.features.watchparty.partyDriftCorrection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
internal fun PlayerScreenRuntime.BindWatchPartyEffect() {
    val partyUi by WatchPartyRepository.uiState.collectAsStateWithLifecycle()
    val party = partyUi.party
    val matchingParty = party?.takeIf {
        it.content.contentId == parentMetaId && it.content.videoId == playbackSession.videoId && it.status != WatchPartyStatus.ended
    }

    LaunchedEffect(matchingParty?.id, matchingParty?.contentGeneration, playbackSnapshot.durationMs) {
        if (matchingParty != null && playbackSnapshot.durationMs > 0L) {
            WatchPartyRepository.updateReady(SourceResolutionState.ready, playbackSnapshot.durationMs)
        }
    }

    LaunchedEffect(matchingParty?.id) {
        while (matchingParty != null) {
            val snapshot = playbackSnapshot
            WatchPartyRepository.heartbeat(
                positionMs = snapshot.positionMs,
                durationMs = snapshot.durationMs,
                speed = snapshot.playbackSpeed,
                status = when {
                    snapshot.isLoading -> WatchPartyStatus.buffering
                    snapshot.isPlaying -> WatchPartyStatus.playing
                    else -> WatchPartyStatus.paused
                },
            )
            delay(WatchPartySnapshotIntervalMs)
        }
    }

    LaunchedEffect(matchingParty?.sequence, matchingParty?.stateUpdatedAt, partyUi.serverClockOffsetMs) {
        val state = matchingParty ?: return@LaunchedEffect
        if (state.hostProfileId == partyUi.activeProfileId) return@LaunchedEffect
        val controller = playerController ?: return@LaunchedEffect
        val updatedAt = runCatching { kotlin.time.Instant.parse(state.stateUpdatedAt).toEpochMilliseconds() }.getOrNull() ?: return@LaunchedEffect
        val serverNow = kotlin.time.Clock.System.now().toEpochMilliseconds() + partyUi.serverClockOffsetMs
        val expected = expectedPartyPositionMs(state.positionMs, updatedAt, serverNow, state.status, state.playbackSpeed)
        if (state.status == WatchPartyStatus.playing) {
            val correction = partyDriftCorrection(playbackSnapshot.positionMs, expected, state.playbackSpeed)
            when (correction.kind) {
                DriftCorrectionKind.NONE -> controller.setPlaybackSpeed(state.playbackSpeed)
                DriftCorrectionKind.SEEK -> { controller.seekTo(correction.targetPositionMs); controller.setPlaybackSpeed(state.playbackSpeed) }
                DriftCorrectionKind.TEMPORARY_SPEED -> {
                    controller.setPlaybackSpeed(correction.temporarySpeed ?: state.playbackSpeed)
                    delay(10_000L)
                    controller.setPlaybackSpeed(state.playbackSpeed)
                }
            }
            controller.play()
        } else if (state.status == WatchPartyStatus.paused || state.status == WatchPartyStatus.buffering || state.status == WatchPartyStatus.lobby) {
            if (abs(playbackSnapshot.positionMs - expected) > 500L) controller.seekTo(expected)
            controller.pause()
        }
    }

    LaunchedEffect(matchingParty?.hostProfileId, matchingParty?.members) {
        val state = matchingParty ?: return@LaunchedEffect
        val hostConnected = state.members.firstOrNull { it.profileId == state.hostProfileId }?.connected != false
        if (!hostConnected && state.hostProfileId != partyUi.activeProfileId) {
            WatchPartyRepository.claimHostAfterGrace()
        }
    }
}

internal fun PlayerScreenRuntime.submitPartyPlayPause(isPlaying: Boolean, positionMs: Long) {
    val ui = WatchPartyRepository.uiState.value
    val party = ui.party ?: return
    if (party.content.contentId != parentMetaId || party.content.videoId != playbackSession.videoId) return
    val allowed = party.hostProfileId == ui.activeProfileId || party.controlMode == WatchPartyControlMode.collaborative
    if (!allowed) return
    scope.launch { if (isPlaying) WatchPartyRepository.play(positionMs) else WatchPartyRepository.pause(positionMs) }
}

internal fun PlayerScreenRuntime.submitPartySeek(positionMs: Long) {
    val ui = WatchPartyRepository.uiState.value
    val party = ui.party ?: return
    if (party.content.contentId != parentMetaId || party.content.videoId != playbackSession.videoId) return
    if (party.hostProfileId != ui.activeProfileId && party.controlMode != WatchPartyControlMode.collaborative) return
    scope.launch { WatchPartyRepository.seek(positionMs) }
}

internal fun PlayerScreenRuntime.submitPartySpeed(speed: Float) {
    val ui = WatchPartyRepository.uiState.value
    val party = ui.party ?: return
    if (party.content.contentId != parentMetaId || party.content.videoId != playbackSession.videoId) return
    if (party.hostProfileId != ui.activeProfileId && party.controlMode != WatchPartyControlMode.collaborative) return
    scope.launch { WatchPartyRepository.setSpeed(speed) }
}
