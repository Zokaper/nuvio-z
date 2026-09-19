package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.nuvio.app.features.playback.PlaybackSelectionContext
import com.nuvio.app.features.playback.PlaybackSourceCandidate
import com.nuvio.app.features.watchparty.PartyRealizationDecision
import com.nuvio.app.features.watchparty.PartySourceHandoff
import com.nuvio.app.features.watchparty.decidePartyRealization
import com.nuvio.app.features.watchparty.decidePartySourceHandoff
import com.nuvio.app.features.watchparty.tierPartyPlaybackSources
import com.nuvio.app.features.watchparty.PartyExactMatchTiers
import com.nuvio.app.features.watchparty.PartySourceRealizer
import com.nuvio.app.features.watchparty.partySourceKey
import com.nuvio.app.features.watchparty.DriftCorrectionKind
import com.nuvio.app.features.watchparty.DriftTracker
import com.nuvio.app.features.watchparty.PartyCommand
import com.nuvio.app.features.watchparty.PartyCommandKind
import com.nuvio.app.features.watchparty.PartyConnectionState
import com.nuvio.app.features.watchparty.PartyHoldReason
import com.nuvio.app.features.watchparty.PartyPlaybackGate
import com.nuvio.app.features.watchparty.PartyPlaybackTelemetry
import com.nuvio.app.features.watchparty.PartyPresentationProjector
import com.nuvio.app.features.watchparty.PartyPromotionFailure
import com.nuvio.app.features.watchparty.PartyTick
import com.nuvio.app.features.watchparty.SourceResolutionState
import com.nuvio.app.features.watchparty.PartySourceMatch
import com.nuvio.app.features.watchparty.StallHoldBudget
import com.nuvio.app.features.watchparty.WatchPartyControlMode
import com.nuvio.app.features.watchparty.WatchPartyDiagnostics
import com.nuvio.app.features.watchparty.WatchPartyIdleTickIntervalMs
import com.nuvio.app.features.watchparty.WatchPartyPausedAlignToleranceMs
import com.nuvio.app.features.watchparty.WatchPartyRepository
import com.nuvio.app.features.watchparty.WatchPartySessionCoordinator
import com.nuvio.app.features.watchparty.WatchPartySeekLandingPollMs
import com.nuvio.app.features.watchparty.partyCorrectionNominalSpeed
import com.nuvio.app.features.watchparty.WatchPartySnapshotIntervalMs
import com.nuvio.app.features.watchparty.WatchPartyStallWatchPollMs
import com.nuvio.app.features.watchparty.WatchPartyStatusSettleMs
import com.nuvio.app.features.watchparty.WatchPartyState
import com.nuvio.app.features.watchparty.WatchPartyStatus
import com.nuvio.app.features.watchparty.WatchPartySync
import com.nuvio.app.features.watchparty.WatchPartyTickIntervalMs
import com.nuvio.app.features.watchparty.arePartyDurationsCompatible
import com.nuvio.app.features.watchparty.currentEpochMs
import com.nuvio.app.features.watchparty.expectedPartyPositionMs
import com.nuvio.app.features.watchparty.actorDisplayName
import com.nuvio.app.features.watchparty.matchesPlayback
import com.nuvio.app.features.watchparty.memberMayControl
import com.nuvio.app.features.watchparty.partyActorNotice
import com.nuvio.app.features.watchparty.partyMembershipNotice
import com.nuvio.app.features.watchparty.PartySourceMatchTier
import com.nuvio.app.features.watchparty.partySourceMatchTier
import com.nuvio.app.features.watchparty.partyBarrierPlan
import com.nuvio.app.features.watchparty.partyFallbackDriftCorrection
import com.nuvio.app.features.watchparty.partyMembersAwaitingSource
import com.nuvio.app.features.watchparty.partyMembersPresent
import com.nuvio.app.features.watchparty.partyMembersStartingUp
import com.nuvio.app.features.watchparty.partyStartPlaybackRelease
import com.nuvio.app.features.watchparty.partyStartReleaseResumes
import com.nuvio.app.features.watchparty.partyPlaybackGate
import com.nuvio.app.features.watchparty.resolvePartyStartupHold
import com.nuvio.app.features.watchparty.partyGenerationKey
import com.nuvio.app.features.watchparty.partySeekPlan
import com.nuvio.app.features.watchparty.pendingPartySeek
import com.nuvio.app.features.watchparty.shortId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.nuvio.app.core.ui.NuvioToastController
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_party_cannot_share_source
import nuvio.composeapp.generated.resources.watch_party_promote_already_in_party
import nuvio.composeapp.generated.resources.watch_party_promote_failed
import nuvio.composeapp.generated.resources.watch_party_promote_presence_stale
import org.jetbrains.compose.resources.getString
import com.nuvio.app.features.watchparty.PartyContent
import com.nuvio.app.features.watchparty.PartyContentHandoff
import com.nuvio.app.features.watchparty.PartySourceDescriptorV2
import com.nuvio.app.features.watchparty.decidePartyContentHandoff
import com.nuvio.app.features.watchparty.shouldPublishPartyContentChange
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.isDesktop

/**
 * The decision half of the Watch Together trace; `WatchParty` carries the transport half.
 *
 * What this exists to answer, in order of how often it is the answer: did this player recognise the
 * party at all, what did the gate decide and who was it waiting on, and what did the correction
 * policy do about the position it was handed. Reaches the `DesktopDebugLog` file when desktop is
 * run with `-Dnuvio.debugTools=true`.
 */
/**
 * What a client tells the party it is doing.
 *
 * [shouldPlay] is the intent, and it is the third case that matters: a player that is starved
 * rather than paused reports neither playing nor loading, and calling that `paused` publishes a
 * deliberate pause the user never made - which every other member then obeys, pausing and seeking
 * to a frozen position. A host whose source stutters must look like a host who is buffering,
 * because that is what it is.
 *
 * A function rather than a value computed in composition, because the publishing loops are keyed
 * only on the party generation: anything they close over from the composition that launched them is
 * captured once and never updated again.
 */
private fun partyStatusFor(
    snapshot: PlayerPlaybackSnapshot,
    shouldPlay: Boolean,
    holding: Boolean = false,
): WatchPartyStatus = when {
    // A client parked for a barrier, or waiting for its own corrective seek to land, is neither
    // paused nor starved: it is doing what the party told it to and it will be playing again at an
    // instant that is already decided. `buffering` is right for the *timeline* - the position is
    // frozen and stale by construction, which is exactly what that status means - and wrong for the
    // guest's peer status, so the publisher below simply does not send one while a hold is on. The
    // two being the same fact is what held the party on every correction: the hold and the stall
    // guard's grace were the same length, so the guard fired every single time.
    holding -> WatchPartyStatus.buffering
    snapshot.isLoading -> WatchPartyStatus.buffering
    snapshot.isPlaying -> WatchPartyStatus.playing
    shouldPlay -> WatchPartyStatus.buffering
    else -> WatchPartyStatus.paused
}

/**
 * How much playable media this client is holding ahead of its own playhead.
 *
 * A member parked with less than this is empty rather than ready, whatever its transport says.
 * One second is short enough that an ordinary pause - where the engine keeps a buffer it is simply
 * not draining - is never mistaken for a stall, and long enough that a player sitting on the
 * single frame a seek just landed on is never mistaken for a recovery.
 */
private const val PartyStarvedBufferMs = 1_000L

/**
 * Whether this client's engine has run out of media, independent of what it has been told to do.
 *
 * ⚠ **[partyStatusFor] cannot answer this and must not be asked to.** Its `isLoading` case is the
 * engine reporting starvation *against an intent to play*, so the instant the party pauses a
 * starving player the starvation stops being reported - the player is no longer failing to play,
 * it is succeeding at being stopped. The host's stall guard then reads its own pause coming back
 * as the guest recovering. The S25 run of 2026-09-19 cost the party its only source that way; see
 * `GuestBufferingWatch`.
 *
 * Buffer occupancy is the fact underneath both, and no command can change it. An engine that does
 * not report a buffer position at all reads as not starved, which is the pre-existing behaviour and
 * the safe direction: a false `true` would hold a healthy party for a member that is fine.
 */
private fun partyStarvedFor(snapshot: PlayerPlaybackSnapshot): Boolean {
    if (snapshot.bufferedPositionMs <= 0L) return false
    return snapshot.bufferedPositionMs - snapshot.positionMs < PartyStarvedBufferMs
}

private val partyLog = Logger.withTag("WatchPartyPlayer")

/**
 * Identifies one stretch of shared playback.
 *
 * The whole authority tuple, because every part of it ends a stretch. Advancing an episode returns
 * the party to a lobby, so the start gate has to close again for the new content rather than stay
 * open because the previous one played. A source change is the same event for a different reason:
 * everybody is realizing something new and resumes together once they have it. And a host transfer
 * advances the epoch, after which commands, ticks and telemetry from the old authority are no
 * longer this stretch's. Omitting the last two is what let a source switch leave every party
 * effect running against the source it replaced.
 */
internal fun WatchPartyState.generationKey(): String =
    "$id:$contentGeneration:$sourceGeneration:$authorityEpoch"

/**
 * A position and the instant it was actually read.
 *
 * The pair is the whole fix for the standing sync error, so it is a type rather than two variables
 * that a later edit can quietly stop passing together.
 */
private data class SampledPosition(val positionMs: Long, val atEpochMs: Long)

/**
 * Reads the position now if the engine can be asked, and otherwise takes the last polled snapshot
 * *with the instant it arrived*.
 *
 * Desktop answers directly, so the pair is exact. The fallback is still honest: a 500ms-old sample
 * that knows it is 500ms old is usable, and a 500ms-old sample stamped with the current time is the
 * bug this whole change exists to remove.
 */
private fun PlayerScreenRuntime.samplePlaybackPosition(): SampledPosition {
    val direct = playerController?.samplePositionMs()
    return if (direct != null) {
        SampledPosition(direct, currentEpochMs())
    } else {
        SampledPosition(playbackSnapshot.positionMs, playbackSnapshotAtMs.takeIf { it > 0L } ?: currentEpochMs())
    }
}

/** The player's position now, for callers outside this file that only want the number. */
internal fun PlayerScreenRuntime.partyPositionNowMs(): Long = samplePlaybackPosition().positionMs

/**
 * Seeks to exactly [targetMs] and waits until the player is actually there.
 *
 * Two faults in one. The seek was a keyframe seek, so on a long-GOP release it landed up to nine
 * seconds early - the 2026-09-02 run has three consecutive corrections aiming at 20988, 23488 and
 * 25991 and landing at 18185 every time. And nothing waited for it, so the next pass half a second
 * later measured the *pre-seek* position, called it a fresh gap, and seeked again further ahead.
 * Seven corrective seeks and twenty engine seeks in two minutes, and the guest never converged.
 *
 * The wait is bounded and the record expires on its own, so a source that genuinely cannot serve the
 * position costs a slower correction rather than a wedged party.
 */
