package com.nuvio.app.core.sync

import co.touchlab.kermit.Logger
import com.nuvio.app.isDesktop
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.collection.CollectionMobileSettingsRepository
import com.nuvio.app.features.collection.CollectionMobileSettingsStorage
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DebridSettingsStorage
import com.nuvio.app.features.details.MetaScreenSettingsStorage
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.mdblist.MdbListMetadataService
import com.nuvio.app.features.mdblist.MdbListSettingsStorage
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsRepository
import com.nuvio.app.features.social.SocialFeaturePreferencesRepository
import com.nuvio.app.features.downloads.DownloadPolicyRepository
import com.nuvio.app.features.downloads.DownloadPolicySyncPayload
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.core.ui.CardDepthStyleRepository
import com.nuvio.app.core.ui.CardDepthStyleStorage
import com.nuvio.app.core.ui.PosterCardStyleRepository
import com.nuvio.app.core.poster.CustomPosterUrlRepository
import com.nuvio.app.core.poster.CustomPosterUrlStorage
import com.nuvio.app.core.ui.PosterCardStyleStorage
import com.nuvio.app.features.settings.ThemeSettingsStorage
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.features.streams.StreamBadgeSettingsRepository
import com.nuvio.app.features.streams.StreamBadgeSettingsStorage
import com.nuvio.app.features.tmdb.TmdbSettingsStorage
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.trakt.TraktCommentsStorage
import com.nuvio.app.features.trakt.TraktCommentsSettings
import com.nuvio.app.features.trakt.TraktSettingsStorage
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesStorage
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private const val PUSH_DEBOUNCE_MS = 500L

object ProfileSettingsSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("ProfileSettingsSync")
    private val syncMutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var isApplyingRemoteBlob: Boolean = false

    @Volatile
    private var isServerSyncInFlight: Boolean = false

    @Volatile
    private var skipNextPushSignature: String? = null

    @Volatile
    private var observeJob: Job? = null

    private val startLock = SynchronizedObject()

    private val profileSettingsPlatform: String
        get() = if (isDesktop) DESKTOP_SYNC_PLATFORM else MOBILE_SYNC_PLATFORM

    /**
     * Idempotent, and safe from any thread. Both callers - `AppGate` and
     * `warmProfileBoundRepositories` - run on `Dispatchers.Default`, so the check and the start
     * happen under one lock; two observers would push every change twice.
     */
    fun startObserving() = synchronized(startLock) {
        if (observeJob?.isActive == true) return@synchronized
        ensureRepositoriesLoaded()
        ProviderCredentialSync.startObserving()
        observeLocalChangesAndPush()
    }

    fun clearAccountState() {
        observeJob?.cancel()
        observeJob = null
        skipNextPushSignature = null
        ProviderCredentialSync.clearAccountState()
    }

    fun onProfileChanged() {
        if (observeJob?.isActive != true) return
        skipNextPushSignature = currentObservedStateSignature()
        ProviderCredentialSync.onProfileChanged()
    }

    suspend fun pull(profileId: Int): Boolean {
        ensureRepositoriesLoaded()
        return syncMutex.withLock {
            if (ProfileRepository.activeProfileId != profileId) {
                log.d { "pull(profileId=$profileId) — skipped because profile is no longer active" }
                return@withLock false
            }
            isServerSyncInFlight = true
            try {
                val localBlob = exportSettingsBlob()
                if (ProfileRepository.activeProfileId != profileId) return@withLock false
                val localSignature = buildSignature(localBlob)

                val params = buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_platform", profileSettingsPlatform)
                }
                val result = SupabaseProvider.client.postgrest.rpc("sync_pull_profile_settings_blob", params)
                if (ProfileRepository.activeProfileId != profileId) return@withLock false
                val response = result.decodeList<SettingsBlobResponse>().firstOrNull()
                val remoteJson = response?.settingsJson

                if (remoteJson == null) {
                    log.i { "pull(profileId=$profileId) — no remote settings blob found" }
                    return@withLock false
                }

                isApplyingRemoteBlob = true
                try {
                    val remoteBlob = runCatching {
                        json.decodeFromJsonElement(MobileProfileSettingsBlob.serializer(), remoteJson)
                    }.getOrElse { error ->
                        log.e(error) { "pull(profileId=$profileId) — failed to decode remote settings blob" }
                        return@withLock false
                    }
                    val remoteSignature = buildSignature(remoteBlob)
                    if (remoteSignature == localSignature) {
                        log.d { "pull(profileId=$profileId) — remote matches local" }
                        return@withLock false
                    }

                    if (ProfileRepository.activeProfileId != profileId) return@withLock false
                    applyRemoteBlob(remoteBlob)
                    skipNextPushSignature = currentObservedStateSignature()
                } finally {
                    isApplyingRemoteBlob = false
                }

                log.i { "pull(profileId=$profileId) — applied remote settings blob" }
                true
            } catch (error: Exception) {
                log.e(error) { "pull(profileId=$profileId) — FAILED" }
                false
            } finally {
                isServerSyncInFlight = false
            }
        }
    }

    suspend fun pushCurrentProfileToRemote(): Boolean {
        ensureRepositoriesLoaded()
        return syncMutex.withLock {
            runCatching {
                val profileId = ProfileRepository.activeProfileId
                val blob = exportSettingsBlob()
                if (ProfileRepository.activeProfileId != profileId) return@runCatching false
                pushToRemoteLocked(profileId, blob)
                true
            }.onFailure { error ->
                log.e(error) { "pushCurrentProfileToRemote() — FAILED" }
            }.getOrDefault(false)
        }
    }

    // --- the cross-family import's entry points (setup + settings pass) -----------------------
    // The import itself is `CrossFamilySettingsImport`; these are the only things it needs from
    // here, so the blob's private shape stays private to this file.

    /** This install's platform family - `"mobile"` or `"desktop"`. */
    internal val ownFamilyPlatform: String get() = profileSettingsPlatform

    /**
     * One family's blob for [profileId], read-only: its JSON, null when that family has none, or a
     * failure when it could not be read. "None" and "could not ask" must stay distinct - only the
     * first may conclude that there is nothing to import.
     */
    internal suspend fun fetchSettingsBlobJson(profileId: Int, platform: String): Result<JsonObject?> = runCatching {
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_platform", platform)
        }
        SupabaseProvider.client.postgrest.rpc("sync_pull_profile_settings_blob", params)
            .decodeList<SettingsBlobResponse>()
            .firstOrNull()
            ?.settingsJson
    }

    /** This profile's current settings, as the JSON a push would send. */
    internal fun exportSettingsBlobJson(): JsonObject =
        json.encodeToJsonElement(MobileProfileSettingsBlob.serializer(), exportSettingsBlob()) as JsonObject

    /**
     * Applies a blob the cross-family import assembled, through exactly the path a pull uses. False
     * when [profileId] is no longer active or the blob does not decode - nothing is applied then.
     */
    internal suspend fun applyImportedSettingsBlobJson(profileId: Int, blobJson: JsonObject): Boolean {
        ensureRepositoriesLoaded()
        return syncMutex.withLock {
            if (ProfileRepository.activeProfileId != profileId) return@withLock false
            val blob = runCatching {
                json.decodeFromJsonElement(MobileProfileSettingsBlob.serializer(), blobJson)
            }.getOrElse { error ->
                log.e(error) { "applyImportedSettingsBlobJson(profileId=$profileId) - failed to decode" }
                return@withLock false
            }
            isApplyingRemoteBlob = true
            try {
                applyRemoteBlob(blob)
                skipNextPushSignature = currentObservedStateSignature()
            } finally {
                isApplyingRemoteBlob = false
            }
            true
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeLocalChangesAndPush() {
        val signatureFlows: List<Flow<Any?>> = observedSyncStates().map { it.state }

        observeJob = scope.launch {
            combine(signatureFlows) { currentObservedStateSignature() }
                .distinctUntilChanged()
                .drop(1)
                .debounce(PUSH_DEBOUNCE_MS)
                .collect { signature ->
                    val authState = AuthRepository.state.value
                    if (authState !is AuthState.Authenticated || authState.isAnonymous) return@collect
                    if (isApplyingRemoteBlob || isServerSyncInFlight) return@collect
                    if (signature != currentObservedStateSignature()) return@collect
                    if (signature == skipNextPushSignature) {
                        skipNextPushSignature = null
                        return@collect
                    }
                    pushCurrentProfileToRemote()
                }
        }
    }

    private suspend fun pushToRemoteLocked(profileId: Int, blob: MobileProfileSettingsBlob) {
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_platform", profileSettingsPlatform)
            put("p_settings_json", json.encodeToJsonElement(MobileProfileSettingsBlob.serializer(), blob))
            putSyncOriginClientId()
        }
        SupabaseProvider.client.postgrest.rpc("sync_push_profile_settings_blob", params)
        log.d { "pushToRemoteLocked(profileId=$profileId) — success" }
    }

    private fun exportSettingsBlob(): MobileProfileSettingsBlob {
        ensureRepositoriesLoaded()
        return MobileProfileSettingsBlob(
            features = MobileProfileSettingsFeatures(
                themeSettings = ThemeSettingsStorage.exportToSyncPayload(),
                posterCardStyleSettingsPayload = PosterCardStyleStorage.loadPayload().orEmpty().trim(),
                customPosterUrlPattern = CustomPosterUrlStorage.loadPattern().orEmpty().trim(),
                customPosterEnabledScreens = CustomPosterUrlStorage.loadEnabledScreens()
                    ?.joinToString(",").orEmpty(),
                cardDepthStyleSettingsPayload = CardDepthStyleStorage.loadPayload().orEmpty().trim(),
                playerSettings = withoutProfileCredentials(
                    PROFILE_PLAYER_SETTINGS_FEATURE,
                    PlayerSettingsStorage.exportToSyncPayload(),
                ),
                streamBadgeSettings = StreamBadgeSettingsStorage.exportToSyncPayload(),
                debridSettings = withoutProfileCredentials(
                    PROFILE_DEBRID_SETTINGS_FEATURE,
                    DebridSettingsStorage.exportToSyncPayload(),
                ),
                tmdbSettings = withoutProfileCredentials(
                    PROFILE_TMDB_SETTINGS_FEATURE,
                    TmdbSettingsStorage.exportToSyncPayload(),
                ),
                mdbListSettings = withoutProfileCredentials(
                    PROFILE_MDBLIST_SETTINGS_FEATURE,
                    MdbListSettingsStorage.exportToSyncPayload(),
                ),
                metaScreenSettingsPayload = MetaScreenSettingsStorage.loadPayload().orEmpty().trim(),
                collectionMobileSettingsPayload = CollectionMobileSettingsStorage.loadPayload().orEmpty().trim(),
                continueWatchingSettingsPayload = ContinueWatchingPreferencesStorage.loadPayload().orEmpty().trim(),
                traktSettingsPayload = TraktSettingsStorage.loadPayload().orEmpty().trim(),
                traktCommentsSettings = TraktCommentsStorage.exportToSyncPayload(),
                notificationsSettings = NotificationsSettingsPayload(
                    episodeReleaseAlertsEnabled = EpisodeReleaseNotificationsRepository.uiState.value.isEnabled,
                ),
                socialFeatures = SocialFeaturesPayload(
                    socialFeaturesEnabled = SocialFeaturePreferencesRepository.exportStoredPreference(),
                ),
                downloadPolicy = DownloadPolicyRepository.exportForSync(),
                zFeatures = buildJsonObject {
                    zProfileSyncContributors.forEach { contributor ->
                        contributor.export()?.let { put(contributor.key, it) }
                    }
                },
            ),
        )
    }

    private fun applyRemoteBlob(blob: MobileProfileSettingsBlob) {
        ThemeSettingsStorage.replaceFromSyncPayload(blob.features.themeSettings)
        ThemeSettingsRepository.onProfileChanged()

        PosterCardStyleStorage.savePayload(blob.features.posterCardStyleSettingsPayload)
        PosterCardStyleRepository.onProfileChanged()

        CustomPosterUrlStorage.savePattern(blob.features.customPosterUrlPattern.ifBlank { null })
        val remoteScreenKeys = blob.features.customPosterEnabledScreens
            .takeIf { it.isNotBlank() }
            ?.split(",")
            ?.toSet()
        CustomPosterUrlStorage.saveEnabledScreens(remoteScreenKeys)
        CustomPosterUrlRepository.onProfileChanged()
        com.nuvio.app.features.home.HomeRepository.applyCurrentSettings()

        CardDepthStyleStorage.savePayload(blob.features.cardDepthStyleSettingsPayload)
        CardDepthStyleRepository.onProfileChanged()

        val localPlayerSettings = PlayerSettingsStorage.exportToSyncPayload()
        val localIntroDbApiKey = PlayerSettingsStorage.loadIntroDbApiKey()
        PlayerSettingsStorage.replaceFromSyncPayload(
            preservingLocalProfileCredentials(
                PROFILE_PLAYER_SETTINGS_FEATURE,
                blob.features.playerSettings,
                localPlayerSettings,
            ),
        )
        localIntroDbApiKey?.let(PlayerSettingsStorage::saveIntroDbApiKey)
        PlayerSettingsRepository.onProfileChanged()

        StreamBadgeSettingsStorage.replaceFromSyncPayload(blob.features.streamBadgeSettings)
        StreamBadgeSettingsRepository.onProfileChanged()

        DebridSettingsStorage.replaceFromSyncPayload(
            preservingLocalProfileCredentials(
                PROFILE_DEBRID_SETTINGS_FEATURE,
                blob.features.debridSettings,
                DebridSettingsStorage.exportToSyncPayload(),
            ),
        )
        DebridSettingsRepository.onProfileChanged()

        TmdbSettingsStorage.replaceFromSyncPayload(
            preservingLocalProfileCredentials(
                PROFILE_TMDB_SETTINGS_FEATURE,
                blob.features.tmdbSettings,
                TmdbSettingsStorage.exportToSyncPayload(),
            ),
        )
        TmdbSettingsRepository.onProfileChanged()

        MdbListSettingsStorage.replaceFromSyncPayload(
            preservingLocalProfileCredentials(
                PROFILE_MDBLIST_SETTINGS_FEATURE,
                blob.features.mdbListSettings,
                MdbListSettingsStorage.exportToSyncPayload(),
            ),
        )
        MdbListMetadataService.clearCache()
        MdbListSettingsRepository.onProfileChanged()

        MetaScreenSettingsStorage.savePayload(blob.features.metaScreenSettingsPayload)
        MetaScreenSettingsRepository.onProfileChanged()

        CollectionMobileSettingsStorage.savePayload(blob.features.collectionMobileSettingsPayload)
        CollectionMobileSettingsRepository.onProfileChanged()

        ContinueWatchingPreferencesStorage.savePayload(blob.features.continueWatchingSettingsPayload)
        ContinueWatchingPreferencesRepository.onProfileChanged()

        TraktSettingsStorage.savePayload(blob.features.traktSettingsPayload)
        TrackingSettingsRepository.onProfileChanged()

        TraktCommentsStorage.replaceFromSyncPayload(blob.features.traktCommentsSettings)
        TraktCommentsSettings.onProfileChanged()

        EpisodeReleaseNotificationsRepository.applyFromSyncEnabled(blob.features.notificationsSettings.episodeReleaseAlertsEnabled)
        SocialFeaturePreferencesRepository.applyFromSync(blob.features.socialFeatures.socialFeaturesEnabled)
        DownloadPolicyRepository.applyFromSync(blob.features.downloadPolicy)
        zProfileSyncContributors.forEach { contributor ->
            contributor.applyFromSync(blob.features.zFeatures[contributor.key])
        }
    }

    private fun ensureRepositoriesLoaded() {
        ThemeSettingsRepository.ensureLoaded()
        PosterCardStyleRepository.ensureLoaded()
        CustomPosterUrlRepository.ensureLoaded()
        CardDepthStyleRepository.ensureLoaded()
        PlayerSettingsRepository.ensureLoaded()
        StreamBadgeSettingsRepository.ensureLoaded()
        DebridSettingsRepository.ensureLoaded()
        TmdbSettingsRepository.ensureLoaded()
        MdbListSettingsRepository.ensureLoaded()
        MetaScreenSettingsRepository.ensureLoaded()
        CollectionMobileSettingsRepository.ensureLoaded()
        ContinueWatchingPreferencesRepository.ensureLoaded()
        TrackingSettingsRepository.ensureLoaded()
        TraktCommentsSettings.ensureLoaded()
        EpisodeReleaseNotificationsRepository.ensureLoaded()
        SocialFeaturePreferencesRepository.ensureLoaded()
        DownloadPolicyRepository.ensureLoaded()
        zProfileSyncContributors.forEach { it.ensureLoaded() }
    }

    private fun buildSignature(blob: MobileProfileSettingsBlob): String =
        json.encodeToString(MobileProfileSettingsBlob.serializer(), blob)

    /**
     * Every state the push observer watches, **with** the projection of it that the signature reads.
     *
     * ⚠ One list for both jobs, and that is the fix, not a tidy-up. The observer used to watch one
     * hand-written list and build its signature from a second one; anything in the first and missing
     * from the second changed nothing the observer compared, so it never pushed - nav glow, custom
     * theme colours and the custom poster URL on mobile, and the custom poster screens on both
     * platforms. The next pull, where remote wins, then silently reverted them. An entry here cannot
     * be observed without also being compared.
     */
    private fun observedSyncStates(): List<ObservedSyncState<*>> = listOf(
        ObservedSyncState("theme", ThemeSettingsRepository.selectedThemePreference) { it?.name },
        ObservedSyncState("custom_theme_colors", ThemeSettingsRepository.customThemePreference),
        ObservedSyncState("amoled", ThemeSettingsRepository.amoledEnabled),
        ObservedSyncState("liquid_glass_tab_bar", ThemeSettingsRepository.liquidGlassNativeTabBarEnabled),
        ObservedSyncState("desktop_navigation_layout", ThemeSettingsRepository.desktopNavigationLayout) { it.name },
        ObservedSyncState("nav_bar_glow_enabled", ThemeSettingsRepository.navBarGlowEnabled),
        ObservedSyncState("nav_bar_style", ThemeSettingsRepository.navBarStyle) { it.key },
        ObservedSyncState("poster_card_style", PosterCardStyleRepository.uiState),
        ObservedSyncState("custom_poster_url", CustomPosterUrlRepository.pattern),
        ObservedSyncState("custom_poster_screens", CustomPosterUrlRepository.enabledScreens),
        ObservedSyncState("card_depth_style", CardDepthStyleRepository.uiState),
        ObservedSyncState("player", PlayerSettingsRepository.uiState),
        ObservedSyncState("stream_badges", StreamBadgeSettingsRepository.uiState),
        ObservedSyncState("debrid", DebridSettingsRepository.uiState),
        ObservedSyncState("tmdb", TmdbSettingsRepository.uiState),
        ObservedSyncState("mdblist", MdbListSettingsRepository.uiState),
        ObservedSyncState("meta", MetaScreenSettingsRepository.uiState),
        ObservedSyncState("collection_mobile_settings", CollectionMobileSettingsRepository.uiState),
        ObservedSyncState("continue", ContinueWatchingPreferencesRepository.uiState),
        ObservedSyncState("trakt_settings", TrackingSettingsRepository.uiState),
        ObservedSyncState("trakt_comments", TraktCommentsSettings.enabled),
        ObservedSyncState("episode_release_alerts", EpisodeReleaseNotificationsRepository.uiState) { it.isEnabled },
        ObservedSyncState("social_features", SocialFeaturePreferencesRepository.uiState) { it.storedPreference },
        ObservedSyncState("download_policy", DownloadPolicyRepository.policy),
    ) + zProfileSyncContributors.map { contributor ->
        ObservedSyncState<Any?>("z:${contributor.key}", contributor.observed) { contributor.export() }
    }

    private fun currentObservedStateSignature(): String =
        observedSyncStates().joinToString(separator = "||") { "${it.name}=${it.projected()}" }

}

