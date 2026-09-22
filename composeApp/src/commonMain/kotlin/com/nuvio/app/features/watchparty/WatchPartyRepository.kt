package com.nuvio.app.features.watchparty

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.ZSessionBridge
import com.nuvio.app.core.network.ZSupabaseProvider
import com.nuvio.app.core.network.runWithZSession
import com.nuvio.app.core.network.shouldReexchangeZSession
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class WatchPartyUiState(
    val activeProfileId: String? = null,
    val party: WatchPartyState? = null,
    // The host's own invite code. Only the last four characters are stored server-side, so a code
    // that is not held here is gone for good; it lived in the lobby composition and vanished the
    // moment the host navigated away.
    val inviteCode: String? = null,
    val health: PartyHealthState = PartyHealthState(),
    val serverClockOffsetMs: Long = 0,
    /**
     * Whether the host holds the party for a guest whose stream has stalled.
     *
     * Host-side and this session only. It is a host's judgement about the party it is running -
     * one guest on a bad line should not be able to stop the film for five other people - so it is
     * deliberately not a party setting the server has to carry, and there is no settings surface
     * for a party to persist it into yet.
     */
    val waitForEveryone: Boolean = true,
    /**
     * Whether the host holds the party while a member is **away**.
     *
     * A separate answer from [waitForEveryone], and it has to be. That one is about a stream that
     * cannot keep up; this one is about a person who is not in the room. Wanting to wait out a bad
     * connection says nothing about wanting the film to stop because somebody answered a message,
     * and a single switch would force the host to accept both to get either.
     *
     * Defaults **off**, which is the least disruptive of the two behaviours: the party plays on and
     * whoever comes back catches up through the timeline they were already following. Host-side and
     * this session only, exactly like [waitForEveryone] - see `partyAwayHoldMembers` for the one
     * case this switch does not govern, which is the host's own absence.
     */
    val pauseForAwayUsers: Boolean = false,
    /**
     * The source the host has chosen but has not started the party on yet.
     *
     * Choosing and starting are two separate decisions, and the host makes them on two separate
     * screens: the source list, then back in the lobby with everyone in front of them. Publishing
     * the pick straight from the list collapsed the two - `party_select_source` is what moves the
     * party out of the lobby, so the host's choice threw everybody into the player before the host
     * had so much as looked at who was in the room. Held here rather than in the lobby's own
     * composition because the lobby leaves composition while the source list is on top of it.
     */
    val stagedHostSource: PartySourceDescriptorV2? = null,
    /** How [stagedHostSource] reads in the lobby - the release the host picked, in their words. */
    val stagedHostSourceLabel: String? = null,
    val isWorking: Boolean = false,
    val errorMessage: String? = null,
)

enum class PartyDepartureMode { LEAVE_AND_TRANSFER, END_PARTY }

object WatchPartyRepository {
    /**
     * The transport half of the Watch Together trace.
     *
     * Two clients disagreeing about a party is the whole class of bug here, and it is only ever
     * diagnosable by lining up the two logs side by side - so every line carries the party, the
     * profile and the sequence, and state is logged when it *changes* rather than on every five
     * second poll. `WatchPartyPlayer` carries the other half: what each client decided to do about
     * the state it was given.
     *
     * On desktop this reaches the file written by `DesktopDebugLog`, which needs
     * `-Dnuvio.debugTools=true` (or a debug-channel build). Invite codes are a bearer credential
     * and are never written out in full.
     */
    private val log = Logger.withTag("WatchParty")
    private var lastLoggedState: String? = null
    private var lastLoggedHeartbeatStatus: String? = null
    private var lastLoggedPollFailure: String? = null
    private var lastSuccessfulContactEpochMs: Long = 0L

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(WatchPartyUiState())
    val uiState: StateFlow<WatchPartyUiState> = _uiState.asStateFlow()
    private var pollJob: Job? = null
    private var playbackTelemetry: PartyPlaybackTelemetry? = null
    // The periodic and transition heartbeats sample playback independently. If their requests are
    // allowed to overlap, an older sample can finish last and overwrite the newer host state.
    private val heartbeatMutex = Mutex()
    // Location and readiness mutate different columns on the same member but each RPC returns a
    // whole snapshot. Serializing them prevents an earlier response from arriving last and
    // restoring the pre-ready member row over the newer local state.
    private val memberStateMutex = Mutex()

    fun updatePlaybackTelemetry(telemetry: PartyPlaybackTelemetry?) {
        playbackTelemetry = telemetry
    }

    private fun updateHealth(event: PartyHealthEvent) {
        var before = PartyHealthState()
        var after = PartyHealthState()
        _uiState.update { current ->
            // Health, and only health. The connection state and its banner are projected facts,
            // and every surface that shows them already runs the projector itself - keeping a copy
            // here made the repository a second presentation authority for a fact it does not own,
            // and one that could disagree with the screen next to it.
            before = current.health
            after = reducePartyHealth(current.health, event)
            current.copy(health = after)
        }
        // ⚠ **The transition, not the event.** The two-client run had `RealtimeSubscribed` in the
        // log and `Live sync lost` on the screen, and nothing anywhere said those were the same
        // fact - that a subscribe had happened and had proved nothing. Every change of the three
        // axes and the capability they combine into is now one line, so "why does it say lost" is
        // answerable from the file rather than from the source.
        if (before.realtime != after.realtime ||
            before.api != after.api ||
            before.capability() != after.capability()
        ) {
            log.i {
                "health realtime=${before.realtime}->${after.realtime} api=${before.api}->${after.api} " +
                    "capability=${before.capability()}->${after.capability()} " +
                    "instance=${after.channelInstance} polling=${after.polling} " +
                    "lastAuthority=${after.lastAuthorityTrafficAtMs} lastPeer=${after.lastPeerTrafficAtMs} " +
                    "lastClock=${after.lastClockTrafficAtMs} lastSend=${after.lastRealtimeSendOutcome} " +
                    "by=${event::class.simpleName}"
            }
        }
        if (after.realtime == PartyRealtimeHealth.SubscribedUnverified &&
            before.realtime != PartyRealtimeHealth.SubscribedUnverified
        ) {
            armUnverifiedRealtimeWatch(after.channelInstance)
        }
    }

