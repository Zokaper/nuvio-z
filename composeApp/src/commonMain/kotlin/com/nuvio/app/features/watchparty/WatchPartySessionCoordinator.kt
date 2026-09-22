package com.nuvio.app.features.watchparty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import co.touchlab.kermit.Logger

private val promotionLog = Logger.withTag("WatchPartyPromotion")

private object RepositoryDurablePartyGateway : DurablePartyGateway {
    override val snapshots = WatchPartyRepository.uiState.map { it.party }.distinctUntilChanged()
    override fun currentParty() = WatchPartyRepository.uiState.value.party
    override fun activeProfileId() = WatchPartyRepository.uiState.value.activeProfileId
    override suspend fun restoreActiveParty() = WatchPartyRepository.restoreActive()
    override suspend fun fetchActiveParty() = WatchPartyRepository.fetchActive()
    override fun installAuthorizedParty(snapshot: WatchPartyState) = WatchPartyRepository.installAuthorizedSnapshot(snapshot)
    override suspend fun publishLocation(location: WatchPartyClientLocation) = WatchPartyRepository.setClientLocation(location)
    override suspend fun publishReadiness(
        state: SourceResolutionState,
        durationMs: Long?,
        sourceGeneration: Int?,
        sourceMatch: PartySourceMatch?,
    ) = WatchPartyRepository.updateReady(state, durationMs, sourceGeneration = sourceGeneration, sourceMatch = sourceMatch)
    override suspend fun promotePlaybackPresence(sessionId: String) = WatchPartyRepository.promotePresence(sessionId)
    override suspend fun leaveParty() = WatchPartyRepository.leave()
    override suspend fun endParty() = WatchPartyRepository.end()
    override fun updatePlaybackTelemetry(telemetry: PartyPlaybackTelemetry?) = WatchPartyRepository.updatePlaybackTelemetry(telemetry)
}

private sealed interface PartySessionIntent {
    data class Register(val context: ActivePlaybackContext, val sessionId: String, val deviceId: String) : PartySessionIntent
    data class Unregister(val attachmentId: String) : PartySessionIntent
    data class Lobby(val partyId: String) : PartySessionIntent
    data class Readiness(
        val state: SourceResolutionState,
        val durationMs: Long?,
        val sourceGeneration: Int?,
        val sourceMatch: PartySourceMatch?,
        /** Derived from the realizer rather than reported by a player. See `realizerReadinessMayPublish`. */
        val fromRealizer: Boolean = false,
    ) : PartySessionIntent
    data object Promote : PartySessionIntent
    data object DiscoverPromotion : PartySessionIntent
    data object Restore : PartySessionIntent
    data class Installed(val snapshot: WatchPartyState) : PartySessionIntent
    data object Leave : PartySessionIntent
    data object End : PartySessionIntent
    data class PartyEnded(val viewerWasHost: Boolean) : PartySessionIntent
    data object ContinueAfterEnd : PartySessionIntent
    data class Snapshot(val value: WatchPartyState?) : PartySessionIntent
}

/**
 * Serialized process owner for party session semantics.
 *
 * It carried a shadow comparison against the legacy repository snapshot while Stage 1 was switching
 * over. Nothing ever read it after the switch, and a comparison nobody looks at is not a safety net
 * - it is a second answer with no arbiter, which is the thing these stages exist to remove.
 */
object WatchPartySessionCoordinator {
    private val gateway: DurablePartyGateway = RepositoryDurablePartyGateway
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val intents = Channel<PartySessionIntent>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(PartySessionState())
    val state: StateFlow<PartySessionState> = _state.asStateFlow()
    private var presenceSessionId: String? = null
    private var presenceDeviceId: String? = null
    private var lastPublishedReadiness: PublishedPartyReadiness? = null
    private val _promotionFailures = MutableSharedFlow<PartyPromotionFailure>(extraBufferCapacity = 4)

    /** Refusals from [promoteCurrentPlayback], so a surface can say what happened. */
    val promotionFailures: SharedFlow<PartyPromotionFailure> = _promotionFailures.asSharedFlow()