@Serializable
private data class MobileProfileSettingsBlob(
    val version: Int = 4,
    val features: MobileProfileSettingsFeatures = MobileProfileSettingsFeatures(),
)

@Serializable
private data class MobileProfileSettingsFeatures(
    @SerialName("theme_settings") val themeSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("poster_card_style_settings_payload") val posterCardStyleSettingsPayload: String = "",
    @SerialName("custom_poster_url_pattern") val customPosterUrlPattern: String = "",
    @SerialName("custom_poster_enabled_screens") val customPosterEnabledScreens: String = "",
    @SerialName("card_depth_style_settings_payload") val cardDepthStyleSettingsPayload: String = "",
    @SerialName("player_settings") val playerSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("stream_badge_settings") val streamBadgeSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("debrid_settings") val debridSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("tmdb_settings") val tmdbSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("mdblist_settings") val mdbListSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("meta_screen_settings_payload") val metaScreenSettingsPayload: String = "",
    @SerialName("collection_mobile_settings_payload") val collectionMobileSettingsPayload: String = "",
    @SerialName("continue_watching_settings_payload") val continueWatchingSettingsPayload: String = "",
    @SerialName("trakt_settings_payload") val traktSettingsPayload: String = "",
    @SerialName("trakt_comments_settings") val traktCommentsSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("notifications_settings") val notificationsSettings: NotificationsSettingsPayload = NotificationsSettingsPayload(),
    @SerialName("social_features") val socialFeatures: SocialFeaturesPayload = SocialFeaturesPayload(),
    /** Download Mode and Download Preferences (Phase 9); every field nullable, see the payload. */
    @SerialName("download_policy") val downloadPolicy: DownloadPolicySyncPayload = DownloadPolicySyncPayload(),
    /**
     * Z-owned preferences, keyed by `ZProfileSyncContributor.key`. A key that is absent - an older Z
     * build, or vanilla Nuvio pushing a blob it knows nothing of this field in - leaves the local
     * value alone.
     */
    @SerialName("z_features") val zFeatures: JsonObject = JsonObject(emptyMap()),
)