private suspend fun PlayerScreenRuntime.seekPartyToExact(targetMs: Long, reason: String) {
    val controller = playerController ?: return
    val issuedAtMs = currentEpochMs()
    partyPendingSeek = pendingPartySeek(targetMs = targetMs, nowMs = issuedAtMs)
    // The single choke point for every authoritative move of this playhead - drift, barrier,
    // pause-align, fallback-align - so it is the one place the startup watchdog's baseline can be
    // rebased from. See `partyAlignedBaselineMs`.
    partyAlignedBaselineMs = targetMs
    partyLog.i { "seek issue reason=$reason targetMs=$targetMs fromMs=${samplePlaybackPosition().positionMs}" }
    controller.seekToExact(targetMs)
    awaitPartySeekLanded()
}

/** Whether a seek this client issued is still in flight, clearing the record when it is not. */
private fun PlayerScreenRuntime.partySeekOutstanding(): Boolean {
    val pending = partyPendingSeek ?: return false
    val nowMs = currentEpochMs()
    val positionMs = samplePlaybackPosition().positionMs
    if (pending.isOutstanding(nowMs, positionMs)) return true
    partyPendingSeek = null
    val tookMs = nowMs - pending.issuedAtMs
    if (pending.timedOut(nowMs, positionMs)) {
        // Not an error on its own - an unbuffered position on a cold source takes what it takes -
        // but it is the line that names a seek that cannot land, so it is a warning.
        partyLog.w { "seek timeout targetMs=${pending.targetMs} landedMs=$positionMs tookMs=$tookMs" }
    } else {
        partyLog.i { "seek landed targetMs=${pending.targetMs} landedMs=$positionMs tookMs=$tookMs" }
    }
    return false
}

private suspend fun PlayerScreenRuntime.awaitPartySeekLanded() {
    while (partySeekOutstanding()) delay(WatchPartySeekLandingPollMs)
}

/**
 * Applies a playback rate only when it is not the one already running.
 *
 * The tick path evaluates a correction twice a second for the length of a film, and most of those
 * evaluations conclude "no change". Handing mpv the rate it is already at, 7,200 times over two
 * hours, is a filter-chain reconfiguration per call for nothing. The snapshot behind this can be up
 * to a polling interval stale, which costs at worst an occasional redundant call - the case this
 * exists to remove is the steady one, not the racing one.
 */
private fun PlayerScreenRuntime.applyPartySpeed(speed: Float, nominal: Float = speed) {
    notePartyNominalSpeed(actual = speed, nominal = nominal)
    if (abs(playbackSnapshot.playbackSpeed - speed) < 0.001f) return
    playerController?.setPlaybackSpeed(speed)
}

/**
 * Sets the engine's rate for the party, recording what the party's own speed is while it differs.
 *
 * Every party write of a rate goes through here or [applyPartySpeed], because a correction is only
 * invisible to the rest of the player if something remembers what it is correcting around.
 */
private fun PlayerScreenRuntime.setPartyEngineSpeed(actual: Float, nominal: Float = actual) {
    notePartyNominalSpeed(actual = actual, nominal = nominal)
    playerController?.setPlaybackSpeed(actual)
}

private fun PlayerScreenRuntime.notePartyNominalSpeed(actual: Float, nominal: Float) {
    partyNominalSpeedDuringCorrection = partyCorrectionNominalSpeed(actual = actual, nominal = nominal)
}

/** Same argument as [applyPartySpeed]: a player that is already playing does not need telling. */
private fun PlayerScreenRuntime.resumePartyPlayback() {
    shouldPlay = true
    if (!playbackSnapshot.isPlaying) playerController?.play()
}

/** The same instant, read in the party's terms. */
private fun partyInstantOf(epochMs: Long): Long = WatchPartySync.partyNowMs() - (currentEpochMs() - epochMs)

/**
 * Whether this player's file is a different length from the host's, past what a party can absorb.
 *
 * Only ever answered against the *host's* resolved duration, because the host is the one the shared
 * position means something in. Unknown on either side is not a mismatch: a stream that is still
 * opening reports nothing, and accusing it would flag every join.
 *
 * `arePartyDurationsCompatible` was written for this and had no caller at all, so a member who had
 * resolved a different cut - which is ordinary, since sources are chosen independently - found out
 * only when a seek landed them on their own last frame.
 */
internal fun WatchPartyState.hostDurationMismatch(localDurationMs: Long): Boolean {
    if (localDurationMs <= 0L) return false
    val hostDurationMs = members.firstOrNull { it.profileId == hostProfileId }?.resolvedDurationMs ?: return false
    return !arePartyDurationsCompatible(hostDurationMs, localDurationMs)
}

