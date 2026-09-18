package com.nuvio.app.features.social

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import co.touchlab.kermit.Logger
import com.nuvio.app.features.watchparty.WatchPartyRepository
import com.nuvio.app.features.watchparty.WatchPartySessionCoordinator
import com.nuvio.app.features.watchparty.WatchPartyState
import com.nuvio.app.features.watchparty.WatchPartyStatus
import com.nuvio.app.features.watchparty.currentEpochMs
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A lobby the store wants opened. The host collects this; see [OutgoingJoinRequestStore.lobbyRequests].
 */
data class JoinLobbyRequest(
    val binding: JoinRequestBinding,
    val party: WatchPartyState,
    val target: JoinRequestTarget,
    val content: JoinRequestContent,
)

/**
 * The process owner of Watching Now's outgoing Join / Ask to join.
 *
 * Replaces `joinApprovalWatch` + `awaitJoinApproval`, which lived on `MainAppContent`'s scope and so
 * could not survive a tab change in any sense a person could see, could not be cancelled, and could
 * not tell declined from expired. Every decision is [reduceOutgoingJoinRequest]'s; this object only
 * serializes events into it, keeps its state, and executes the effects it returns.
 *
 * ⚠ **The identity token is the safety.** [liveToken] moves at every identity boundary *before* the
 * boundary is queued, and every asynchronous result is compared against it both when it is produced
 * and again when it is reduced. A send, a status read, an invalidation or a countdown that finishes
 * after a profile switch, a sign-out, Social being turned off or the Watch Party capability going
 * away is dropped and logged under `SocialJoin`. None of them can open a lobby, depart a party or
 * show an outcome. Surfaces read only [state], so a boundary clears the dock, the Watching Now card
 * and the player mirror in the same frame.
 */
object OutgoingJoinRequestStore {
    private val log = Logger.withTag("SocialJoin")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<OutgoingJoinRequestState>(OutgoingJoinRequestState.Idle)
    val state: StateFlow<OutgoingJoinRequestState> = _state.asStateFlow()

    private val _lobbyRequests = MutableSharedFlow<JoinLobbyRequest>(extraBufferCapacity = 4)

    /** Collected by the app shell, which owns navigation. Check [isCurrent] before acting. */
    val lobbyRequests: SharedFlow<JoinLobbyRequest> = _lobbyRequests.asSharedFlow()

    private class Envelope(val event: OutgoingJoinEvent, val done: CompletableDeferred<Unit>? = null)

    private val events = Channel<Envelope>(Channel.UNLIMITED)

    @Volatile private var liveToken = 0L
    @Volatile private var inOwnPlayer = false
    @Volatile private var invalidated = false
    @Volatile private var lastReadAtMs: Long? = null
    private var statusJob: Job? = null
    private var sendJob: Job? = null

    /** Tokens of sends cancelled while on the wire; their answers are released, not applied. */
    private val abandonedSendTokens = mutableSetOf<Long>()
    private val sendLock = SynchronizedObject()
    private val playerCountLock = SynchronizedObject()

    /** The item a request was started from, for "Ask again" / "Try again". */
    @Volatile private var lastItem: WatchingNowItem? = null

    private var started = false

    /** Idempotent. Called by the app shell once it owns the runtime. */
    fun start() {
        if (started) return
        started = true
        scope.launch { for (envelope in events) handle(envelope) }
        scope.launch {
            while (true) {
                delay(TickMs)
                tick()
            }
        }
        // The friend's presence going away ends a pending request. Only a settled, online state
        // counts: an empty Watching Now while loading or on the offline cache says nothing.
        scope.launch {
            SocialRepository.uiState
                .map { ui ->
                    if (ui.isLoading || ui.isOfflineCache || ui.errorMessage != null) null
                    else ui.watchingNow.map { it.sessionId }.toSet()
                }
                .distinctUntilChanged()
                .collect { sessions ->
                    val pending = _state.value as? OutgoingJoinRequestState.Pending ?: return@collect
                    if (sessions == null) return@collect
                    enqueue(
                        OutgoingJoinEvent.TargetPresence(
                            pending.binding,
                            stillWatching = pending.target.sessionId in sessions,
                            nowMs = currentEpochMs(),
                        ),
                    )
                }
        }
        scope.launch {
            WatchPartyRepository.uiState
                .map { ui -> ui.party?.takeIf { it.status != WatchPartyStatus.ended }?.id }
                .distinctUntilChanged()
                .collect { heldId ->
                    val bound = _state.value as? OutgoingJoinRequestState.Bound ?: return@collect
                    enqueue(OutgoingJoinEvent.HeldPartyChanged(bound.binding, heldId))
                }
        }
        // The Watch Party capability going away is a boundary: nothing may keep asking to join.
        scope.launch {
            SocialRepository.uiState
                .map { it.activeProfileId to it.capabilities.watchPartyEnabled }
                .distinctUntilChanged()
                .collect { (profileId, enabled) ->
                    if (!enabled && profileId != null && _state.value is OutgoingJoinRequestState.Bound) {
                        log.i { "watch party capability off - clearing the outgoing request" }
                        onIdentityBoundary(profileId, serverCleanup = true)
                    }
                }
        }
    }

