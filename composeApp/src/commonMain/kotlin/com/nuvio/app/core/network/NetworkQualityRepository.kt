package com.nuvio.app.core.network

import com.nuvio.app.features.downloads.DownloadsClock
import com.nuvio.app.features.downloads.VideoResolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * How much the current estimate is worth.
 *
 * [PLATFORM_DEFAULT] is the only one that is not a measurement, and the distinction is
 * load-bearing rather than informational: it is what stops `resolutionForEstimate` starting a
 * 4K download on a guess, and what keeps the quality sheet from printing a preset as if the
 * connection had been tested.
 */
enum class NetworkEstimateConfidence {
    PLATFORM_DEFAULT,
    CACHED,
    PASSIVE,
    PROBED,
}

data class NetworkQualityUiState(
    val connectionType: NetworkConnectionType = NetworkConnectionType.UNKNOWN,
    val isMetered: Boolean = false,
    val networkId: String = "unknown",
    val providerId: String? = null,
    val estimatedMbps: Double = 5.0,
    val confidence: NetworkEstimateConfidence = NetworkEstimateConfidence.PLATFORM_DEFAULT,
) {
    /** True when [estimatedMbps] was actually observed rather than assumed from the link type. */
    val isMeasured: Boolean
        get() = confidence != NetworkEstimateConfidence.PLATFORM_DEFAULT
}

/**
 * What the connection has actually been observed to carry, per network and per provider.
 *
 * Measurements are keyed by both the active network and the debrid/provider host. A generic
 * network sample is used only until a provider-specific sample exists, so slow hosts do not
 * teach the app that an otherwise fast Wi-Fi connection can sustain 4K from that provider.
 *
 * Three things feed it, and they are not interchangeable:
 *
 * - [recordTransfer] and [recordMeasuredThroughput] are **real throughput**. They may move the
 *   estimate in either direction, which is the only way a wrong platform default is ever
 *   corrected downwards.
 * - [recordSustainedBitrate] is a **lower bound** and is monotonic. See its own note.
 * - [recordProbeResult] is a **direct measurement** taken before playback, which is what makes
 *   the very first play on a network something other than a guess.
 *
 * Estimates outlive the process ([NetworkQualityStorage]) and are aged out, because a
 * measurement taken on a network the user has since left is worth less than nothing.
 */
object NetworkQualityRepository {
    private data class EstimateKey(val networkId: String, val providerId: String?)

    private enum class EstimateSource { TRANSFER, PLAYBACK, PROBE }

    private data class Estimate(
        val mbps: Double,
        val samples: Int,
        val atEpochMs: Long,
        val source: EstimateSource,
        /** False for anything restored from storage, which is what separates CACHED from the rest. */
        val isThisSession: Boolean,
    )

    /** What is playing right now, as far as the estimator knows. See [armedPlayback]. */
    data class ArmedPlayback(val mbps: Double, val providerId: String?)

    @Serializable
    private data class StoredEstimate(
        val networkId: String,
        val providerId: String? = null,
        val mbps: Double,
        val samples: Int = 1,
        val atEpochMs: Long,
        val source: String = "TRANSFER",
    )

    private val json = Json { ignoreUnknownKeys = true }

    private var pendingPlayback: ArmedPlayback? = null
    private var armed: ArmedPlayback? = null
    private val estimates = mutableMapOf<EstimateKey, Estimate>()
    private val meteredAnswers = mutableMapOf<String, MeteredPlaybackChoice>()
    private val _uiState = MutableStateFlow(NetworkQualityUiState())
    val uiState: StateFlow<NetworkQualityUiState> = _uiState.asStateFlow()

    private var restored = false
    private var lastPersistAtMs = 0L

    /**
     * Epoch clock, replaceable only by tests.
     *
     * Aging and persistence are the two behaviours here that cannot be exercised at all without
     * moving time, and a week-long expiry verified by reading the code is not verified.
     */
    internal var nowProvider: () -> Long = DownloadsClock::nowEpochMs

    /** The persistence seam, replaceable only by tests. See [nowProvider]. */
    internal var loadJson: () -> String? = { NetworkQualityStorage.loadEstimatesJson() }
    internal var saveJson: (String) -> Unit = { NetworkQualityStorage.saveEstimatesJson(it) }

