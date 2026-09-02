package com.nuvio.app.features.watchparty

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max

const val WatchPartyMaxParticipants = 8
const val WatchPartyHostGraceMs = 15_000L
const val WatchPartySnapshotIntervalMs = 5_000L

/**
 * A refused private topic never reports itself subscribed - the client library retries the join in
 * the background and `subscribe(blockUntilSubscribed = true)` simply never returns - so the wait
 * needs a deadline of its own to be a wait at all rather than a coroutine parked for the life of
 * the app.
 */
const val WatchPartyChannelSubscribeTimeoutMs = 12_000L

/** Leaving a channel sends over the socket that has just failed, so it is bounded for the same reason. */
const val WatchPartyChannelCloseTimeoutMs = 3_000L

/** Drift the guest lives with: below this, correcting is more visible than the error. */
const val WatchPartyDriftDeadbandMs = 750L

/**
 * Above this a nudge cannot close the gap inside [WatchPartyNudgeWindowMs] without an audible
 * pitch shift, so the guest seeks and accepts the rebuffer.
 */
const val WatchPartySeekThresholdMs = 4_000L

/** How long a speed nudge is given to close the gap it was chosen for. */
const val WatchPartyNudgeWindowMs = 10_000L

/**
 * Bound on the nudge, as a fraction of the party's shared speed. The player preserves pitch, so
 * +-10% is unobjectionable across a few seconds of dialogue; past that it is audible.
 */
const val WatchPartyMaxNudgeRate = 0.10f

/**
 * How far ahead of the party a corrective seek aims.
 *
 * Seeking to where the party is *now* lands the guest where the party *was* by the time the seek
 * completes: the shared clock runs on through the rebuffer, so the guest resumes exactly one
 * rebuffer behind, measures the same drift again, and seeks again. Leading by roughly the cost of
 * resuming is what makes one correction one correction. Seeded from media3's own
 * `DEFAULT_BUFFER_FOR_PLAYBACK_MS`, which is the gate a post-seek resume actually waits on.
 */
const val WatchPartySeekLeadMs = 2_500L

@Serializable
enum class WatchPartyControlMode { host_only, collaborative }

@Serializable
enum class WatchPartyStatus { lobby, playing, paused, buffering, ended }

@Serializable
enum class SourceResolutionState { joined, resolving, buffering, ready, failed, left }

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
data class SourceFingerprint(
    @SerialName("addon_id") val addonId: String? = null,
    @SerialName("info_hash") val infoHash: String? = null,
    @SerialName("file_index") val fileIndex: Int? = null,
    @SerialName("release_fingerprint") val releaseFingerprint: String,
    val resolution: String? = null,
    val quality: String? = null,
    val languages: Set<String> = emptySet(),
    @SerialName("media_tags") val mediaTags: Set<String> = emptySet(),
)

@Serializable
data class WatchPartyParticipant(
    @SerialName("profile_id") val profileId: String,
    val role: String,
    @SerialName("ready_state") val readyState: SourceResolutionState,
    @SerialName("ready_error") val readyError: String? = null,
    @SerialName("resolved_duration_ms") val resolvedDurationMs: Long? = null,
    val connected: Boolean = true,
    @SerialName("joined_at") val joinedAt: String,
)

@Serializable
data class WatchPartyState(
    val id: String,
    @SerialName("host_profile_id") val hostProfileId: String,
    val status: WatchPartyStatus,
    @SerialName("control_mode") val controlMode: WatchPartyControlMode,
    @SerialName("content_generation") val contentGeneration: Int,
    val content: PartyContent,
    @SerialName("source_fingerprint") val sourceFingerprint: SourceFingerprint? = null,
    @SerialName("position_ms") val positionMs: Long,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("playback_speed") val playbackSpeed: Float,
    val sequence: Long,
    @SerialName("state_updated_at") val stateUpdatedAt: String,
    val members: List<WatchPartyParticipant> = emptyList(),
)

