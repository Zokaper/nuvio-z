package com.nuvio.app.features.playback

import com.nuvio.app.features.player.DeviceLanguagePreferences
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.platformDisplayMaxHeight

/**
 * The one way settings become a [PlaybackSelectionContext].
 *
 * ⚠ **There were three hand-built copies of this, and they had drifted.** The stream route's was
 * complete; the in-player next-episode sheet's set six of thirteen fields, silently dropping the
 * quality ceiling, the language requirement, the secondary audio language, the audio preference
 * and the display height. The user-visible shape of that was an episode picked under the ceiling
 * you set, followed by an episode picked without it - the same title, the same session, two
 * different rules, and nothing on screen to say why.
 *
 * Whenever [PlaybackSelectionContext] gains a field, it gains it here, once. A caller that needs a
 * field set differently passes it; a caller that forgets one gets the same answer as the others
 * rather than a quietly weaker rule.
 */
internal fun playbackSelectionContextOf(
    settings: PlayerSettingsUiState,
    isEpisode: Boolean,
    runtimeMinutes: Int? = null,
    /**
     * This title's own language, for the `original` sentinel. Null is honest: see
     * [resolveRankableLanguages], which resolves `original` to no opinion rather than guessing.
     */
    contentOriginalLanguage: String? = null,
    /**
     * ⚠ **Automatic modes only.** A manual pick is the user reading the release name and choosing
     * anyway, so the callers that serve Classic and every manual path leave this null.
     */
    identity: RequestedContent? = null,
    displayMaxHeight: Int? = platformDisplayMaxHeight(),
    /**
     * "Prefer built-in subtitles", already reduced to a language or null by
     * [automaticEmbeddedSubtitleLanguage]. Not derived here: whether a pick is automatic is the
     * caller's fact (Classic, a manual pick and a download intent all suppress it), and the factory
     * does not know which of those it is serving.
     */
    preferredEmbeddedSubtitleLanguage: String? = null,
    deviceLanguages: List<String> = DeviceLanguagePreferences.preferredLanguageCodes(),
): PlaybackSelectionContext {
    val languages = resolveRankableLanguages(
        preferredAudio = settings.preferredAudioLanguage,
        secondaryAudio = settings.secondaryPreferredAudioLanguage,
        preferredSubtitle = settings.preferredSubtitleLanguage,
        secondarySubtitle = settings.secondaryPreferredSubtitleLanguage,
        deviceLanguages = deviceLanguages,
        contentOriginalLanguage = contentOriginalLanguage,
    )
    return PlaybackSelectionContext(
        runtimeMinutes = runtimeMinutes,
        isEpisode = isEpisode,
        allowTorrentSources = settings.playbackAllowTorrentAutopick,
        preferredAudioLanguage = languages.audio,
        secondaryAudioLanguage = languages.secondaryAudio,
        preferredSubtitleLanguage = languages.subtitle,
        secondarySubtitleLanguage = languages.secondarySubtitle,
        languageStrictness = settings.playbackLanguageStrictness,
        qualityCeilingMbps = settings.playbackQualityCeilingMbps.takeIf { it > 0 }?.toDouble(),
        identity = identity,
        codecPreference = settings.playbackCodecPreference,
        dynamicRangePolicy = settings.playbackDynamicRangePolicy,
        audioPreference = settings.playbackAudioPreference,
        displayMaxHeight = displayMaxHeight,
        preferredEmbeddedSubtitleLanguage = preferredEmbeddedSubtitleLanguage,
    )
}
