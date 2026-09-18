package com.nuvio.app.features.watchparty

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max

const val WatchPartyMaxParticipants = 8
/**
 * The backend's host-transfer grace, mirrored here only so the client can describe it.
 *
 * It is not a rule this client applies. Host transfer is decided by `party_transfer_stale_host`
 * alone; a second local grace-and-claim race against it is what Stage 5 removed.
 */
const val WatchPartyHostGraceMs = 15_000L
const val WatchPartySnapshotIntervalMs = 5_000L

/**
 * How long to wait for the realtime channel to report itself subscribed.
 *
 * A topic the server refuses never reaches `SUBSCRIBED`: the client library retries the join in the
 * background and a caller blocking on the subscription waits for a message that is never coming.
 * Long enough that a slow socket still connects, short enough that a refused one is reported.
 */
const val WatchPartyChannelSubscribeTimeoutMs = 12_000L

/** How long to wait for a channel to be given up, over a socket that may be exactly what failed. */
const val WatchPartyChannelCloseTimeoutMs = 3_000L

/**
 * How long a subscribed channel may deliver nothing at all before the client says so.
 *
 * A join proves the read policy passed and nothing else. Comfortably longer than the clock ping
 * interval and the tick rate, so a party with any live peer in it has produced traffic well inside
 * this; short enough that a run does not have to be over before the file admits the transport is
 * dead. A solo party is expected to be silent and this is only ever a log line, never a banner.
 */
const val WatchPartyRealtimeVerificationGraceMs = 20_000L

/**
 * The party's two Realtime topics, and the reason there are two of them.
 *
 * Supabase Realtime authorizes a private channel **once, at join**, by asking the
 * `realtime.messages` policies for a read capability and a write capability, and it caches both for
 * the life of the socket. The write capability is decided by inserting a **stub row** - topic and
 * extension set, `payload` NULL - and rolling it back. There is no per-broadcast payload hook, so a
 * policy cannot decide anything about a message that has not been written yet.
 *
 * That is not a subtlety: the previous policy called a validator that read `sender_profile_id`,
 * `content_generation`, `source_generation` and `authority_epoch` off the payload. Asked about the
 * NULL stub it refused, so **every member was granted read and refused write for the life of the
 * socket** and no client broadcast has ever been delivered - which is exactly what the two-client
 * run showed: both clients subscribed, both stayed subscribed, every send returned local success,
 * and `WatchPartyTrace T3 RealtimeReceived` was zero for the whole run and for every historical
 * debugTools log beside it.
 *
 * So authority lives on the topic, which is the one thing this transport layer can authorize:
 *
 *  - [watchPartyAuthorityTopic] - members read; **no client may write it**. The backend authors
 *    everything on it: the durable `state` broadcasts, and the accepted transport command that
 *    `party_submit_command_v2` emits with the sender and generations it read from the locked row.
 *  - [watchPartyPeerTopic] - members read and write. Host position ticks, the clock exchange and
 *    per-member telemetry. Nothing here has authority: a tick is accepted only from the host the
 *    *durable snapshot* names, a pong only from that host for an exchange this client started, and
 *    a peer status can only ever make the host wait for somebody.
 */
fun watchPartyAuthorityTopic(partyId: String): String = "party:$partyId"

/** See [watchPartyAuthorityTopic]. `PartyRealtimePlane` in the protocol file decides what each carries. */
fun watchPartyPeerTopic(partyId: String): String = "party_peer:$partyId"

@Serializable
enum class WatchPartyControlMode { host_only, collaborative }

@Serializable
enum class WatchPartyStatus { lobby, playing, paused, buffering, ended }

@Serializable
enum class SourceResolutionState {
    joined,
    waiting_for_host,
    fetching,
    resolving,
    choosing_fallback,
    source_ready,
    buffering,
    ready,
    failed,
    left,
    disconnected,
}

@Serializable
enum class WatchPartyStage { lobby, waiting_for_host_source, resolving_sources, ready_to_launch, playing }

