package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import co.touchlab.kermit.Logger
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

/**
 * What this client decided to do about the state it was given. `WatchParty` carries the other half,
 * the transport: two clients disagreeing about a party is the whole class of bug here, and it is
 * only ever diagnosable by lining the two logs up side by side.
 */
private val partyLog = Logger.withTag("WatchPartyPlayer")

@Composable
internal fun PlayerScreenRuntime.BindWatchPartyEffect() {
    val partyUi by WatchPartyRepository.uiState.collectAsStateWithLifecycle()
    val party = partyUi.party
    val matchingParty = party?.takeIf {
        it.content.contentId == parentMetaId && it.content.videoId == playbackSession.videoId && it.status != WatchPartyStatus.ended
    }

    // Keyed on whether a duration is known, never on the duration itself: the extractor refines
    // that value several times while a file opens, and each refinement used to fire another
    // party_set_ready RPC - and so another member-row broadcast to everyone in the party.
    val durationKnown = playbackSnapshot.durationMs > 0L

    LaunchedEffect(matchingParty?.id, matchingParty?.contentGeneration, durationKnown) {
        if (matchingParty != null && durationKnown) {
            WatchPartyRepository.updateReady(SourceResolutionState.ready, playbackSnapshot.durationMs)
        }
    }

    LaunchedEffect(matchingParty?.id) {
        // Re-read the live party each pass. `matchingParty` is captured when the effect launches, so
        // testing it here was testing a value that cannot change - the loop only ever ended because
        // the key changed and cancelled it.
        while (WatchPartyRepository.uiState.value.party != null) {
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

    // Correcting a stream that has not loaded is how a guest ends up watching a black frame: the
    // seek lands on a player with no timeline, and the play that follows has nothing to play.
    val mediaLoaded = playbackSnapshot.durationMs > 0L && !playbackSnapshot.isLoading

    LaunchedEffect(matchingParty?.sequence, matchingParty?.stateUpdatedAt, partyUi.serverClockOffsetMs, mediaLoaded) {
        val state = matchingParty ?: return@LaunchedEffect
        if (state.hostProfileId == partyUi.activeProfileId) return@LaunchedEffect
        val controller = playerController ?: return@LaunchedEffect
        // The guard that keeps a correction from feeding the next one. A seek on Android is a
        // rebuffer, and `isLoading` covers the stall it causes: without this the guest measures its
        // drift *during* that stall, finds it has grown by the length of the stall, and seeks again.
        val durationMs = playbackSnapshot.durationMs
        if (durationMs <= 0L || playbackSnapshot.isLoading) return@LaunchedEffect
        val updatedAt = runCatching { kotlin.time.Instant.parse(state.stateUpdatedAt).toEpochMilliseconds() }.getOrNull() ?: return@LaunchedEffect
        val serverNow = kotlin.time.Clock.System.now().toEpochMilliseconds() + partyUi.serverClockOffsetMs
        // A position past the end of this file is not a position. Clamping keeps a host on a longer
        // cut - or a shared clock that has run on without anyone playing - from seeking a guest into
        // empty space and leaving them there.
        val raw = expectedPartyPositionMs(state.positionMs, updatedAt, serverNow, state.status, state.playbackSpeed)
        val expected = raw.coerceIn(0L, durationMs - 1L)
        if (state.status == WatchPartyStatus.playing) {
            val correction = partyDriftCorrection(playbackSnapshot.positionMs, expected, state.playbackSpeed)
            // The line that says whether this client is actually in sync. `raw` is kept beside
            // `expected` on purpose: the two diverging means the shared clock has run past the end
            // of this file, which is the signature of a timeline that started without anybody playing.
            partyLog.i {
                "drift seq=${state.sequence} localMs=${playbackSnapshot.positionMs} expectedMs=$expected " +
                    "rawMs=$raw durationMs=$durationMs driftMs=${expected - playbackSnapshot.positionMs} " +
                    "action=${correction.kind} offsetMs=${partyUi.serverClockOffsetMs}"
            }
            when (correction.kind) {
                // Also the restore path for a nudge: once the gap is back inside the dead-band the
                // next pass lands here and puts the shared speed back. That used to be done by
                // sleeping ten seconds inside this effect, which meant every snapshot arriving
                // during the sleep was skipped - the guest stopped correcting for exactly as long
                // as it was busy correcting.
                DriftCorrectionKind.NONE -> controller.setPlaybackSpeed(state.playbackSpeed)
                DriftCorrectionKind.SEEK -> { controller.seekTo(correction.targetPositionMs); controller.setPlaybackSpeed(state.playbackSpeed) }
                DriftCorrectionKind.TEMPORARY_SPEED -> controller.setPlaybackSpeed(correction.temporarySpeed ?: state.playbackSpeed)
            }
            controller.play()
        } else if (state.status == WatchPartyStatus.paused || state.status == WatchPartyStatus.buffering || state.status == WatchPartyStatus.lobby) {
            partyLog.i {
                "hold seq=${state.sequence} status=${state.status} localMs=${playbackSnapshot.positionMs} " +
                    "expectedMs=$expected durationMs=$durationMs"
            }
            // `buffering` is the host stalling, not a position anyone chose. The host publishes it
            // from its own `isLoading` (see the heartbeat above), `expectedPartyPositionMs` freezes
            // at the last written position for any non-playing status, and the 500ms test below
            // then passes almost every time - so on torrent and debrid sources, where the host
            // rebuffers routinely, every host stall cost every guest a seek and a stall of its own.
            // Hold position and wait: it is a transient the host leaves within seconds, and the
            // position it froze at is stale by construction.
            if (state.status != WatchPartyStatus.buffering &&
                abs(playbackSnapshot.positionMs - expected) > 500L
            ) {
                controller.seekTo(expected)
            }
            // A nudge left running into a pause would drift the guest right back out again.
            controller.setPlaybackSpeed(state.playbackSpeed)
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