    /**
     * Says out loud when a subscribed channel has still delivered nothing.
     *
     * This is the shape of the defect that cost a whole physical run: subscribed, healthy-looking,
     * sends "succeeding", and not one message ever arriving from anybody. It is invisible without a
     * peer to compare against, so the client has to notice it about itself. One line, once per
     * channel instance, naming the interval - not a banner, because the projector already shows
     * `Live sync lost` and a second surface for the same fact is a second thing to keep right.
     */
    private fun armUnverifiedRealtimeWatch(instance: Long) {
        unverifiedRealtimeWatch?.cancel()
        unverifiedRealtimeWatch = scope.launch {
            delay(WatchPartyRealtimeVerificationGraceMs)
            val health = _uiState.value.health
            if (health.channelInstance != instance) return@launch
            if (health.realtime != PartyRealtimeHealth.SubscribedUnverified) return@launch
            log.w {
                "realtime still unverified after ${WatchPartyRealtimeVerificationGraceMs}ms " +
                    "instance=$instance party=${_uiState.value.party?.id.shortId()} - subscribed, " +
                    "nothing received from any member; the party is running on durable polling"
            }
            WatchPartyDiagnostics.transport(
                "unverified-timeout",
                _uiState.value.party?.id,
                realtime = "subscribed-unverified",
                detail = "${WatchPartyRealtimeVerificationGraceMs}ms",
            )
        }
    }

    private var unverifiedRealtimeWatch: Job? = null

    fun installAuthorizedSnapshot(snapshot:WatchPartyState) = installSnapshot(snapshot)

    suspend fun restoreActive():Result<WatchPartyState?> = call {
        val snapshot=ZSupabaseProvider.client.postgrest.rpc("party_get_active",buildJsonObject {
            put("p_profile_id",requireProfile());put("p_contract_version",PartySourceContractVersion)
        }).decodeAs<WatchPartyState?>()
        snapshot?.let(::installSnapshot)
        snapshot
    }

    /**
     * This profile's live party on the server, **without installing it**.
     *
     * For the callers that must decide whether the answer is theirs before it becomes the held party:
     * a host checking for a party built from its playback, a guest waiting for a request to be
     * accepted. Deliberately not routed through [call], so a background probe never flips the working
     * flag or overwrites an error the user is reading.
     */
    suspend fun fetchActive(): Result<WatchPartyState?> {
        val profileId = _uiState.value.activeProfileId ?: return Result.success(null)
        return runCatching {
            if (!ZSessionBridge.ensureSession(profileId)) error("Nuvio Z session unavailable")
            ZSupabaseProvider.client.postgrest.rpc("party_get_active", buildJsonObject {
                put("p_profile_id", profileId); put("p_contract_version", PartySourceContractVersion)
            }).decodeAs<WatchPartyState?>()
        }
    }

