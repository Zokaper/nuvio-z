package com.nuvio.app.features.watchparty

import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(WatchPartyUiState())
    val uiState: StateFlow<WatchPartyUiState> = _uiState.asStateFlow()
    private var channel: RealtimeChannel? = null
    private var collector: Job? = null

    fun setActiveProfile(profileId: String?) {
        if (_uiState.value.activeProfileId == profileId) return
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
        closeChannel(); _uiState.value = WatchPartyUiState(activeProfileId = _uiState.value.activeProfileId)
    }

    suspend fun leave(): Result<Unit> = runCatching {
        val party = _uiState.value.party
        val profile = _uiState.value.activeProfileId
        if (party != null && profile != null) SupabaseProvider.client.postgrest.rpc("party_leave", buildJsonObject {
            put("p_party_id", party.id); put("p_profile_id", profile)
        })
        closeChannel(); _uiState.value = WatchPartyUiState(activeProfileId = profile)
    }

    private suspend fun installSnapshot(snapshot: WatchPartyState, reopenChannel: Boolean = true) {
        _uiState.value = _uiState.value.copy(party = snapshot, isWorking = false, errorMessage = null)
        if (reopenChannel && channel?.topic != "realtime:party:${snapshot.id}") openChannel(snapshot.id)
    }

    private suspend fun openChannel(partyId: String) {
        closeChannel()
        _uiState.value = _uiState.value.copy(connection = PartyConnectionState.reconnecting)
        SupabaseProvider.client.realtime.setAuth()
        val next = SupabaseProvider.client.channel("party:$partyId") { isPrivate = true; presence { key = requireProfile() } }
        collector = next.broadcastFlow<JsonObject>("state").onEach { refresh() }.launchIn(scope)
        next.subscribe(blockUntilSubscribed = true)
        next.track(buildJsonObject { put("profile_id", requireProfile()) })
        channel = next
        _uiState.value = _uiState.value.copy(connection = PartyConnectionState.connected)
        refresh(); measureClockOffset()
    }

    private suspend fun closeChannel() {
        collector?.cancel(); collector = null
        channel?.let { runCatching { SupabaseProvider.client.realtime.removeChannel(it) } }
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

private fun currentEpochMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

private fun parseIsoEpochMs(value: String): Long? = runCatching { kotlin.time.Instant.parse(value).toEpochMilliseconds() }.getOrNull()