    // ---------------------------------------------------------------------------- user actions

    /** Join / Ask to join on a Watching Now card. A new press replaces any earlier request. */
    fun ask(item: WatchingNowItem) {
        val profileId = SocialRepository.uiState.value.activeProfileId ?: return
        val binding = JoinRequestBinding(profileId, ++liveToken)
        lastItem = item
        enqueue(OutgoingJoinEvent.Send(binding, item.joinRequestTarget(), item.joinRequestContent()))
    }

    /** "Ask again" / "Try again": the same friend's current session if it is still listed. */
    fun askAgain() {
        val previous = lastItem ?: return
        val current = SocialRepository.uiState.value.watchingNow.firstOrNull {
            it.profile.profileId == previous.profile.profileId && it.sessionId == previous.sessionId
        } ?: previous
        ask(current)
    }

    fun cancel() = currentBinding()?.let { enqueue(OutgoingJoinEvent.CancelPressed(it)) }
    fun joinNow() = currentBinding()?.let { enqueue(OutgoingJoinEvent.JoinPressed(it)) }
    fun notNow() = currentBinding()?.let { enqueue(OutgoingJoinEvent.NotNowPressed(it)) }
    fun dismiss() = currentBinding()?.let { enqueue(OutgoingJoinEvent.Dismissed(it)) }

    private var ownPlayerCount = 0

    /**
     * The viewer entered or left their own player; an accepted request then waits for a choice.
     *
     * Counted, not a flag: a player replacing another composes its enter before the old one's dispose
     * runs, and a flag then read "not in a player" for the whole of the new film - the exact case an
     * accepted request must not count down and pull the viewer out of.
     */
    fun setInOwnPlayer(value: Boolean) {
        val next = synchronized(playerCountLock) {
            ownPlayerCount = (ownPlayerCount + if (value) 1 else -1).coerceAtLeast(0)
            ownPlayerCount > 0
        }
        if (inOwnPlayer == next) return
        inOwnPlayer = next
        currentBinding()?.let { enqueue(OutgoingJoinEvent.PlayerPresenceChanged(it, next)) }
    }

    fun isCurrent(binding: JoinRequestBinding): Boolean =
        binding.token == liveToken && (_state.value as? OutgoingJoinRequestState.Bound)?.binding == binding

    fun lobbyOpened(binding: JoinRequestBinding) = enqueue(OutgoingJoinEvent.LobbyOpened(binding))
    fun lobbyFailed(binding: JoinRequestBinding, message: String) = enqueue(OutgoingJoinEvent.LobbyFailed(binding, message))

    /** `social:<requester>` said a join request changed; read the status on the next tick. */
    fun onJoinRequestInvalidated() {
        invalidated = true
    }

    // ------------------------------------------------------------------------- boundaries (§4)

    /**
     * Profile switch, sign-out, Social disabled, or the capability gone.
     *
     * In order: move the token (every in-flight job is inert from this line), cancel the poll and
     * the send, clean up on the server *as the previous profile* within
     * [OutgoingJoinBoundaryCleanupTimeoutMs] - departing the party if the host had already accepted -
     * and clear with no outcome. Idempotent, and a no-op with nothing pending. Returns once the
     * cleanup has finished or timed out, so a caller can hold its own teardown behind it.
     */
    suspend fun onIdentityBoundary(previousProfileId: String?, serverCleanup: Boolean) {
        liveToken += 1
        statusJob?.cancel()
        // Not the send: cancelling the coroutine does not cancel the request the server is already
        // acting on. It finishes, sees the moved token and releases its own answer as its owner.
        start()
        val done = CompletableDeferred<Unit>()
        events.send(Envelope(OutgoingJoinEvent.IdentityBoundary(previousProfileId, serverCleanup), done))
        done.await()
    }

