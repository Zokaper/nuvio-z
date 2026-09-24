package com.nuvio.app.features.downloads

import com.nuvio.app.features.playback.PlaybackMode
import com.nuvio.app.features.player.PlayerSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where the Download Mode and Download Preferences live on disk: profile-scoped, one payload.
 *
 * Its own store, not a field on `PlayerSettingsStorage` - the same reasoning as
 * `SocialFeaturePreferencesStorage`: downloads are an application-level feature with their own
 * sync payload, and the player blob's monotonic-merge special case is wrong for preferences a
 * user may change in either direction.
 */
internal expect object DownloadPolicyStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}

/**
 * The stored and synced form. **Every field is nullable**: a blob written by a build that
 * predates a field decodes to null, and null means "the sender has not heard of this", never
 * "reset it". Values are enum names as strings so a newer build's value an older build does not
 * know decodes to the default instead of failing the whole payload.
 */
@Serializable
data class DownloadPolicySyncPayload(
    @SerialName("mode") val mode: String? = null,
    @SerialName("preferred_resolution") val preferredResolution: String? = null,
    @SerialName("size_level") val sizeLevel: String? = null,
    @SerialName("pick_rule") val pickRule: String? = null,
    @SerialName("range") val range: String? = null,
    @SerialName("resolution_fallback") val resolutionFallback: String? = null,
)

internal fun DownloadPolicySyncPayload.toPolicy(base: DownloadPolicy = DownloadPolicy()): DownloadPolicy =
    DownloadPolicy(
        mode = mode?.let { value -> DownloadMode.entries.firstOrNull { it.name == value } } ?: base.mode,
        preferredResolution = enumOr(preferredResolution, base.preferredResolution),
        sizeLevel = enumOr(sizeLevel, base.sizeLevel),
        pickRule = enumOr(pickRule, base.pickRule),
        range = enumOr(range, base.range),
        resolutionFallback = enumOr(resolutionFallback, base.resolutionFallback),
    )

internal fun DownloadPolicy.toSyncPayload(): DownloadPolicySyncPayload = DownloadPolicySyncPayload(
    mode = mode?.name,
    preferredResolution = preferredResolution.name,
    sizeLevel = sizeLevel.name,
    pickRule = pickRule.name,
    range = range.name,
    resolutionFallback = resolutionFallback.name,
)

private inline fun <reified E : Enum<E>> enumOr(value: String?, fallback: E): E =
    value?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: fallback

/**
 * The profile's Download Mode and Download Preferences (Phase 9).
 *
 * A first load with nothing stored migrates from the retired presets - the most recently used
 * preset's resolution, the nearest size level to its cap, its range and its size rule - so an
 * upgraded user's downloads keep behaving as they did. The mode stays **null** through that
 * migration: it is derived from Playback Mode until the user answers, and whether the user is
 * asked is the wizard revision's business, not this repository's.
 */
object DownloadPolicyRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _policy = MutableStateFlow(DownloadPolicy())
    val policy: StateFlow<DownloadPolicy> = _policy.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() = loadFromDisk()

    fun clearLocalState() {
        hasLoaded = false
        _policy.value = DownloadPolicy()
    }

    /** The mode that applies right now: the stored answer, or the one Playback Mode implies. */
    fun effectiveMode(): DownloadMode {
        ensureLoaded()
        PlayerSettingsRepository.ensureLoaded()
        return _policy.value.effectiveMode(PlayerSettingsRepository.uiState.value.playbackMode.forDownloads())
    }

    fun update(transform: (DownloadPolicy) -> DownloadPolicy) {
        ensureLoaded()
        val next = transform(_policy.value)
        if (next == _policy.value) return
        _policy.value = next
        persist()
    }

    fun setMode(mode: DownloadMode) = update { it.copy(mode = mode) }

    internal fun exportForSync(): DownloadPolicySyncPayload {
        ensureLoaded()
        return _policy.value.toSyncPayload()
    }

    /** A null remote field leaves the local value alone (see [DownloadPolicySyncPayload]). */
    internal fun applyFromSync(remote: DownloadPolicySyncPayload) {
        ensureLoaded()
        val next = remote.toPolicy(base = _policy.value)
        if (next == _policy.value) return
        _policy.value = next
        persist()
    }

    private fun loadFromDisk() {
        hasLoaded = true
        val stored = DownloadPolicyStorage.loadPayload()
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(DownloadPolicySyncPayload.serializer(), it) }.getOrNull() }
        if (stored != null) {
            _policy.value = stored.toPolicy()
            return
        }
        _policy.value = DownloadPolicyMigration.fromPresets(
            lastUsedPreset = runCatching {
                DownloadsRepository.ensureLoaded()
                DownloadsRepository.batches.value.firstOrNull()?.presetSnapshot
            }.getOrNull(),
        )
        persist()
    }

    private fun persist() {
        DownloadPolicyStorage.savePayload(
            json.encodeToString(DownloadPolicySyncPayload.serializer(), _policy.value.toSyncPayload()),
        )
    }
}

internal fun PlaybackMode.forDownloads(): PlaybackModeForDownloads = when (this) {
    PlaybackMode.CLASSIC -> PlaybackModeForDownloads.CLASSIC
    PlaybackMode.STREAMLINED -> PlaybackModeForDownloads.STREAMLINED
    PlaybackMode.INSTANT -> PlaybackModeForDownloads.INSTANT
}

/** Maps a retired preset onto the new preferences. The mode is never set here. */
object DownloadPolicyMigration {
    fun fromPresets(lastUsedPreset: DownloadPreset?): DownloadPolicy {
        val preset = lastUsedPreset ?: DownloadPreset.Balanced
        val height = preset.targetResolution.height
        val resolution = when {
            height >= 2160 -> DownloadResolutionPreference.P2160
            height >= 1080 -> DownloadResolutionPreference.P1080
            else -> DownloadResolutionPreference.P720
        }
        val levels = listOf(DownloadSizeLevel.SMALL, DownloadSizeLevel.MEDIUM, DownloadSizeLevel.LARGE, DownloadSizeLevel.HUGE)
        // Nearest level at the preset's resolution; a tie goes to the larger level, so nobody's
        // downloads get worse on upgrade.
        val sizeLevel = levels.minWith(
            compareBy<DownloadSizeLevel> {
                kotlin.math.abs(DownloadSizeLevels.gigabytesPerHour(it, resolution.height.coerceAtMost(2160))!! - preset.gigabytesPerHourLimit)
            }.thenByDescending { it.ordinal },
        )
        val range = when (preset.dynamicRangePolicy) {
            DynamicRangePolicy.ANY -> DownloadRange.SAME_AS_PLAYBACK
            DynamicRangePolicy.AVOID_HDR -> DownloadRange.AVOID_HDR
            DynamicRangePolicy.PREFER_HDR,
            DynamicRangePolicy.REQUIRE_HDR,
            DynamicRangePolicy.REQUIRE_DOLBY_VISION,
            -> DownloadRange.PREFER_HDR
        }
        val pickRule = when (preset.sizePreference) {
            SizePreference.LARGEST_UNDER_CAP -> DownloadPickRule.BEST_THAT_FITS
            SizePreference.MID_RANGE -> DownloadPickRule.BALANCED
            SizePreference.SMALLEST -> DownloadPickRule.SMALLEST_THAT_FITS
        }
        return DownloadPolicy(
            mode = null,
            preferredResolution = resolution,
            sizeLevel = sizeLevel,
            pickRule = pickRule,
            range = range,
        )
    }
}