@Composable
internal fun PlayerScreenRuntime.BindWatchPartyEffect() {
    // ⚠ **Promotion runs on the coordinator's own scope, so its refusals have no caller to return
    // to.** Every one of them used to be a bare `return` or a discarded `Result`, which is how
    // "Start Watch Together" became a control that sometimes did nothing at all. This is the one
    // place a player is guaranteed to be composed while a promotion is in flight.
    LaunchedEffect(Unit) {
        WatchPartySessionCoordinator.promotionFailures.collect { failure ->
            // Inline in the panel's StartFailed state, with Try again, rather than a toast.
            partyPromotion = PartyPromotionProgress.Failed(failure)
        }
    }

    val partyUi by WatchPartyRepository.uiState.collectAsStateWithLifecycle()
    val matchingParty = partyUi.party?.takeIf { it.matchesPlayback(parentMetaId, playbackSession.videoId) }

    // Guests arriving and leaving, told to everyone already watching. Read from the repository's
    // stream rather than from composition, so two snapshots between frames cannot hide a change.
    LaunchedEffect(Unit) {
        var previous: com.nuvio.app.features.watchparty.WatchPartyState? = null
        WatchPartyRepository.uiState.collect { ui ->
            val current = ui.party?.takeIf { it.matchesPlayback(parentMetaId, playbackSession.videoId) }
            partyMembershipNotice(previous, current, ui.activeProfileId)?.let { notice ->
                partyLog.i { "membership notice=\"$notice\"" }
                if (isDesktop) {
                    playerNotificationMessage = notice
                    playerNotificationToken += 1
                } else {
                    showGestureMessage(notice)
                }
            }
            previous = current
        }
    }
    val generationKey = matchingParty?.generationKey()
    val isHost = matchingParty != null && matchingParty.hostProfileId == partyUi.activeProfileId
    val mediaLoaded = playbackSnapshot.durationMs > 0L
    val gate = partyPlaybackGate(
        party = matchingParty,
        viewerProfileId = partyUi.activeProfileId,
        hostStartReleased = generationKey != null && partyStartReleasedKey == generationKey,
        hostBufferingReleased = false,
    )

    // What the party is doing to this player, for the startup watchdog and for the log.
    //
    // Recomputed on every composition rather than in a `LaunchedEffect`, because the watchdog polls
    // it once a second from a coroutine that is not keyed on any of these inputs: a value that
    // updated a frame late would charge the source a second of hold it was in.
    val startupHold = resolvePartyStartupHold(
        inMatchingParty = matchingParty != null,
        gate = gate,
        holdingForBarrier = partyHoldingForBarrier,
        partyWantsPlayback = shouldPlay,
    )
    SideEffect { partyStartupHold = startupHold }
    LaunchedEffect(generationKey, startupHold) {
        if (generationKey == null) return@LaunchedEffect
        partyLog.i {
            "hold $generationKey role=${if (isHost) "host" else "guest"} held=${startupHold.isHeld} " +
                "reason=${startupHold.reason} gateReason=${startupHold.gateReason} " +
                "shouldPlay=$shouldPlay barrier=$partyHoldingForBarrier"
        }
    }

    // A party that is held but does not match this playback is silent by design and indistinguishable
    // from no party at all - which is exactly the shape of "Watch Together did nothing". Logged once
    // per player entry, with both identifiers, so a mismatch names itself.
    LaunchedEffect(partyUi.party?.id, parentMetaId, playbackSession.videoId) {
        val held = partyUi.party
        when {
            held == null -> partyLog.i { "bind party=none content=$parentMetaId video=${playbackSession.videoId}" }
            matchingParty != null -> partyLog.i {
                "bind party=${held.id.shortId()} matched role=${if (isHost) "host" else "guest"} " +
                    "status=${held.status} gen=${held.contentGeneration} video=${playbackSession.videoId}"
            }
            else -> partyLog.w {
                "bind party=${held.id.shortId()} MISMATCH status=${held.status} " +
                    "partyContent=${held.content.contentId}/${held.content.videoId} " +
                    "playerContent=$parentMetaId/${playbackSession.videoId}"
            }
        }
    }

    // Once the party is genuinely playing the start gate is spent. Without this the host's own pause
    // reads as "has not started yet", and the next readiness tick restarts the film under them.
    LaunchedEffect(generationKey, matchingParty?.status) {
        if (generationKey != null && matchingParty?.status == WatchPartyStatus.playing) {
            partyStartReleasedKey = generationKey
        }
    }

    // Readiness is what the host's gate waits on, so it has to be reported both ways: a stream that
    // is open, and one that is not open yet.
    LaunchedEffect(generationKey, mediaLoaded) {
        if (generationKey == null) return@LaunchedEffect
        if (mediaLoaded) {
            val match = matchingParty?.sourceFingerprint?.let { target ->
                val local = activePartySourceDescriptor
                if (local != null && partySourceMatchTier(target,local) in setOf(
                        PartySourceMatchTier.ExactTorrentFile,
                        PartySourceMatchTier.ExactOriginRelease,
                        PartySourceMatchTier.ExactRelease,
                        PartySourceMatchTier.EquivalentMedia,
                    )) {
                    PartySourceMatch.exact
                } else {
                    PartySourceMatch.alternate
                }
            }
            WatchPartySessionCoordinator.reportReadiness(
                SourceResolutionState.ready,
                playbackSnapshot.durationMs,
                sourceGeneration = matchingParty?.sourceGeneration,
                sourceMatch = match,
            )
        } else {
            WatchPartySessionCoordinator.reportReadiness(
                SourceResolutionState.resolving,
                sourceGeneration = matchingParty?.sourceGeneration,
            )
        }
    }

    // Said once, when it becomes true. Not a banner - two releases of the same film differ by more
    // than the tolerance often enough that one would be up for the whole film without anything ever
    // going wrong - but it is the first thing to look for when a member cannot follow the party, so
    // it belongs in the log rather than only on the HUD.
    val durationMismatch = matchingParty?.hostDurationMismatch(playbackSnapshot.durationMs) == true
    LaunchedEffect(generationKey, durationMismatch) {
        val state = matchingParty ?: return@LaunchedEffect
        if (!durationMismatch) return@LaunchedEffect
        val hostMs = state.members.firstOrNull { it.profileId == state.hostProfileId }?.resolvedDurationMs
        partyLog.w {
            "duration mismatch localMs=${playbackSnapshot.durationMs} hostMs=$hostMs - " +
                "this source is a different cut, positions past its end cannot be followed"
        }
    }

    // PlayerRoute disposal is only a local attachment transition. The durable source realization
    // remains valid for this generation and must not be downgraded to `resolving`: doing that made
    // a lobby visit look like a new preparation round to every member. A real source/content
    // generation change resets readiness on the backend and starts the matching flow explicitly.
    DisposableEffect(generationKey) {
        onDispose {
            if (generationKey != null) {
                partyBarrierAtMs = 0L
                partyReportedPeerStatus = null
                partyReportedPeerStarved = false
                partyHoldingForBarrier = false
                partyPendingSeek = null
                // A commanded playhead belongs to the generation that commanded it. Carried into
                // the next one it would be a baseline for a file the party has not placed yet.
                partyAlignedBaselineMs = null
                partyNominalSpeedDuringCorrection = null
                partyPositionUnreachable = false
                // Per content generation: a new episode is a new stream, and it deserves the
                // benefit of the doubt rather than inheriting the previous one's exhausted budget.
                partyStallHoldBudget = StallHoldBudget()
                partyLastPauseActor = null
                // "Don't wait" was an answer about one guest's stream on this episode.
                if (partyDontWaitGenerationKey == generationKey) {
                    partyDontWaitGenerationKey = null
                    WatchPartyRepository.setWaitForEveryone(true)
                }
            }
        }
    }

    LaunchedEffect(generationKey, gate.allowPlayback, mediaLoaded, isHost) {
        if (generationKey == null) return@LaunchedEffect
        partyLog.i {
            val waiting = matchingParty
                ?.let { partyMembersAwaitingSource(it, excludeProfileId = partyUi.activeProfileId) }
                ?.joinToString { "${it.profileId.shortId()}:${it.readyState}" }
                .orEmpty()
            "gate $generationKey role=${if (isHost) "host" else "guest"} allow=${gate.allowPlayback} " +
                "reason=${gate.reason} waitingOn=${gate.waitingOn} [$waiting] mediaLoaded=$mediaLoaded " +
                "released=${partyStartReleasedKey == generationKey}"
        }
        val controller = playerController ?: return@LaunchedEffect
        // Holding a stream that has not opened yet would stop it opening: the load is what produces
        // the duration this gate is waiting to hear about.
        if (!mediaLoaded) return@LaunchedEffect
        // A guest with a live timeline does not need this gate at all, and must not be driven by it:
        // the gate reads the database snapshot, which is up to five seconds old, while the timeline
        // is the same fact half a second old. Two authorities over one transport is how a guest ends
        // up paused by the older of them a moment after the newer one started it.
        if (!isHost && WatchPartySync.isPrecise()) return@LaunchedEffect
        // The intent the barrier resumes into, captured before the gate below can pause anything.
        if (isHost && partyStartReleasedKey != generationKey && partyStartIntentKey != generationKey) {
            partyStartIntentKey = generationKey
            partyStartIntentPlaying = shouldPlay
            partyLog.i { "start barrier $generationKey intent=${if (shouldPlay) "playing" else "paused"}" }
        }
        if (!gate.allowPlayback) {
            if (shouldPlay || playbackSnapshot.isPlaying) {
                partyLog.i { "pause src=start-barrier $generationKey reason=${gate.reason} waitingOn=${gate.waitingOn}" }
            }
            shouldPlay = false
            controller.pause()
            return@LaunchedEffect
        }
        if (isHost && partyStartReleasedKey != generationKey) {
            // Everyone has a source. Before the clock starts, everyone the barrier waited on must also
            // be able to *play*: `ready` only says the file opened. Releasing on it is what put the
            // physical run's guest on a cold stream at the barrier instant and handed its first
            // rebuffers to the stall guard - two more pause and play cycles for everybody. Polled,
            // because the evidence is live peer status and the timeout is elapsed time; this effect
            // is cancelled with the gate the moment another member starts waiting again.
            val durablyReadyAt = WatchPartySync.partyNowMs()
            var reported: List<String>? = null
            while (true) {
                // Start anyway: the host released it by hand while this was waiting.
                if (partyStartReleasedKey == generationKey) return@LaunchedEffect
                val live = WatchPartyRepository.uiState.value
                val party = live.party?.takeIf { it.generationKey() == generationKey } ?: return@LaunchedEffect
                val decision = partyStartPlaybackRelease(
                    members = party.members,
                    viewerProfileId = live.activeProfileId,
                    peerTelemetry = WatchPartySync.state.value.peerTelemetry,
                    realtimeLive = live.health.capability() == com.nuvio.app.features.watchparty.PartySyncCapability.FullSync,
                    partyNowMs = WatchPartySync.partyNowMs(),
                    durablyReadyAtPartyMs = durablyReadyAt,
                )
                if (decision.release) {
                    if (decision.timedOut) {
                        partyLog.w { "start barrier $generationKey timed out waiting for playback from [${decision.waitingOn.joinToString { it.shortId() }}]" }
                    }
                    releasePartyStart(generationKey, party, by = if (decision.timedOut) "timeout" else "allPlaybackReady")
                    return@LaunchedEffect
                }
                if (decision.waitingOn != reported) {
                    reported = decision.waitingOn
                    partyLog.i { "start barrier $generationKey waiting for playback from [${decision.waitingOn.joinToString { it.shortId() }}]" }
                }
                delay(WatchPartyStallWatchPollMs)
            }
        }
    }

    // Composition supplies fresh telemetry; the process-scoped repository poll owns liveness and
    // durable publication. Disposing this effect can no longer silently stop the heartbeat.
    LaunchedEffect(
        generationKey,
        playbackSnapshot.positionMs,
        playbackSnapshot.durationMs,
        nominalPlaybackSpeed,
        playbackSnapshot.isPlaying,
        playbackSnapshot.isLoading,
        shouldPlay,
        partyHoldingForBarrier,
    ) {
        val generation = matchingParty?.partyGenerationKey() ?: return@LaunchedEffect
        val snapshot = playbackSnapshot
        val sample = samplePlaybackPosition()
        WatchPartySessionCoordinator.reportPlaybackTelemetry(
            PartyPlaybackTelemetry(
                generation = generation,
                positionMs = sample.positionMs,
                capturedAtMs = sample.atEpochMs,
                durationMs = snapshot.durationMs,
                playbackSpeed = nominalPlaybackSpeed,
                status = partyStatusFor(snapshot, shouldPlay, partyHoldingForBarrier),
            ),
        )
    }

    /**
     * The host's timeline.
     *
     * Twice a second while playing, and the position is sampled and stamped on the same line -
     * which is the entire fix for the standing offset. Every other cadence in this feature is a
     * floor beneath this one.
     */
    LaunchedEffect(generationKey, isHost) {
        if (generationKey == null || !isHost) return@LaunchedEffect
        while (true) {
            val live = WatchPartyRepository.uiState.value.party
            if (live == null || live.status == WatchPartyStatus.ended) break
            val snapshot = playbackSnapshot
            val status = partyStatusFor(snapshot, shouldPlay, partyHoldingForBarrier)
            if (snapshot.durationMs > 0L) {
                val sample = samplePlaybackPosition()
                WatchPartySync.publishTick(
                    status = status,
                    positionMs = sample.positionMs,
                    capturedAtPartyMs = partyInstantOf(sample.atEpochMs),
                    playbackSpeed = nominalPlaybackSpeed,
                    durationMs = snapshot.durationMs,
                    hold = partyAutoPausedForGuests,
                )
            }
            delay(
                if (status == WatchPartyStatus.playing) WatchPartyTickIntervalMs else WatchPartyIdleTickIntervalMs,
            )
        }
    }

    /**
     * The host's status, published out of turn when it changes.
     *
     * The periodic loop alone is not enough at either end of a stall. Entering one it would be up
     * to half a second late, and every guest spends that half second playing on past a host that
     * has stopped. Leaving one it would be up to *two* seconds late, because a host that is not
     * playing ticks on the idle interval - so the recovery, which is the moment everyone is waiting
     * for, was the slowest thing in the feature.
     */
    val hostStatus = partyStatusFor(playbackSnapshot, shouldPlay, partyHoldingForBarrier)
    LaunchedEffect(generationKey, isHost, hostStatus) {
        if (generationKey == null || !isHost) return@LaunchedEffect
        if (playbackSnapshot.durationMs <= 0L) return@LaunchedEffect
        // Keyed on the status, so a flap cancels the pending publish rather than adding to it.
        if (hostStatus != WatchPartyStatus.buffering) delay(WatchPartyStatusSettleMs)
        val snapshot = playbackSnapshot
        val sample = samplePlaybackPosition()
        WatchPartySync.publishTick(
            status = partyStatusFor(snapshot, shouldPlay, partyHoldingForBarrier),
            positionMs = sample.positionMs,
            capturedAtPartyMs = partyInstantOf(sample.atEpochMs),
            playbackSpeed = nominalPlaybackSpeed,
            durationMs = snapshot.durationMs,
            hold = partyAutoPausedForGuests,
        )
    }

    // What a guest is doing, published the moment it changes rather than at the next heartbeat: the
    // host's grace for a stalled guest is shorter than any polling interval this feature has.
    //
    // Deliberately *not* holding-aware: a guest parked for a barrier or waiting for its own
    // corrective seek is doing what the party asked, and telling the host it is buffering is what
    // made the stall guard hold the party on every single correction. The host already knows a
    // barrier is in flight - it sent it - so silence here is the accurate answer, not a missing one.
    val peerStatus = partyStatusFor(playbackSnapshot, shouldPlay)
    // Keyed alongside the status, because the transition this whole mechanism turns on does not
    // change the status at all: a guest held paused by the host's stall guard reports `paused`
    // while it is empty and `paused` again once it has refilled, and the second one is the only
    // thing that ends the hold. Leaving it out of the key would publish the first and never the
    // second, which is a party stopped until [WatchPartyStallHoldMaxMs] gives up on it.
    val peerStarved = partyStarvedFor(playbackSnapshot)
    LaunchedEffect(generationKey, isHost, peerStatus, peerStarved, partyHoldingForBarrier) {
        if (generationKey == null || isHost) return@LaunchedEffect
        if (partyHoldingForBarrier) return@LaunchedEffect
        val edgeAtMs = currentEpochMs()
        // Keyed on the status, so a flap cancels the pending publish rather than adding to it - the
        // same debounce the host's status gets, and for a sharper reason here: the snapshot poll is
        // up to a full interval behind the player, so the first read after a hold ends still says
        // "not playing". Publishing that would be a stall report for a client that has this instant
        // been told to resume. The host's grace is measured in seconds; two hundred milliseconds of
        // honesty costs nothing against it.
        delay(WatchPartyStatusSettleMs)
        if (partyHoldingForBarrier) return@LaunchedEffect
        val settled = partyStatusFor(playbackSnapshot, shouldPlay)
        val settledStarved = partyStarvedFor(playbackSnapshot)
        if (partyReportedPeerStatus == settled && partyReportedPeerStarved == settledStarved) {
            return@LaunchedEffect
        }
        partyReportedPeerStatus = settled
        partyReportedPeerStarved = settledStarved
        // The first leg of "a guest buffered and the host waited": from this player's own edge to
        // the report. The host's `peer status` line carries the transit, and its `waiting for` line
        // the grace, so the whole delay can be read off the two logs.
        partyLog.i {
            "peer status settled=$settled starved=$settledStarved " +
                "bufferedAheadMs=${playbackSnapshot.bufferedPositionMs - playbackSnapshot.positionMs} " +
                "afterEdgeMs=${currentEpochMs() - edgeAtMs}"
        }
        WatchPartySync.publishPeerStatus(settled, settledStarved)
    }

    // Every transport action, host and guest alike, through one path and one instant.
    LaunchedEffect(generationKey) {
        if (generationKey == null) return@LaunchedEffect
        WatchPartySync.commands.collect { command -> executePartyBarrier(command) }
    }

    // The guest's correction, driven by the timeline rather than by a database row.
    LaunchedEffect(generationKey, isHost) {
        if (generationKey == null || isHost) return@LaunchedEffect
        var tracker = DriftTracker()
        WatchPartySync.ticks.collect { tick -> tracker = followPartyTick(tick, tracker) }
    }

    // Wait for everyone: a guest that stalls used to be left behind and then dragged back by a seek,
    // which on a torrent or debrid source is most of what a party feels like.
    //
    // Read on a clock rather than collected from the transport's state, because the edge that
    // matters most here is not a message. A guest recovering sends one status - the one that starts
    // the settle - and then has nothing more to say; the release is [WatchPartyStallRecoverySettleMs]
    // of silence later. Collecting messages, the host therefore learned it could start again only at
    // the guest's next liveness ping, and the 2026-09-10 run reported exactly that as the party
    // staying paused until somebody pressed play.
    LaunchedEffect(generationKey, isHost) {
        if (generationKey == null || !isHost) return@LaunchedEffect
        var reactedTo: List<String>? = null
        while (true) {
            // Read fresh each pass: membership and readiness move under this loop, and a member who is
            // still opening, or who has gone, must never be what the party stops for.
            val party = WatchPartyRepository.uiState.value.party
            val holding = WatchPartySync.refreshStallWatch(
                startingUp = party?.let { partyMembersStartingUp(it, WatchPartyRepository.uiState.value.activeProfileId) }.orEmpty(),
                present = party?.let(::partyMembersPresent),
            )
            if (holding != reactedTo) {
                reactedTo = holding
                reactToStalledGuests(holding)
            }
            delay(WatchPartyStallWatchPollMs)
        }
    }

    /**
     * The degraded ladder: the database anchor, when no timeline is arriving.
     *
     * Reached when the socket is down, or when the host is on a build that publishes no ticks. It
     * is the behaviour the feature shipped with, wider bands and all, and it is deliberately kept
     * whole rather than folded into the tick path: the two are correcting against evidence of
     * different quality, and a single set of bands would be either too loose for the good anchor or
     * far too tight for the bad one.
     */
    LaunchedEffect(
        matchingParty?.sequence,
        matchingParty?.stateUpdatedAt,
        partyUi.serverClockOffsetMs,
        mediaLoaded,
        isHost,
    ) {
        val state = matchingParty ?: return@LaunchedEffect
        if (isHost) return@LaunchedEffect
        if (WatchPartySync.isPrecise()) return@LaunchedEffect
        val controller = playerController ?: return@LaunchedEffect
        // Correcting a stream that has not loaded is how a guest ends up watching a black frame: the
        // seek lands on a player with no timeline, and the play that follows has nothing to play.
        val durationMs = playbackSnapshot.durationMs
        if (durationMs <= 0L || playbackSnapshot.isLoading) return@LaunchedEffect
        val updatedAt = runCatching { kotlin.time.Instant.parse(state.stateUpdatedAt).toEpochMilliseconds() }
            .getOrNull() ?: return@LaunchedEffect
        val serverNow = currentEpochMs() + partyUi.serverClockOffsetMs
        // A position past the end of this file is not a position, and the answer is to refuse it
        // rather than to clamp: clamping turns "the party is somewhere this copy does not reach"
        // into "go to the last frame", which is where a player stops. This is the anchor most able
        // to produce one - its `elapsed` is however long the row has been stale, so a party left
        // `playing` in the database runs it past the end of any film.
        val raw = expectedPartyPositionMs(state.positionMs, updatedAt, serverNow, state.status, state.playbackSpeed)
        val expected = partyPositionInThisFile(raw, durationMs) ?: return@LaunchedEffect
        // Same guard as the tick path, for the same reason: the position this whole effect is
        // measured against is the pre-seek one while a seek is in flight, and a correction taken
        // against that is a correction for a gap that has already been answered.
        if (partySeekOutstanding()) return@LaunchedEffect
        val local = samplePlaybackPosition().positionMs
        if (state.status == WatchPartyStatus.playing) {
            val correction = partyFallbackDriftCorrection(local, expected, state.playbackSpeed)
            // `raw` is kept beside `expected` on purpose: the two diverging means the shared clock
            // has run past the end of this file, which is the signature of a timeline that started
            // without anybody playing.
            partyLog.i {
                "fallbackDrift seq=${state.sequence} localMs=$local expectedMs=$expected rawMs=$raw " +
                    "durationMs=$durationMs driftMs=${expected - local} action=${correction.kind} " +
                    "offsetMs=${partyUi.serverClockOffsetMs}"
            }
            when (correction.kind) {
                DriftCorrectionKind.NONE -> setPartyEngineSpeed(state.playbackSpeed)
                DriftCorrectionKind.SEEK -> {
                    partyHoldingForBarrier = true
                    try {
                        // Paused across the seek so the landing is a fixed number to compare
                        // against: a player still running would move past the target while the
                        // seek was completing and never look like it had arrived. The `play` at
                        // the end of this branch starts it again.
                        controller.pause()
                        seekPartyToExact(
                            targetMs = partyPositionInThisFile(correction.targetPositionMs, durationMs)
                                ?: return@LaunchedEffect,
                            reason = "fallback-drift",
                        )
                    } finally {
                        partyHoldingForBarrier = false
                    }
                    setPartyEngineSpeed(state.playbackSpeed)
                }
                DriftCorrectionKind.TEMPORARY_SPEED ->
                    setPartyEngineSpeed(correction.temporarySpeed ?: state.playbackSpeed, nominal = state.playbackSpeed)
            }
            shouldPlay = true
            controller.play()
        } else if (state.status == WatchPartyStatus.paused ||
            state.status == WatchPartyStatus.buffering ||
            state.status == WatchPartyStatus.lobby
        ) {
            partyLog.i { "fallbackHold seq=${state.sequence} status=${state.status} localMs=$local expectedMs=$expected" }
            // `buffering` is the host stalling, not a position anyone chose, and the position it
            // froze at is stale by construction.
            // A nudge left running into a pause would drift the guest right back out again.
            setPartyEngineSpeed(state.playbackSpeed)
            shouldPlay = false
            controller.pause()
            if (state.status != WatchPartyStatus.buffering && abs(local - expected) > 500L) {
                partyHoldingForBarrier = true
                try {
                    seekPartyToExact(targetMs = expected, reason = "fallback-align")
                } finally {
                    partyHoldingForBarrier = false
                }
                controller.pause()
            }
        }
    }

    // The party moved to a different episode, and this player adopts it without going anywhere.
    //
    // ⚠ **Read from the whole party, not from `matchingParty`.** The moment the host advances,
    // `matchesPlayback` is false for everyone still on the previous episode - that is what it is
    // for - so the effects keyed on it correctly disengage and this one, keyed on the party's own
    // content, is what brings them back. Everything else is the source handoff's shape exactly:
    // the same catalogue, the same tiering, the same `decidePartyRealization`, the same readiness
    // reports, the same barrier. The only differences are that the sources loaded are the *new*
    // episode's and the swap carries the episode with it.
    val contentHandoff = decidePartyContentHandoff(
        party = partyUi.party,
        localContentId = parentMetaId,
        localVideoId = playbackSession.videoId,
        handledContentGeneration = partyHandledContentGeneration,
        // The host's own advance, still on its way to the server. See the function.
        pendingPublishedContentGeneration = partyPublishedContentGeneration,
    )
    // ⚠⚠ **The episode catalogue, not the sources catalogue.** This read `sourceState` - the
    // current episode's sources panel - while the effect below loads `loadEpisodeStreams`, which
    // fills `episodeStreamsState`. The two never met: the catalogue this decision waited on was
    // either empty and unsettled, so the guest waited forever on the previous episode while the host
    // held the party for it, or it still held the *previous* episode's sources from an earlier
    // source handoff, which is the wrong video to match against. That is hardware Bug 2
    // (2026-09-15): the host advanced, and every guest stayed where it was.
    val partyEpisodeCatalogue by PlayerStreamsRepository.episodeStreamsState.collectAsStateWithLifecycle()
    LaunchedEffect(contentHandoff) {
        val adopt = contentHandoff as? PartyContentHandoff.Adopt ?: return@LaunchedEffect
        if (adopt.target == null) {
            // The host has moved the party but not yet chosen a release for it. Nothing to realize;
            // say so and wait to be told again on the next generation.
            partyLog.i { "content handoff waiting for host source generation=${adopt.contentGeneration}" }
            WatchPartySessionCoordinator.reportReadiness(
                SourceResolutionState.waiting_for_host,
                sourceGeneration = adopt.sourceGeneration,
            )
            return@LaunchedEffect
        }
        partyContentHandoffInFlight = true
        partyLog.i {
            "content handoff begin contentGeneration=${adopt.contentGeneration} " +
                "sourceGeneration=${adopt.sourceGeneration} videoId=${adopt.content.videoId}"
        }
        WatchPartySessionCoordinator.reportReadiness(
            SourceResolutionState.fetching,
            sourceGeneration = adopt.sourceGeneration,
        )
        PlayerStreamsRepository.loadEpisodeStreams(
            type = contentType ?: parentMetaType,
            videoId = adopt.content.videoId,
            season = adopt.content.season,
            episode = adopt.content.episode,
        )
    }

    LaunchedEffect(contentHandoff, partyEpisodeCatalogue) {
        val adopt = contentHandoff as? PartyContentHandoff.Adopt ?: return@LaunchedEffect
        val target = adopt.target ?: return@LaunchedEffect
        if (!partyContentHandoffInFlight) return@LaunchedEffect
        // The collected value can be a frame older than the request the effect above just made, so
        // the catalogue is only trusted once the repository says it describes the adopted episode.
        val catalogue = partyEpisodeCatalogueFor(
            targetVideoId = adopt.content.videoId,
            loadedVideoId = PlayerStreamsRepository.episodeStreamsVideoId,
            catalogue = PlayerStreamsRepository.episodeStreamsState.value,
        ) ?: return@LaunchedEffect
        val candidates = catalogue.groups.flatMapIndexed { addonOrder, group ->
            group.streams.map { stream -> PlaybackSourceCandidate(stream = stream, addonOrder = addonOrder) }
        }
        val decision = decidePartyRealization(
            catalogueSettled = !catalogue.isAnyLoading &&
                (candidates.isNotEmpty() || catalogue.emptyStateReason != null),
            tiered = tierPartyPlaybackSources(
                host = target,
                candidates = candidates,
                normalOrder = emptyList(),
                selection = PlaybackSelectionContext(
                    isEpisode = adopt.content.season != null && adopt.content.episode != null,
                    allowTorrentSources = true,
                ),
            ),
        )
        when (decision) {
            PartyRealizationDecision.Wait -> return@LaunchedEffect
            PartyRealizationDecision.FallbackRequired -> {
                // The previous episode keeps playing. A member who cannot realize the party's new
                // episode is out of sync, not stranded, and the generation is never rolled back.
                partyContentHandoffInFlight = false
                partyHandledContentGeneration = adopt.contentGeneration
                partyLog.w { "content handoff unavailable generation=${adopt.contentGeneration}" }
                WatchPartySessionCoordinator.reportReadiness(
                    SourceResolutionState.choosing_fallback,
                    sourceGeneration = adopt.sourceGeneration,
                )
            }
            is PartyRealizationDecision.Resolve -> {
                val winner = decision.candidates.firstOrNull() ?: return@LaunchedEffect
                partyContentHandoffInFlight = false
                partyHandledContentGeneration = adopt.contentGeneration
                // ⚠ **The publish latch is deliberately *not* touched here.** Setting it to the
                // generation being adopted looks like the careful thing to do - this client did not
                // publish this content, so it should not publish it back - but it would then equal
                // `party.contentGeneration` for as long as the party stayed on this episode, and
                // `shouldPublishPartyContentChange` reads that as "already published". A guest
                // promoted to host would be unable to advance the party at all.
                //
                // Nothing is needed: re-announcing the episode the party is already on is refused
                // by the `content.videoId` test, which is the check that actually means it.
                WatchPartySessionCoordinator.reportReadiness(
                    SourceResolutionState.resolving,
                    sourceGeneration = adopt.sourceGeneration,
                )
                partyLog.i { "content handoff adopt generation=${adopt.contentGeneration}" }
                switchToEpisodeStream(winner.stream, adopt.content.toPartyEpisodeVideo(playerMetaVideos))
            }
        }
    }

    // The party's source moved, and this player adopts it without going anywhere.
    //
    // Everything about this is in-route by construction: the catalogue is the player's own sources
    // panel repository, the swap is the same `switchToSource` an in-player pick uses, and the old
    // source keeps playing the whole time. No navigation, no route replacement, no controller or
    // HWND teardown - which is the difference between an active source switch and the destructive
    // lobby round trip it replaces.
    val partyHandoff = decidePartySourceHandoff(
        party = matchingParty,
        localDescriptor = activePartySourceDescriptor,
        handledSourceGeneration = partyHandledSourceGeneration,
    )
    val partySourceCatalogue by PlayerStreamsRepository.sourceState.collectAsStateWithLifecycle()
    LaunchedEffect(partyHandoff) {
        val adopt = partyHandoff as? PartySourceHandoff.Adopt ?: run {
            // Nothing to adopt: either the party has no authoritative source yet, or this player is
            // already playing it. Recording the generation settles the decision without reporting
            // preparation nobody is doing. It also means a guest who deliberately picked an
            // alternate under host-only control is left on it rather than pulled back.
            matchingParty?.let { partyHandledSourceGeneration = it.sourceGeneration }
            return@LaunchedEffect
        }
        partySourceHandoffInFlight = true
        partyLog.i { "source handoff begin generation=${adopt.sourceGeneration}" }
        WatchPartySessionCoordinator.reportReadiness(
            SourceResolutionState.fetching,
            sourceGeneration = adopt.sourceGeneration,
        )
        PlayerStreamsRepository.loadSources(
            type = contentType ?: parentMetaType,
            videoId = activeVideoId ?: playbackSession.videoId,
            season = activeSeasonNumber,
            episode = activeEpisodeNumber,
        )
    }

    LaunchedEffect(partyHandoff, partySourceCatalogue) {
        val adopt = partyHandoff as? PartySourceHandoff.Adopt ?: return@LaunchedEffect
        if (!partySourceHandoffInFlight) return@LaunchedEffect
        val candidates = partySourceCatalogue.groups.flatMapIndexed { addonOrder, group ->
            group.streams.map { stream ->
                PlaybackSourceCandidate(stream = stream, addonOrder = addonOrder)
            }
        }
        val decision = decidePartyRealization(
            catalogueSettled = !partySourceCatalogue.isAnyLoading &&
                (candidates.isNotEmpty() || partySourceCatalogue.emptyStateReason != null),
            tiered = tierPartyPlaybackSources(
                host = adopt.target,
                candidates = candidates,
                normalOrder = emptyList(),
                selection = PlaybackSelectionContext(
                    isEpisode = activeSeasonNumber != null && activeEpisodeNumber != null,
                    allowTorrentSources = true,
                ),
            ),
        )
        when (decision) {
            PartyRealizationDecision.Wait -> return@LaunchedEffect
            PartyRealizationDecision.FallbackRequired -> {
                // The old source keeps playing. A member who cannot realize the party's new pick is
                // out of sync, not stranded, and the generation is never silently rolled back to
                // the one they can play - the party moved, and only the party can move it again.
                partySourceHandoffInFlight = false
                partyHandledSourceGeneration = adopt.sourceGeneration
                partyLog.w { "source handoff unavailable generation=${adopt.sourceGeneration}" }
                WatchPartySessionCoordinator.reportReadiness(
                    SourceResolutionState.choosing_fallback,
                    sourceGeneration = adopt.sourceGeneration,
                )
            }
            is PartyRealizationDecision.Resolve -> {
                val winner = decision.candidates.firstOrNull() ?: return@LaunchedEffect
                partySourceHandoffInFlight = false
                partyHandledSourceGeneration = adopt.sourceGeneration
                // Published before the swap, not after: this is the generation the member is now
                // preparing, and the host's wait gate reads it while the new source opens.
                WatchPartySessionCoordinator.reportReadiness(
                    SourceResolutionState.resolving,
                    sourceGeneration = adopt.sourceGeneration,
                )
                partyLog.i { "source handoff adopt generation=${adopt.sourceGeneration}" }
                // Not `switchToUserSelectedSource`: nobody here picked anything, and refunding the
                // credential budget for a source the party chose would hand an automatic retry a
                // fresh budget on every generation.
                switchToSource(winner.stream)
            }
        }
    }

    // An active player *is* this authority's automatic launch, so it spends that claim.
    //
    // Without this a member who adopted a source change in place, and then deliberately backed out
    // to the lobby, would be thrown straight back into the player by the lobby's start effect: the
    // claim for the new generation was never spent by anybody, because nobody navigated to make it.
    // Spending it here is the same statement the lobby's own hand-off makes, from the one place
    // that knows the player is already playing the party's release.
    LaunchedEffect(matchingParty?.partySourceKey(), activePartySourceDescriptor) {
        val key = matchingParty?.partySourceKey() ?: return@LaunchedEffect
        val local = activePartySourceDescriptor ?: return@LaunchedEffect
        if (partySourceMatchTier(key.descriptor, local) !in PartyExactMatchTiers) return@LaunchedEffect
        if (PartySourceRealizer.claimAutomaticLaunch(key)) {
            partyLog.i { "automatic launch claim spent by active player generation=${key.sourceGeneration}" }
        }
    }

    // Host transfer used to be decided twice: once by `party_transfer_stale_host`, which the
    // backend already runs from the heartbeat trigger, and once here by a local grace-and-claim
    // race against it - with a different candidate window, so the two could disagree about who the
    // host now is. Stage 5 left the server rule as the only one. This client learns the new host
    // from the state broadcast like every other authority change.
}