/** One entry of `ProfileSettingsSync.observedSyncStates`: a watched state and what is compared of it. */
private class ObservedSyncState<T>(
    val name: String,
    val state: StateFlow<T>,
    private val projection: (T) -> Any? = { it },
) {
    fun projected(): Any? = projection(state.value)
}

@Serializable
private data class NotificationsSettingsPayload(
    @SerialName("episode_release_alerts_enabled") val episodeReleaseAlertsEnabled: Boolean = false,
)

/**
 * Whether the social product layer is switched on for this profile.
 *
 * ⚠ **Nullable, and it is the only field on this blob that has to be.** Three states must survive
 * the round trip and only two of them are booleans: never answered, explicitly on, explicitly off.
 * A blob written by a build that predates this field decodes to null, which
 * `SocialFeaturePreferencesRepository.applyFromSync` treats as "the remote has not caught up"
 * rather than as "the user chose off" - the same rule `syncKeysToClear` encodes, and the reason a
 * pull once wiped every playback setting the remote had never heard of.
 *
 * It is a typed sub-payload rather than a field on `player_settings` because whether the social
 * layer exists is an application-level preference, not a player setting - and because the player
 * blob carries `mergeMonotonicSyncInt`, which is right for a revision that may only rise and
 * exactly wrong for a preference the user may switch back off.
 */
@Serializable
private data class SocialFeaturesPayload(
    @SerialName("social_features_enabled") val socialFeaturesEnabled: Boolean? = null,
)

@Serializable
private data class SettingsBlobResponse(
    @SerialName("profile_id") val profileId: Int = 0,
    @SerialName("settings_json") val settingsJson: JsonObject? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)