    suspend fun promotePresence(sessionId:String):Result<Unit> = call {
        val snapshot=ZSupabaseProvider.client.postgrest.rpc("party_promote_presence",buildJsonObject {
            put("p_profile_id",requireProfile());put("p_presence_session_id",sessionId);put("p_contract_version",PartySourceContractVersion)
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot)
    }

    suspend fun setClientLocation(location: WatchPartyClientLocation): Result<Unit> = setClientLocation(location.name)

    suspend fun setClientLocation(location: String): Result<Unit> = memberStateMutex.withLock {
        call {
            val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_set_client_location", buildJsonObject {
                put("p_party_id", requireParty().id); put("p_profile_id", requireProfile()); put("p_location", location)
            }).decodeAs<WatchPartyState>()
            installSnapshot(snapshot)
        }
    }

    fun authoritativePositionMs(party: WatchPartyState = requireParty()): Long {
        val updatedAt = parseIsoEpochMs(party.stateUpdatedAt) ?: return party.positionMs.coerceAtLeast(0L)
        val serverNow = currentEpochMs() + _uiState.value.serverClockOffsetMs
        return expectedPartyPositionMs(
            statePositionMs = party.positionMs,
            stateUpdatedAtEpochMs = updatedAt,
            serverNowEpochMs = serverNow,
            status = party.status,
            playbackSpeed = party.playbackSpeed,
        ).coerceAtLeast(0L)
    }

    /**
     * Coalesces broadcast-driven refreshes.
     *
     * The broadcast carried no state, so every one of them meant a `party_snapshot` RPC - and they
     * arrived in bursts, four to six inside a second, because a member heartbeat broadcast as
     * loudly as a real command. `onEach { refresh() }` collects sequentially, so those RPCs queued,
     * and the newest command - the pause someone is waiting on - sat at the back of a queue whose
     * depth *was* the latency. Dropping the oldest keeps at most one refresh in flight and one
     * pending: a burst of six costs two round trips instead of six, and the newest always wins
     * because every refresh fetches current state anyway.
     */
    private val refreshRequests = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var clockOffsetPartyId: String? = null

    init {
        WatchPartySync.configure(
            health = ::updateHealth,
            stateBroadcast = { payload ->
                if (!applyBroadcastState(payload)) refreshRequests.tryEmit(Unit)
            },
            refresh = ::requestRefresh,
            failure = { message -> _uiState.update { it.copy(errorMessage = message) } },
        )
        refreshRequests.onEach {
            val held = _uiState.value.party
            if (held != null && held.status != WatchPartyStatus.ended) refresh()
        }.launchIn(scope)
    }

    fun setActiveProfile(profileId: String?) {
        if (_uiState.value.activeProfileId == profileId) return
        log.i { "profile from=${_uiState.value.activeProfileId.shortId()} to=${profileId.shortId()}" }
        lastLoggedState = null
        lastLoggedHeartbeatStatus = null
        lastLoggedPollFailure = null
        playbackTelemetry = null
        // Cleared here rather than only inside `leave()`: a departure RPC that fails deliberately
        // keeps local state for a retry, and one profile's resolved media may never outlive the
        // switch to another - whether or not the server was reachable at the time.
        PartySourceRealizer.clear()
        scope.launch { if (_uiState.value.party != null) leave() else WatchPartySync.updateAuthority(null) }
        _uiState.value = WatchPartyUiState(activeProfileId = profileId)
    }

    /**
     * Records the host's pick without telling the party about it.
     *
     * Deliberately local: the party is only told when the host starts it, and until then the
     * lobby is the one screen that knows a source exists.
     */
    fun stageHostSource(fingerprint: PartySourceDescriptorV2, label: String?) {
        log.i { "staged host source party=${_uiState.value.party?.id.shortId()} label=$label" }
        _uiState.value = _uiState.value.copy(
            stagedHostSource = fingerprint,
            stagedHostSourceLabel = label?.takeIf { it.isNotBlank() },
        )
    }

    /** The panel's error row was dismissed. */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun setWaitForEveryone(enabled: Boolean) {
        if (_uiState.value.waitForEveryone == enabled) return
        log.i { "waitForEveryone=$enabled party=${_uiState.value.party?.id.shortId()}" }
        _uiState.value = _uiState.value.copy(waitForEveryone = enabled)
    }

    fun setPauseForAwayUsers(enabled: Boolean) {
        if (_uiState.value.pauseForAwayUsers == enabled) return
        log.i { "pauseForAwayUsers=$enabled party=${_uiState.value.party?.id.shortId()}" }
        _uiState.value = _uiState.value.copy(pauseForAwayUsers = enabled)
    }

    suspend fun create(
        content: PartyContent,
        sourceFingerprint: PartySourceDescriptorV2? = null,
        qualityIntent: JsonObject? = null,
        controlMode: WatchPartyControlMode = WatchPartyControlMode.host_only,
        initialPositionMs: Long = 0L,
        initialPlaybackSpeed: Float = 1f,
    ): Result<String> = call {
        val profileId = requireProfile()
        val code = generateInviteCode()
        val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_create_v2", buildJsonObject {
            put("p_host_profile_id", profileId); put("p_invite_code", code)
            put("p_content", json.encodeToJsonElement(content))
            sourceFingerprint?.let { put("p_source_descriptor", json.encodeToJsonElement(it)) }
            qualityIntent?.let { put("p_track_intent", it) }
            put("p_control_mode", controlMode.name)
            put("p_position_ms", initialPositionMs.coerceAtLeast(0L))
            put("p_playback_speed", initialPlaybackSpeed.coerceIn(0.25f, 4f))
            put("p_contract_version", PartySourceContractVersion)
        }).decodeAs<WatchPartyState>()
        log.i { "create party=${snapshot.id.shortId()} host=${profileId.shortId()} code=****${code.takeLast(4)}" }
        installSnapshot(snapshot)
        _uiState.value = _uiState.value.copy(inviteCode = code)
        code
    }

    suspend fun join(partyId: String? = null, inviteCode: String? = null): Result<Unit> = call {
        _uiState.value = _uiState.value.copy(inviteCode = null)
        require(partyId != null || !inviteCode.isNullOrBlank()) { "Party ID or invite code required" }
        val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_join", buildJsonObject {
            put("p_profile_id", requireProfile())
            partyId?.let { put("p_party_id", it) }
            inviteCode?.let { put("p_invite_code", it) }
        }).decodeAs<WatchPartyState>()
        log.i {
            "join party=${snapshot.id.shortId()} profile=${_uiState.value.activeProfileId.shortId()} " +
                "via=${if (partyId != null) "id" else "code"} status=${snapshot.status} members=${snapshot.members.size}"
        }
        installSnapshot(snapshot)
    }

    suspend fun invite(friendProfileId: String): Result<Unit> = partyCall("party_invite_friend") {
        put("p_party_id", requireParty().id); put("p_host_profile_id", requireProfile()); put("p_receiver_profile_id", friendProfileId)
    }

    suspend fun updateReady(
        state: SourceResolutionState,
        durationMs: Long? = null,
        error: String? = null,
        sourceGeneration: Int? = null,
        sourceMatch: PartySourceMatch? = null,
    ): Result<Unit> = memberStateMutex.withLock {
        call {
            val party = requireParty()
            log.i { "ready party=${party.id.shortId()} profile=${_uiState.value.activeProfileId.shortId()} state=$state durationMs=$durationMs error=$error" }
            val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_set_ready", buildJsonObject {
                put("p_party_id", party.id); put("p_profile_id", requireProfile()); put("p_ready_state", state.name)
                durationMs?.let { put("p_duration_ms", it) }; error?.let { put("p_error", it) }
                sourceGeneration?.let { put("p_source_generation", it) }
                sourceMatch?.let { put("p_source_match", it.name) }
            }).decodeAs<WatchPartyState>()
            installSnapshot(snapshot)
        }
    }

    /**
     * Leaves the lobby without starting the shared clock.
     *
     * Start used to submit `play`, which set the authoritative position running from the instant it
     * was pressed - before the host had so much as opened the source list. Every guest then computed
     * an expected position that had already left their file behind. `buffering` is the honest state
     * for "we have left the lobby and nobody is playing yet": it moves everyone on to pick a source
     * and holds the timeline where it is until a real play command starts it.
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun beginSourceSelection(addons: List<PartyAddonSignature>): Result<Unit> = call {
        val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_begin_source_selection", buildJsonObject {
            put("p_party_id", requireParty().id)
            put("p_host_profile_id", requireProfile())
            put("p_addon_signature", json.encodeToJsonElement(addons))
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot)
    }

    /** Backward source-compatible name for callers that have not adopted the preflight flow yet. */
    suspend fun startResolving(): Result<Unit> = beginSourceSelection(emptyList())

    suspend fun publishAddonSignature(addons: List<PartyAddonSignature>): Result<Unit> = call {
        val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_set_addon_signature", buildJsonObject {
            put("p_party_id", requireParty().id)
            put("p_profile_id", requireProfile())
            put("p_addon_signature", json.encodeToJsonElement(addons))
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot)
    }

    suspend fun selectSource(
        fingerprint: PartySourceDescriptorV2,
        expectedSourceGeneration: Int,
    ): Result<Unit> = call {
        val party=requireParty()
        val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_select_source_v2", buildJsonObject {
            put("p_party_id", party.id)
            put("p_host_profile_id", requireProfile())
            put("p_expected_content_generation",party.contentGeneration)
            put("p_expected_source_generation", expectedSourceGeneration)
            put("p_source_descriptor", json.encodeToJsonElement(fingerprint))
            put("p_contract_version",PartySourceContractVersion)
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot)
    }

    suspend fun submit(command: WatchPartyCommand): Result<Unit> {
        val startedAt = currentEpochMs()
        val partyAtStart = _uiState.value.party
        val result = call {
            log.i {
                "command party=${_uiState.value.party?.id.shortId()} profile=${_uiState.value.activeProfileId.shortId()} " +
                    "type=${command.type} positionMs=${command.positionMs} speed=${command.playbackSpeed}"
            }
            val party=requireParty()
            val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_submit_command_v2", buildJsonObject {
                put("p_party_id", party.id); put("p_profile_id", requireProfile()); put("p_command_id", command.commandId)
                put("p_command_type", command.type); put("p_payload", buildJsonObject {
                    command.positionMs?.let { put("position_ms", it) }; command.playbackSpeed?.let { put("playback_speed", it) }
                    // The barrier. Carried here because this RPC is now the *transport* for a
                    // command and not merely its record: no client may write the authority plane,
                    // so what the backend emits from this call is what every other member executes.
                    command.startAtPartyMs?.let { put("start_at_party_ms", it) }
                    command.playAfter?.let { put("play_after", it) }
                })
                put("p_content_generation",party.contentGeneration);put("p_source_generation",party.sourceGeneration)
                put("p_authority_epoch",party.authorityEpoch)
            }).decodeAs<WatchPartyState>()
            installSnapshot(snapshot)
        }
        WatchPartyDiagnostics.durableCommand(
            command = command,
            party = partyAtStart,
            outcome = if (result.isSuccess) "success" else "failed",
            durationMs = (currentEpochMs() - startedAt).coerceAtLeast(0L),
        )
        return result
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun play(
        positionMs: Long,
        commandId: String = Uuid.random().toString(),
        startAtPartyMs: Long? = null,
    ) = submit(WatchPartyCommand(commandId, "play", positionMs, startAtPartyMs = startAtPartyMs, playAfter = true))
    @OptIn(ExperimentalUuidApi::class)
    suspend fun pause(positionMs: Long, commandId: String = Uuid.random().toString()) =
        submit(WatchPartyCommand(commandId, "pause", positionMs, playAfter = false))
    @OptIn(ExperimentalUuidApi::class)
    suspend fun seek(
        positionMs: Long,
        commandId: String = Uuid.random().toString(),
        startAtPartyMs: Long? = null,
        playAfter: Boolean = true,
    ) = submit(WatchPartyCommand(commandId, "seek", positionMs, startAtPartyMs = startAtPartyMs, playAfter = playAfter))
    @OptIn(ExperimentalUuidApi::class)
    suspend fun setSpeed(speed: Float, commandId: String = Uuid.random().toString()) =
        submit(WatchPartyCommand(commandId, "speed", playbackSpeed = speed))

    /**
     * Submits an accepted local command, as the remote half of `WatchPartySync.issueCommand`.
     *
     * One place, because this is now the only path a command has to the other members and a call
     * site that maps a kind to the wrong RPC argument would cost the party the command rather than
     * a log line.
     */
    suspend fun submitAccepted(command: PartyCommand): Result<Unit> = submit(
        WatchPartyCommand(
            commandId = command.commandId,
            type = command.kind.name,
            positionMs = command.startPositionMs,
            playbackSpeed = command.playbackSpeed.takeIf { command.kind == PartyCommandKind.speed },
            startAtPartyMs = command.startAtPartyMs,
            playAfter = command.playAfter,
        ),
    )

    /**
     * The durable position, aged forward to roughly when the server will write it.
     *
     * This row is the anchor for the degraded ladder - a client whose socket is down, or whose peer
     * is on an older build - and it carried the same bias the whole feature did: the position was
     * sampled from a 500ms polling loop and then stamped with the server's `now()` at commit, so a
     * guest reading it ran behind by the sample's age. [positionCapturedAtMs] closes the part of
     * that gap this client can actually measure. The uplink is deliberately not guessed at: it is
     * the smaller term and nothing here has measured it, and a made-up correction would be worse
     * than a known-small one.
     */
    suspend fun heartbeat(
        positionMs: Long,
        durationMs: Long,
        speed: Float,
        status: WatchPartyStatus,
        positionCapturedAtMs: Long = 0L,
    ): Result<Unit> = call {
        heartbeatMutex.withLock {
            // Every five seconds forever, so only the transitions are worth a line.
            if (lastLoggedHeartbeatStatus != status.name) {
                lastLoggedHeartbeatStatus = status.name
                log.i {
                    "heartbeat party=${_uiState.value.party?.id.shortId()} profile=${_uiState.value.activeProfileId.shortId()} " +
                        "status=$status positionMs=$positionMs durationMs=$durationMs speed=$speed"
                }
            }
            val aged = if (positionCapturedAtMs > 0L && status == WatchPartyStatus.playing) {
                val age = (currentEpochMs() - positionCapturedAtMs).coerceIn(0L, WatchPartySnapshotIntervalMs)
                positionMs + (age.toDouble() * speed.toDouble()).toLong()
            } else {
                positionMs
            }
            val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_heartbeat", buildJsonObject {
                put("p_party_id", requireParty().id); put("p_profile_id", requireProfile()); put("p_position_ms", aged)
                put("p_duration_ms", durationMs); put("p_playback_speed", speed); put("p_status", status.name)
            }).decodeAs<WatchPartyState>()
            installSnapshot(snapshot)
        }
    }

    suspend fun changeContent(content: PartyContent, fingerprint: PartySourceDescriptorV2?, qualityIntent: JsonObject? = null): Result<Unit> = call {
        val party=requireParty()
        val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_change_content_v2", buildJsonObject {
            put("p_party_id", party.id); put("p_host_profile_id", requireProfile());put("p_expected_content_generation",party.contentGeneration)
            put("p_content", json.encodeToJsonElement(content))
            fingerprint?.let { put("p_source_descriptor", json.encodeToJsonElement(it)) }; qualityIntent?.let { put("p_track_intent", it) }
            put("p_contract_version",PartySourceContractVersion)
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot)
    }

    suspend fun setControlMode(mode: WatchPartyControlMode): Result<Unit> = call {
        val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_set_control_mode", buildJsonObject {
            put("p_party_id", requireParty().id); put("p_host_profile_id", requireProfile()); put("p_mode", mode.name)
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot)
    }

    suspend fun refresh(): Result<Unit> = call {
        val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_snapshot", buildJsonObject {
            put("p_party_id", requireParty().id); put("p_profile_id", requireProfile())
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot)
    }

    suspend fun measureClockOffset(): Result<Long> = call {
        var bestRtt = Long.MAX_VALUE
        var bestOffset = 0L
        repeat(3) {
            val started = currentEpochMs()
            val serverIso = ZSupabaseProvider.client.postgrest.rpc("party_clock").decodeAs<String>()
            val ended = currentEpochMs()
            val server = parseIsoEpochMs(serverIso) ?: return@repeat
            val rtt = ended - started
            if (rtt < bestRtt) { bestRtt = rtt; bestOffset = server - ((started + ended) / 2) }
        }
        log.i { "clock offsetMs=$bestOffset bestRttMs=${if (bestRtt == Long.MAX_VALUE) -1 else bestRtt}" }
        _uiState.value = _uiState.value.copy(serverClockOffsetMs = bestOffset)
        bestOffset
    }

    suspend fun depart(mode: PartyDepartureMode): Result<Unit> = runCatching {
        val party = _uiState.value.party
            ?: throw IllegalStateException("No active party")
        val profile = _uiState.value.activeProfileId
            ?: throw IllegalStateException("No active profile")
        log.i { "depart mode=$mode party=${party.id.shortId()} profile=${profile.shortId()}" }
        try {
            withTimeout(WatchPartyChannelCloseTimeoutMs) {
                ZSupabaseProvider.client.postgrest.rpc("party_depart_v2",buildJsonObject {
                    put("p_party_id",party.id);put("p_profile_id",profile)
                    put("p_mode",if(mode==PartyDepartureMode.END_PARTY) "end" else "leave_transfer")
                    put("p_contract_version",PartySourceContractVersion)
                })
            }
        } catch (failure: Throwable) {
            log.w(failure) { "departure rpc failed party=${party.id.shortId()}, retaining local state for retry" }
            throw failure
        }
        stopPolling(); WatchPartySync.updateAuthority(null); PartySourceRealizer.clear(); clockOffsetPartyId = null
        lastSuccessfulContactEpochMs = 0L
        _uiState.value = WatchPartyUiState(activeProfileId = profile)
        Unit
    }

    /**
     * Leaves a party this client does **not** hold - a membership the server has and the client never
     * adopted. Local state is untouched, because none of it describes that party. Refuses the held
     * party outright: that one is left through [leave], which also tears down what the client built
     * for it. See `joinWatchingNow`.
     */
    suspend fun departStrayMembership(partyId: String): Result<Unit> = runCatching {
        check(_uiState.value.party?.id != partyId) { "Refusing to depart the held party as a stray" }
        val profile = _uiState.value.activeProfileId ?: throw IllegalStateException("No active profile")
        log.i { "depart stray membership party=${partyId.shortId()} profile=${profile.shortId()}" }
        withTimeout(WatchPartyChannelCloseTimeoutMs) {
            ZSupabaseProvider.client.postgrest.rpc("party_depart_v2", buildJsonObject {
                put("p_party_id", partyId); put("p_profile_id", profile)
                put("p_mode", "leave_transfer")
                put("p_contract_version", PartySourceContractVersion)
            })
        }
        Unit
    }

    /**
     * Leave [partyId] **as [profileId]**, without touching the held party state.
     *
     * For an accepted join request abandoned at an identity boundary: by the time the departure runs
     * the repository may already hold the next profile, so neither the active profile nor the held
     * party is the right thing to consult. Never used for the party this client holds.
     */
    suspend fun departMembershipAs(profileId: String, partyId: String): Result<Unit> = runCatching {
        check(_uiState.value.party?.id != partyId || _uiState.value.activeProfileId != profileId) {
            "Refusing to depart the held party as a stray"
        }
        log.i { "depart abandoned membership party=${partyId.shortId()} profile=${profileId.shortId()}" }
        withTimeout(WatchPartyChannelCloseTimeoutMs) {
            ZSupabaseProvider.client.postgrest.rpc("party_depart_v2", buildJsonObject {
                put("p_party_id", partyId); put("p_profile_id", profileId)
                put("p_mode", "leave_transfer")
                put("p_contract_version", PartySourceContractVersion)
            })
        }
        Unit
    }

    suspend fun end(): Result<Unit> = depart(PartyDepartureMode.END_PARTY)

    suspend fun leave(): Result<Unit> = depart(PartyDepartureMode.LEAVE_AND_TRANSFER)

    private fun installSnapshot(snapshot: WatchPartyState) {
        lastSuccessfulContactEpochMs = currentEpochMs()
        val held = _uiState.value.party
        if (held != null && isStalePartySnapshot(held, snapshot)) {
            log.i {
                "discard stale snapshot party=${snapshot.id.shortId()} gen=${snapshot.contentGeneration} " +
                    "srcGen=${snapshot.sourceGeneration} epoch=${snapshot.authorityEpoch} seq=${snapshot.sequence}"
            }
            return
        }
        // The authoritative state, logged only when it actually moves. This is the line to line up
        // between two machines: same sequence and same state_updated_at means they agree, and a
        // client whose sequence has stopped advancing has lost both realtime and the poll.
        val signature = snapshot.logSignature()
        if (signature != lastLoggedState) {
            lastLoggedState = signature
            log.i { "state viewer=${_uiState.value.activeProfileId.shortId()} $signature" }
        }
        // A different party - or a different source generation - is a different everything: neither
        // the host's staged pick nor anything realized for the old identity means anything about this
        // one. The realizer's authority moves in the same update that installs the snapshot, so no
        // frame is ever composed with one party's state and another's pick or realization.
        val previousParty = _uiState.value.party
        val stagedPickCarriesOver = shouldRetainStagedHostSource(previousParty, snapshot)
        // An ended party is never re-entered as a party, so its resolved media has no remaining
        // use - and it is the kind of thing that must not sit in memory for want of a reason to
        // drop it.
        PartySourceRealizer.updateAuthority(
            snapshot.takeIf { it.status != WatchPartyStatus.ended }?.partySourceKey(),
        )
        _uiState.value = _uiState.value.copy(
            party = snapshot,
            stagedHostSource = _uiState.value.stagedHostSource.takeIf { stagedPickCarriesOver },
            stagedHostSourceLabel = _uiState.value.stagedHostSourceLabel.takeIf { stagedPickCarriesOver },
            isWorking = false,
            errorMessage = null,
        )
        if (snapshot.status == WatchPartyStatus.ended) {
            // Terminal. Nothing about an ended party is worth polling, and a live channel for it is
            // an authority that no longer exists.
            stopPolling()
            WatchPartySync.updateAuthority(null)
            return
        }
        WatchPartySync.updateAuthority(snapshot.authorityContext(_uiState.value.activeProfileId))
        // The poll is the floor under this whole feature, so nothing may come before it. Opening the
        // channel used to, and `subscribe(blockUntilSubscribed = true)` never returns for a topic
        // the server refuses - so a channel that could not be authorized left this line unreached
        // for the life of the app. The member kept whatever state they joined with, forever: a
        // lobby that never noticed the party had started.
        startPolling()
    }

    private var membershipProbePartyId: String? = null

    /**
     * Routes a failed member RPC through [classifyPartyRpcFailure], and concludes the party when the
     * failure proves this member is no longer in it. See `PartyTermination.kt` for why this is the
     * only way a guest ever learns the host ended the party.
     */
    private fun onMemberRpcFailure(failedPartyId: String?, cause: Throwable) {
        val held = _uiState.value.party
        if (classifyPartyRpcFailure(held, failedPartyId, cause.message) != PartyRpcFailureVerdict.ConfirmMembership) return
        val partyId = held?.id ?: return
        // One probe per party. The poll and a refresh can both be refused inside the same second.
        if (membershipProbePartyId == partyId) return
        membershipProbePartyId = partyId
        scope.launch {
            try {
                confirmMembershipLost(partyId)
            } finally {
                if (membershipProbePartyId == partyId) membershipProbePartyId = null
            }
        }
    }

    private suspend fun confirmMembershipLost(partyId: String) {
        val profileId = _uiState.value.activeProfileId ?: return
        val probe = fetchActive()
        // A probe that could not answer proves nothing. The poll keeps running, and the next refusal
        // asks again - which is the transient outcome, not a guess in either direction.
        val active = probe.getOrElse { failure ->
            log.w(failure) { "membership probe failed party=${partyId.shortId()} - keeping the party" }
            return
        }
        if (_uiState.value.party?.id != partyId || _uiState.value.activeProfileId != profileId) return
        if (!membershipProbeConcludesParty(partyId, active)) {
            log.i { "membership probe party=${partyId.shortId()} still a member - refusal was transient" }
            active?.let(::installSnapshot)
            return
        }
        log.i {
            "membership lost party=${partyId.shortId()} profile=${profileId.shortId()} " +
                "active=${active?.id.shortId()} - concluding the party locally as ended"
        }
        concludeHeldParty()
    }

    /**
     * Makes the held party terminal on this client, and stops every piece of work it was driving.
     *
     * ⚠ **A terminal party must never cause another resolution.** So the realizer is cleared - any
     * in-flight realization for it is dropped and its readiness reports stop - the timing plane loses
     * its authority, the poll stops, and the host's staged pick goes with it. What remains is the
     * party marked `ended`, which every consumer already reads as "not a party".
     */
    private fun concludeHeldParty(ended: WatchPartyState? = null) {
        val held = ended ?: _uiState.value.party ?: return
        stopPolling()
        WatchPartySync.updateAuthority(null)
        PartySourceRealizer.clear()
        clockOffsetPartyId = null
        playbackTelemetry = null
        _uiState.value = _uiState.value.copy(
            party = held.concludedLocally(),
            inviteCode = null,
            stagedHostSource = null,
            stagedHostSourceLabel = null,
            isWorking = false,
            errorMessage = null,
        )
        WatchPartyDiagnostics.durableState(held.id, held.sequence, WatchPartyStatus.ended, applied = true)
    }

    /**
     * Polls the snapshot while a party is held.
     *
     * Party state reached clients only through the realtime broadcast, so a member whose channel was
     * not working never learned anything had changed. When the host started, they simply stayed in
     * the lobby while everyone else moved on, with nothing to indicate why.
     *
     * Realtime stays the fast path; this is the floor beneath it. `party_snapshot` is the same call
     * the screen already makes, and it is cheap enough at this interval to be worth never being
     * wrong for longer than it.
     */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        updateHealth(PartyHealthEvent.PollingChanged(running = true))
        WatchPartyDiagnostics.poll(_uiState.value.party?.id, running = true, api = "idle")
        pollJob = scope.launch {
            val liveness = PartyPollLiveness()
            var nextDelayMs = WatchPartySnapshotIntervalMs
            while (true) {
                val sleptFrom = currentEpochMs()
                delay(nextDelayMs)
                // Anything well past the requested delay means this loop could not get a thread,
                // which the server sees exactly as it sees a dropped request.
                val loopLagMs = (currentEpochMs() - sleptFrom - nextDelayMs).coerceAtLeast(0L)
                val partyId = _uiState.value.party?.id ?: break
                val profileId = _uiState.value.activeProfileId ?: break
                val pollStartedAt = currentEpochMs()
                // Drift is measured against the server's clock, so the offset has to be taken for
                // every party - including one whose channel never opens, which is where this used
                // to live. Without it a guest corrects towards this machine's idea of now, and two
                // machines never agree on that to better than a second or two.
                if (clockOffsetPartyId != partyId) {
                    clockOffsetPartyId = partyId
                    runCatching { withTimeoutOrNull(PartyPollAttemptDeadlineMs) { measureClockOffset() } }
                }
                // Deliberately not routed through call(): a background poll must not flip the
                // working flag or overwrite an error the user is still reading. It does need what
                // call() does for the session, though: without it an expired Z token failed every
                // heartbeat from then on, and the server read the silence as the host leaving.
                val attempt = withTimeoutOrNull(PartyPollAttemptDeadlineMs) {
                    runWithZSession(
                        ensure = { ZSessionBridge.ensureSession(profileId) },
                        reexchange = { ZSessionBridge.reexchange(profileId) },
                    ) {
                        // `party_heartbeat` with no position is `party_snapshot` plus a liveness stamp:
                        // it refreshes last_seen_at for this member and expires anyone who has stopped
                        // reporting. Only the player used to heartbeat, so a member sitting in the lobby
                        // or on the source list looked disconnected after fifteen seconds - and a host
                        // waiting on them to be ready would give up on them for no reason.
                        val liveGeneration = _uiState.value.party?.partyGenerationKey()
                        val telemetry = playbackTelemetry?.takeIf {
                            liveGeneration != null && liveGeneration.accepts(it.generation) &&
                                currentEpochMs() - it.capturedAtMs <= WatchPartySnapshotIntervalMs * 2
                        }
                        val snapshot = ZSupabaseProvider.client.postgrest.rpc("party_heartbeat", buildJsonObject {
                            put("p_party_id", partyId)
                            put("p_profile_id", profileId)
                            telemetry?.let { sample ->
                                val agedPosition = if (sample.status == WatchPartyStatus.playing) {
                                    val age = (currentEpochMs() - sample.capturedAtMs)
                                        .coerceIn(0L, WatchPartySnapshotIntervalMs)
                                    sample.positionMs + (age.toDouble() * sample.playbackSpeed.toDouble()).toLong()
                                } else {
                                    sample.positionMs
                                }
                                put("p_position_ms", agedPosition)
                                put("p_duration_ms", sample.durationMs)
                                put("p_playback_speed", sample.playbackSpeed)
                                put("p_status", sample.status.name)
                            }
                        }).decodeAs<WatchPartyState>()
                        installSnapshot(snapshot)
                    }
                } ?: Result.failure(PartyPollAttemptTimeoutException())
                attempt.onSuccess {
                    val now = currentEpochMs()
                    nextDelayMs = partyPollDelayAfter(succeeded = true)
                    liveness.onSuccess(now)?.let { silenceMs ->
                        log.w {
                            "poll recovered party=${partyId.shortId()} profile=${profileId.shortId()} " +
                                "silentMs=$silenceMs"
                        }
                    }
                    lastSuccessfulContactEpochMs = now
                    lastLoggedPollFailure = null
                    updateHealth(PartyHealthEvent.DurableSucceeded(now, heartbeat = true))
                    WatchPartyDiagnostics.poll(
                        partyId,
                        running = true,
                        api = "success",
                        sequence = _uiState.value.party?.sequence,
                        durationMs = (currentEpochMs() - pollStartedAt).coerceAtLeast(0L),
                    )
                }.onFailure { cause ->
                    WatchPartyDiagnostics.poll(
                        partyId,
                        running = true,
                        api = "failed",
                        durationMs = (currentEpochMs() - pollStartedAt).coerceAtLeast(0L),
                    )
                    val now = currentEpochMs()
                    nextDelayMs = partyPollDelayAfter(succeeded = false)
                    liveness.onFailure(now)?.let { silenceMs ->
                        // Class only: a message can carry a URL or a response body.
                        log.w {
                            "poll silent party=${partyId.shortId()} profile=${profileId.shortId()} " +
                                "silentMs=$silenceMs failedAttempts=${liveness.failuresSinceSuccess} " +
                                "lastFailure=${cause::class.simpleName} attemptMs=${now - pollStartedAt} " +
                                "loopLagMs=$loopLagMs"
                        }
                    }
                    updateHealth(
                        if (cause is PostgrestRestException) PartyHealthEvent.DurableRejected(now)
                        else PartyHealthEvent.DurableFailed(now),
                    )
                    val reason = cause.message ?: cause::class.simpleName
                    if (reason != lastLoggedPollFailure) {
                        lastLoggedPollFailure = reason
                        log.w(cause) { "poll failed party=${partyId.shortId()} profile=${profileId.shortId()}" }
                    }
                    onMemberRpcFailure(partyId, cause)
                }
            }
            pollJob = null
            updateHealth(PartyHealthEvent.PollingChanged(running = false))
            WatchPartyDiagnostics.poll(_uiState.value.party?.id, running = false, api = "idle")
        }
    }

    /**
     * Applies a broadcast that carries the party's playback state, and reports whether it did.
     *
     * Returns false only when this payload cannot stand in for a snapshot - a different party, a
     * content generation this client has not seen, a field missing because the server predates the
     * payload - so the caller falls back to the RPC. A *stale* payload returns true: dropping it is
     * a successful outcome, not a reason to go and ask again.
     *
     * `content` and `members` are deliberately not carried, so the held values are kept. Neither is
     * on the latency path, and a change to either moves `content_generation`, which sends this
     * whole payload to the fallback.
     */
    private fun applyBroadcastState(payload: JsonObject): Boolean {
        val held = _uiState.value.party
        val outcome = applyPartyStateBroadcast(held, payload, ::parseIsoEpochMs)
        val serverTimeMs = payload["server_time"]?.jsonPrimitive?.contentOrNull?.let { parseIsoEpochMs(it) }
        if (held != null) {
            log.i {
                val age = serverTimeMs?.let { currentEpochMs() + _uiState.value.serverClockOffsetMs - it }
                val applied = outcome is PartyBroadcastOutcome.Applied
                "broadcast party=${held.id.shortId()} " +
                    "seq=${payload["sequence"]?.jsonPrimitive?.longOrNull} " +
                    "outcome=${outcome::class.simpleName} ageMs=${age ?: -1} applied=$applied"
            }
        }
        return when (outcome) {
            PartyBroadcastOutcome.RefreshRequired -> false
            PartyBroadcastOutcome.Ignored -> {
                held?.let { WatchPartyDiagnostics.durableState(it.id, it.sequence, it.status, applied = false) }
                true
            }
            is PartyBroadcastOutcome.Applied -> {
                WatchPartyDiagnostics.durableState(
                    outcome.party.id,
                    outcome.party.sequence,
                    outcome.party.status,
                    applied = true,
                )
                if (outcome.party.status == WatchPartyStatus.ended) {
                    concludeHeldParty(outcome.party)
                } else {
                    _uiState.value = _uiState.value.copy(party = outcome.party)
                }
                true
            }
        }
    }

    /**
     * Asks for a snapshot without waiting for one.
     *
     * The timing plane calls this when a broadcast is about a content generation this client has
     * not seen: the payload cannot say what changed, and the RPC can. Coalesced like every other
     * refresh, so a burst of them costs one round trip.
     */
    internal fun requestRefresh() {
        val held = _uiState.value.party
        // An ended party has nothing left to refresh, and a guest is no longer allowed to read it.
        if (held == null || held.status == WatchPartyStatus.ended) return
        refreshRequests.tryEmit(Unit)
    }

    private fun stopPolling() {
        pollJob?.cancel(); pollJob = null
        updateHealth(PartyHealthEvent.PollingChanged(running = false))
        WatchPartyDiagnostics.poll(_uiState.value.party?.id, running = false, api = "idle")
    }

    private suspend fun partyCall(name: String, params: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): Result<Unit> = call {
        ZSupabaseProvider.client.postgrest.rpc(name, buildJsonObject(params)); refresh()
    }
    private suspend fun <T> call(block: suspend () -> T): Result<T> {
        _uiState.value = _uiState.value.copy(isWorking = true, errorMessage = null)
        val profileId = _uiState.value.activeProfileId
        if (profileId != null && !ZSessionBridge.ensureSession(profileId)) {
            _uiState.value = _uiState.value.copy(isWorking = false, errorMessage = "Nuvio Z is unavailable")
            return Result.failure(IllegalStateException("Nuvio Z session unavailable"))
        }
        var result = runCatching { block() }
        if (profileId != null && result.exceptionOrNull()?.let(::shouldReexchangeZSession) == true) {
            // A rejected Z token is the expected failure once one expires; the official session is
            // still live, so re-exchanging is the recovery. Retried once, so a real server error is
            // reported rather than looped on. Party control actions are latency-sensitive, which is
            // why this recovers in place instead of surfacing a reconnect to the user.
            if (ZSessionBridge.reexchange(profileId)) result = runCatching { block() }
        }
        return result.onSuccess {
            updateHealth(PartyHealthEvent.DurableSucceeded(currentEpochMs()))
            _uiState.value = _uiState.value.copy(isWorking = false)
        }
            .onFailure {
                val now = currentEpochMs()
                updateHealth(
                    if (it is PostgrestRestException) PartyHealthEvent.DurableRejected(now)
                    else PartyHealthEvent.DurableFailed(now),
                )
                // Until now a rejected RPC only ever reached the lobby's error line, which the
                // player never shows - so a party that quietly stopped working looked like a party
                // that was working.
                log.w(it) { "rpc failed party=${_uiState.value.party?.id.shortId()} profile=${profileId.shortId()}" }
                _uiState.value = _uiState.value.copy(isWorking = false, errorMessage = it.message)
                onMemberRpcFailure(failedPartyId = null, cause = it)
            }
    }
    private fun requireProfile(): String = requireNotNull(_uiState.value.activeProfileId) { "No active profile" }
    private fun requireParty(): WatchPartyState = requireNotNull(_uiState.value.party) { "No active party" }
}