    /**
     * `LocalAccountDataCleaner`: clear now, without waiting. The server cleanup still goes out as the
     * previous profile on a best-effort basis; it simply cannot be relied on, since the session may
     * already be gone - an unanswered request then expires within two minutes.
     */
    fun onAccountWipe() {
        val owner = (_state.value as? OutgoingJoinRequestState.Bound)?.binding?.ownerProfileId
        liveToken += 1
        statusJob?.cancel()
        start()
        events.trySend(Envelope(OutgoingJoinEvent.IdentityBoundary(owner, serverCleanup = true)))
    }

    /**
     * Settles join requests this device abandoned at a boundary, once [profileId] is active again.
     *
     * The backstop for a cancel that never reached the server: a request the host accepted after the
     * boundary is a membership nobody here wants, and restoring it would drop the viewer into a party
     * they had walked away from. Accepted → leave that party; still pending → cancel it; anything else,
     * or older than [AbandonedJoinRequestMaxAgeMs], is forgotten.
     */
    suspend fun reconcileAbandoned(profileId: String) {
        val entries = loadAbandoned(profileId)
        if (entries.isEmpty()) return
        val now = currentEpochMs()
        val remaining = mutableListOf<AbandonedJoinRequest>()
        entries.forEach { entry ->
            if (now - entry.abandonedAtMs > AbandonedJoinRequestMaxAgeMs) return@forEach
            val status = SocialRepository.joinRequestStatus(entry.requestId).getOrElse {
                remaining += entry
                return@forEach
            }
            // A cleanup that fails keeps its entry: this list is the only record the request exists.
            val settled = when (status.status) {
                "accepted" -> status.party?.takeIf { it.status != WatchPartyStatus.ended }?.let { party ->
                    log.i { "reconcile: leaving party=${party.id.take(8)} from abandoned request=${entry.requestId.take(8)}" }
                    departAs(profileId, party.id)
                } ?: true
                "pending" -> SocialRepository.cancelJoinRequestAs(profileId, entry.requestId).isSuccess
                else -> true
            }
            if (!settled) remaining += entry
        }
        saveAbandoned(profileId, remaining)
    }

    // ------------------------------------------------------------------------------ internals

    private fun currentBinding(): JoinRequestBinding? = (_state.value as? OutgoingJoinRequestState.Bound)?.binding

    private fun enqueue(event: OutgoingJoinEvent) {
        start()
        if (event is OutgoingJoinEvent.Bound && event.binding.token != liveToken) {
            log.i { "stale ${event::class.simpleName} dropped before queueing" }
            return
        }
        events.trySend(Envelope(event))
    }

    private suspend fun handle(envelope: Envelope) {
        val event = envelope.event
        if (event is OutgoingJoinEvent.Bound && event.binding.token != liveToken) {
            log.i { "stale ${event::class.simpleName} dropped" }
            envelope.done?.complete(Unit)
            return
        }
        val transition = reduceOutgoingJoinRequest(_state.value, event)
        if (transition.state != _state.value) {
            log.i { "state ${_state.value::class.simpleName} -> ${transition.state::class.simpleName} on ${event::class.simpleName}" }
        }
        _state.value = transition.state
        val cleanups = transition.effects.mapNotNull { execute(it) }
        if (envelope.done != null) {
            withTimeoutOrNull(OutgoingJoinBoundaryCleanupTimeoutMs) { cleanups.joinAll() }
            envelope.done.complete(Unit)
        }
    }