/**
 * Waits for a party instant, re-reading the clock as it goes.
 *
 * The offset can slew while a barrier is pending - it is being re-measured the whole time - so a
 * single `delay` computed once would land on the instant the clock believed in when the wait
 * started rather than the one it believes in when the wait ends.
 */
private suspend fun awaitPartyInstant(atPartyMs: Long) {
    while (true) {
        val remaining = atPartyMs - WatchPartySync.partyNowMs()
        if (remaining <= 0L) return
        delay(if (remaining > 100L) remaining - 50L else remaining)
    }
}

/**
 * Does what a command says, at the instant it says to.
 *
 * The host runs this too, on its own command, which is the point: one implementation means the host
 * and every guest reach the same frame at the same time by construction rather than by two pieces of
 * code agreeing.
 */
private suspend fun PlayerScreenRuntime.executePartyBarrier(command: PartyCommand) {
    val partyId = WatchPartyRepository.uiState.value.party?.id
    // Announced from here because this is the one place every accepted command passes through,
    // whoever issued it and whichever plane it arrived on - and it is announced before the plan is
    // built, so the viewer is told who moved the party at the same moment the party moves.
    announcePartyActor(command)
    val controller = playerController ?: run {
        WatchPartyDiagnostics.applied(command, partyId, outcome = "skipped-no-controller")
        return
    }
    val durationMs = playbackSnapshot.durationMs
    if (durationMs <= 0L) {
        WatchPartyDiagnostics.applied(command, partyId, outcome = "skipped-no-duration")
        return
    }
    val sample = samplePlaybackPosition()
    // A client with no clock estimate cannot be scheduled against one, and pretending otherwise
    // would put the barrier hours away or hours past. Reading the command as though it had arrived
    // exactly on time collapses it to what the transport did before barriers existed: go to the
    // position, act now. Which is the right answer when there is no shared instant to wait for.
    val clockUsable = WatchPartySync.isClockUsable()
    val partyNow = if (clockUsable) WatchPartySync.partyNowMs() else command.startAtPartyMs
    val plan = partyBarrierPlan(command, sample.positionMs, partyNow)
    partyLog.i {
        "barrier kind=${plan.kind} localMs=${sample.positionMs} seekToMs=${plan.seekToMs} " +
            "holdMs=${plan.holdMs} playAfter=${plan.playAfter} speed=${plan.speed}"
    }
    when (plan.kind) {
        PartyCommandKind.pause -> {
            shouldPlay = false
            controller.pause()
            // Aligned *while paused*, where closing a gap costs one frame rather than the visible
            // jump the same correction makes against a running player - and exactly, because a
            // keyframe seek can never satisfy the tolerance that decides whether to align at all,
            // which had a paused guest re-seeking to the same position every tick forever.
            plan.seekToMs?.let {
                // `return@let` and not `return`: the speed below still has to be restored, because a
                // nudge left running into a pause drifts the guest straight back out of it.
                val target = partyPositionInThisFile(it, durationMs) ?: return@let
                partyHoldingForBarrier = true
                try {
                    seekPartyToExact(target, reason = "pause-align")
                } finally {
                    partyHoldingForBarrier = false
                }
                // Said again rather than assumed: a seek can leave a paused player running on some
                // engines, and a guest that quietly resumes under a party pause is the worst of the
                // failures this feature can have.
                controller.pause()
            }
            setPartyEngineSpeed(plan.speed)
            WatchPartyDiagnostics.applied(command, partyId, outcome = "pause")
        }
        PartyCommandKind.speed -> {
            if (clockUsable) awaitPartyInstant(command.startAtPartyMs)
            setPartyEngineSpeed(plan.speed)
            WatchPartyDiagnostics.applied(command, partyId, outcome = "speed")
        }
        PartyCommandKind.play, PartyCommandKind.seek -> {
            partyBarrierAtMs = command.startAtPartyMs
            // Held, not "playing". Desktop drives the engine off `shouldPlay` through its own
            // effect, so setting the intent here started this player the moment the state changed
            // rather than at the instant the barrier names - which is the whole point of a barrier.
            // The flag carries the intent to the status publishers instead, and `shouldPlay` is
            // flipped where it means something: at the instant.
            partyHoldingForBarrier = true
            try {
                if (plan.seekToMs != null) {
                    val target = partyPositionInThisFile(plan.seekToMs, durationMs) ?: return
                    controller.pause()
                    seekPartyToExact(target, reason = "barrier")
                } else if (plan.holdMs > 0L) {
                    controller.pause()
                }
                setPartyEngineSpeed(plan.speed)
                if (plan.playAfter && clockUsable) awaitPartyInstant(command.startAtPartyMs)
            } finally {
                partyHoldingForBarrier = false
            }
            // A scrub on a paused party moves everybody and starts nobody.
            if (!plan.playAfter) {
                shouldPlay = false
                controller.pause()
                WatchPartyDiagnostics.applied(command, partyId, outcome = "seek-paused")
                return
            }
            shouldPlay = true
            controller.play()
            WatchPartyDiagnostics.applied(command, partyId, outcome = "${command.kind}-playing")
        }
    }
}

