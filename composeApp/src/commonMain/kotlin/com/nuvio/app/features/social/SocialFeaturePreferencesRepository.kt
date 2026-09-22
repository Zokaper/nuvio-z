package com.nuvio.app.features.social

import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.setup.SocialIdentityProbe
import com.nuvio.app.features.setup.resolveSocialFeaturesEnabled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The stored form.
 *
 * ⚠ **`socialFeaturesEnabled` is nullable, and that is the whole design.** Three states have to
 * be distinguishable and only two of them are booleans: never answered, explicitly on, and
 * explicitly off. Collapsing the first into the third is what would hide an established social
 * user's friends and activity behind a new preference defaulting false - see
 * [resolveSocialFeaturesEnabled], which exists for that case.
 */
@Serializable
private data class StoredSocialFeaturePreferences(
    @SerialName("social_features_enabled") val socialFeaturesEnabled: Boolean? = null,
)

data class SocialFeaturePreferencesUiState(
    /** The user's explicit answer, or null when they have never given one. */
    val storedPreference: Boolean? = null,

    /**
     * What the app should behave as right now.
     *
     * The resolution in [resolveSocialFeaturesEnabled]: the stored answer if there is one, else
     * whatever this profile's social history says.
     */
    val enabled: Boolean = false,

    /** Whether a social identity is known for this profile, from the local cache or a probe. */
    val hasKnownIdentity: Boolean = false,

    /** What a backend probe was able to say, if one has run. */
    val identityProbe: SocialIdentityProbe = SocialIdentityProbe.NotRun,
)

/**
 * Whether the social product layer exists for this profile.
 *
 * ⚠ **Its own repository, deliberately not a field on `PlayerSettingsRepository`.** See the
 * header of [SocialFeaturePreferencesStorage] for why. It joins `ProfileSettingsSync` the same
 * way every other feature repository does - an observed signature flow, a payload on the blob,
 * and an [applyFromSync] call - so it is profile-scoped and synced without borrowing the player
 * blob's monotonic-merge special case.
 *
 * **Readable before any social runtime starts.** [ensureLoaded] is a synchronous disk read and
 * the cached-identity check is a second one, so the gate has an answer before the first frame
 * and cannot flicker on a machine that has used social before. Only the cache-cold case needs
 * the network, and that is [refreshIdentityProbe].
 */
object SocialFeaturePreferencesRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(SocialFeaturePreferencesUiState())
    val uiState: StateFlow<SocialFeaturePreferencesUiState> = _uiState.asStateFlow()

    private var hasLoaded = false

    /** The profile the probe last ran for, so a switch re-probes and a recomposition does not. */
    private var probedProfileId: String? = null

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        probedProfileId = null
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        probedProfileId = null
        _uiState.value = SocialFeaturePreferencesUiState()
    }

    /**
     * Record an explicit answer.
     *
     * ⚠ **The only writer of a stored preference, and there are exactly three callers.** A value
     * that was *inferred* - from the local cache, from a probe, or from a skipped wizard - must
     * never arrive here, or the inference becomes indistinguishable from a decision and the
     * migration rule can never run again. The three legitimate moments are:
     *
     * 1. the user moves the switch on the wizard's Social step;
     * 2. the user leaves that step forward, which commits whatever the switch shows - seeing the
     *    question and walking past it is an answer, and it is what stops the derived path
     *    running forever;
     * 3. the user moves the master control in Settings.
     *
     * Turning social **off** has a lifecycle consequence beyond this write - see
     * `shutdownSocialLayer`, which the callers run first so the layer is taken down in order
     * before its surfaces disappear.
     */
    fun setEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_uiState.value.storedPreference == enabled) return
        _uiState.value = _uiState.value.copy(storedPreference = enabled, enabled = enabled)
        persist()
    }

    /**
     * Apply a value that arrived from another device.
     *
     * ⚠ **A null remote must not clear a local answer.** The blob is authoritative for what it
     * knows and never for what it has not caught up with - the rule `syncKeysToClear` encodes,
     * and the reason a pull once wiped every playback setting written after the remote blob was
     * last saved. A remote written by a build that predates this preference carries null for it.
     *
     * Note what is deliberately absent: this does **not** take the larger of the two values the
     * way the setup revision does. `mergeMonotonicSyncInt` is right for a number that may only
     * rise; a preference the user may legitimately switch off has to be able to fall, so an
     * explicit remote false overwrites a local true.
     */
    internal fun applyFromSync(socialFeaturesEnabled: Boolean?) {
        ensureLoaded()
        if (socialFeaturesEnabled == null) return
        if (_uiState.value.storedPreference == socialFeaturesEnabled) return
        _uiState.value = _uiState.value.copy(
            storedPreference = socialFeaturesEnabled,
            enabled = socialFeaturesEnabled,
        )
        persist()
    }

    /** What the sync blob should carry. Null when this profile has never answered. */
    internal fun exportStoredPreference(): Boolean? {
        ensureLoaded()
        return _uiState.value.storedPreference
    }

    /**
     * Records that this profile now has a social identity because the user just saved a handle.
     *
     * The wizard needs this: it runs before the social layer is active, so the saved profile never
     * reaches `SocialRepository.uiState.me`, and without it the handle step would stay in the
     * plan after a successful save.
     */
    fun recordKnownIdentity() {
        ensureLoaded()
        _uiState.value = _uiState.value.copy(hasKnownIdentity = true)
    }

    /**
     * Ask the backend whether this profile already has a social identity.
     *
     * Only worth doing when the local cache could not answer and no explicit preference exists -
     * a second install, a cleared cache, a fresh data root. Runs at most once per profile per
     * launch, and its result is never persisted: an [SocialIdentityProbe.Indeterminate] answer
     * that got written down would look forever afterwards like a user who chose "off".
     */
    fun refreshIdentityProbe(profileId: String?) {
        ensureLoaded()
        if (profileId == null) return
        if (probedProfileId == profileId) return
        val state = _uiState.value
        if (state.storedPreference != null || state.hasKnownIdentity) return
        probedProfileId = profileId
        scope.launch {
            val probe = SocialRepository.probeExistingIdentity(profileId)
            val current = _uiState.value
            // Re-read rather than closing over `state`: the user may have answered the wizard's
            // social question while this was in flight, and an explicit answer outranks a probe.
            _uiState.value = current.copy(
                identityProbe = probe,
                hasKnownIdentity = current.hasKnownIdentity || probe == SocialIdentityProbe.Present,
                enabled = resolveSocialFeaturesEnabled(
                    stored = current.storedPreference,
                    hasCachedIdentity = current.hasKnownIdentity,
                    probe = probe,
                ),
            )
        }
    }

    private fun loadFromDisk() {
        hasLoaded = true
        val stored = SocialFeaturePreferencesStorage.loadPayload()
            ?.let { runCatching { json.decodeFromString<StoredSocialFeaturePreferences>(it) }.getOrNull() }
            ?.socialFeaturesEnabled
        val cached = hasCachedSocialIdentity()
        _uiState.value = SocialFeaturePreferencesUiState(
            storedPreference = stored,
            enabled = resolveSocialFeaturesEnabled(
                stored = stored,
                hasCachedIdentity = cached,
                probe = SocialIdentityProbe.NotRun,
            ),
            hasKnownIdentity = cached,
            identityProbe = SocialIdentityProbe.NotRun,
        )
    }

    /**
     * Whether this machine already holds a social payload naming this profile.
     *
     * Synchronous and local, which is the point: for anyone who has used social on this machine
     * the migration answer is available before the first frame, so the common upgrade case never
     * flickers the Social tab away and back.
     */
    private fun hasCachedSocialIdentity(): Boolean {
        val profileId = ProfileRepository.state.value.activeProfile?.id?.takeIf(String::isNotBlank)
            ?: return false
        val cached = SocialStorage.loadPayload(profileId) ?: return false
        return runCatching { json.decodeFromString<SocialStatePayload>(cached) }.getOrNull()?.me != null
    }

    private fun persist() {
        SocialFeaturePreferencesStorage.savePayload(
            json.encodeToString(
                StoredSocialFeaturePreferences(
                    socialFeaturesEnabled = _uiState.value.storedPreference,
                ),
            ),
        )
    }
}