    /** Returns the job for effects a boundary waits on. */
    private fun execute(effect: OutgoingJoinEffect): Job? = when (effect) {
        is OutgoingJoinEffect.SendJoin -> {
            val item = lastItem?.takeIf { it.sessionId == effect.target.sessionId && it.profile.profileId == effect.target.profileId }
            // ⚠ **Not `cancel()`.** Cancelling the previous send does not stop the server acting on it,
            // and it still held `joinInFlight`, so the new send's `tryLock` failed with "A join is
            // already in progress". Waiting lets the old one land and release its own answer first.
            val previous = sendJob
            sendJob = scope.launch {
                previous?.join()
                send(effect.binding, item)
            }
            null
        }
        is OutgoingJoinEffect.CancelOnServer -> scope.launch {
            val result = withTimeoutOrNull(OutgoingJoinBoundaryCleanupTimeoutMs) {
                SocialRepository.cancelJoinRequestAs(effect.ownerProfileId, effect.requestId)
            } ?: Result.failure(IllegalStateException("cancel timed out"))
            val answer = result.fold(
                onSuccess = { it.toAnswer() },
                onFailure = { JoinCancelAnswer.Failed(it.message ?: "Couldn't cancel. Try again.") },
            )
            if (effect.boundary) {
                if (answer !is JoinCancelAnswer.Failed) forgetAbandoned(effect.ownerProfileId, effect.requestId)
                boundaryCancelFollowUp(effect.ownerProfileId, answer)?.let { follow ->
                    (follow as? OutgoingJoinEffect.DepartParty)?.let { departAs(it.ownerProfileId, it.partyId) }
                }
                log.i { "boundary cancel request=${effect.requestId.take(8)} answer=${answer::class.simpleName}" }
            } else {
                enqueue(OutgoingJoinEvent.CancelAnswered(effect.binding, answer, currentEpochMs()))
            }
        }
        is OutgoingJoinEffect.DepartParty -> scope.launch { departAs(effect.ownerProfileId, effect.partyId) }
        is OutgoingJoinEffect.OpenLobby -> {
            val bound = _state.value as? OutgoingJoinRequestState.Bound
            if (bound != null) {
                _lobbyRequests.tryEmit(JoinLobbyRequest(effect.binding, effect.party, bound.target, bound.content))
            }
            null
        }
        is OutgoingJoinEffect.RememberAbandoned -> {
            rememberAbandoned(effect.ownerProfileId, effect.requestId)
            null
        }
        OutgoingJoinEffect.CancelJobs -> {
            statusJob?.cancel()
            null
        }
        is OutgoingJoinEffect.LogStale -> {
            log.i { "stale ${effect.event} ignored" }
            null
        }
        is OutgoingJoinEffect.AbandonSend -> {
            synchronized(sendLock) { abandonedSendTokens += effect.binding.token }
            null
        }
        is OutgoingJoinEffect.ReleaseOrphanedSend -> scope.launch { releaseOrphanedSend(effect.ownerProfileId, effect.answer) }
    }

    private suspend fun send(binding: JoinRequestBinding, item: WatchingNowItem?) {
        if (item == null) {
            enqueue(OutgoingJoinEvent.SendFailed(binding, "Couldn't join. Try again."))
            return
        }
        val step = joinWatchingNow(
            item = item,
            heldLivePartyId = {
                WatchPartyRepository.uiState.value.party?.takeIf { it.status != WatchPartyStatus.ended }?.id
            },
        )
        val orphaned = synchronized(sendLock) { abandonedSendTokens.remove(binding.token) } ||
            binding.token != liveToken
        val event = when (step) {
            null -> OutgoingJoinEvent.SendFailed(binding, "A join is already in progress")
            is WatchingNowJoinStep.OpenParty ->
                OutgoingJoinEvent.SendAnswered(binding, JoinSendAnswer.OpenParty(step.party), currentEpochMs(), inOwnPlayer)
            is WatchingNowJoinStep.AwaitApproval -> OutgoingJoinEvent.SendAnswered(
                binding,
                JoinSendAnswer.ApprovalRequired(step.requestId, step.expiresAtMs),
                currentEpochMs(),
                inOwnPlayer,
            )
            is WatchingNowJoinStep.Notice ->
                OutgoingJoinEvent.SendAnswered(binding, JoinSendAnswer.Notice(step.message), currentEpochMs(), inOwnPlayer)
            // Resolved inside `joinWatchingNow`; never escapes it.
            is WatchingNowJoinStep.ReleaseStrayMembership -> OutgoingJoinEvent.SendFailed(binding, "Couldn't join. Try again.")
        }
        if (orphaned) {
            // Cancelled, replaced or past a boundary while on the wire. Released here, in this job, so
            // a send queued behind it starts only once the server no longer holds this one.
            log.i { "join answer arrived for an abandoned request - releasing it" }
            (event as? OutgoingJoinEvent.SendAnswered)?.let { releaseOrphanedSend(binding.ownerProfileId, it.answer) }
            return
        }
        // Answers are exempt from the stale-token drop: one that crosses a boundary between here and
        // the reducer is still released there (`ReleaseOrphanedSend`).
        events.trySend(Envelope(event))
    }

