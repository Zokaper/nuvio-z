package com.nuvio.app.core.network

import com.nuvio.app.features.downloads.VideoResolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NetworkEstimateConfidence { PLATFORM_DEFAULT, CACHED, PASSIVE }

data class NetworkQualityUiState(
    val connectionType: NetworkConnectionType = NetworkConnectionType.UNKNOWN,
    val isMetered: Boolean = false,
    val networkId: String = "unknown",
    val providerId: String? = null,
    val estimatedMbps: Double = 5.0,
    val confidence: NetworkEstimateConfidence = NetworkEstimateConfidence.PLATFORM_DEFAULT,
)

/**
 * Session cache for Instant-mode quality decisions.
 *
 * Measurements are keyed by both the active network and the debrid/provider host. A generic
 * network sample is used only until a provider-specific sample exists, so slow hosts do not
 * teach the app that an otherwise fast Wi-Fi connection can sustain 4K from that provider.
 */
object NetworkQualityRepository {
    private data class EstimateKey(val networkId: String, val providerId: String?)
    private data class Estimate(val mbps: Double, val samples: Int)
    private data class PendingPlayback(val mbps: Double, val providerId: String?)

    private var pendingPlayback: PendingPlayback? = null
    private val estimates = mutableMapOf<EstimateKey, Estimate>()
    private val meteredAnswers = mutableMapOf<String, MeteredPlaybackChoice>()
    private val _uiState = MutableStateFlow(NetworkQualityUiState())
    val uiState: StateFlow<NetworkQualityUiState> = _uiState.asStateFlow()

    fun current(providerId: String? = null): NetworkQualityUiState {
        val platform = NetworkQualityPlatform.current()
        val normalizedProvider = providerId.normalizedProvider()
        val exact = estimates[EstimateKey(platform.networkId, normalizedProvider)]
        val generic = estimates[EstimateKey(platform.networkId, null)]
        val estimate = exact ?: generic
        return NetworkQualityUiState(
            connectionType = platform.connectionType,
            isMetered = platform.isMetered,
            networkId = platform.networkId,
            providerId = normalizedProvider,
            estimatedMbps = estimate?.mbps ?: defaultMbps(platform.connectionType),
            confidence = when {
                exact != null -> NetworkEstimateConfidence.PASSIVE
                generic != null -> NetworkEstimateConfidence.CACHED
                else -> NetworkEstimateConfidence.PLATFORM_DEFAULT
            },
        ).also { _uiState.value = it }
    }

    /** Records real transfer throughput; sub-second and tiny samples are deliberately ignored. */
    fun recordTransfer(bytes: Long, elapsedMs: Long, providerId: String? = null) {
        if (bytes < MIN_SAMPLE_BYTES || elapsedMs < MIN_SAMPLE_DURATION_MS) return
        val mbps = bytes.toDouble() * 8.0 / elapsedMs.toDouble() / 1_000.0
        if (!mbps.isFinite() || mbps <= 0.0) return
        val network = NetworkQualityPlatform.current()
        val key = EstimateKey(network.networkId, providerId.normalizedProvider())
        val old = estimates[key]
        val smoothed = if (old == null) mbps else old.mbps * 0.7 + mbps * 0.3
        estimates[key] = Estimate(smoothed.coerceIn(MIN_MBPS, MAX_MBPS), (old?.samples ?: 0) + 1)
        current(providerId)
    }

    /**
     * Arms an observation: this bitrate is about to be streamed.
     *
     * Nothing is stored yet. The claim only becomes evidence once the player has actually
     * held it for a while, which is what [confirmPlaybackBitrate] reports. Starting a 40 Mbps
     * remux proves nothing about the line if it stalls ten seconds in.
     */
    fun notePlaybackBitrate(mbps: Double, providerId: String? = null) {
        pendingPlayback = if (mbps.isFinite() && mbps > 0.0) {
            PendingPlayback(mbps, providerId.normalizedProvider())
        } else {
            null
        }
    }

    /** Discards an armed observation - the source changed, or playback never settled. */
    fun cancelPlaybackObservation() {
        pendingPlayback = null
    }