    init {
        scope.launch { for (intent in intents) reduce(intent) }
        scope.launch { gateway.snapshots.collect { intents.send(PartySessionIntent.Snapshot(it)) } }
        // Readiness from the work itself, not from whichever screen happened to be composed.
        // Matching and resolving are the window the host's wait gate is looking at, and until this
        // ran nobody reported them: a member spent the whole preparation showing the party the
        // state they joined with.
        scope.launch {
            PartySourceRealizer.state
                .map(::partyReadinessReport)
                .distinctUntilChanged()
                .collect { report ->
                    report?.let { (state, sourceGeneration) ->
                        intents.send(
                            PartySessionIntent.Readiness(
                                state = state,
                                durationMs = null,
                                sourceGeneration = sourceGeneration,
                                sourceMatch = null,
                                fromRealizer = true,
                            ),
                        )
                    }
                }
        }
    }

    fun registerPlayback(context: ActivePlaybackContext, sessionId: String, deviceId: String) =
        enqueue(PartySessionIntent.Register(context, sessionId, deviceId))
    fun unregisterPlayback(attachmentId: String) = enqueue(PartySessionIntent.Unregister(attachmentId))
    fun enterLobby(partyId: String) = enqueue(PartySessionIntent.Lobby(partyId))
    fun reportReadiness(
        state: SourceResolutionState,
        durationMs: Long? = null,
        sourceGeneration: Int? = null,
        sourceMatch: PartySourceMatch? = null,
    ) = enqueue(PartySessionIntent.Readiness(state, durationMs, sourceGeneration, sourceMatch))
    fun reportPlaybackTelemetry(telemetry: PartyPlaybackTelemetry?) = gateway.updatePlaybackTelemetry(telemetry)
    fun promoteCurrentPlayback() = enqueue(PartySessionIntent.Promote)

    /**
     * Adopts a party someone else built from this client's playback - a friend's direct join, or a
     * join request accepted on another surface. See `WatchingNowJoin.kt` for why nothing else tells
     * the host. Quiet when there is none, and never while a live party is already held.
     */
    fun discoverPromotedParty() = enqueue(PartySessionIntent.DiscoverPromotion)
    fun restore() = enqueue(PartySessionIntent.Restore)
    fun installAuthorizedParty(snapshot: WatchPartyState) {
        // Preserve the established navigation contract: callers install the authorized snapshot
        // before opening its lobby. Only the semantic reducer update waits in the serialized queue.
        gateway.installAuthorizedParty(snapshot)
        enqueue(PartySessionIntent.Installed(snapshot))
    }
    fun leave() = enqueue(PartySessionIntent.Leave)
    fun end() = enqueue(PartySessionIntent.End)
    fun partyEnded(viewerWasHost: Boolean) = enqueue(PartySessionIntent.PartyEnded(viewerWasHost))
    fun continueAfterPartyEnd() = enqueue(PartySessionIntent.ContinueAfterEnd)

    private fun enqueue(intent: PartySessionIntent) { intents.trySend(intent) }