/**
 * The party's position, expressed in this file, or null when this file does not have one.
 *
 * Every party position used to be clamped into `0..durationMs - 1`, which quietly turns "the party
 * is somewhere this copy does not reach" into "go to the last frame" - and the last frame is where
 * the player stops. A guest whose source is shorter than the host's, or whose duration has not
 * settled yet on a stream that is still opening, was therefore seeked to the end of its own file and
 * pinned there: the correction then measured no drift, because both the target and the position were
 * the same clamped end, and agreed forever.
 *
 * Refusing is the honest answer. A position past the end is not a position, and a guest that cannot
 * follow the party should say so rather than sit on the credits.
 */
private fun PlayerScreenRuntime.partyPositionInThisFile(positionMs: Long, durationMs: Long): Long? {
    if (positionMs <= durationMs - 1L) {
        partyPositionUnreachable = false
        return positionMs.coerceAtLeast(0L)
    }
    partyLog.w {
        "position unreachable targetMs=$positionMs durationMs=$durationMs " +
            "overMs=${positionMs - durationMs} - refusing to seek to the end"
    }
    partyPositionUnreachable = true
    return null
}

/**
 * Follows the host's timeline.
 *
 * Everything this does was previously done against `(sequence, state_updated_at)` - a row whose
 * position and timestamp were taken at different instants, so the correction was chasing a target
 * that was systematically behind the host. The tick carries its own capture instant, so the gap
 * measured here is the real one.
 */
