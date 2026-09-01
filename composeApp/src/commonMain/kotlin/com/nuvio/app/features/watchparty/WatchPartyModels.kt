package com.nuvio.app.features.watchparty

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max

const val WatchPartyMaxParticipants = 8
const val WatchPartyHostGraceMs = 15_000L
const val WatchPartySnapshotIntervalMs = 5_000L

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

fun partyDriftCorrection(localPositionMs: Long, expectedPositionMs: Long, sharedSpeed: Float): DriftCorrection {
    val drift = expectedPositionMs - localPositionMs
    val magnitude = abs(drift)
    return when {
        magnitude <= 750L -> DriftCorrection(DriftCorrectionKind.NONE, expectedPositionMs, restoreSpeed = sharedSpeed)
        magnitude <= 2_500L -> DriftCorrection(
            kind = DriftCorrectionKind.TEMPORARY_SPEED,
            targetPositionMs = expectedPositionMs,
            temporarySpeed = (sharedSpeed * if (drift > 0) 1.03f else 0.97f).coerceIn(0.25f, 4f),
            restoreSpeed = sharedSpeed,
        )
        else -> DriftCorrection(DriftCorrectionKind.SEEK, expectedPositionMs, restoreSpeed = sharedSpeed)
    }
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