    /** The armed bitrate held without starvation. Promotes it to a real measurement. */
    fun confirmPlaybackBitrate() {
        val pending = pendingPlayback ?: return
        pendingPlayback = null
        recordSustainedBitrate(pending.mbps, pending.providerId)
    }

    /**
     * Records that a file of [mbps] streamed without sustained starvation.
     *
     * Separate from [recordTransfer] and deliberately **monotonic**: it may raise the stored
     * estimate, never lower it. A stream delivers at the file's own bitrate and no faster,
     * so this is a *lower bound* on the line - a 5 Mbps file playing perfectly says nothing
     * about whether 40 was possible. Blending it in the way real transfer samples are
     * blended would drag the estimate down towards whatever the user last watched, and
     * Instant would lose access to the top qualities the more it was used.
     */
    fun recordSustainedBitrate(mbps: Double, providerId: String? = null) {
        if (!mbps.isFinite() || mbps <= 0.0) return
        val network = NetworkQualityPlatform.current()
        val key = EstimateKey(network.networkId, providerId.normalizedProvider())
        val observed = mbps.coerceIn(MIN_MBPS, MAX_MBPS)
        val old = estimates[key]
        if (old != null && old.mbps >= observed) return
        estimates[key] = Estimate(observed, (old?.samples ?: 0) + 1)
        current(providerId)
    }

    /**
     * The resolution worth aiming for on a connection of this speed.
     *
     * Used where there is no candidate list to derive real options from - the details
     * screen's download button, which has to pick a preset before any source is known.
     * Playback itself never comes here; it compares against a source's measured cost.
     */
    fun resolutionForEstimate(providerId: String? = null): VideoResolution {
        val state = current(providerId)
        val estimate = state.estimatedMbps
        val resolved = when {
            estimate < 3.0 -> VideoResolution.SD
            estimate < 6.0 -> VideoResolution.HD_720
            estimate < 14.0 -> VideoResolution.FULL_HD_1080
            else -> VideoResolution.UHD_2160
        }
        // Erring high is right for streaming - a stream that over-reaches is caught by
        // downshift and costs nothing but a hiccup. It is not right for disk. Instant's
        // download button never asks, so an unmeasured platform guess must not silently
        // start a 4K download; a real measurement unlocks it.
        return if (
            state.confidence == NetworkEstimateConfidence.PLATFORM_DEFAULT &&
            resolved.height > VideoResolution.FULL_HD_1080.height
        ) {
            VideoResolution.FULL_HD_1080
        } else {
            resolved
        }
    }

    fun meteredChoiceForCurrentNetwork(): MeteredPlaybackChoice? =
        meteredAnswers[NetworkQualityPlatform.current().networkId]

    fun rememberMeteredChoice(choice: MeteredPlaybackChoice) {
        meteredAnswers[NetworkQualityPlatform.current().networkId] = choice
    }

    internal fun resetForTest() {
        estimates.clear()
        meteredAnswers.clear()
        pendingPlayback = null
        _uiState.value = NetworkQualityUiState()
    }

    /**
     * What to assume before anything has been measured.
     *
     * These are first-impression numbers, and the old conservative set made Instant pick as
     * if every home connection were 3 Mbps - which, once quality options are costed from
     * real file sizes rather than waved through for having no size, means never offering
     * more than a lean 720p. Erring high is the safer error now: [AutoDownshiftDetector]
     * catches an over-reach mid-playback, whereas an under-reach is invisible and permanent.
     */
    private fun defaultMbps(type: NetworkConnectionType): Double = when (type) {
        NetworkConnectionType.OFFLINE -> MIN_MBPS
        NetworkConnectionType.CELLULAR -> 8.0
        NetworkConnectionType.WIFI -> 25.0
        NetworkConnectionType.ETHERNET -> 50.0
        NetworkConnectionType.UNKNOWN -> 10.0
    }

    private fun String?.normalizedProvider(): String? =
        this?.trim()?.lowercase()?.takeIf(String::isNotEmpty)

    private const val MIN_SAMPLE_BYTES = 256L * 1024L
    private const val MIN_SAMPLE_DURATION_MS = 750L
    private const val MIN_MBPS = 0.25
    private const val MAX_MBPS = 1_000.0
}

enum class MeteredPlaybackChoice {
    CAPPED,
    FULL_QUALITY,
}