private suspend fun PlayerScreenRuntime.followPartyTick(tick: PartyTick, tracker: DriftTracker): DriftTracker {
    val controller = playerController ?: return tracker
    val durationMs = playbackSnapshot.durationMs
    // Correcting a stream that has not loaded is how a guest ends up watching a black frame: the
    // seek lands on a player with no timeline, and the play that follows has nothing to play.
    if (durationMs <= 0L || playbackSnapshot.isLoading) return tracker
    // One authority at a time. Until the clock is locked the database anchor is the better of two
    // imperfect answers, and its effect below is doing the work; two correction paths acting on one
    // player is how a guest gets seeked twice for the same gap.
    if (!WatchPartySync.isPrecise()) return tracker
    // A seek this client issued is still in flight, so the position everything below would be
    // measured against is the one from *before* it. Correcting against that is what turned one
    // corrective seek into a cascade of them, each aimed further ahead than the last.
    if (partySeekOutstanding()) return tracker
    val partyNow = WatchPartySync.partyNowMs()
    // A barrier is already putting this player exactly where it should be. Measuring against a
    // position it is deliberately holding would produce a correction for a gap that is intentional.
    if (partyNow < partyBarrierAtMs) return tracker
    // And a timeline captured before that barrier is about the party as it was *before* the command
    // everyone just obeyed. The host's next tick is up to half a second behind its own pause, so
    // without this a guest resumes at the barrier and is put straight back by the tick in flight.
    if (tick.capturedAtPartyMs < partyBarrierAtMs) return tracker
    // Checked against the tick in hand rather than relying on the one the transport happens to
    // hold, so this reads correctly wherever it is called from.
    if (tick.isStale(partyNow)) return tracker

    if (tick.status == WatchPartyStatus.playing) {
        val expected = tick.expectedPositionMs(partyNow).let { partyPositionInThisFile(it, durationMs) }
            ?: return tracker
        val local = samplePlaybackPosition().positionMs
        val outcome = tracker.next(local, expected, tick.playbackSpeed)
        partyLog.i {
            "drift localMs=$local expectedMs=$expected driftMs=${expected - local} " +
                "action=${outcome.correction.kind} offsetMs=${WatchPartySync.state.value.clockOffsetMs} " +
                "tickAgeMs=${partyNow - tick.capturedAtPartyMs}"
        }
        when (outcome.correction.kind) {
            DriftCorrectionKind.NONE -> applyPartySpeed(tick.playbackSpeed)
            DriftCorrectionKind.TEMPORARY_SPEED ->
                applyPartySpeed(outcome.correction.temporarySpeed ?: tick.playbackSpeed, nominal = tick.playbackSpeed)
            DriftCorrectionKind.SEEK -> {
                // Scheduled, like a host's seek: park on where the party *will* be and start when
                // it gets there. Nothing has to predict the reload cost, so being wrong about it
                // costs a slightly longer hold instead of the standing error a fixed lead left.
                val plan = partySeekPlan(tick, partyNow)
                partyBarrierAtMs = plan.resumeAtPartyMs
                applyPartySpeed(tick.playbackSpeed)
                // Held for the party's own reasons, so the host is not told this client is stalling.
                partyHoldingForBarrier = true
                try {
                    controller.pause()
                    val target = partyPositionInThisFile(plan.seekToMs, durationMs)
                    if (target != null) seekPartyToExact(target, reason = "drift")
                    awaitPartyInstant(plan.resumeAtPartyMs)
                } finally {
                    partyHoldingForBarrier = false
                }
            }
        }
        resumePartyPlayback()
        return outcome.tracker
    }

    // A nudge left running into a pause would drift the guest right back out again. Restored, and
    // the player stopped, before the align below, so the seek is not racing a rate change or a
    // position that is still moving under it.
    applyPartySpeed(tick.playbackSpeed)
    shouldPlay = false
    controller.pause()
    // `buffering` is the host stalling, not a position anyone chose. The host publishes it from its
    // own `isLoading` and the timeline freezes at the last written position, so aligning to it would
    // seek every guest to a position that is stale by construction - and on torrent and debrid
    // sources, where the host rebuffers routinely, that is a stall of one's own per stall of theirs.
    if (tick.status != WatchPartyStatus.buffering) {
        val local = samplePlaybackPosition().positionMs
        if (abs(local - tick.positionMs) > WatchPartyPausedAlignToleranceMs) {
            // Exact, and waited for. A keyframe seek could not land inside the tolerance that
            // decides whether to align at all, so a paused guest re-issued the same seek on every
            // tick for as long as the party stayed paused.
            partyHoldingForBarrier = true
            try {
                val target = partyPositionInThisFile(tick.positionMs, durationMs)
                if (target != null) seekPartyToExact(target, reason = "paused-align")
            } finally {
                partyHoldingForBarrier = false
            }
            // The align is a seek, and a seek on a paused player can leave it unpaused on some
            // engines; say it again rather than assume.
            controller.pause()
        }
    }
    return DriftTracker()
}

/**
 * Says who just moved the party, when it was not this viewer.
 *
 * Reads the actor off the server-authored command rather than assuming the host: a collaborative
 * party has as many possible actors as it has members, and presenting all of them as the host is
 * what the physical run reported. The name comes from the durable snapshot's social profile, which
 * is the same source the member list and the lobby already read.
 */
private fun PlayerScreenRuntime.announcePartyActor(command: PartyCommand) {
    val ui = WatchPartyRepository.uiState.value
    val party = ui.party?.takeIf { it.matchesPlayback(parentMetaId, playbackSession.videoId) } ?: return
    // ⚠ **Pause is a condition, not an event.** It used to be a toast like the rest, which was gone
    // in 900ms and left a party that stayed paused a minute with nothing saying why - and a stall
    // hold, which goes out as an ordinary `pause`, arrived as "Seraph paused" for something nobody
    // pressed. Recorded instead for the status pill, which defers it by a tick so a hold can say so.
    when (command.kind) {
        PartyCommandKind.pause -> {
            partyLastPauseActor = command.issuedByProfileId
                .takeIf { it != ui.activeProfileId }
                ?.let { PartyPauseAttribution(profileId = it, atEpochMs = currentEpochMs()) }
            return
        }
        PartyCommandKind.play -> partyLastPauseActor = null
        else -> Unit
    }
    // A seek's direction is what makes "skipped back" and "skipped ahead" different sentences, and
    // the only honest reading of it is against where this player is standing right now.
    val seekingBackwards = command.kind == PartyCommandKind.seek &&
        command.startPositionMs < samplePlaybackPosition().positionMs
    val notice = partyActorNotice(
        kind = command.kind,
        actorProfileId = command.issuedByProfileId,
        viewerProfileId = ui.activeProfileId,
        actorName = party.actorDisplayName(command.issuedByProfileId),
        seekingBackwards = seekingBackwards,
    ) ?: return
    partyLog.i { "actor ${command.kind} by=${command.issuedByProfileId.shortId()} notice=\"$notice\"" }
    // ⚠ **Desktop cannot see a Compose overlay here.** The chrome is drawn in the native controls
    // layer above the video surface, so anything Compose paints over that surface is invisible -
    // the same rule `RenderPlaybackOverlays` already states for the party banner, which is routed
    // to `PlayerControlsState` for exactly this reason.
    //
    // That is why naming the actor looked unfixed after it had been fixed. `issuedByProfileId` is
    // the true caller and has been since the backend began authoring commands, and this notice has
    // never consulted host-ness - but on desktop nobody ever saw it, and the only party text a
    // desktop viewer *did* see was the banner, whose vocabulary is host-centric ("Waiting for the
    // host to start", "Host is buffering"). A correct sentence drawn where it cannot be read is
    // indistinguishable from the wrong sentence.
    //
    // The native side already has a token-driven toast for precisely this shape of transient
    // message, and the party path already uses it for its own failures.
    if (isDesktop) {
        playerNotificationMessage = notice
        playerNotificationToken += 1
    } else {
        showGestureMessage(notice)
    }
}

/** Who paused the party, and when this player heard about it. */
internal data class PartyPauseAttribution(val profileId: String, val atEpochMs: Long)

/**
 * Ends the start barrier exactly once, resuming only if the party was playing when it closed.
 *
 * Everyone the barrier waited on is starting their stream now, so they get the start-up grace from
 * this instant: their first rebuffers are opening, not stalls. Then one `play` - or, for a host who
 * had paused before the barrier, nothing, and the party stays where the host left it.
 */
private fun PlayerScreenRuntime.releasePartyStart(generationKey: String, party: WatchPartyState, by: String) {
    if (partyStartReleasedKey == generationKey) return
    partyStartReleasedKey = generationKey
    val viewer = WatchPartyRepository.uiState.value.activeProfileId
    WatchPartySync.grantStartupGrace(party.members.map { it.profileId }.filter { it != viewer })
    val intentPlaying = partyStartIntentPlaying.takeIf { partyStartIntentKey == generationKey }
    val sample = samplePlaybackPosition()
    if (partyStartReleaseResumes(intentPlaying)) {
        partyLog.i { "gate released $generationKey by=$by intent=playing positionMs=${sample.positionMs}" }
        startPartyPlayback(sample.positionMs, source = "gate")
    } else {
        partyLog.i { "gate released $generationKey by=$by intent=paused - staying paused positionMs=${sample.positionMs}" }
    }
}