    /**
     * [peek], then published to [uiState].
     *
     * Every non-composable caller wants this one. A composable must use [peek] instead: this
     * writes to a `StateFlow` that composition is collecting, and a read that mutates the thing
     * it is being read from is a recomposition loop waiting for a second provider to exist.
     */
    fun current(providerId: String? = null): NetworkQualityUiState =
        peek(providerId).also { _uiState.value = it }

    /**
     * What the estimate is right now, without publishing anything.
     *
     * [uiState] carries whichever provider asked last, which is not necessarily the provider
     * the caller cares about - the quality sheet scopes its figure to the host that would serve
     * the stream, and reads that flow only as a signal that *something* changed.
     */
    fun peek(providerId: String? = null): NetworkQualityUiState {
        restoreIfNeeded()
        val platform = NetworkQualityPlatform.current()
        val normalizedProvider = providerId.normalizedProvider()
        val exact = liveEstimate(EstimateKey(platform.networkId, normalizedProvider))
        val generic = liveEstimate(EstimateKey(platform.networkId, null))
        val estimate = exact ?: generic
        return NetworkQualityUiState(
            connectionType = platform.connectionType,
            isMetered = platform.isMetered,
            networkId = platform.networkId,
            providerId = normalizedProvider,
            estimatedMbps = estimate?.mbps ?: defaultMbps(platform.connectionType),
            confidence = when {
                estimate == null -> NetworkEstimateConfidence.PLATFORM_DEFAULT
                !estimate.isThisSession -> NetworkEstimateConfidence.CACHED
                estimate.source == EstimateSource.PROBE -> NetworkEstimateConfidence.PROBED
                else -> NetworkEstimateConfidence.PASSIVE
            },
        )
    }

    /**
     * How long ago the estimate stored under **exactly this key** was taken, or null if there is
     * none. `null` [providerId] asks about the line-wide estimate.
     *
     * The active probe asks this before spending anything: a measurement from four minutes ago
     * against the same host is worth more than a fresh 4 MB one, and re-probing every time the
     * quality sheet opens would charge the user for the same answer repeatedly.
     *
     * ⚠ **Deliberately not the exact-then-generic fallback [peek] uses**, and the difference is
     * the point. Answering "how old is the number you would *show*" meant a two-minute-old
     * line-wide estimate reported a brand-new debrid host as freshly measured, so that host was
     * never probed and kept borrowing a figure measured somewhere else - which defeats the
     * per-provider keying this repository is built around. A caller that wants the displayed
     * figure's age wants [peek] and its own arithmetic.
     */
    fun estimateAgeMs(providerId: String? = null): Long? {
        restoreIfNeeded()
        val networkId = NetworkQualityPlatform.current().networkId
        val estimate = liveEstimate(EstimateKey(networkId, providerId.normalizedProvider()))
            ?: return null
        return (nowProvider() - estimate.atEpochMs).coerceAtLeast(0L)
    }

    /** Records real transfer throughput; sub-second and tiny samples are deliberately ignored. */
    fun recordTransfer(bytes: Long, elapsedMs: Long, providerId: String? = null) {
        if (bytes < MIN_SAMPLE_BYTES || elapsedMs < MIN_SAMPLE_DURATION_MS) return
        val mbps = bytes.toDouble() * 8.0 / elapsedMs.toDouble() / 1_000.0
        blendMeasurement(mbps, providerId, EstimateSource.TRANSFER)
    }

    /**
     * Records throughput measured from the player's own buffer - see [NetworkThroughputMeter].
     *
     * Unlike [recordSustainedBitrate] this is a real rate rather than a lower bound, so it is
     * blended like any other measurement and **may lower the estimate**. That is the point: it
     * is the only signal on the playback path that can contradict an over-generous platform
     * default, which is otherwise invisible and permanent.
     */
    fun recordMeasuredThroughput(mbps: Double, providerId: String? = null) {
        blendMeasurement(mbps, providerId, EstimateSource.PLAYBACK)
    }

