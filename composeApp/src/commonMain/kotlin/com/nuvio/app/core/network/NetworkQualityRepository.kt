package com.nuvio.app.core.network

import com.nuvio.app.features.playback.PlaybackQualityTier
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

    fun resolveTier(tiers: List<PlaybackQualityTier>, providerId: String? = null): PlaybackQualityTier {
        val available = tiers.ifEmpty { PlaybackQualityTier.BuiltIns }
            .sortedBy(PlaybackQualityTier::megabitsPerSecond)
        val estimate = current(providerId).estimatedMbps
        return available.lastOrNull { it.megabitsPerSecond <= estimate }
            ?: available.first()
    }

    fun meteredChoiceForCurrentNetwork(): MeteredPlaybackChoice? =
        meteredAnswers[NetworkQualityPlatform.current().networkId]

    fun rememberMeteredChoice(choice: MeteredPlaybackChoice) {
        meteredAnswers[NetworkQualityPlatform.current().networkId] = choice
    }

    internal fun resetForTest() {
        estimates.clear()
        meteredAnswers.clear()
        _uiState.value = NetworkQualityUiState()
    }

    private fun defaultMbps(type: NetworkConnectionType): Double = when (type) {
        NetworkConnectionType.OFFLINE -> MIN_MBPS
        NetworkConnectionType.CELLULAR -> 5.0
        NetworkConnectionType.WIFI -> 12.0
        NetworkConnectionType.ETHERNET -> 25.0
        NetworkConnectionType.UNKNOWN -> 5.0
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