/**
 * "Don't wait" on the stall-hold pill: the host lets the party play on without the buffering guest.
 *
 * Turning the switch off alone would leave the party standing still, because the guard that took the
 * hold is the only thing that would ever release it and it has just been told to stop looking. So
 * the hold is released here, the same way the guard would have released it. Scoped to this
 * generation: the next episode starts with the host's preference back on.
 */
internal fun PlayerScreenRuntime.stopWaitingForStalledGuests() {
    val party = WatchPartyRepository.uiState.value.party
        ?.takeIf { it.matchesPlayback(parentMetaId, playbackSession.videoId) } ?: return
    if (party.hostProfileId != WatchPartyRepository.uiState.value.activeProfileId) return
    partyDontWaitGenerationKey = party.generationKey()
    WatchPartyRepository.setWaitForEveryone(false)
    val waited = partyAutoPausedForGuests
    if (waited.isEmpty()) return
    partyAutoPausedForGuests = emptyList()
    partyLog.i { "dont-wait: releasing hold for=${waited.joinToString { it.shortId() }}" }
    startPartyPlayback(samplePlaybackPosition().positionMs, source = "dont-wait")
}

/**
 * Holds the party for a guest whose stream has stalled, and starts it again together.
 *
 * Off is a legitimate answer - one bad connection should not be able to stop the film for everyone -
 * so this is a host-side switch rather than a rule.
 */
private suspend fun PlayerScreenRuntime.reactToStalledGuests(holding: List<String>) {
    if (!WatchPartyRepository.uiState.value.waitForEveryone) return
    val partyNow = WatchPartySync.partyNowMs()
    if (holding.isNotEmpty() && partyAutoPausedForGuests.isEmpty()) {
        if (!playbackSnapshot.isPlaying) return
        // A hold that has just been taken, or one taken too many times, is not taken again. A source
        // that flaps would otherwise produce a hold, a resume and another hold indefinitely, and a
        // party spent stopping and starting is worse than one member being a second behind.
        if (!partyStallHoldBudget.mayHold(partyNow)) {
            if (partyStallHoldBudget.exhausted(partyNow)) {
                partyLog.w {
                    "stall holds exhausted, playing on without " +
                        holding.joinToString { it.shortId() }
                }
            }
            return
        }
        partyStallHoldBudget = partyStallHoldBudget.record(partyNow)
        partyAutoPausedForGuests = holding
        // `intent=playing` is the fact the resume below depends on, so it is stated where the hold
        // is taken rather than inferred later from a party status that now reads `paused`.
        partyLog.i { "waiting for ${holding.joinToString { it.shortId() }} intent=playing" }
        pausePartyPlayback(samplePlaybackPosition().positionMs, source = "stall-guard")
    } else if (holding.isEmpty() && partyAutoPausedForGuests.isNotEmpty()) {
        val waited = partyAutoPausedForGuests
        partyAutoPausedForGuests = emptyList()
        partyLog.i { "stalled guests recovered, resuming for=${waited.joinToString { it.shortId() }}" }
        startPartyPlayback(samplePlaybackPosition().positionMs, source = "stall-guard")
    }
}

/**
 * Places the player at the party's position rather than at this profile's own resume point.
 *
 * A party has one position by definition. Continue Watching is per profile, so a guest who had
 * already seen half the film opened the player at their own bookmark and the correction policy then
 * had to drag them back - visibly, and through a seek that can cost the stream its buffer. Applied
 * during composition, before the surface reads the initial position, because on desktop that value
 * is handed to the engine at attach rather than seeked to afterwards.
 */
internal fun PlayerScreenRuntime.applyWatchPartyStartPosition(party: WatchPartyState?) {
    // Only ever a launch decision. Starting a party from inside a running player creates one at
    // position zero around the film already playing, and moving the host back to the beginning is
    // not what they asked for.
    if (initialLoadCompleted) return
    val matching = party?.takeIf { it.matchesPlayback(parentMetaId, playbackSession.videoId) } ?: return
    val key = matching.generationKey()
    if (partyStartPositionAppliedKey == key) return
    partyStartPositionAppliedKey = key
    val positionMs = matching.positionMs.coerceAtLeast(0L)
    partyLog.i {
        "startPosition $key partyMs=$positionMs replacedResumeMs=$activeInitialPositionMs " +
            "resumeFraction=$activeInitialProgressFraction"
    }
    activeInitialPositionMs = positionMs
    activeInitialProgressFraction = null
    initialSeekApplied = positionMs <= 0L
    // A guest opening into a party that is not playing starts parked. Its engine used to start on
    // load and be paused a moment later by the gate or the host's tick - a visible start and stop on
    // the joiner, and a `playing` status the host could mistake for a member already running. The
    // party's own `play` barrier is what starts it, at the instant everyone else starts.
    if (shouldStartParkedForParty(matching, WatchPartyRepository.uiState.value.activeProfileId)) {
        partyLog.i { "startPosition $key guest opens parked status=${matching.status}" }
        shouldPlay = false
    }
}

/** Whether a player opening into [party] should wait for the party's play rather than start itself. */
internal fun shouldStartParkedForParty(party: WatchPartyState, viewerProfileId: String?): Boolean =
    party.hostProfileId != viewerProfileId && party.status != WatchPartyStatus.playing

/** Whether this client's transport belongs to the party rather than to whoever pressed the button. */
internal fun PlayerScreenRuntime.partyOwnsTransport(): Boolean =
    WatchPartyRepository.uiState.value.party?.matchesPlayback(parentMetaId, playbackSession.videoId) == true

private fun PlayerScreenRuntime.mayControlParty(): Boolean {
    val ui = WatchPartyRepository.uiState.value
    val party = ui.party?.takeIf { it.matchesPlayback(parentMetaId, playbackSession.videoId) } ?: return false
    return party.memberMayControl(ui.activeProfileId)
}

/**
 * Says why a press did nothing.
 *
 * A guest without control has always been unable to move the party, but until the transport was
 * claimed the press still moved *their own* player, so it looked like it worked and desynced them.
 * Refusing it is right; refusing it silently would be reported as the party being broken.
 */
private fun PlayerScreenRuntime.refusePartyControl(): Boolean {
    showGestureMessage("The host controls playback")
    return true
}

/** Starts, or restarts, shared playback at an instant everyone can reach. */
private fun PlayerScreenRuntime.startPartyPlayback(
    positionMs: Long,
    source: String,
    diagnosticInputId: String? = null,
) {
    val uiAtInput = WatchPartyRepository.uiState.value
    val inputId = diagnosticInputId ?: WatchPartyDiagnostics.input(
        kind = PartyCommandKind.play,
        positionMs = positionMs,
        party = uiAtInput.party,
        actorProfileId = uiAtInput.activeProfileId,
        source = source,
    )
    // Named for the same reason a pause is: a party that starts and stops has to say who kept
    // asking it to, and "who" is the difference between a user, a readiness gate and a stall
    // guard that has decided a guest has recovered.
    partyLog.i { "play src=$source positionMs=$positionMs" }
    // A stall recorded before the party was playing is not evidence about the party playing. The
    // ten seconds a guest spends resolving its own source is reported as `buffering`, and left in
    // the window it made the party's first act a play followed instantly by a hold.
    WatchPartySync.resetStallWatch()
    val startAt = WatchPartySync.partyNowMs() + WatchPartySync.barrierLeadMs()
    // The durable submission *is* the barrier's route to every other member: no client may write
    // the authority plane, so `party_submit_command_v2` is what validates this command and emits
    // it. The local player has already moved by the time this runs.
    WatchPartySync.issueCommand(
        kind = PartyCommandKind.play,
        startPositionMs = positionMs,
        startAtPartyMs = startAt,
        playbackSpeed = nominalPlaybackSpeed,
        diagnosticInputId = inputId,
        submitDurable = { accepted -> scope.launch { WatchPartyRepository.submitAccepted(accepted) } },
    )
}

private fun PlayerScreenRuntime.pausePartyPlayback(
    positionMs: Long,
    source: String,
    diagnosticInputId: String? = null,
) {
    val uiAtInput = WatchPartyRepository.uiState.value
    val inputId = diagnosticInputId ?: WatchPartyDiagnostics.input(
        kind = PartyCommandKind.pause,
        positionMs = positionMs,
        party = uiAtInput.party,
        actorProfileId = uiAtInput.activeProfileId,
        source = source,
    )
    // Every pause names where it came from. A pause with no origin is exactly what the
    // 2026-09-02 run could not explain: the host's player stopped, no command was issued, and
    // the guests learned about it only from the timeline's status.
    partyLog.i { "pause src=$source positionMs=$positionMs" }
    WatchPartySync.issueCommand(
        kind = PartyCommandKind.pause,
        startPositionMs = positionMs,
        // Pause carries no lead. Pausing 60ms apart is worth far more than pausing together and
        // late, and the alignment that follows is paid while paused. Stamped with now rather
        // than zero so the `leadMs` beside it is a number a person can read - the plan is what
        // ignores the instant for a pause, not the sender.
        startAtPartyMs = WatchPartySync.partyNowMs(),
        playbackSpeed = nominalPlaybackSpeed,
        diagnosticInputId = inputId,
        submitDurable = { accepted -> scope.launch { WatchPartyRepository.submitAccepted(accepted) } },
    )
}

/**
 * A transport press, turned into a party barrier.
 *
 * Returns true when the party has taken the action, which on desktop is also the signal that stops
 * the native controls layer performing it: a guest without control must not move its own player,
 * and a host must not start before the instant it just told everybody else about.
 */
internal fun PlayerScreenRuntime.submitPartyPlayPause(isPlaying: Boolean, positionMs: Long): Boolean {
    if (!partyOwnsTransport()) return false
    val uiAtInput = WatchPartyRepository.uiState.value
    val kind = if (isPlaying) PartyCommandKind.play else PartyCommandKind.pause
    val inputId = WatchPartyDiagnostics.input(kind, positionMs, uiAtInput.party, uiAtInput.activeProfileId, source = "user")
    if (!mayControlParty()) {
        WatchPartyDiagnostics.rejected(inputId, kind, uiAtInput.party, uiAtInput.activeProfileId, reason = "permission")
        return refusePartyControl()
    }
    val party = WatchPartyRepository.uiState.value.party ?: return true
    // Pressing play while the gate is still holding is the force start: the host has decided not to
    // wait, and the command that follows is what tells everyone else the film has begun.
    if (isPlaying && partyStartReleasedKey != party.generationKey()) {
        partyLog.i { "gate released ${party.generationKey()} by=forceStart positionMs=$positionMs" }
    }
    if (isPlaying) partyStartReleasedKey = party.generationKey()
    // A pause pressed while the start barrier still holds is the user changing what it resumes into.
    if (!isPlaying && partyStartReleasedKey != party.generationKey() && partyStartIntentKey == party.generationKey()) {
        partyStartIntentPlaying = false
    }
    // The user has taken the transport back. Without this, a guest recovering later would have the
    // stall guard resume over a pause a person made in the meantime - the guard would be undoing a
    // decision it did not take.
    partyAutoPausedForGuests = emptyList()
    if (isPlaying) {
        startPartyPlayback(positionMs, source = "user", diagnosticInputId = inputId)
    } else {
        pausePartyPlayback(positionMs, source = "user", diagnosticInputId = inputId)
    }
    return true
}

