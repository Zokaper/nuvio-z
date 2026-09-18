package com.nuvio.app.features.watchparty

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.ZSessionBridge
import com.nuvio.app.core.network.ZSupabaseProvider
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.broadcastFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The party's timing plane, carried between clients over the channel that is already open.
 *
 * `WatchPartyRepository` owns the durable half - membership, readiness, who the host is, the
 * snapshot a late joiner needs - and every one of those still goes through Postgres, because none
 * of them is on the latency path and all of them are already correct. What used to be on that path
 * and should never have been is the position and the transport: a pause went host -> PostgREST ->
 * Postgres -> trigger -> Realtime -> guest, two server hops for a button press, and the best
 * measurement of it was 225ms.
 *
 * **That was implemented over one channel and it never worked.** A private channel's write
 * capability is decided once, at join, by inserting a stub `realtime.messages` row whose payload is
 * NULL; the policy called a validator that refused a missing `sender_profile_id`, so every member
 * was granted read and refused write for the life of the socket. Both clients subscribed, every
 * send returned local success - supabase-kt does not await an ack for a broadcast push, and a
 * refused push is answered with nothing at all - and no client broadcast was ever delivered, on
 * this run or on any historical one. See `watchPartyAuthorityTopic` for the full account.
 *
 * So the party has two topics, and which one a message arrived on is the whole of its authority:
 *
 *  - `party:<id>` carries what the **backend** authors - the durable `state` broadcasts, and the
 *    accepted transport command that `party_submit_command_v2` emits after validating it against
 *    the locked row. No client may write it, so a command's sender is knowable.
 *  - `party_peer:<id>` carries what **members** author - host position ticks, the clock exchange
 *    and per-member telemetry. Nothing on it may command the party, and every sender-sensitive
 *    message on it is checked against the durable snapshot before use: a tick only from the host
 *    the server named, a pong only from that host, for an exchange this client started.
 *
 * A pause therefore costs one round trip to Postgres rather than nothing, and the sender's own
 * player still moves immediately - [issueCommand] dispatches locally before it submits. The barrier
 * is what absorbs the difference, which is what barriers were for.
 *
 * State this object holds is deliberately not in `WatchPartyUiState`: it changes twice a second and
 * recomposing the lobby at that rate would be the cost of the feature. [state] carries the summary
 * the UI actually wants, and it is written only when something in it changes.
 */
internal object WatchPartySync : PartyRealtimeTransport {

    private val log = Logger.withTag("WatchPartySync")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(WatchPartySyncState())
    override val state: StateFlow<WatchPartySyncState> = _state.asStateFlow()

    /**
     * Barriers this client has to execute, host included.
     *
     * A host does not receive its own broadcast, so [issueCommand] emits here as well as sending -
     * which is what makes the host and every guest run the *same* code against the same instant.
     * Anything else and the two paths drift apart the first time one of them is changed.
     *
     * No replay: a player re-entering the screen must align from a tick, not re-execute a transport
     * action the user took minutes ago.
     */
    private val _commands = MutableSharedFlow<PartyCommand>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val commands: SharedFlow<PartyCommand> = _commands.asSharedFlow()