/**
 * Invite codes are a bearer credential: anyone holding one can join the party. They therefore come
 * from [Uuid.random], which is specified to draw from the platform's secure generator on every
 * target, rather than from [kotlin.random.Random], whose sequence is predictable once observed.
 * The alphabet is exactly 32 characters, so masking five bits per byte stays uniform.
 */
@OptIn(ExperimentalUuidApi::class)
private fun generateInviteCode(): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val bytes = Uuid.random().toByteArray()
    return buildString { repeat(12) { index -> append(alphabet[bytes[index].toInt() and 31]) } }
}

/** UUIDs in full make the trace unreadable; the first eight characters identify a party or profile. */
internal fun String?.shortId(): String = this?.take(8) ?: "-"

private fun WatchPartyState.logSignature(): String = buildString {
    // `stage` and `sourceGeneration` are what the lobby decides on, and neither was here: a run
    // where the host pressed Start twice was indistinguishable from one where the snapshot never
    // moved, because the only generation logged was the content one.
    append("party=${id.shortId()} status=$status stage=${effectiveStage()} gen=$contentGeneration ")
    append("srcGen=$sourceGeneration src=${if (sourceFingerprint == null) "-" else "set"} seq=$sequence ")
    append("host=${hostProfileId.shortId()} positionMs=$positionMs durationMs=$durationMs ")
    append("speed=$playbackSpeed updatedAt=$stateUpdatedAt mode=$controlMode video=${content.videoId} ")
    append("members=[")
    members.joinTo(this) { member ->
        "${member.profileId.shortId()}:${member.role}:${member.readyState}:${if (member.connected) "up" else "down"}"
    }
    append("]")
}

internal fun currentEpochMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

private fun parseIsoEpochMs(value: String): Long? = runCatching { kotlin.time.Instant.parse(value).toEpochMilliseconds() }.getOrNull()