internal fun PlayerScreenRuntime.submitPartySeek(positionMs: Long): Boolean {
    if (!partyOwnsTransport()) return false
    // Scrubbing a paused party is how anybody finds a scene, and the barrier used to resume everyone
    // when it landed - so dragging the bar on a paused film started it again, on every member. The
    // seek carries what the party was doing, because a guest cannot tell a scrub-while-paused from a
    // scrub-while-playing by looking at its own player.
    val resumeAfter = playbackSnapshot.isPlaying || shouldPlay
    // Nobody may ask the party to go somewhere this file does not reach. Every caller here is a
    // position from somewhere else - a scrub bar, a skip marker from a different cut, a ten second
    // step off the end - and one that overshoots is sent to every member, who each land on their own
    // last frame and stop. Bounded once, at the one place they all pass through.
    val durationMs = playbackSnapshot.durationMs
    val targetMs = if (durationMs > 0L) positionMs.coerceIn(0L, durationMs - 1L) else positionMs.coerceAtLeast(0L)
    val uiAtInput = WatchPartyRepository.uiState.value
    val inputId = WatchPartyDiagnostics.input(
        PartyCommandKind.seek,
        targetMs,
        uiAtInput.party,
        uiAtInput.activeProfileId,
        source = "user",
    )
    if (!mayControlParty()) {
        WatchPartyDiagnostics.rejected(
            inputId,
            PartyCommandKind.seek,
            uiAtInput.party,
            uiAtInput.activeProfileId,
            reason = "permission",
        )
        return refusePartyControl()
    }
    val startAt = WatchPartySync.partyNowMs() + WatchPartySync.barrierLeadMs()
    WatchPartySync.issueCommand(
        kind = PartyCommandKind.seek,
        startPositionMs = targetMs,
        startAtPartyMs = startAt,
        playbackSpeed = nominalPlaybackSpeed,
        playAfter = resumeAfter,
        diagnosticInputId = inputId,
        submitDurable = { accepted -> scope.launch { WatchPartyRepository.submitAccepted(accepted) } },
    )
    return true
}

internal fun PlayerScreenRuntime.submitPartySpeed(speed: Float): Boolean {
    if (!partyOwnsTransport()) return false
    val uiAtInput = WatchPartyRepository.uiState.value
    val inputId = WatchPartyDiagnostics.input(
        PartyCommandKind.speed,
        playbackSnapshot.positionMs,
        uiAtInput.party,
        uiAtInput.activeProfileId,
        source = "user",
    )
    if (!mayControlParty()) {
        WatchPartyDiagnostics.rejected(
            inputId,
            PartyCommandKind.speed,
            uiAtInput.party,
            uiAtInput.activeProfileId,
            reason = "permission",
        )
        return refusePartyControl()
    }
    val startAt = WatchPartySync.partyNowMs() + WatchPartySync.barrierLeadMs()
    val positionMs = samplePlaybackPosition().positionMs
    WatchPartySync.issueCommand(
        kind = PartyCommandKind.speed,
        startPositionMs = positionMs,
        startAtPartyMs = startAt,
        playbackSpeed = speed,
        diagnosticInputId = inputId,
        submitDurable = { accepted -> scope.launch { WatchPartyRepository.submitAccepted(accepted) } },
    )
    return true
}

/**
 * The party's side of a startup abandonment, for the one log line that ends a play.
 *
 * `none` when this player is not in a party, which is what makes the line safe to read: a source
 * abandoned with `party=none` really was abandoned on its own account.
 */
internal fun PlayerScreenRuntime.partyAbandonContext(): String {
    val ui = WatchPartyRepository.uiState.value
    val party = ui.party?.takeIf { it.matchesPlayback(parentMetaId, playbackSession.videoId) } ?: return "none"
    val hold = partyStartupHold
    return "role=${if (party.hostProfileId == ui.activeProfileId) "host" else "guest"}" +
        ",status=${party.status},stage=${party.stage}" +
        ",held=${hold.isHeld},holdReason=${hold.reason},gateReason=${hold.gateReason}" +
        ",shouldPlay=$shouldPlay,barrier=$partyHoldingForBarrier" +
        ",gen=${party.contentGeneration}/${party.sourceGeneration},epoch=${party.authorityEpoch}"
}

/**
 * The live sync numbers, for the playback HUD.
 *
 * Every previous round of this work was measured by pulling two log files off two machines and
 * lining up timestamps. `errMs` is the number the whole feature is judged on - how far this client
 * is from where the party says it should be - and it belongs somewhere a person can read it while
 * the film is playing.
 */
internal fun PlayerScreenRuntime.partyDiagnosticsLine(): String? {
    val ui = WatchPartyRepository.uiState.value
    val party = ui.party?.takeIf { it.matchesPlayback(parentMetaId, playbackSession.videoId) } ?: return null
    val isHost = party.hostProfileId == ui.activeProfileId
    val sync = WatchPartySync.state.value
    val partyNow = WatchPartySync.partyNowMs()
    val errMs = WatchPartySync.heldTick()
        ?.takeIf { !isHost && it.status == WatchPartyStatus.playing && !it.isStale(partyNow) }
        ?.let { it.expectedPositionMs(partyNow) - samplePlaybackPosition().positionMs }
    return buildString {
        append("party=${if (isHost) "host" else "guest"} errMs=${errMs ?: "-"} ")
        append("offsetMs=${sync.clockOffsetMs} locked=${sync.clockLocked} rttMs=${sync.bestRttMs} ")
        append("tickAgeMs=${WatchPartySync.tickAgeMs()} tickStatus=${sync.tickStatus} ")
        append("leadMs=${WatchPartySync.barrierLeadMs()} precise=${WatchPartySync.isPrecise()}")
        // Everything that explains a player which is not moving when the party is. Without these a
        // client held on purpose, one on a source that cannot reach the party, and one that has
        // simply broken all look identical on screen.
        if (partyHoldingForBarrier) append(" holding")
        if (party.hostDurationMismatch(playbackSnapshot.durationMs)) {
            val hostMs = party.members.firstOrNull { it.profileId == party.hostProfileId }?.resolvedDurationMs
            append(" durationMismatch=${playbackSnapshot.durationMs}vs$hostMs")
        }
        if (partyPositionUnreachable) append(" unreachable")
        partyPendingSeek?.let { append(" seekTo=${it.targetMs}") }
        if (sync.holdingProfiles.isNotEmpty()) {
            append(" waitingOn=${sync.holdingProfiles.joinToString { it.shortId() }}")
        }
        // How many times this host has stopped the party for somebody. Rising while the film plays
        // is the signature of a guard that is firing on its own corrections rather than on stalls.
        val holds = partyStallHoldBudget.holdsAtMs.size
        if (isHost && holds > 0) append(" holds=$holds")
    }
}

/**
 * The host moving the party to a different episode, published once per advance.
 *
 * ⚠ **This is the convergence point §11 asks for.** Next episode, autoplay-next and the episode
 * picker all end at the same local apply, so hooking the publish there is what makes manual and
 * automatic advances literally the same content-change path rather than two that agree by
 * inspection. There is no second resolver, no second matcher and no second barrier.
 *
 * A no-op for everyone but the host, and for a host who is already on this content: the server
 * refuses a non-host with `host_required`, and republishing the current content would burn a
 * generation for a change nobody made and reset every member to `fetching` for it.
 *
 * [descriptor] is null when the host switched to something a guest cannot obtain - a local
 * download. The party is still moved, because leaving it on the previous episode while the host
 * watches the next one is a worse answer than the state the backend already models for this:
 * `waiting_for_host_source`, with the members told `waiting_for_host` until the host picks a
 * shareable release.
 */
internal fun PlayerScreenRuntime.publishPartyEpisodeChange(
    episode: MetaVideo,
    descriptor: PartySourceDescriptorV2?,
) {
    val party = WatchPartyRepository.uiState.value.party
    val profileId = WatchPartyRepository.uiState.value.activeProfileId
    if (
        !shouldPublishPartyContentChange(
            party = party,
            profileId = profileId,
            nextVideoId = episode.id,
            publishedContentGeneration = partyPublishedContentGeneration,
        )
    ) {
        return
    }
    val current = party ?: return
    // Latched before the call, not after. The apply this runs from can be re-entered by a debrid
    // re-resolution of the same pick, and two advances for one episode change would reset every
    // member twice.
    partyPublishedContentGeneration = current.contentGeneration
    scope.launch {
        WatchPartyRepository.changeContent(
            content = PartyContent(
                contentId = parentMetaId,
                contentType = parentMetaType,
                videoId = episode.id,
                title = title,
                poster = poster,
                season = episode.season,
                episode = episode.episode,
                episodeTitle = episode.title,
            ),
            fingerprint = descriptor,
        ).onFailure { error ->
            // `stale_party_generation` (40001) is the optimistic-concurrency answer: somebody else
            // advanced the party first and theirs is the accepted advance. Releasing the latch lets
            // this client publish again if it is still the host of a party that has since moved on,
            // without ever competing for the generation it just lost.
            partyPublishedContentGeneration = null
            partyLog.w(error) { "content change refused generation=${current.contentGeneration} videoId=${episode.id}" }
        }
    }
}

/**
 * The party's current content as the episode object the in-route switch needs.
 *
 * Prefers this client's own metadata, matched by the video id the party agreed on and then by
 * season and episode number - the same two-step `partyLaunchArtwork` uses, and for the same reason:
 * two clients can carry differently-numbered ids for the same episode. That entry carries the
 * overview and thumbnail this client's addons resolved, which is what keeps the transition looking
 * like an ordinary next episode.
 *
 * Falls back to what the party carried. A member whose metadata has not loaded, or whose addons do
 * not list this episode at all, still changes episode correctly - with a thinner card - rather than
 * being left behind on the previous one, which is the only outcome that would actually break the
 * party.
 */
/**
 * The in-player episode catalogue, but only while it describes [targetVideoId].
 *
 * The content handoff has exactly one catalogue it may realize from - the *episode* streams it
 * requested for the party's new episode - and exactly one way to be wrong about it, which is to read
 * a catalogue that belongs to some other video. Null means "not yet": keep waiting.
 */
internal fun partyEpisodeCatalogueFor(
    targetVideoId: String,
    loadedVideoId: String?,
    catalogue: StreamsUiState,
): StreamsUiState? = catalogue.takeIf { loadedVideoId == targetVideoId }

private fun PartyContent.toPartyEpisodeVideo(known: List<MetaVideo>): MetaVideo =
    known.firstOrNull { it.id == videoId }
        ?: season?.let { s -> episode?.let { e -> known.firstOrNull { it.season == s && it.episode == e } } }
        ?: MetaVideo(
            id = videoId,
            title = episodeTitle.orEmpty(),
            season = season,
            episode = episode,
        )