    /**
     * The host's timeline, at the rate it is published.
     *
     * Deliberately not part of [state]: this moves twice a second, and putting it where the lobby
     * collects it would recompose the screen at that rate for the whole film. The player is the
     * only thing that wants every one of them, so it is the only thing that gets them. Replay of
     * one, so a player that attaches between ticks aligns immediately rather than half a second
     * later.
     */
    private val _ticks = MutableSharedFlow<PartyTick>(
        replay = 1,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val ticks: SharedFlow<PartyTick> = _ticks.asSharedFlow()

    /**
     * The server-authored plane. Read-only by policy: no client may write `party:<id>` at all.
     *
     * Commands and durable state arrive here, which is what makes a command's sender knowable -
     * the backend stamped it from the row it had just locked, and nothing else can put a frame on
     * this topic. See `watchPartyAuthorityTopic`.
     */
    private var authorityChannel: RealtimeChannel? = null

    /** The member-written plane: ticks, the clock exchange, telemetry, presence. Never authority. */
    private var peerChannel: RealtimeChannel? = null
    private var boundPartyId: String? = null
    private var authorityCollector: Job? = null
    private var peerCollector: Job? = null
    private var stateCollector: Job? = null
    private var statusCollector: Job? = null
    private var clockJob: Job? = null
    private var healthMonitorJob: Job? = null
    private val desiredAuthority = MutableStateFlow<PartyAuthorityContext?>(null)

    private var clock = PartyClock()
    private var commandLog = PartyCommandLog()
    private var bufferWatch = GuestBufferingWatch()
    private var tick: PartyTick? = null
    private val guestRttMs = mutableMapOf<String, Long>()
    private val guestStatus = mutableMapOf<String, WatchPartyStatus>()
    private val guestLastTelemetryAtPartyMs = mutableMapOf<String, Long>()
    private val outstandingPings = mutableMapOf<String, Long>()
    private var commandCounter = 0L
    private var peerStatus: WatchPartyStatus? = null
    private var authority: PartyAuthorityContext? = null
    private var channelInstance: Long = 0L
    private var healthSink: (PartyHealthEvent) -> Unit = {}
    private var refreshRequest: () -> Unit = {}
    private var stateBroadcastSink: (JsonObject) -> Unit = {}
    private var failureSink: (String?) -> Unit = {}
    private var lastValidatedReceiveAtMs: Long? = null

    init {
        scope.launch {
            desiredAuthority.collectLatest { context ->
                if (context == null) closeChannel(clearProtocol = true)
                else maintainChannel(context.partyId, context.selfProfileId)
            }
        }
    }

    fun configure(
        health: (PartyHealthEvent) -> Unit,
        stateBroadcast: (JsonObject) -> Unit,
        refresh: () -> Unit,
        failure: (String?) -> Unit,
    ) {
        healthSink = health
        stateBroadcastSink = stateBroadcast
        refreshRequest = refresh
        failureSink = failure
    }

    override fun updateAuthority(context: PartyAuthorityContext?) {
        val previous = authority
        authority = context
        if (
            previous != null && context != null &&
            previous.partyId == context.partyId &&
            previous.selfProfileId == context.selfProfileId &&
            previous.generation != context.generation
        ) {
            invalidateGenerationState()
        }
        val desired = desiredAuthority.value
        if (desired?.partyId != context?.partyId || desired?.selfProfileId != context?.selfProfileId) {
            desiredAuthority.value = context
        }
    }

    /** The one place that decides whose clock this is: a host is the clock, so its offset is zero. */
    private fun isHost(): Boolean = authority?.let { it.hostProfileId == it.selfProfileId } == true

    fun partyNowMs(): Long {
        val now = currentEpochMs()
        return if (isHost()) now else clock.partyNowMs(now)
    }

    /** Whether the tight bands are earned: a fresh anchor on a clock this client has actually locked. */
    fun isPrecise(): Boolean {
        val held = tick ?: return false
        if (held.isStale(partyNowMs())) return false
        return isHost() || clock.locked
    }

    /**
     * Whether a party instant means anything on this machine yet.
     *
     * A guest that has exchanged nothing with the host has an offset of zero, which is not an
     * estimate - it is the absence of one, and two wall clocks are routinely seconds apart. A
     * barrier scheduled against it would be either far in the future or long past. The host is
     * always usable, because it *is* the clock.
     */
    fun isClockUsable(): Boolean = isHost() || clock.samples.isNotEmpty()

    fun heldTick(): PartyTick? = tick

    /** How old the held timeline is, for the debug overlay. -1 when there is none. */
    fun tickAgeMs(): Long = tick?.let { partyNowMs() - it.capturedAtPartyMs } ?: -1L

    /** The lead a barrier is given, sized from the worst round trip anyone has reported. */
    fun barrierLeadMs(): Long {
        val worstRtt = guestRttMs.values.filter { it >= 0 }.maxOrNull()
            ?: clock.bestRttMs.takeIf { it >= 0 }
            ?: 0L
        return watchPartyBarrierLeadMs(worstRtt / 2)
    }

    /**
     * Members the host is holding the party for, when the party waits for everyone.
     *
     * [GuestBufferingWatch.advance] is where both edges are decided - a stall becoming a hold when
     * the grace runs out, a hold ending when the held member has been ready again for the settle -
     * and neither of those is a message, so it has to be driven from a read rather than from
     * `observe`.
     */
    private fun advanceBufferWatch(): List<String> {
        val before = bufferWatch
        bufferWatch = bufferWatch.advance(partyNowMs())
        val now = partyNowMs()
        (bufferWatch.heldSinceByProfile.keys - before.heldSinceByProfile.keys).forEach { profileId ->
            WatchPartyDiagnostics.hold(
                partyId = boundPartyId,
                profileId = profileId,
                event = "start",
                engineState = guestStatus[profileId],
                telemetryAgeMs = guestLastTelemetryAtPartyMs[profileId]?.let { now - it } ?: -1L,
                holdAgeMs = 0L,
                classification = "genuine-stall-candidate",
            )
        }
        (before.heldSinceByProfile.keys - bufferWatch.heldSinceByProfile.keys).forEach { profileId ->
            WatchPartyDiagnostics.hold(
                partyId = boundPartyId,
                profileId = profileId,
                event = "release",
                engineState = guestStatus[profileId],
                telemetryAgeMs = guestLastTelemetryAtPartyMs[profileId]?.let { now - it } ?: -1L,
                holdAgeMs = before.heldSinceByProfile[profileId]?.let { now - it } ?: -1L,
                // A member released while it reads `paused` has recovered just as much as one
                // reading `playing`: the party's own hold is what stopped it, and `paused` rather
                // than `buffering` is the engine saying it is full. Only a member still reading
                // `buffering` at release was abandoned rather than waited out.
                classification = when (guestStatus[profileId]) {
                    WatchPartyStatus.playing, WatchPartyStatus.paused -> "recovered"
                    else -> "telemetry-stale"
                },
            )
        }
        return bufferWatch.holdingProfiles
    }

    fun holdingProfiles(): List<String> = advanceBufferWatch()

    /**
     * Re-reads the stall watch on a clock, and republishes only when it actually moved.
     *
     * The host's caller polls this because a hold's *release* has no message behind it: the guest's
     * last word is the status that starts the settle, and the settle has not run out at the instant
     * it arrives. Everything else that advances the watch is a message, so this is the only path by
     * which a pure elapsed-time transition reaches anybody.
     */
    fun refreshStallWatch(
        startingUp: Collection<String> = emptyList(),
        present: Set<String>? = null,
    ): List<String> {
        // Membership first: a member who has gone must stop being held before anything is decided.
        present?.let { bufferWatch = bufferWatch.retainOnly(it) }
        if (startingUp.isNotEmpty()) {
            bufferWatch = bufferWatch.graceStartup(startingUp, partyNowMs() + WatchPartyStartupStallGraceMs)
        }
        val holding = advanceBufferWatch()
        if (_state.value.holdingProfiles != holding) publishState()
        return holding
    }

    /** Starts the start-up grace for [profileIds] now - the instant a start barrier releases them. */
    fun grantStartupGrace(profileIds: Collection<String>) {
        if (profileIds.isEmpty()) return
        bufferWatch = bufferWatch.graceStartup(profileIds, partyNowMs() + WatchPartyStartupStallGraceMs)
    }

    /**
     * Forgets what everyone was doing before the party started playing.
     *
     * A guest reports `buffering` for the whole time it is resolving its own source, which is
     * routinely ten seconds and is not a stall - the readiness gate is what that phase is for. Left
     * in the window it became a hold the instant the gate released, so the party's first act was to
     * play and immediately pause again for somebody who was already ready.
     */
    fun resetStallWatch() {
        val reset = bufferWatch.resetKeepingStartupGrace()
        if (bufferWatch == reset) return
        bufferWatch = reset
        publishState()
    }

    private fun attach(
        authority: RealtimeChannel,
        peer: RealtimeChannel,
        partyId: String,
        channelInstance: Long,
    ) {
        resetProtocolState()
        this.authorityChannel = authority
        this.peerChannel = peer
        boundPartyId = partyId
        this.channelInstance = channelInstance
        WatchPartyDiagnostics.channelAttached(partyId)
        log.i {
            "attach party=${partyId.shortId()} role=${if (isHost()) "host" else "guest"} " +
                "authority=${watchPartyAuthorityTopic(partyId)} peer=${watchPartyPeerTopic(partyId)}"
        }
        authorityCollector = authority.broadcastFlow<JsonObject>(WatchPartySyncEvent)
            .onEach { payload -> receive(payload, PartyRealtimePlane.Authority) }
            .launchIn(scope)
        peerCollector = peer.broadcastFlow<JsonObject>(WatchPartySyncEvent)
            .onEach { payload -> receive(payload, PartyRealtimePlane.Peer) }
            .launchIn(scope)
        stateCollector = authority.broadcastFlow<JsonObject>("state")
            .onEach(stateBroadcastSink)
            .launchIn(scope)
    }

    private fun resetProtocolState() {
        WatchPartyDiagnostics.channelDetached(boundPartyId)
        authorityCollector?.cancel(); authorityCollector = null
        peerCollector?.cancel(); peerCollector = null
        stateCollector?.cancel(); stateCollector = null
        statusCollector?.cancel(); statusCollector = null
        clockJob?.cancel(); clockJob = null
        healthMonitorJob?.cancel(); healthMonitorJob = null
        boundPartyId = null
        clock = PartyClock()
        commandLog = PartyCommandLog()
        bufferWatch = GuestBufferingWatch()
        tick = null
        _ticks.resetReplayCache()
        guestRttMs.clear()
        guestStatus.clear()
        guestLastTelemetryAtPartyMs.clear()
        outstandingPings.clear()
        commandCounter = 0
        peerStatus = null
        lastValidatedReceiveAtMs = null
        _state.value = WatchPartySyncState()
    }

    /** Clears state scoped to the durable identity tuple without replacing a healthy channel. */
    private fun invalidateGenerationState() {
        commandLog = PartyCommandLog()
        bufferWatch = GuestBufferingWatch()
        tick = null
        _ticks.resetReplayCache()
        guestRttMs.clear()
        guestStatus.clear()
        guestLastTelemetryAtPartyMs.clear()
        outstandingPings.clear()
        commandCounter = 0
        peerStatus = null
        publishState()
    }

    private suspend fun maintainChannel(partyId: String, profileId: String) {
        var retryMs = 500L
        while (desiredAuthority.value?.let { it.partyId == partyId && it.selfProfileId == profileId } == true) {
            try {
                openChannel(partyId, profileId)
                retryMs = 500L
                val liveAuthority = authorityChannel ?: continue
                val livePeer = peerChannel ?: continue
                // Either plane going away is the transport going away. A party running on the
                // authority plane alone takes commands and never publishes a tick; one running on
                // the peer plane alone follows the host's position and never hears a pause. Both
                // are worse than a reconnect, and both would look "subscribed" to the health state.
                merge(liveAuthority.status.drop(1), livePeer.status.drop(1))
                    .first { it == RealtimeChannel.Status.UNSUBSCRIBED }
                healthSink(PartyHealthEvent.RealtimeDegraded(channelInstance))
            } catch (failure: Throwable) {
                if (partyChannelFailureIsScopeCancellation(failure)) throw failure
                reportOpenFailure(partyId, failure)
            } finally {
                val stillDesired = desiredAuthority.value?.let {
                    it.partyId == partyId && it.selfProfileId == profileId
                } == true
                closeChannel(clearProtocol = true, detached = !stillDesired)
            }
            healthSink(PartyHealthEvent.RealtimeDegraded(channelInstance))
            delay(retryMs)
            retryMs = (retryMs * 2).coerceAtMost(5_000L)
        }
    }

    private fun reportOpenFailure(partyId: String, failure: Throwable) {
        healthSink(PartyHealthEvent.RealtimeDegraded(channelInstance))
        WatchPartyDiagnostics.transport(
            "subscribe-failed", partyId, realtime = "disconnected",
            detail = failure::class.simpleName,
        )
        log.w { "realtime party=${partyId.shortId()} state=disconnected cause=${failure.message ?: failure::class.simpleName}" }
        failureSink("Live sync unavailable: ${failure.message ?: failure::class.simpleName}")
    }

    private suspend fun openChannel(partyId: String, profileId: String) {
        WatchPartyDiagnostics.transport("subscribe-start", partyId, realtime = "subscribing")
        if (!ZSessionBridge.ensureSession(profileId)) {
            throw IllegalStateException(ZSessionBridge.lastFailure ?: "Nuvio Z session unavailable")
        }
        ZSupabaseProvider.client.realtime.setAuth()
        channelInstance += 1
        val openingInstance = channelInstance
        healthSink(PartyHealthEvent.RealtimeConnecting(openingInstance))
        // Read-only by policy. `acknowledgeBroadcasts` and presence are deliberately absent: this
        // client never writes here, and asking to track presence on a topic it may not write would
        // be asking the server for a refusal on every reconnect.
        val nextAuthority = ZSupabaseProvider.client.channel(watchPartyAuthorityTopic(partyId)) {
            isPrivate = true
        }
        val nextPeer = ZSupabaseProvider.client.channel(watchPartyPeerTopic(partyId)) {
            isPrivate = true
            broadcast { acknowledgeBroadcasts = true }
            presence { key = profileId }
        }
        attach(nextAuthority, nextPeer, partyId, openingInstance)
        statusCollector = merge(nextAuthority.status, nextPeer.status).onEach { _ ->
            val authorityStatus = nextAuthority.status.value
            val peerStatus = nextPeer.status.value
            when {
                // Subscribed is the *conjunction*. Reporting it on the first plane to arrive is how
                // a half-open transport would be reported as live.
                authorityStatus == RealtimeChannel.Status.SUBSCRIBED &&
                    peerStatus == RealtimeChannel.Status.SUBSCRIBED ->
                    healthSink(PartyHealthEvent.RealtimeSubscribed(openingInstance))
                authorityStatus == RealtimeChannel.Status.UNSUBSCRIBED ||
                    peerStatus == RealtimeChannel.Status.UNSUBSCRIBED ||
                    authorityStatus == RealtimeChannel.Status.UNSUBSCRIBING ||
                    peerStatus == RealtimeChannel.Status.UNSUBSCRIBING ->
                    if (desiredAuthority.value?.partyId == partyId) {
                        healthSink(PartyHealthEvent.RealtimeDegraded(openingInstance))
                    }
                else -> healthSink(PartyHealthEvent.RealtimeConnecting(openingInstance))
            }
        }.launchIn(scope)
        withTimeout(WatchPartyChannelSubscribeTimeoutMs) {
            nextAuthority.subscribe(blockUntilSubscribed = true)
            nextPeer.subscribe(blockUntilSubscribed = true)
        }
        nextPeer.track(buildJsonObject { put("profile_id", profileId) })
        clockJob = scope.launch { runClockExchange(partyId) }
        healthMonitorJob = scope.launch {
            while (true) {
                delay(1_000)
                val lastReceive = lastValidatedReceiveAtMs ?: continue
                if (currentEpochMs() - lastReceive > WatchPartyClockStaleMs) {
                    healthSink(PartyHealthEvent.RealtimeDegraded(openingInstance))
                }
            }
        }
        failureSink(null)
        WatchPartyDiagnostics.transport("subscribe-complete", partyId, realtime = "subscribed")
        log.i { "realtime party=${partyId.shortId()} state=subscribed-unverified" }
        healthSink(PartyHealthEvent.RealtimeSubscribed(openingInstance))
        refreshRequest()
    }

    private suspend fun closeChannel(clearProtocol: Boolean, detached: Boolean = true) {
        val closingAuthority = authorityChannel
        val closingPeer = peerChannel
        val closingPartyId = boundPartyId
        val closingInstance = channelInstance
        val closePlan = partyChannelClosePlan(
            hasChannel = closingAuthority != null || closingPeer != null,
            boundPartyId = closingPartyId,
            channelInstance = closingInstance,
            detached = detached,
        ) ?: return
        authorityChannel = null
        peerChannel = null
        if (clearProtocol) resetProtocolState()
        listOfNotNull(closingAuthority, closingPeer).forEach { closing ->
            runCatching {
                withTimeout(WatchPartyChannelCloseTimeoutMs) {
                    ZSupabaseProvider.client.realtime.removeChannel(closing)
                }
            }
        }
        if (closePlan.detached) healthSink(PartyHealthEvent.RealtimeDetached(closePlan.channelInstance))
        WatchPartyDiagnostics.transport("channel-closed", closePlan.partyId, realtime = "disconnected")
    }

    /**
     * The host's position, paired with the instant it was read.
     *
     * [capturedAtPartyMs] is the argument that matters and it is the caller's to get right: it has
     * to be stamped where the position was *sampled*, not where the message was built, or this
     * re-introduces the very bias the tick exists to remove.
     */
    fun publishTick(
        status: WatchPartyStatus,
        positionMs: Long,
        capturedAtPartyMs: Long,
        playbackSpeed: Float,
        durationMs: Long,
        /** The guests a stall-guard hold is waiting on, so nobody reads the hold as a person pausing. */
        hold: List<String> = emptyList(),
    ) {
        val context = authority ?: return
        val generation = context.generation
        val next = PartyTick(
            partyId = context.partyId,
            contentGeneration = generation.contentGeneration,
            sequence = context.durableSequence,
            status = status,
            positionMs = positionMs,
            capturedAtPartyMs = capturedAtPartyMs,
            playbackSpeed = playbackSpeed,
            durationMs = durationMs,
            sourceGeneration = generation.sourceGeneration,
            authorityEpoch = generation.authorityEpoch,
            hold = hold,
        )
        tick = next
        _ticks.tryEmit(next)
        publishState()
        scope.launch { send(PartyTickMessage(fromProfileId = context.selfProfileId, tick = next)) }
    }

    /**
     * Applies a transport action here, and hands it to the one path that reaches everybody else.
     *
     * Returns null when this client may not control the party, so a caller cannot half-issue one.
     *
     * [submitDurable] is **required**, and it is the remote half of the command - not a durable
     * record that follows one. A client cannot write the authority plane, so the accepted command
     * only reaches other members by way of `party_submit_command_v2`, which validates it against
     * the locked row and then emits the authoritative broadcast with the sender and generations it
     * read there. It is a parameter rather than a call the caller makes afterwards because
     * forgetting it no longer costs a database row - it costs every other member the command.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun issueCommand(
        kind: PartyCommandKind,
        startPositionMs: Long,
        startAtPartyMs: Long,
        playbackSpeed: Float,
        playAfter: Boolean = true,
        diagnosticInputId: String? = null,
        submitDurable: (PartyCommand) -> Unit,
    ): PartyCommand? {
        val context = authority ?: run {
            diagnosticInputId?.let { WatchPartyDiagnostics.rejected(it, kind, null, null, "authority-missing") }
            return null
        }
        val profileId = context.selfProfileId
        if (!context.mayControl(profileId)) {
            diagnosticInputId?.let { WatchPartyDiagnostics.rejected(it, kind, null, profileId, "permission") }
            return null
        }
        val generation = context.generation
        commandCounter += 1
        val command = PartyCommand(
            commandId = Uuid.random().toString(),
            kind = kind,
            issuedByProfileId = profileId,
            counter = commandCounter,
            contentGeneration = generation.contentGeneration,
            startPositionMs = startPositionMs,
            startAtPartyMs = startAtPartyMs,
            playbackSpeed = playbackSpeed,
            playAfter = playAfter,
            sourceGeneration = generation.sourceGeneration,
            authorityEpoch = generation.authorityEpoch,
        )
        log.i {
            "issue party=${context.partyId.shortId()} kind=$kind posMs=$startPositionMs " +
                "startAtMs=$startAtPartyMs leadMs=${startAtPartyMs - partyNowMs()} n=$commandCounter " +
                "playAfter=$playAfter"
        }
        commandLog = commandLog.record(command)
        WatchPartyDiagnostics.accepted(diagnosticInputId, command, context.partyId)
        dispatchPartyCommandLocallyFirst(
            command = command,
            emitDirective = { _commands.tryEmit(it) },
            enqueueRemoteDelivery = submitDurable,
        )
        return command
    }

    /** What this client is doing, for a host deciding whether to wait for it. */
    fun publishPeerStatus(status: WatchPartyStatus) {
        val context = authority ?: return
        val generation = context.generation
        val profileId = context.selfProfileId
        if (profileId == context.hostProfileId) return
        // Only when it changes: the clock exchange re-sends the held one for liveness, and logging
        // every one of those would bury the transitions that decide whether the host holds.
        if (peerStatus != status) {
            log.i { "peer publish party=${context.partyId.shortId()} status=$status" }
        }
        peerStatus = status
        scope.launch {
            send(PartyPeerStatusMessage(
                partyId = context.partyId,
                fromProfileId = profileId,
                status = status,
                atPartyMs = partyNowMs(),
                rttMs = clock.bestRttMs,
                contentGeneration = generation.contentGeneration,
                sourceGeneration = generation.sourceGeneration,
                authorityEpoch = generation.authorityEpoch,
            ))
        }
    }

    /**
     * Puts a message on the peer plane, which is the only topic this client may write.
     *
     * A [PartyCommandMessage] never comes through here. The accepted command is authored by
     * `party_submit_command_v2` and emitted by the backend onto the authority plane; sending one
     * from a client would be re-creating the forgeable path the topic split exists to close, and
     * with the peer plane's policy it would be accepted by the socket and then ignored by every
     * receiver. Refused loudly rather than silently, because the silent version is a party where
     * pauses stop working and nothing says why.
     */
    private suspend fun send(message: PartySyncMessage) {
        if (message is PartyCommandMessage) {
            log.w { "refusing to broadcast a command from the client; the backend authors those" }
            return
        }
        val startedAt = currentEpochMs()
        val live = peerChannel
        val sendInstance = channelInstance
        if (live == null) {
            healthSink(
                PartyHealthEvent.RealtimeSendCompleted(
                    sendInstance,
                    startedAt,
                    PartyRealtimeSendOutcome.Unavailable,
                ),
            )
            return
        }
        // A send that throws is a socket that has gone away, and the poll underneath this is what
        // covers that. Failing loudly here would put a banner on every transient reconnect.
        try {
            live.broadcast(WatchPartySyncEvent, encodePartySyncMessage(message))
            healthSink(
                PartyHealthEvent.RealtimeSendCompleted(
                    sendInstance,
                    currentEpochMs(),
                    PartyRealtimeSendOutcome.LocallyAccepted,
                ),
            )
        } catch (cancelled: CancellationException) {
            // Channel teardown cancels its in-flight broadcasts. That is normal lifecycle cleanup,
            // not a failed transport send and must not poison live-health telemetry.
            throw cancelled
        } catch (cause: Throwable) {
            healthSink(
                PartyHealthEvent.RealtimeSendCompleted(
                    sendInstance,
                    currentEpochMs(),
                    PartyRealtimeSendOutcome.Failed,
                ),
            )
            log.d { "send failed kind=${message::class.simpleName} cause=${cause.message}" }
        }
    }

    private fun receive(payload: JsonObject, plane: PartyRealtimePlane) {
        // Null is every kind of "this build cannot act on it": a newer protocol, an unknown type, a
        // field an older sender did not write. All of them mean fall back, none of them mean guess.
        val message = decodePartySyncMessage(payload) ?: return
        val context = authority ?: return
        val generation = context.generation
        val self = context.selfProfileId
        if (message.partyId != context.partyId) return
        // ⚠ **The whole of the sender binding lives on this line.** A command is trusted because it
        // arrived on a topic no client may write; a tick is trusted only as far as the durable host
        // check in `acceptTick`. Reading the type off a plane it cannot have come from is the one
        // way a member could put words in the server's mouth, so it is refused before anything else
        // looks at it.
        if (!partyMessageIsAdmissible(message, plane)) {
            log.w { "dropping ${message::class.simpleName} arriving on the $plane plane" }
            return
        }
        // A command emitted by the backend names its *actor*, so the actor's own client sees its
        // own command come back. It has already run it locally, and re-running it would re-execute
        // a barrier the user is already past. Everything else is genuinely peer traffic.
        if (message.fromProfileId == self) {
            if (plane == PartyRealtimePlane.Authority) {
                lastValidatedReceiveAtMs = currentEpochMs()
                healthSink(
                    PartyHealthEvent.RealtimeReceived(
                        channelInstance,
                        lastValidatedReceiveAtMs ?: currentEpochMs(),
                        PartyRealtimeTrafficKind.Authority,
                    ),
                )
            }
            return
        }
        // Subscription and successful sends proved nothing in Stage 0. Only an authenticated,
        // party-matching message from another member establishes live peer delivery.
        val trafficKind = when {
            plane == PartyRealtimePlane.Authority -> PartyRealtimeTrafficKind.Authority
            message is PartyClockPingMessage || message is PartyClockPongMessage -> PartyRealtimeTrafficKind.Clock
            else -> PartyRealtimeTrafficKind.Peer
        }
        val receivedAt = currentEpochMs()
        lastValidatedReceiveAtMs = receivedAt
        healthSink(PartyHealthEvent.RealtimeReceived(channelInstance, receivedAt, trafficKind))
        if (
            message.contentGeneration != generation.contentGeneration ||
            message.sourceGeneration != generation.sourceGeneration ||
            message.authorityEpoch != generation.authorityEpoch
        ) {
            refreshRequest()
            return
        }
        when (message) {
            is PartyClockPingMessage -> if (isHost()) scope.launch { answerPing(message, context) }
            is PartyClockPongMessage -> acceptPong(message, context.hostProfileId, self)
            is PartyTickMessage -> acceptTick(message, context)
            is PartyCommandMessage -> acceptCommand(message, context)
            is PartyPeerStatusMessage -> acceptPeerStatus(message)
        }
    }

    private suspend fun answerPing(ping: PartyClockPingMessage, context: PartyAuthorityContext) {
        val generation = context.generation
        send(
            PartyClockPongMessage(
                partyId = context.partyId,
                fromProfileId = context.selfProfileId,
                toProfileId = ping.fromProfileId,
                exchangeId = ping.exchangeId,
                sentAtMs = ping.sentAtMs,
                // The host is the clock, so this is the whole of what the exchange is for.
                hostAtMs = currentEpochMs(),
                contentGeneration = generation.contentGeneration,
                sourceGeneration = generation.sourceGeneration,
                authorityEpoch = generation.authorityEpoch,
            ),
        )
    }

    private fun acceptPong(pong: PartyClockPongMessage, hostProfileId: String, self: String) {
        if (pong.toProfileId != self) return
        // Only the host answers, and only for an exchange this client started: `t0` is echoed
        // rather than remembered, so without this a peer could hand us any offset it liked.
        if (pong.fromProfileId != hostProfileId) return
        if (outstandingPings.remove(pong.exchangeId) == null) return
        val sample = partyClockSample(
            sentAtMs = pong.sentAtMs,
            hostAtMs = pong.hostAtMs,
            receivedAtMs = currentEpochMs(),
        )
        val before = clock
        clock = clock.accept(sample)
        if (before.locked != clock.locked || absDelta(before.offsetMs, clock.offsetMs) >= WatchPartyClockSlewLimitMs) {
            log.i {
                "clock offsetMs=${clock.offsetMs} rttMs=${sample.rttMs} bestRttMs=${clock.bestRttMs} " +
                    "locked=${clock.locked} samples=${clock.samples.size}"
            }
        }
        publishState()
    }

    private fun acceptTick(message: PartyTickMessage, context: PartyAuthorityContext) {
        // Only the host publishes a timeline. A payload cannot promote itself: the durable snapshot
        // is the only thing that says who the host is.
        if (message.fromProfileId != context.hostProfileId) return
        val generation = context.generation
        val next = message.tick
        if (
            next.contentGeneration != generation.contentGeneration ||
            next.sourceGeneration != generation.sourceGeneration ||
            next.authorityEpoch != generation.authorityEpoch
        ) {
            refreshRequest()
            return
        }
        val held = tick
        if (held != null && next.contentGeneration != held.contentGeneration) {
            // Content moved under us. The tick cannot say what to, so ask the thing that can.
            refreshRequest()
        }
        if (!next.supersedes(held)) return
        tick = next
        _ticks.tryEmit(next)
        publishState()
    }

    private fun acceptCommand(message: PartyCommandMessage, context: PartyAuthorityContext) {
        val command = message.command
        if (!context.mayControl(command.issuedByProfileId)) {
            WatchPartyDiagnostics.received(command, context.partyId, outcome = "rejected-permission")
            return
        }
        if (
            command.contentGeneration != context.generation.contentGeneration ||
            command.sourceGeneration != context.generation.sourceGeneration ||
            command.authorityEpoch != context.generation.authorityEpoch
        ) {
            WatchPartyDiagnostics.received(command, context.partyId, outcome = "rejected-generation")
            refreshRequest()
            return
        }
        if (!commandLog.accepts(command)) {
            WatchPartyDiagnostics.received(command, context.partyId, outcome = "rejected-duplicate")
            return
        }
        commandLog = commandLog.record(command)
        WatchPartyDiagnostics.received(command, context.partyId, outcome = "accepted")
        log.i {
            "command party=${context.partyId.shortId()} kind=${command.kind} posMs=${command.startPositionMs} " +
                "inMs=${command.startAtPartyMs - partyNowMs()} from=${command.issuedByProfileId.shortId()}"
        }
        _commands.tryEmit(command)
    }

    private fun acceptPeerStatus(message: PartyPeerStatusMessage) {
        if (message.rttMs >= 0) guestRttMs[message.fromProfileId] = message.rttMs
        // Freshness is stamped on receipt in the host's clock domain. The sender timestamp is
        // useful content, but it must not be allowed to keep its own presence fresh indefinitely.
        guestLastTelemetryAtPartyMs[message.fromProfileId] = partyNowMs()
        val before = if (isHost()) advanceBufferWatch() else emptyList()
        if (isHost()) {
            bufferWatch = bufferWatch.observe(
                profileId = message.fromProfileId,
                status = message.status,
                partyNowMs = partyNowMs(),
            )
        }
        val after = if (isHost()) advanceBufferWatch() else emptyList()
        // The decisive input to the whole "wait for everyone" behaviour, and it was invisible: the
        // 2026-09-02 run showed the host pausing for a guest with nothing in either log saying what
        // the guest had reported. Logged on the guest's *transitions* rather than per message - the
        // clock exchange re-sends the held status for liveness, and a line each would bury them.
        if (guestStatus.put(message.fromProfileId, message.status) != message.status || before != after) {
            log.i {
                "peer status from=${message.fromProfileId.shortId()} status=${message.status} " +
                    "rttMs=${message.rttMs} transitMs=${partyNowMs() - message.atPartyMs} " +
                    "holding=[${after.joinToString { it.shortId() }}]"
            }
        }
        publishState()
    }

    /**
     * Keeps the estimate current, and keeps this client's own status current with it.
     *
     * A guest's status rides the same loop so that liveness costs nothing extra; a *change* of
     * status is published the moment it happens, from the player, because the host's grace for a
     * stalled guest is shorter than this interval.
     */
    @OptIn(ExperimentalUuidApi::class)
    private suspend fun runClockExchange(partyId: String) {
        while (true) {
            if (isHost()) {
                // The host is the clock. Nothing to measure, and the pongs it owes are answered
                // from the collector rather than from here.
                delay(WatchPartyClockPingIntervalMs)
                continue
            }
            val context = authority ?: return
            if (context.partyId != partyId) return
            val exchangeId = Uuid.random().toString()
            val sentAt = currentEpochMs()
            outstandingPings[exchangeId] = sentAt
            // An exchange that is never answered would otherwise accumulate forever on a host that
            // is on an older build, which is exactly the case this has to survive.
            outstandingPings.entries.removeAll { (_, at) -> sentAt - at > WatchPartyClockStaleMs }
            val generation = context.generation
            send(
                PartyClockPingMessage(
                    partyId = partyId,
                    fromProfileId = context.selfProfileId,
                    exchangeId = exchangeId,
                    sentAtMs = sentAt,
                    contentGeneration = generation.contentGeneration,
                    sourceGeneration = generation.sourceGeneration,
                    authorityEpoch = generation.authorityEpoch,
                ),
            )
            peerStatus?.let { publishPeerStatus(it) }
            delay(watchPartyClockPingDelayMs(clock.samples.size))
        }
    }

    private fun publishState() {
        val held = tick
        _state.value = WatchPartySyncState(
            clockLocked = isHost() || clock.locked,
            clockOffsetMs = if (isHost()) 0L else clock.offsetMs,
            bestRttMs = clock.bestRttMs,
            tickStatus = held?.status,
            tickCapturedAtPartyMs = held?.capturedAtPartyMs,
            tickHold = held?.hold.orEmpty(),
            holdingProfiles = advanceBufferWatch(),
            peerTelemetry = guestStatus.mapValues { (profileId, status) ->
                PartyPeerTelemetry(
                    status = status,
                    receivedAtPartyMs = guestLastTelemetryAtPartyMs[profileId] ?: 0L,
                )
            },
        )
    }

    private fun absDelta(a: Long, b: Long): Long = if (a > b) a - b else b - a
}