    private suspend fun reduce(intent: PartySessionIntent) {
        when (intent) {
            is PartySessionIntent.Register -> {
                val wasAttached = _state.value.playback?.attachmentId == intent.context.attachmentId &&
                    _state.value.phase == PartyClientPhase.ActivePlayer
                presenceSessionId = intent.sessionId
                presenceDeviceId = intent.deviceId
                val party = liveParty()
                _state.value = reducePartySession(_state.value, PartySessionEvent.PlayerAttached(intent.context, party?.partyGenerationKey()))
                if (party != null && !wasAttached) gateway.publishLocation(WatchPartyClientLocation.player)
            }
            is PartySessionIntent.Unregister -> {
                _state.value = reducePartySession(_state.value, PartySessionEvent.PlayerAttachmentLost(intent.attachmentId))
                if (_state.value.playback == null) {
                    presenceSessionId = null
                    presenceDeviceId = null
                    gateway.updatePlaybackTelemetry(null)
                }
            }
            is PartySessionIntent.Lobby -> {
                if (liveParty()?.id != intent.partyId) return
                _state.value = reducePartySession(_state.value, PartySessionEvent.LobbyEntered(intent.partyId))
                gateway.publishLocation(WatchPartyClientLocation.lobby)
            }
            is PartySessionIntent.Readiness -> {
                // ⚠ An ended party gets no readiness. A report queued by work that was already in
                // flight when the party ended would otherwise be a write to a party this member has
                // left - refused, and indistinguishable in the log from a party still preparing.
                val party = liveParty() ?: return
                val generation = intent.sourceGeneration
                if (intent.fromRealizer && generation != null &&
                    !realizerReadinessMayPublish(intent.state to generation, party.id, lastPublishedReadiness)
                ) {
                    promotionLog.i {
                        "readiness ${intent.state} from realizer skipped - already ${lastPublishedReadiness?.state} " +
                            "for party=${party.id.shortId()} srcGen=$generation"
                    }
                    return
                }
                lastPublishedReadiness = PublishedPartyReadiness(party.id, intent.state, generation)
                gateway.publishReadiness(
                    intent.state,
                    intent.durationMs,
                    intent.sourceGeneration,
                    intent.sourceMatch,
                )
            }
            PartySessionIntent.Promote -> {
                // ⚠ **Promotion is built from the presence row, not from the player.** The backend
                // reads `watch_presence` for this session - within 90 seconds of a heartbeat - and
                // raises `presence_stale` (P0002) when there is none. So this path could not have
                // worked at all before `c1c104fd`: presence publication outside a party was being
                // rejected by the sanitizer on every call, no row was ever written, and every
                // "Start Watch Together" failed on the server for a reason nothing here reported.
                //
                // That silence was the second half of it. `presenceSessionId` is only set by
                // `registerPlayback`, which needs a shareable descriptor, and a bare `?: return`
                // here made "no session registered" and "the server refused" indistinguishable
                // from a promotion that worked.
                val session = presenceSessionId
                if (session == null) {
                    promotionLog.w { "promote refused - no presence session registered for this playback" }
                    _promotionFailures.tryEmit(PartyPromotionFailure.NoPresenceSession)
                    return
                }
                if (liveParty() != null) {
                    promotionLog.w { "promote refused - a live party is already held" }
                    _promotionFailures.tryEmit(PartyPromotionFailure.AlreadyInAnotherParty)
                    return
                }
                // ⚠ Ask before writing. See `decidePartyPromotionPreflight`: a promotion that runs
                // while the server already holds this profile in a party is not a no-op, it is a
                // departure - and a departing host hands the party to whoever else is in it.
                when (
                    val preflight = decidePartyPromotionPreflight(
                        probe = gateway.fetchActiveParty(),
                        selfProfileId = gateway.activeProfileId(),
                        playback = _state.value.playback,
                        presenceSessionId = session,
                    )
                ) {
                    PartyPromotionPreflight.Promote -> Unit
                    is PartyPromotionPreflight.Adopt -> {
                        if (liveParty() == null) adoptPartyBuiltFromThisPlayback(preflight.party)
                        return
                    }
                    PartyPromotionPreflight.AlreadyInAnotherParty -> {
                        promotionLog.w { "promote refused - this profile is already in another live party" }
                        _promotionFailures.tryEmit(PartyPromotionFailure.AlreadyInAnotherParty)
                        return
                    }
                    PartyPromotionPreflight.Unverified -> {
                        promotionLog.w { "promote refused - could not confirm this profile holds no party" }
                        _promotionFailures.tryEmit(PartyPromotionFailure.Refused)
                        return
                    }
                }
                gateway.promotePlaybackPresence(session)
                    .onSuccess {
                        val party = gateway.currentParty() ?: return@onSuccess
                        val playback = _state.value.playback ?: return@onSuccess
                        _state.value = reducePartySession(_state.value, PartySessionEvent.PlayerAttached(playback, party.partyGenerationKey()))
                        gateway.publishLocation(WatchPartyClientLocation.player)
                    }
                    .onFailure { error ->
                        promotionLog.w(error) { "promote failed session=${session.take(8)}" }
                        _promotionFailures.tryEmit(
                            // The one failure a user can actually act on: their presence has not
                            // reached the server yet, and waiting a moment fixes it.
                            if (error.message?.contains("presence_stale") == true) {
                                PartyPromotionFailure.PresenceStale
                            } else {
                                PartyPromotionFailure.Refused
                            },
                        )
                    }
            }
            PartySessionIntent.DiscoverPromotion -> {
                if (liveParty() != null) return
                // Not `Restore`: that reduces to Connecting before it knows the answer, and the
                // common answer here - no party - must leave an ordinary player exactly as it was.
                // And a probe, not an install, because only one answer is this player's to adopt.
                gateway.fetchActiveParty().onSuccess { party ->
                    val live = party?.takeIf { it.status != WatchPartyStatus.ended } ?: return@onSuccess
                    // ⚠ **Host only.** Both server paths build the party from the *host's* presence
                    // and make the host its host. A guest who is waiting on a request is also a
                    // member once it is accepted, but that party is not this player's - the guest
                    // goes to its lobby through the approval watcher instead. Adopting it here would
                    // attach the guest's own unrelated playback to somebody else's party.
                    if (!shouldAdoptDiscoveredParty(live, gateway.activeProfileId(), liveParty())) return@onSuccess
                    if (liveParty() != null) return@onSuccess
                    adoptPartyBuiltFromThisPlayback(live)
                }
            }
            PartySessionIntent.Restore -> {
                _state.value = reducePartySession(_state.value, PartySessionEvent.RestoreStarted)
                gateway.restoreActiveParty().onSuccess { party ->
                    _state.value = if (party == null) reducePartySession(_state.value, PartySessionEvent.NoActiveParty)
                    else reducePartySession(_state.value, PartySessionEvent.Restored(party.partyGenerationKey()))
                }
            }
            is PartySessionIntent.Installed -> {
                _state.value = reducePartySession(_state.value, PartySessionEvent.Restored(intent.snapshot.partyGenerationKey()))
            }
            PartySessionIntent.Leave -> gateway.leaveParty().onSuccess {
                _state.value = reducePartySession(_state.value, PartySessionEvent.Left())
            }
            PartySessionIntent.End -> gateway.endParty().onSuccess {
                _state.value = reducePartySession(_state.value, PartySessionEvent.Ended(viewerWasHost = true))
            }
            is PartySessionIntent.PartyEnded -> _state.value = reducePartySession(_state.value, PartySessionEvent.Ended(intent.viewerWasHost))
            PartySessionIntent.ContinueAfterEnd -> _state.value = PartySessionState(playback = _state.value.playback)
            is PartySessionIntent.Snapshot -> observeSnapshot(intent.value)
        }
    }