@Serializable
data class WatchPartyCommand(
    @SerialName("command_id") val commandId: String,
    val type: String,
    @SerialName("position_ms") val positionMs: Long? = null,
    @SerialName("playback_speed") val playbackSpeed: Float? = null,
)

enum class DriftCorrectionKind { NONE, TEMPORARY_SPEED, SEEK }

data class DriftCorrection(
    val kind: DriftCorrectionKind,
    val targetPositionMs: Long,
    val temporarySpeed: Float? = null,
    val restoreSpeed: Float,
)

fun expectedPartyPositionMs(
    statePositionMs: Long,
    stateUpdatedAtEpochMs: Long,
    serverNowEpochMs: Long,
    status: WatchPartyStatus,
    playbackSpeed: Float,
): Long {
    if (status != WatchPartyStatus.playing) return statePositionMs.coerceAtLeast(0L)
    val elapsed = (serverNowEpochMs - stateUpdatedAtEpochMs).coerceAtLeast(0L)
    return (statePositionMs + elapsed * playbackSpeed).toLong().coerceAtLeast(0L)
}

/**
 * Chooses how a guest closes the gap between where it is and where the party says it should be.
 *
 * A seek is the expensive option, not the safe one: on Android it is a rebuffer, and a rebuffer
 * does not resume until `bufferForPlaybackAfterRebufferMs` of media is held. A guest that seeks on
 * every correction therefore stalls, falls further behind while stalled, and is handed a *larger*
 * drift on the next pass - a loop that settles at roughly the rebuffer cushion and never leaves.
 * So the nudge band has to be wide enough, and the nudge itself strong enough, to be the ordinary
 * answer; seeking is reserved for a gap no nudge can close.
 *
 * The old fixed 1.03x could not: it recovered 300ms over its ten second hold, against a band that
 * admitted gaps up to 2,500ms. Every drift that mattered escalated to a seek. The rate is now
 * chosen for the gap - cover the party's own advance plus the drift, across
 * [WatchPartyNudgeWindowMs] - and clamped where the pitch shift starts to be audible.
 */
fun partyDriftCorrection(localPositionMs: Long, expectedPositionMs: Long, sharedSpeed: Float): DriftCorrection {
    val drift = expectedPositionMs - localPositionMs
    val magnitude = abs(drift)
    return when {
        magnitude <= WatchPartyDriftDeadbandMs ->
            DriftCorrection(DriftCorrectionKind.NONE, expectedPositionMs, restoreSpeed = sharedSpeed)
        magnitude <= WatchPartySeekThresholdMs -> DriftCorrection(
            kind = DriftCorrectionKind.TEMPORARY_SPEED,
            targetPositionMs = expectedPositionMs,
            temporarySpeed = partyNudgeSpeed(drift, sharedSpeed),
            restoreSpeed = sharedSpeed,
        )
        // Only when behind. A guest that is ahead is about to lose time to the stall anyway, so
        // leading it further would overshoot in the direction it is already going.
        else -> DriftCorrection(
            kind = DriftCorrectionKind.SEEK,
            targetPositionMs = expectedPositionMs + if (drift > 0) WatchPartySeekLeadMs else 0L,
            restoreSpeed = sharedSpeed,
        )
    }
}

/**
 * The playback rate that closes [driftMs] over [WatchPartyNudgeWindowMs] while the party goes on
 * advancing at [sharedSpeed] - so the guest has to cover the party's advance *and* the gap.
 *
 * Clamped as a factor of the shared speed rather than in absolute terms, so a party watching at
 * 1.5x is nudged by the same proportion a party at 1x is, and never past [DriftCorrection]'s own
 * playable range.
 */
internal fun partyNudgeSpeed(driftMs: Long, sharedSpeed: Float): Float {
    val rate = (driftMs.toFloat() / WatchPartyNudgeWindowMs)
        .coerceIn(-WatchPartyMaxNudgeRate, WatchPartyMaxNudgeRate)
    return (sharedSpeed * (1f + rate)).coerceIn(0.25f, 4f)
}

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