@Serializable
enum class PartySourceMatch { exact, alternate }

@Serializable
data class PartyAddonSignature(
    val id: String,
    val version: String,
)

@Serializable
data class PartyParticipantProfile(
    @SerialName("display_name") val displayName: String = "",
    val handle: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("avatar_color_hex") val avatarColorHex: String = "#1E88E5",
)

@Serializable
enum class PartyConnectionState { connected, reconnecting, disconnected }

@Serializable
data class PartyContent(
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("video_id") val videoId: String,
    val title: String,
    val poster: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("episode_title") val episodeTitle: String? = null,
)

@Serializable
@Deprecated("V1 compatibility only; new party flows use PartySourceDescriptorV2")
data class SourceFingerprint(
    @SerialName("addon_id") val addonId: String? = null,
    @SerialName("info_hash") val infoHash: String? = null,
    @SerialName("file_index") val fileIndex: Int? = null,
    @SerialName("release_fingerprint") val releaseFingerprint: String = "",
    val resolution: String? = null,
    val quality: String? = null,
    val languages: Set<String> = emptySet(),
    @SerialName("media_tags") val mediaTags: Set<String> = emptySet(),
)

@Serializable
enum class WatchPartyClientLocation { lobby, matching, loading, player, reconnecting }

@Serializable
data class WatchPartyParticipant(
    @SerialName("profile_id") val profileId: String,
    val role: String,
    @SerialName("ready_state") val readyState: SourceResolutionState,
    @SerialName("ready_error") val readyError: String? = null,
    @SerialName("resolved_duration_ms") val resolvedDurationMs: Long? = null,
    val profile: PartyParticipantProfile? = null,
    @SerialName("addon_signature") val addonSignature: List<PartyAddonSignature> = emptyList(),
    @SerialName("source_generation") val sourceGeneration: Int = 0,
    @SerialName("source_match") val sourceMatch: PartySourceMatch? = null,
    val connected: Boolean = true,
    @SerialName("client_location") val clientLocation: WatchPartyClientLocation = WatchPartyClientLocation.lobby,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("joined_at") val joinedAt: String,
)

@Serializable
data class WatchPartyState(
    val id: String,
    @SerialName("host_profile_id") val hostProfileId: String,
    val status: WatchPartyStatus,
    @SerialName("control_mode") val controlMode: WatchPartyControlMode,
    @SerialName("content_generation") val contentGeneration: Int,
    @SerialName("source_generation") val sourceGeneration: Int = 0,
    val stage: WatchPartyStage = WatchPartyStage.lobby,
    val content: PartyContent,
    @SerialName("source_fingerprint") val sourceFingerprint: PartySourceDescriptorV2? = null,
    @SerialName("position_ms") val positionMs: Long,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("playback_speed") val playbackSpeed: Float,
    val sequence: Long,
    @SerialName("state_updated_at") val stateUpdatedAt: String,
    val members: List<WatchPartyParticipant> = emptyList(),
    @SerialName("authority_epoch") val authorityEpoch: Long = 0,
    @SerialName("origin_presence_session_id") val originPresenceSessionId: String? = null,
    @SerialName("ended_reason") val endedReason: String? = null,
)

@Serializable
data class WatchPartyCommand(
    @SerialName("command_id") val commandId: String,
    val type: String,
    @SerialName("position_ms") val positionMs: Long? = null,
    @SerialName("playback_speed") val playbackSpeed: Float? = null,
    /**
     * The party instant every member should reach [positionMs] at, and whether to run from there.
     *
     * These used to travel only on the client's own broadcast, because the durable row was a record
     * of what had happened rather than the thing that made it happen. It is now both: no client may
     * write the authority plane, so `party_submit_command_v2` is what carries this command to the
     * other members, and a barrier with no instant is a barrier every member reaches at a different
     * time - which is the jump-on-resume this whole transport exists to remove.
     *
     * Null means "as soon as you get it", which is what a pause has always meant.
     */
    @SerialName("start_at_party_ms") val startAtPartyMs: Long? = null,
    @SerialName("play_after") val playAfter: Boolean? = null,
)

