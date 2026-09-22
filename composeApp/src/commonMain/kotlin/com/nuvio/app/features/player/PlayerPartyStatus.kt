package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.watchparty.PartyPlaybackStatusInputs
import com.nuvio.app.features.watchparty.PartySourceActivityInputs
import com.nuvio.app.features.watchparty.PartySourceHandoff
import com.nuvio.app.features.watchparty.PartySourceMatch
import com.nuvio.app.features.watchparty.PartySourceTimelineDecision
import com.nuvio.app.features.watchparty.decidePartySourceHandoff
import com.nuvio.app.features.watchparty.partySourceActivity
import com.nuvio.app.features.watchparty.PartyPresentationProjector
import com.nuvio.app.features.watchparty.partyRealizationPhaseFor
import com.nuvio.app.features.watchparty.PartyRealtimeHealth
import com.nuvio.app.features.watchparty.PartySourceRealizationState
import com.nuvio.app.features.watchparty.PartySourceRealizer
import com.nuvio.app.features.watchparty.PartyStatusDebounceState
import com.nuvio.app.features.watchparty.PartyStatusLine
import com.nuvio.app.features.watchparty.PartyStatusPerson
import com.nuvio.app.features.watchparty.WatchPartyParticipant
import com.nuvio.app.features.watchparty.WatchPartyRepository
import com.nuvio.app.features.watchparty.WatchPartyStage
import com.nuvio.app.features.watchparty.WatchPartyState
import com.nuvio.app.features.watchparty.WatchPartyStatus
import com.nuvio.app.features.watchparty.WatchPartySync
import com.nuvio.app.features.watchparty.WatchPartyTickIntervalMs
import com.nuvio.app.features.watchparty.currentEpochMs
import com.nuvio.app.features.watchparty.debouncePartyStatus
import com.nuvio.app.features.watchparty.displayName
import com.nuvio.app.features.watchparty.matchesPlayback
import com.nuvio.app.features.watchparty.partyMembersAwaitingSource
import com.nuvio.app.features.watchparty.partyPlaybackGate
import com.nuvio.app.features.watchparty.projectPartyPlaybackStatus
import kotlinx.coroutines.delay

private val partyStatusLog = co.touchlab.kermit.Logger.withTag("WatchPartyStatus")

/** How often the pill re-reads the signals that are not Compose state (the stall guard, a pending seek). */
private const val PartyStatusTickMs = 250L
/**
 * The one status line the player shows about the party, debounced.
 *
 * Replaces `rememberWatchPartyStatus().bannerText()`. Every decision is in the pure
 * [projectPartyPlaybackStatus]; this only gathers what it reads from the signals that already exist
 * and runs the result through [debouncePartyStatus] on a clock, so the 700ms appear delay and the
 * 1200ms minimum still elapse when nothing else recomposes.
 */
