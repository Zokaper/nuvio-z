package com.nuvio.app.features.watchparty

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class WatchPartyUiState(
    val activeProfileId: String? = null,
    val party: WatchPartyState? = null,
    val connection: PartyConnectionState = PartyConnectionState.disconnected,
    val serverClockOffsetMs: Long = 0,
    val isWorking: Boolean = false,
    val errorMessage: String? = null,
)

object WatchPartyRepository {
    /**
     * The transport half of the Watch Together trace; `WatchPartyPlayer` carries what each client
     * decided to do about the state it was given. Every line names the party and the profile,
     * because two clients disagreeing about a party is only ever diagnosable by lining the two logs
     * up side by side. Invite codes are a bearer credential and are never written out in full.
     */
    private val log = Logger.withTag("WatchParty")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(WatchPartyUiState())
    val uiState: StateFlow<WatchPartyUiState> = _uiState.asStateFlow()
    private var channel: RealtimeChannel? = null
    private var collector: Job? = null
    private var pollJob: Job? = null
    private var refreshJob: Job? = null

    /**
     * Coalesces broadcast-driven refreshes.
     *
     * The broadcast carries no state, so every one of them used to mean a `party_snapshot` RPC -
     * and they arrived in bursts, four to six inside a second, because a member heartbeat broadcast
     * as loudly as a real command. `onEach { refresh() }` collects sequentially, so those RPCs
     * queued, and the newest command - the pause the user is waiting on - sat at the back of a
     * queue whose depth *was* the latency. Dropping the oldest keeps at most one refresh in flight
     * and one pending: a burst of six now costs two round trips instead of six, and the newest
     * always wins because every refresh fetches current state anyway.
     */
    private val refreshRequests = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var channelSubscribed: Boolean = false
    private var clockOffsetPartyId: String? = null
    private var lastLoggedPollFailure: String? = null

    fun setActiveProfile(profileId: String?) {
        if (_uiState.value.activeProfileId == profileId) return
        log.i { "profile from=${_uiState.value.activeProfileId.shortId()} to=${profileId.shortId()}" }
        stopPolling()
        scope.launch { if (_uiState.value.party != null) leave() else closeChannel() }
        _uiState.value = WatchPartyUiState(activeProfileId = profileId)
    }