fun arePartyDurationsCompatible(hostDurationMs: Long, candidateDurationMs: Long): Boolean {
    if (hostDurationMs <= 0L || candidateDurationMs <= 0L) return true
    val tolerance = max(90_000L, (hostDurationMs * 0.02).toLong())
    return abs(hostDurationMs - candidateDurationMs) <= tolerance
}

fun sourceFingerprintMatchScore(host: SourceFingerprint, candidate: SourceFingerprint): Int {
    if (host.infoHash != null && candidate.infoHash != null && host.infoHash.equals(candidate.infoHash, true)) {
        return if (host.fileIndex == candidate.fileIndex) 10_000 else 9_000
    }
    var score = 0
    if (host.addonId != null && host.addonId == candidate.addonId) score += 500
    if (host.releaseFingerprint == candidate.releaseFingerprint) score += 4_000
    if (host.resolution != null && host.resolution == candidate.resolution) score += 200
    if (host.quality != null && host.quality == candidate.quality) score += 100
    score += host.languages.intersect(candidate.languages).size * 20
    score += host.mediaTags.intersect(candidate.mediaTags).size * 10
    return score
}

fun normalizeReleaseFingerprint(value: String): String = value
    .lowercase()
    .replace(Regex("[^a-z0-9]+"), ".")
    .trim('.')
    .replace(Regex("\\.+"), ".")

/** Why a client is holding playback back while it sits in a party. */
enum class PartyHoldReason { NONE, WAITING_FOR_PARTICIPANTS, WAITING_FOR_HOST, HOST_BUFFERING }

data class PartyPlaybackGate(
    val allowPlayback: Boolean,
    val reason: PartyHoldReason,
    val waitingOn: Int = 0,
)

/** Why Watch Together is deliberately keeping a player still, for the startup watchdog and the log. */
enum class PartyStartupHoldReason {
    NONE,

    /** The readiness gate: this member may not play yet. Carries the gate's own reason. */
    GATE,

    /** Parked on a barrier instant, or seeking to one. The player is meant to be motionless. */
    BARRIER,

    /** The party is paused. A paused player does not buffer, and is not failing to. */
    PAUSED,
}

/**
 * Whether the party is holding this player, and which of its own mechanisms is doing it.
 *
 * Pure and separate from the player so the physically-reproduced abandonment can be tested: a guest
 * held at the gate, then paused by the host before its first frame, was abandoned twelve seconds
 * later by a startup watchdog that could not tell "no progress" from "no progress *asked for*".
 */
data class PartyStartupHold(
    val isHeld: Boolean = false,
    val reason: PartyStartupHoldReason = PartyStartupHoldReason.NONE,
    val gateReason: PartyHoldReason = PartyHoldReason.NONE,
) {
    companion object { val none = PartyStartupHold() }
}

/**
 * Reads the party's intent towards one player.
 *
 * [partyWantsPlayback] is this client's own play intent as the party has set it - `shouldPlay` in
 * the player runtime, which the gate clears, a `pause` barrier clears, and a `play` barrier sets.
 * It is deliberately the *intent* and not `isPlaying`: a player that has been told to play and has
 * not managed it yet is exactly the source this watchdog is for, and reading the engine here would
 * excuse it.
 *
 * Ordered by specificity rather than by precedence, because all three are true together during a
 * gated start and the log wants the innermost reason.
 */
fun resolvePartyStartupHold(
    inMatchingParty: Boolean,
    gate: PartyPlaybackGate,
    holdingForBarrier: Boolean,
    partyWantsPlayback: Boolean,
): PartyStartupHold = when {
    !inMatchingParty -> PartyStartupHold.none
    !gate.allowPlayback -> PartyStartupHold(true, PartyStartupHoldReason.GATE, gate.reason)
    holdingForBarrier -> PartyStartupHold(true, PartyStartupHoldReason.BARRIER)
    !partyWantsPlayback -> PartyStartupHold(true, PartyStartupHoldReason.PAUSED)
    else -> PartyStartupHold.none
}