@Composable
internal fun PlayerScreenRuntime.rememberPartyStatusLine(
    incomingRequester: PartyStatusPerson?,
    outgoingTarget: PartyStatusPerson?,
    panelOpen: Boolean,
): PartyStatusLine? {
    val partyUi by WatchPartyRepository.uiState.collectAsStateWithLifecycle()
    val syncState by WatchPartySync.state.collectAsStateWithLifecycle()
    val realization by PartySourceRealizer.state.collectAsStateWithLifecycle()
    val viewerId = partyUi.activeProfileId
    val matching = partyUi.party?.takeIf { it.matchesPlayback(parentMetaId, playbackSession.videoId) }

    // ⚠ **A guest's in-player episode handoff is exactly when the party stops matching this playback.**
    // The host moved the party to the next episode, so `matchesPlayback` is false for the seconds the
    // guest spends matching a source for it - the seconds the video has simply stopped. The party
    // this player last followed stays the status line's party while its realization is still working.
    var followedPartyId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(matching?.id) { if (matching != null) followedPartyId = matching.id }
    val realizationKey = when (val r = realization) {
        is PartySourceRealizationState.Matching -> r.key
        is PartySourceRealizationState.Resolving -> r.key
        is PartySourceRealizationState.FallbackRequired -> r.key
        is PartySourceRealizationState.Failed -> r.key
        else -> null
    }
    val party: WatchPartyState? = matching ?: partyUi.party?.takeIf {
        it.status != WatchPartyStatus.ended && it.id == followedPartyId && realizationKey?.partyId == it.id
    }
    val isHost = party != null && party.hostProfileId == viewerId

    var debounce by remember { mutableStateOf(PartyStatusDebounceState()) }
    // Also while a line is still shown or pending, so one whose condition cleared with the party
    // itself still gets its minimum and then goes, rather than staying up with no clock to end it.
    val needsClock = party != null || incomingRequester != null || outgoingTarget != null ||
        debounce.shown != null || debounce.candidate != null
    val nowMs by produceState(currentEpochMs(), needsClock) {
        value = currentEpochMs()
        while (needsClock) {
            delay(PartyStatusTickMs)
            value = currentEpochMs()
        }
    }

    // When the conditions that only count past a threshold began.
    var barrierSinceMs by remember { mutableStateOf(0L) }
    val barrierHolding = party != null && (partyHoldingForBarrier || partyPendingSeek != null)
    LaunchedEffect(barrierHolding) { barrierSinceMs = if (barrierHolding) currentEpochMs() else 0L }
    var realtimeUnhealthySinceMs by remember { mutableStateOf(0L) }
    val realtimeUnhealthy = partyUi.health.realtime == PartyRealtimeHealth.Connecting ||
        partyUi.health.realtime == PartyRealtimeHealth.Degraded
    LaunchedEffect(realtimeUnhealthy) { realtimeUnhealthySinceMs = if (realtimeUnhealthy) currentEpochMs() else 0L }

    val presentation = PartyPresentationProjector.project(
        party = party,
        selfProfileId = viewerId,
        health = partyUi.health,
        realtime = syncState,
        partyNowMs = WatchPartySync.partyNowMs(),
    )
    val projected = if (party == null) {
        projectPartyPlaybackStatus(
            PartyPlaybackStatusInputs(
                inParty = false,
                isHost = false,
                incomingRequester = incomingRequester,
                panelOpen = panelOpen,
                outgoingRequestTarget = outgoingTarget,
            ),
        )
    } else {
        val tickHold = syncState.tickHold.takeIf { !isHost && syncState.tickStatus != WatchPartyStatus.playing }.orEmpty()
        // The host's own waits, which are one thing to everybody looking at the screen: a stall it
        // took, a seek it is waiting to resume from, and a member who has stepped away.
        val held = if (isHost) {
            (partyAutoPausedForGuests + partyAwaitingResumeReadiness + partyAutoPausedForAway).distinct()
        } else {
            tickHold.filter { it != viewerId }
        }
        // Split by the away roster rather than by a second hold list on the wire. The host
        // publishes who it is waiting for and, separately, who is away; the intersection is the
        // only thing that can say which of the two waits this is, and deriving it on both sides
        // from the same two facts means the host and the guests cannot word it differently.
        val awayHold = held.filter { it in syncState.awayProfileIds }
        val stallHold = held.filter { it !in syncState.awayProfileIds }
        val realizationPhase = partyRealizationPhaseFor(realization, party.id)
        // What this client is doing about a source, derived from what it already knows rather than
        // from `loading = true`. Every input here is local state the player holds; none of it is a
        // new fact on the wire.
        val sourceActivity = partySourceActivity(
            PartySourceActivityInputs(
                inParty = true,
                isHost = isHost,
                partyStarted = partyStartReleasedKey == party.generationKey(),
                realization = realizationPhase,
                // The party's source moved and this player has not caught up with it. `Adopt` is
                // the same verdict the transition itself runs on, so the copy cannot disagree with
                // what the player is actually doing.
                adoptingNewPartySource = decidePartySourceHandoff(
                    party = party,
                    localDescriptor = activePartySourceDescriptor,
                    handledSourceGeneration = partyHandledSourceGeneration,
                ) is PartySourceHandoff.Adopt,
                hostAdvancingPartySource = isHost &&
                    partyReportedTimelineDecision == PartySourceTimelineDecision.AdvancePartySource,
                localAttempt = args.playbackAttempt,
                needsPartyMatch =
                    partyReportedTimelineDecision == PartySourceTimelineDecision.NeedsPartyMatch,
                usingCompatibleAlternate = partyLocalSourceMatch == PartySourceMatch.alternate,
                waitingForPartyReadiness = partyPendingResume != null,
                mediaReady = playbackSnapshot.durationMs > 0L,
                buffering = playbackSnapshot.isLoading,
            ),
        )
        val partyPaused = if (isHost) !playbackSnapshot.isPlaying else presentation.freshHostStatus != WatchPartyStatus.playing
        // Deferred by one tick interval: a stall hold's tick follows its `pause` command, and "Paused
        // by Seraph" flashing up before "Waiting for Ahmed to buffer" is the misreading this removes.
        val pausedBy = partyLastPauseActor
            ?.takeIf { partyPaused && tickHold.isEmpty() && nowMs - it.atEpochMs >= WatchPartyTickIntervalMs }
            ?.let { attribution -> party.members.firstOrNull { it.profileId == attribution.profileId } }
            ?.let { it.toStatusPerson() }
        projectPartyPlaybackStatus(
            PartyPlaybackStatusInputs(
                inParty = true,
                isHost = isHost,
                sourceActivity = sourceActivity,
                host = party.members.firstOrNull { it.profileId == party.hostProfileId }?.toStatusPerson(),
                realization = realizationPhase,
                realizationChangesEpisode = matching == null,
                handoffEpisodeLabel = party.content.let { content ->
                    if (content.season != null && content.episode != null) "S${content.season}E${content.episode}" else ""
                },
                positionUnreachable = partyPositionUnreachable,
                waitingForHostSource = party.sourceFingerprint == null || party.stage == WatchPartyStage.waiting_for_host_source,
                gate = partyPlaybackGate(
                    party = party,
                    viewerProfileId = viewerId,
                    hostStartReleased = partyStartReleasedKey == party.generationKey(),
                    hostBufferingReleased = false,
                ),
                // Set for guests too, the first time the durable row reads playing (PlayerWatchPartyEffect).
                partyStarted = partyStartReleasedKey == party.generationKey(),
                awaitingSource = if (isHost) {
                    partyMembersAwaitingSource(party, excludeProfileId = viewerId).map { it.toStatusPerson() }
                } else {
                    emptyList()
                },
                stallHoldOthers = stallHold.mapNotNull { id -> party.members.firstOrNull { it.profileId == id }?.toStatusPerson() },
                awayHoldOthers = awayHold.mapNotNull { id -> party.members.firstOrNull { it.profileId == id }?.toStatusPerson() },
                selfHeld = viewerId != null && viewerId in tickHold,
                hostBuffering = !isHost && presentation.freshHostStatus == WatchPartyStatus.buffering,
                timelinePlaying = presentation.freshHostStatus == WatchPartyStatus.playing,
                barrierHoldMs = if (barrierHolding && barrierSinceMs > 0L) nowMs - barrierSinceMs else 0L,
                incomingRequester = incomingRequester,
                panelOpen = panelOpen,
                realtimeUnhealthyMs = if (realtimeUnhealthy && realtimeUnhealthySinceMs > 0L) nowMs - realtimeUnhealthySinceMs else 0L,
                capability = partyUi.health.capability(),
                pausedBy = pausedBy,
                outgoingRequestTarget = outgoingTarget,
            ),
        )
    }

    LaunchedEffect(projected, nowMs) {
        debounce = debouncePartyStatus(debounce, projected, currentEpochMs())
    }
    // The visible line, logged on change only, so a trace can say what the viewer was actually shown
    // beside the realization and hold transitions that produced it.
    val shownKind = debounce.shown?.kind
    LaunchedEffect(shownKind, party?.id) {
        partyStatusLog.i { "status shown=${shownKind ?: "none"} party=${party?.id?.take(8)} realization=${realization::class.simpleName}" }
    }
    return debounce.shown
}

private fun WatchPartyParticipant.toStatusPerson() = PartyStatusPerson(
    profileId = profileId,
    // Asked as a stranger would, so a sentence about somebody is never about "You".
    name = displayName(viewerProfileId = null),
    avatarUrl = profile?.avatarUrl,
    avatarColorHex = profile?.avatarColorHex ?: "#1E88E5",
)