    /**
     * Records the result of an active probe against a real host.
     *
     * A probe is a direct reading taken seconds ago, so it **replaces** anything restored from
     * a previous session outright rather than being averaged into it - blending a fresh
     * measurement of the network the user is on now with one from a network they have left is
     * how a stale estimate survives being disproved. Two probes within one session average
     * evenly, because neither is better evidence than the other.
     */
    fun recordProbeResult(mbps: Double, providerId: String? = null) {
        if (!mbps.isFinite() || mbps <= 0.0) return
        restoreIfNeeded()
        val key = EstimateKey(NetworkQualityPlatform.current().networkId, providerId.normalizedProvider())
        val old = liveEstimate(key)
        // A probe deliberately saturates the line, so unlike the playback signals it can observe
        // more than it was asked for and may legitimately move the estimate in either direction.
        // What made it untrustworthy was the arithmetic, not the direction: it recorded the mean
        // over a short transfer, which is mostly TCP slow start. That is fixed at the source in
        // `ThroughputWindow`, so the blend below stands.
        val blended = if (old == null || !old.isThisSession) {
            mbps
        } else {
            old.mbps * 0.5 + mbps * 0.5
        }
        store(key, blended, (old?.samples ?: 0) + 1, EstimateSource.PROBE)
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
            ArmedPlayback(mbps, providerId.normalizedProvider())
        } else {
            null
        }
        armed = pendingPlayback
    }

    /**
     * The file the player is on, for [NetworkThroughputMeter] to convert buffer growth with.
     *
     * Deliberately **not** cleared by [confirmPlaybackBitrate]. That call ends the lower-bound
     * observation, which is a different thing with a different deadline; the meter is measuring
     * the same file and has no reason to stop because a minute happened to elapse.
     */
    val armedPlayback: ArmedPlayback?
        get() = armed

    /** Discards an armed observation - the source changed, or playback never settled. */
    fun cancelPlaybackObservation() {
        pendingPlayback = null
        armed = null
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
     * Separate from the throughput sinks and deliberately **monotonic**: it may raise the
     * stored estimate, never lower it. A stream delivers at the file's own bitrate and no
     * faster, so this is a *lower bound* on the line - a 5 Mbps file playing perfectly says
     * nothing about whether 40 was possible. Blending it in the way real throughput samples
     * are blended would drag the estimate down towards whatever the user last watched, and
     * Instant would lose access to the top qualities the more it was used.
     *
     * This is the fallback for sources whose bitrate the app cannot compute;
     * [recordMeasuredThroughput] is the real measurement and is preferred wherever a file
     * bitrate is known.
     */
    fun recordSustainedBitrate(mbps: Double, providerId: String? = null) {
        if (!mbps.isFinite() || mbps <= 0.0) return
        restoreIfNeeded()
        val key = EstimateKey(NetworkQualityPlatform.current().networkId, providerId.normalizedProvider())
        val observed = mbps.coerceIn(MIN_MBPS, MAX_MBPS)
        val old = liveEstimate(key)
        if (old != null && old.mbps >= observed) return
        store(key, observed, (old?.samples ?: 0) + 1, EstimateSource.PLAYBACK)
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

    /**
     * [restoredAlready] false lets a test exercise the cold-start restore path, which is the
     * whole reason estimates are persisted - without it the first play of every session is a
     * platform guess no matter how much was measured yesterday.
     */
    internal fun resetForTest(restoredAlready: Boolean = true) {
        nowProvider = DownloadsClock::nowEpochMs
        loadJson = { NetworkQualityStorage.loadEstimatesJson() }
        saveJson = { NetworkQualityStorage.saveEstimatesJson(it) }
        estimates.clear()
        meteredAnswers.clear()
        pendingPlayback = null
        armed = null
        restored = restoredAlready
        lastPersistAtMs = 0L
        _uiState.value = NetworkQualityUiState()
    }

    private fun blendMeasurement(mbps: Double, providerId: String?, source: EstimateSource) {
        if (!mbps.isFinite() || mbps <= 0.0) return
        restoreIfNeeded()
        val key = EstimateKey(NetworkQualityPlatform.current().networkId, providerId.normalizedProvider())
        val old = liveEstimate(key)
        val smoothed = if (old == null) mbps else old.mbps * 0.7 + mbps * 0.3
        store(key, smoothed, (old?.samples ?: 0) + 1, source)
        current(providerId)
    }

    private fun store(key: EstimateKey, mbps: Double, samples: Int, source: EstimateSource) {
        val isNewKey = key !in estimates
        estimates[key] = Estimate(
            mbps = mbps.coerceIn(MIN_MBPS, MAX_MBPS),
            samples = samples,
            atEpochMs = nowProvider(),
            source = source,
            isThisSession = true,
        )
        persist(force = isNewKey)
    }

    /** An estimate old enough to describe a network the user has probably left is not one. */
    private fun liveEstimate(key: EstimateKey): Estimate? = estimates[key]?.takeIf {
        nowProvider() - it.atEpochMs < ESTIMATE_MAX_AGE_MS
    }

    private fun restoreIfNeeded() {
        if (restored) return
        restored = true
        val raw = runCatching { loadJson() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return
        val stored = runCatching {
            json.decodeFromString<List<StoredEstimate>>(raw)
        }.getOrNull().orEmpty()
        val now = nowProvider()
        stored.forEach { entry ->
            if (now - entry.atEpochMs >= ESTIMATE_MAX_AGE_MS) return@forEach
            if (!entry.mbps.isFinite() || entry.mbps <= 0.0) return@forEach
            estimates[EstimateKey(entry.networkId, entry.providerId)] = Estimate(
                mbps = entry.mbps.coerceIn(MIN_MBPS, MAX_MBPS),
                samples = entry.samples.coerceAtLeast(1),
                atEpochMs = entry.atEpochMs,
                source = runCatching { EstimateSource.valueOf(entry.source) }
                    .getOrDefault(EstimateSource.TRANSFER),
                // Restored, so CACHED however it was originally measured. A number from a
                // previous session is still worth far more than a platform guess, but it is
                // not evidence about the network in front of the user right now.
                isThisSession = false,
            )
        }
    }

    /**
     * [force] is for a key seen for the first time, which is the write that matters: the rest
     * are refinements of a number already on disk, and `recordTransfer` fires on every download
     * progress callback.
     */
    private fun persist(force: Boolean) {
        val now = nowProvider()
        if (!force && now - lastPersistAtMs < PERSIST_MIN_INTERVAL_MS) return
        lastPersistAtMs = now
        val payload = estimates.entries
            .filter { now - it.value.atEpochMs < ESTIMATE_MAX_AGE_MS }
            .sortedByDescending { it.value.atEpochMs }
            .take(MAX_STORED_ESTIMATES)
            .map { (key, estimate) ->
                StoredEstimate(
                    networkId = key.networkId,
                    providerId = key.providerId,
                    mbps = estimate.mbps,
                    samples = estimate.samples,
                    atEpochMs = estimate.atEpochMs,
                    source = estimate.source.name,
                )
            }
        runCatching { saveJson(json.encodeToString(payload)) }
    }

    private fun String?.normalizedProvider(): String? =
        this?.trim()?.lowercase()?.takeIf(String::isNotEmpty)

    /**
     * What to assume before anything has been measured.
     *
     * These are first-impression numbers and nothing more. They are never shown to the user as
     * a connection speed - `confidence` stays [NetworkEstimateConfidence.PLATFORM_DEFAULT] and
     * the quality sheet says it is still checking rather than quoting one of these - because a
     * preset printed as "your connection" is a claim the app has not earned.
     *
     * Erring high is the safer error for the ordering they still do: `AutoDownshiftDetector`
     * catches an over-reach mid-playback, whereas an under-reach is invisible. One measurement
     * of any kind replaces them.
     */
    private fun defaultMbps(type: NetworkConnectionType): Double = when (type) {
        NetworkConnectionType.OFFLINE -> MIN_MBPS
        NetworkConnectionType.CELLULAR -> 10.0
        NetworkConnectionType.WIFI -> 50.0
        NetworkConnectionType.ETHERNET -> 100.0
        NetworkConnectionType.UNKNOWN -> 15.0
    }

    private const val MIN_SAMPLE_BYTES = 256L * 1024L
    private const val MIN_SAMPLE_DURATION_MS = 750L
    private const val MIN_MBPS = 0.25
    private const val MAX_MBPS = 1_000.0

    /** A week. Past that the network behind the id has probably changed under the same name. */
    private const val ESTIMATE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
    private const val MAX_STORED_ESTIMATES = 32
    private const val PERSIST_MIN_INTERVAL_MS = 5_000L
}

enum class MeteredPlaybackChoice {
    CAPPED,
    FULL_QUALITY,
}