private val PartyBlockingReadyStates = setOf(
    SourceResolutionState.joined,
    SourceResolutionState.waiting_for_host,
    SourceResolutionState.fetching,
    SourceResolutionState.resolving,
    SourceResolutionState.choosing_fallback,
    SourceResolutionState.source_ready,
    SourceResolutionState.buffering,
    SourceResolutionState.disconnected,
)

/**
 * Whether this party is the one behind the video currently open in the player.
 *
 * Every party action is scoped through this, so it lives beside the state rather than being
 * re-spelled at each call site - the seek that fires against the wrong title is the one written
 * out by hand a fourth time.
 */
fun WatchPartyState.matchesPlayback(contentId: String, videoId: String?): Boolean =
    status != WatchPartyStatus.ended &&
        content.contentId == contentId &&
        content.videoId == videoId

/**
 * Members the host is still waiting on before playback can begin.
 *
 * A member counts only while they are connected and still working towards a source: someone whose
 * resolution failed, who left, or whose app is gone cannot be waited for, and treating them as a
 * blocker is how one closed laptop holds a party hostage forever.
 */
fun partyMembersAwaitingSource(
    party: WatchPartyState,
    excludeProfileId: String? = null,
): List<WatchPartyParticipant> = party.members.filter { member ->
    member.profileId != excludeProfileId &&
        member.connected &&
        member.readyState in PartyBlockingReadyStates
}

/**
 * Whether this client may play, and what it is waiting for when it may not.
 *
 * The host used to begin the moment their own source opened, which started the authoritative clock
 * while everyone else was still on the source list; by the time a guest had a stream the shared
 * timeline had run minutes ahead of anything they could show, and the correction policy chased it
 * with seeks into an unbuffered file. Holding the host until every connected member reports a
 * resolved source is what makes "everyone starts together" true rather than aspirational.
 *
 * [hostStartReleased] is the escape: once the party has genuinely started, the host owns their own
 * transport again and a mid-film pause must not be mistaken for a fresh start.
 */
fun partyPlaybackGate(
    party: WatchPartyState?,
    viewerProfileId: String?,
    hostStartReleased: Boolean,
    hostBufferingReleased: Boolean = false,
): PartyPlaybackGate {
    if (party == null || party.status == WatchPartyStatus.ended) {
        return PartyPlaybackGate(allowPlayback = true, reason = PartyHoldReason.NONE)
    }
    if (party.status == WatchPartyStatus.playing) {
        return PartyPlaybackGate(allowPlayback = true, reason = PartyHoldReason.NONE)
    }
    if (party.hostProfileId != viewerProfileId) {
        return when {
            party.status == WatchPartyStatus.buffering && hostBufferingReleased ->
                PartyPlaybackGate(allowPlayback = true, reason = PartyHoldReason.NONE)
            party.status == WatchPartyStatus.buffering ->
                PartyPlaybackGate(allowPlayback = false, reason = PartyHoldReason.HOST_BUFFERING)
            else ->
                PartyPlaybackGate(allowPlayback = false, reason = PartyHoldReason.WAITING_FOR_HOST)
        }
    }
    if (hostStartReleased) {
        return PartyPlaybackGate(allowPlayback = true, reason = PartyHoldReason.NONE)
    }
    val waiting = partyMembersAwaitingSource(party, excludeProfileId = viewerProfileId)
    return if (waiting.isEmpty()) {
        PartyPlaybackGate(allowPlayback = true, reason = PartyHoldReason.NONE)
    } else {
        PartyPlaybackGate(
            allowPlayback = false,
            reason = PartyHoldReason.WAITING_FOR_PARTICIPANTS,
            waitingOn = waiting.size,
        )
    }
}