    suspend fun create(
        content: PartyContent,
        sourceFingerprint: SourceFingerprint? = null,
        qualityIntent: JsonObject? = null,
        controlMode: WatchPartyControlMode = WatchPartyControlMode.host_only,
    ): Result<String> = call {
        val profileId = requireProfile()
        val code = generateInviteCode()
        val snapshot = SupabaseProvider.client.postgrest.rpc("party_create", buildJsonObject {
            put("p_host_profile_id", profileId); put("p_invite_code", code)
            put("p_content", json.encodeToJsonElement(content))
            sourceFingerprint?.let { put("p_source_fingerprint", json.encodeToJsonElement(it)) }
            qualityIntent?.let { put("p_quality_intent", it) }
            put("p_control_mode", controlMode.name)
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot)
        code
    }

    suspend fun join(partyId: String? = null, inviteCode: String? = null): Result<Unit> = call {
        require(partyId != null || !inviteCode.isNullOrBlank()) { "Party ID or invite code required" }
        val snapshot = SupabaseProvider.client.postgrest.rpc("party_join", buildJsonObject {
            put("p_profile_id", requireProfile())
            partyId?.let { put("p_party_id", it) }
            inviteCode?.let { put("p_invite_code", it) }
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot)
    }

    suspend fun invite(friendProfileId: String): Result<Unit> = partyCall("party_invite_friend") {
        put("p_party_id", requireParty().id); put("p_host_profile_id", requireProfile()); put("p_receiver_profile_id", friendProfileId)
    }

    suspend fun updateReady(state: SourceResolutionState, durationMs: Long? = null, error: String? = null): Result<Unit> = call {
        val party = requireParty()
        val snapshot = SupabaseProvider.client.postgrest.rpc("party_set_ready", buildJsonObject {
            put("p_party_id", party.id); put("p_profile_id", requireProfile()); put("p_ready_state", state.name)
            durationMs?.let { put("p_duration_ms", it) }; error?.let { put("p_error", it) }
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot, reopenChannel = false)
    }

    suspend fun submit(command: WatchPartyCommand): Result<Unit> = call {
        val snapshot = SupabaseProvider.client.postgrest.rpc("party_submit_command", buildJsonObject {
            put("p_party_id", requireParty().id); put("p_profile_id", requireProfile()); put("p_command_id", command.commandId)
            put("p_command_type", command.type); put("p_payload", buildJsonObject {
                command.positionMs?.let { put("position_ms", it) }; command.playbackSpeed?.let { put("playback_speed", it) }
            })
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot, reopenChannel = false)
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun play(positionMs: Long) = submit(WatchPartyCommand(Uuid.random().toString(), "play", positionMs))
    @OptIn(ExperimentalUuidApi::class)
    suspend fun pause(positionMs: Long) = submit(WatchPartyCommand(Uuid.random().toString(), "pause", positionMs))
    @OptIn(ExperimentalUuidApi::class)
    suspend fun seek(positionMs: Long) = submit(WatchPartyCommand(Uuid.random().toString(), "seek", positionMs))
    @OptIn(ExperimentalUuidApi::class)
    suspend fun setSpeed(speed: Float) = submit(WatchPartyCommand(Uuid.random().toString(), "speed", playbackSpeed = speed))

    suspend fun heartbeat(positionMs: Long, durationMs: Long, speed: Float, status: WatchPartyStatus): Result<Unit> = call {
        val snapshot = SupabaseProvider.client.postgrest.rpc("party_heartbeat", buildJsonObject {
            put("p_party_id", requireParty().id); put("p_profile_id", requireProfile()); put("p_position_ms", positionMs)
            put("p_duration_ms", durationMs); put("p_playback_speed", speed); put("p_status", status.name)
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot, reopenChannel = false)
    }

    suspend fun changeContent(content: PartyContent, fingerprint: SourceFingerprint?, qualityIntent: JsonObject? = null): Result<Unit> = call {
        val snapshot = SupabaseProvider.client.postgrest.rpc("party_change_content", buildJsonObject {
            put("p_party_id", requireParty().id); put("p_host_profile_id", requireProfile()); put("p_content", json.encodeToJsonElement(content))
            fingerprint?.let { put("p_source_fingerprint", json.encodeToJsonElement(it)) }; qualityIntent?.let { put("p_quality_intent", it) }
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot, reopenChannel = false)
    }

    suspend fun setControlMode(mode: WatchPartyControlMode): Result<Unit> = call {
        val snapshot = SupabaseProvider.client.postgrest.rpc("party_set_control_mode", buildJsonObject {
            put("p_party_id", requireParty().id); put("p_host_profile_id", requireProfile()); put("p_mode", mode.name)
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot, reopenChannel = false)
    }

    suspend fun refresh(): Result<Unit> = call {
        val snapshot = SupabaseProvider.client.postgrest.rpc("party_snapshot", buildJsonObject {
            put("p_party_id", requireParty().id); put("p_profile_id", requireProfile())
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot, reopenChannel = false)
    }

    suspend fun measureClockOffset(): Result<Long> = call {
        var bestRtt = Long.MAX_VALUE
        var bestOffset = 0L
        repeat(3) {
            val started = currentEpochMs()
            val serverIso = SupabaseProvider.client.postgrest.rpc("party_clock").decodeAs<String>()
            val ended = currentEpochMs()
            val server = parseIsoEpochMs(serverIso) ?: return@repeat
            val rtt = ended - started
            if (rtt < bestRtt) { bestRtt = rtt; bestOffset = server - ((started + ended) / 2) }
        }
        _uiState.value = _uiState.value.copy(serverClockOffsetMs = bestOffset)
        bestOffset
    }

    suspend fun claimHostAfterGrace(): Result<Unit> = call {
        val snapshot = SupabaseProvider.client.postgrest.rpc("party_claim_or_transfer_host", buildJsonObject {
            put("p_party_id", requireParty().id); put("p_requester_profile_id", requireProfile())
        }).decodeAs<WatchPartyState>()
        installSnapshot(snapshot, reopenChannel = false)
    }

    suspend fun end(): Result<Unit> = call {
        val party = requireParty()
        SupabaseProvider.client.postgrest.rpc("party_end", buildJsonObject { put("p_party_id", party.id); put("p_host_profile_id", requireProfile()) })
        stopPolling(); closeChannel(); _uiState.value = WatchPartyUiState(activeProfileId = _uiState.value.activeProfileId)
    }

    suspend fun leave(): Result<Unit> = runCatching {
        val party = _uiState.value.party
        val profile = _uiState.value.activeProfileId
        if (party != null && profile != null) SupabaseProvider.client.postgrest.rpc("party_leave", buildJsonObject {
            put("p_party_id", party.id); put("p_profile_id", profile)
        })
        stopPolling(); closeChannel(); _uiState.value = WatchPartyUiState(activeProfileId = profile)
    }

    private suspend fun installSnapshot(snapshot: WatchPartyState, reopenChannel: Boolean = true) {
        _uiState.value = _uiState.value.copy(party = snapshot, isWorking = false, errorMessage = null)
        // The poll is the floor under this whole feature, so nothing may come before it. Opening the
        // channel used to, and `subscribe(blockUntilSubscribed = true)` never returns for a topic
        // the server refuses - so a channel that could not be authorized left this line unreached
        // for the life of the app. The member kept whatever state they joined with, forever.
        startPolling()
        if (reopenChannel && channel?.topic != "realtime:party:${snapshot.id}") openChannel(snapshot.id)
    }

    /**
     * Polls the snapshot while a party is held.
     *
     * Realtime stays the fast path; this is the floor beneath it. Party state used to reach clients
     * only through the broadcast, so a member whose channel was not working never learned anything
     * had changed - and had no way to tell why.
     */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                delay(WatchPartySnapshotIntervalMs)
                val partyId = _uiState.value.party?.id ?: break
                val profileId = _uiState.value.activeProfileId ?: break
                // Drift is measured against the server clock, so the offset has to be taken for
                // every party - including one whose channel never opens, which is where this used
                // to live. Behind the subscribe it was never reached at all when the channel was
                // refused, and a guest left on an offset of zero corrects towards *this* device's
                // idea of now: two phones never agree on that to better than a second or two, and
                // the error is constant, so it shows up as a fixed gap that never closes.
                if (clockOffsetPartyId != partyId) {
                    clockOffsetPartyId = partyId
                    runCatching { measureClockOffset() }
                }
                // Realtime is worth another attempt whenever it is down: the party is still live,
                // and the alternative is running the rest of it on this interval.
                if (!channelSubscribed) openChannel(partyId)
                // Deliberately not routed through call(): a background poll must not flip the
                // working flag or overwrite an error the user is still reading.
                runCatching {
                    // `party_heartbeat` with no position is `party_snapshot` plus a liveness stamp:
                    // it refreshes last_seen_at for this member and expires anyone who has stopped
                    // reporting. Only the player used to heartbeat, so a member sitting in the lobby
                    // or on the source list looked disconnected after fifteen seconds.
                    val snapshot = SupabaseProvider.client.postgrest.rpc("party_heartbeat", buildJsonObject {
                        put("p_party_id", partyId); put("p_profile_id", profileId)
                    }).decodeAs<WatchPartyState>()
                    installSnapshot(snapshot, reopenChannel = false)
                }.onSuccess { lastLoggedPollFailure = null }.onFailure { cause ->
                    // A poll failing every five seconds is the one thing that can strand a member on
                    // state that never moves again, and it used to leave no trace at all. Logged
                    // when the reason changes, not on every tick.
                    val reason = cause.message ?: cause::class.simpleName
                    if (reason != lastLoggedPollFailure) {
                        lastLoggedPollFailure = reason
                        log.w(cause) { "poll failed party=${partyId.shortId()} profile=${profileId.shortId()}" }
                    }
                }
            }
            pollJob = null
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
        fun str(key: String) = payload[key]?.jsonPrimitive?.contentOrNull

        val held = _uiState.value.party ?: return false
        if (str("party_id") != held.id) return false
        val sequence = payload["sequence"]?.jsonPrimitive?.longOrNull ?: return false
        val updatedAt = str("state_updated_at") ?: return false
        val updatedAtMs = parseIsoEpochMs(updatedAt) ?: return false
        val generation = payload["content_generation"]?.jsonPrimitive?.intOrNull ?: return false
        if (generation != held.contentGeneration) return false
        val status = str("status")?.let { name -> runCatching { WatchPartyStatus.valueOf(name) }.getOrNull() }
            ?: return false
        val positionMs = payload["position_ms"]?.jsonPrimitive?.longOrNull ?: return false

        // `party_heartbeat` moves the host's position and `state_updated_at` *without* bumping
        // `sequence`, so a strict `sequence >` guard would drop every running-position update and
        // leave a guest correcting against a clock that never moved. The order is lexicographic
        // over the pair, and the timestamps are compared as epoch millis because Postgres trims
        // trailing zeros from fractional seconds - the ISO strings do not sort correctly as text.
        val heldUpdatedAtMs = parseIsoEpochMs(held.stateUpdatedAt) ?: Long.MIN_VALUE
        val newer = sequence > held.sequence || (sequence == held.sequence && updatedAtMs > heldUpdatedAtMs)

        val serverTimeMs = str("server_time")?.let { parseIsoEpochMs(it) }
        log.i {
            val age = serverTimeMs?.let { currentEpochMs() + _uiState.value.serverClockOffsetMs - it }
            "broadcast party=${held.id.shortId()} seq=$sequence status=$status ageMs=${age ?: -1} applied=$newer"
        }
        if (!newer) return true

        _uiState.value = _uiState.value.copy(
            party = held.copy(
                sequence = sequence,
                status = status,
                positionMs = positionMs,
                stateUpdatedAt = updatedAt,
                durationMs = payload["duration_ms"]?.jsonPrimitive?.longOrNull ?: held.durationMs,
                playbackSpeed = payload["playback_speed"]?.jsonPrimitive?.floatOrNull ?: held.playbackSpeed,
                hostProfileId = str("host_profile_id") ?: held.hostProfileId,
                controlMode = str("control_mode")
                    ?.let { name -> runCatching { WatchPartyControlMode.valueOf(name) }.getOrNull() }
                    ?: held.controlMode,
            ),
        )
        return true
    }

    private fun stopPolling() {
        pollJob?.cancel(); pollJob = null
        clockOffsetPartyId = null
        lastLoggedPollFailure = null
    }

    private suspend fun openChannel(partyId: String) {
        closeChannel()
        _uiState.value = _uiState.value.copy(connection = PartyConnectionState.reconnecting)

        // `reconnecting` was set before the attempt and cleared only on success, so anything thrown
        // below left the lobby reporting "Reconnecting" forever with no way to tell why.
        //
        // Realtime is an accelerator, not the source of truth: every snapshot still comes from an
        // RPC, so a party whose channel will not open is degraded rather than broken. Say so, and
        // carry on.
        val opened = runCatching {
            SupabaseProvider.client.realtime.setAuth()
            val next = SupabaseProvider.client.channel("party:$partyId") { isPrivate = true; presence { key = requireProfile() } }
            // Held before it is subscribed, because a channel dropped on the floor is not idle: the
            // client library goes on retrying its join, and nothing is left holding it to stop.
            channel = next
            // A broadcast that carries the state is applied here and now; one that does not - a
            // member change, an older server, a payload this build cannot read - falls through to
            // the snapshot RPC. The fallback is what keeps this an optimisation rather than a
            // second source of truth.
            collector = next.broadcastFlow<JsonObject>("state")
                .onEach { payload -> if (!applyBroadcastState(payload)) refreshRequests.tryEmit(Unit) }
                .launchIn(scope)
            refreshJob?.cancel()
            refreshJob = refreshRequests.onEach { refresh() }.launchIn(scope)
            // A refused topic never reports itself subscribed, so the wait needs a deadline of its
            // own to be a wait at all rather than a coroutine parked for the life of the app.
            withTimeout(WatchPartyChannelSubscribeTimeoutMs) { next.subscribe(blockUntilSubscribed = true) }
            next.track(buildJsonObject { put("profile_id", requireProfile()) })
            channelSubscribed = true
        }

        opened.onFailure { cause ->
            // A timeout is a channel that will not open and is worth saying so. A plain cancellation
            // is this party going away underneath the attempt, and a banner written on the way out
            // would outlive the thing it describes.
            if (cause !is CancellationException || cause is TimeoutCancellationException) {
                log.w { "realtime party=${partyId.shortId()} state=disconnected cause=${cause.message ?: cause::class.simpleName}" }
                closeChannel()
                _uiState.value = _uiState.value.copy(
                    connection = PartyConnectionState.disconnected,
                    errorMessage = "Live sync unavailable: ${cause.message ?: cause::class.simpleName}",
                )
            }
        }.onSuccess {
            log.i { "realtime party=${partyId.shortId()} state=connected" }
            _uiState.value = _uiState.value.copy(connection = PartyConnectionState.connected, errorMessage = null)
            refresh()
        }
    }

    private suspend fun closeChannel() {
        channelSubscribed = false
        collector?.cancel(); collector = null
        refreshJob?.cancel(); refreshJob = null
        // Leaving a channel means sending over the socket that has just failed, so this is bounded
        // for the same reason the subscription is: `leave()` runs through here from a button press,
        // and a party that cannot be left is worse than one that cannot be joined.
        channel?.let {
            runCatching { withTimeout(WatchPartyChannelCloseTimeoutMs) { SupabaseProvider.client.realtime.removeChannel(it) } }
        }
        channel = null
    }

    private suspend fun partyCall(name: String, params: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): Result<Unit> = call {
        SupabaseProvider.client.postgrest.rpc(name, buildJsonObject(params)); refresh()
    }
    private suspend inline fun <T> call(crossinline block: suspend () -> T): Result<T> {
        _uiState.value = _uiState.value.copy(isWorking = true, errorMessage = null)
        return runCatching { block() }.onSuccess { _uiState.value = _uiState.value.copy(isWorking = false) }
            .onFailure { _uiState.value = _uiState.value.copy(isWorking = false, errorMessage = it.message) }
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

internal fun String?.shortId(): String = this?.take(8) ?: "-"

private fun currentEpochMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

private fun parseIsoEpochMs(value: String): Long? = runCatching { kotlin.time.Instant.parse(value).toEpochMilliseconds() }.getOrNull()
