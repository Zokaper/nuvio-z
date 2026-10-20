package com.nuvio.app.features.language

import com.nuvio.app.core.language.LanguagePreferences
import com.nuvio.app.core.language.OtherLanguageAudio
import com.nuvio.app.core.language.SubtitleKind
import com.nuvio.app.core.language.legacyLanguageFields
import com.nuvio.app.core.language.migrateLegacyLanguageFields
import com.nuvio.app.features.player.DeviceLanguagePreferences
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The language settings (`core/language/TitleLanguagePlan.kt`), per profile.
 *
 * **Stored or derived.** A profile that has never saved them reads them off upstream's old fields
 * through [migrateLegacyLanguageFields] - live, so a value an older build syncs into those fields
 * still lands - and nothing is written until the user changes something. Only a stored value is
 * exported by [LanguagePreferencesSync], so an untouched device never overwrites another's choice.
 *
 * **Every save also writes the old fields** ([legacyLanguageFields]): downloads, the external player
 * hand-off, older Z builds and vanilla Nuvio still read them.
 */
object LanguagePreferencesRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var hasLoaded = false
    private val stored = MutableStateFlow<LanguagePreferences?>(null)

    val uiState: StateFlow<LanguagePreferences> by lazy {
        PlayerSettingsRepository.ensureLoaded()
        combine(stored, PlayerSettingsRepository.uiState) { saved, player -> saved ?: migrated(player) }
            .stateIn(scope, SharingStarted.Eagerly, current())
    }

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        stored.value = null
    }

    /** The settings now, stored or derived. Synchronous, for callers outside composition. */
    fun current(): LanguagePreferences {
        ensureLoaded()
        return stored.value ?: migrated(PlayerSettingsRepository.uiState.value)
    }

    /** What [LanguagePreferencesSync] exports: null while this profile has never saved them. */
    internal fun storedPreferences(): LanguagePreferences? {
        ensureLoaded()
        return stored.value
    }

    fun update(transform: (LanguagePreferences) -> LanguagePreferences) {
        save(transform(current()))
    }

    fun save(preferences: LanguagePreferences) {
        ensureLoaded()
        if (stored.value == preferences) return
        stored.value = preferences
        persist(preferences)
        writeLegacyFields(preferences)
    }

    internal fun applyFromSync(preferences: LanguagePreferences) {
        save(preferences)
    }

    private fun migrated(player: PlayerSettingsUiState): LanguagePreferences = migrateLegacyLanguageFields(
        preferredAudio = player.preferredAudioLanguage,
        secondaryAudio = player.secondaryPreferredAudioLanguage,
        preferredSubtitle = player.preferredSubtitleLanguage,
        secondarySubtitle = player.secondaryPreferredSubtitleLanguage,
        useForcedSubtitles = player.subtitleStyle.useForcedSubtitles,
        deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
    )

    private fun writeLegacyFields(preferences: LanguagePreferences) {
        val legacy = legacyLanguageFields(preferences)
        val player = PlayerSettingsRepository.uiState.value
        if (player.preferredAudioLanguage != legacy.preferredAudio) {
            PlayerSettingsRepository.setPreferredAudioLanguage(legacy.preferredAudio)
        }
        if (player.secondaryPreferredAudioLanguage != legacy.secondaryAudio) {
            PlayerSettingsRepository.setSecondaryPreferredAudioLanguage(legacy.secondaryAudio)
        }
        if (player.preferredSubtitleLanguage != legacy.preferredSubtitle) {
            PlayerSettingsRepository.setPreferredSubtitleLanguage(legacy.preferredSubtitle)
        }
        if (player.secondaryPreferredSubtitleLanguage != legacy.secondarySubtitle) {
            PlayerSettingsRepository.setSecondaryPreferredSubtitleLanguage(legacy.secondarySubtitle)
        }
        if (player.subtitleStyle.useForcedSubtitles != legacy.useForcedSubtitles) {
            PlayerSettingsRepository.setSubtitleStyle(player.subtitleStyle.copy(useForcedSubtitles = legacy.useForcedSubtitles))
        }
    }

    private fun loadFromDisk() {
        hasLoaded = true
        stored.value = LanguagePreferencesStorage.loadPayload()
            ?.let { runCatching { json.decodeFromString<StoredLanguagePreferences>(it) }.getOrNull() }
            ?.toPreferences()
    }

    private fun persist(preferences: LanguagePreferences) {
        LanguagePreferencesStorage.savePayload(json.encodeToString(StoredLanguagePreferences.from(preferences)))
    }
}

/**
 * The stored and synced shape. Enum values travel as their keys, so a value a newer build adds reads
 * as the default here instead of failing the whole payload.
 */
@Serializable
internal data class StoredLanguagePreferences(
    @SerialName("my_language") val myLanguage: String,
    @SerialName("fallback_language") val fallbackLanguage: String? = null,
    @SerialName("subtitle_language") val subtitleLanguage: String? = null,
    @SerialName("own_subtitles") val ownSubtitles: String? = null,
    @SerialName("other_audio") val otherAudio: String? = null,
    @SerialName("other_subtitles") val otherSubtitles: String? = null,
) {
    fun toPreferences(): LanguagePreferences {
        val defaults = LanguagePreferences()
        return LanguagePreferences(
            myLanguage = myLanguage.ifBlank { defaults.myLanguage },
            fallbackLanguage = fallbackLanguage?.takeIf(String::isNotBlank),
            subtitleLanguage = subtitleLanguage?.takeIf(String::isNotBlank),
            ownSubtitles = SubtitleKind.fromKey(ownSubtitles) ?: defaults.ownSubtitles,
            otherAudio = OtherLanguageAudio.fromKey(otherAudio) ?: defaults.otherAudio,
            otherSubtitles = SubtitleKind.fromKey(otherSubtitles) ?: defaults.otherSubtitles,
        )
    }

    companion object {
        fun from(preferences: LanguagePreferences) = StoredLanguagePreferences(
            myLanguage = preferences.myLanguage,
            fallbackLanguage = preferences.fallbackLanguage,
            subtitleLanguage = preferences.subtitleLanguage,
            ownSubtitles = preferences.ownSubtitles.key,
            otherAudio = preferences.otherAudio.key,
            otherSubtitles = preferences.otherSubtitles.key,
        )
    }
}