    private suspend fun adoptPartyBuiltFromThisPlayback(live: WatchPartyState) {
        gateway.installAuthorizedParty(live)
        val generation = live.partyGenerationKey()
        promotionLog.i {
            "adopted a party built from this playback party=${live.id.shortId()} " +
                "host=${live.hostProfileId == gateway.activeProfileId()} attached=${_state.value.playback != null}"
        }
        _state.value = reducePartySession(_state.value, PartySessionEvent.Restored(generation))
        // The same in-place attachment a successful promotion makes: the player keeps
        // playing and simply becomes the party's.
        _state.value.playback?.let { playback ->
            _state.value = reducePartySession(_state.value, PartySessionEvent.PlayerAttached(playback, generation))
            gateway.publishLocation(WatchPartyClientLocation.player)
        }
    }

    private fun observeSnapshot(party: WatchPartyState?) {
        val before = _state.value
        _state.value = observePartySnapshot(before, party, gateway.activeProfileId())
        if (party?.status == WatchPartyStatus.ended && before.phase != _state.value.phase) {
            promotionLog.i {
                "party ended party=${party.id.shortId()} phase=${before.phase}->${_state.value.phase} " +
                    "postEndChoice=${_state.value.guestPostEndChoice}"
            }
        }
    }

    /** Durable member writes are only for a party that is still running. */
    private fun liveParty(): WatchPartyState? = gateway.currentParty()?.takeIf { it.status != WatchPartyStatus.ended }
}