    private suspend fun releaseOrphanedSend(ownerProfileId: String, answer: JoinSendAnswer) {
        when (answer) {
            is JoinSendAnswer.ApprovalRequired -> {
                val requestId = answer.requestId ?: return
                val result = withTimeoutOrNull(OutgoingJoinBoundaryCleanupTimeoutMs) {
                    SocialRepository.cancelJoinRequestAs(ownerProfileId, requestId)
                }?.getOrNull()
                when {
                    result == null -> rememberAbandoned(ownerProfileId, requestId)
                    result.outcome == "already_accepted" -> result.party?.let { departAs(ownerProfileId, it.id) }
                }
                log.i { "orphaned request=${requestId.take(8)} released outcome=${result?.outcome ?: "failed"}" }
            }
            is JoinSendAnswer.OpenParty -> {
                val current = _state.value
                // A request now in flight or opening may be for this very party; joining it again is
                // idempotent, and the join path already releases a stray membership it does not want.
                val stillWanted = current is OutgoingJoinRequestState.Sending ||
                    (current as? OutgoingJoinRequestState.Joining)?.party?.id == answer.party.id ||
                    (current as? OutgoingJoinRequestState.Accepted)?.party?.id == answer.party.id ||
                    WatchPartyRepository.uiState.value.party?.id == answer.party.id
                if (stillWanted) return
                log.i { "orphaned direct join released party=${answer.party.id.take(8)}" }
                departAs(ownerProfileId, answer.party.id)
            }
            is JoinSendAnswer.Notice -> Unit
        }
    }

    private fun tick() {
        val now = currentEpochMs()
        enqueue(OutgoingJoinEvent.Tick(now))
        val current = _state.value
        val decision = decideJoinRequestPoll(current, now, lastReadAtMs, invalidated)
        if (decision != JoinRequestPollDecision.ReadNow || statusJob?.isActive == true) return
        val bound = current as? OutgoingJoinRequestState.Bound ?: return
        val requestId = when (current) {
            is OutgoingJoinRequestState.Pending -> current.requestId
            is OutgoingJoinRequestState.Cancelling -> current.requestId
            else -> return
        }
        invalidated = false
        lastReadAtMs = now
        statusJob = scope.launch {
            val result = SocialRepository.joinRequestStatus(requestId).getOrNull() ?: return@launch
            if (bound.binding.token != liveToken) return@launch
            enqueue(
                OutgoingJoinEvent.StatusRead(
                    binding = bound.binding,
                    requestId = requestId,
                    status = runCatching { JoinRequestServerStatus.valueOf(result.status) }
                        .getOrDefault(JoinRequestServerStatus.pending),
                    party = result.party,
                    expiresAtMs = result.expiresAt?.let(::parseSocialTimestampMs),
                    nowMs = currentEpochMs(),
                    inOwnPlayer = inOwnPlayer,
                ),
            )
        }
    }

    /** Leave through the coordinator when it is the party held here, directly otherwise. */
    /** True once the departure was accepted (or handed to the coordinator, which owns its retries). */
    private suspend fun departAs(profileId: String, partyId: String): Boolean {
        val party = WatchPartyRepository.uiState.value
        return if (party.party?.id == partyId && party.activeProfileId == profileId) {
            WatchPartySessionCoordinator.leave()
            true
        } else {
            WatchPartyRepository.departMembershipAs(profileId, partyId)
                .onFailure { log.w(it) { "could not leave party=${partyId.take(8)} as ${profileId.take(8)}" } }
                .isSuccess
        }
    }

    private fun JoinCancelResult.toAnswer(): JoinCancelAnswer = when (outcome) {
        "cancelled" -> JoinCancelAnswer.Cancelled
        "already_accepted" -> JoinCancelAnswer.AlreadyAccepted(party)
        "already_closed", "not_found" -> JoinCancelAnswer.AlreadyClosed
        else -> JoinCancelAnswer.Failed("Couldn't cancel. Try again.")
    }

    @Serializable
    private data class AbandonedJoinRequest(val requestId: String, val abandonedAtMs: Long)

    private fun loadAbandoned(profileId: String): List<AbandonedJoinRequest> =
        SocialStorage.loadAbandonedJoinRequests(profileId)
            ?.let { runCatching { json.decodeFromString<List<AbandonedJoinRequest>>(it) }.getOrNull() }
            .orEmpty()

    private fun saveAbandoned(profileId: String, entries: List<AbandonedJoinRequest>) =
        SocialStorage.saveAbandonedJoinRequests(profileId, entries.takeIf { it.isNotEmpty() }?.let(json::encodeToString))

    private fun rememberAbandoned(profileId: String, requestId: String) {
        val entries = loadAbandoned(profileId).filterNot { it.requestId == requestId }
        saveAbandoned(profileId, entries + AbandonedJoinRequest(requestId, currentEpochMs()))
    }

    private fun forgetAbandoned(profileId: String, requestId: String) {
        val entries = loadAbandoned(profileId)
        if (entries.any { it.requestId == requestId }) saveAbandoned(profileId, entries.filterNot { it.requestId == requestId })
    }

    private const val TickMs = 500L

    /** Past this, an abandoned request has expired and been reaped on any backend state. */
    private const val AbandonedJoinRequestMaxAgeMs = 10 * 60_000L
}
