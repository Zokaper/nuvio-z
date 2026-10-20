package com.nuvio.app.features.player

import com.nuvio.app.core.language.LanguagePreferences
import com.nuvio.app.core.language.TitleLanguagePlan
import com.nuvio.app.core.language.resolveTitleLanguagePlan

/**
 * Z: the language settings (`core/language/TitleLanguagePlan.kt`) reach the player and the source
 * picker by **rewriting the fields both already read** - audio targets, subtitle targets, the forced
 * switch - for one title. Neither upstream picker is rewritten; this is the whole seam.
 */
internal fun PlayerSettingsUiState.withTitleLanguagePlan(plan: TitleLanguagePlan): PlayerSettingsUiState = copy(
    preferredAudioLanguage = plan.preferredAudio,
    secondaryPreferredAudioLanguage = plan.secondaryAudio,
    preferredSubtitleLanguage = plan.preferredSubtitle,
    secondaryPreferredSubtitleLanguage = plan.secondarySubtitle,
    subtitleStyle = subtitleStyle.copy(useForcedSubtitles = plan.forcedOnly),
)

/**
 * The source picker's view of one title, before anything plays. An unknown original language keeps
 * the stored fields, which describe a title in my language: ranking cannot see a file's default
 * track, so "leave the default" has nothing to rank by, and preferring my language is what every
 * download and launch did before.
 */
internal fun PlayerSettingsUiState.forTitle(
    preferences: LanguagePreferences?,
    originalLanguage: String?,
    deviceLanguages: List<String>,
): PlayerSettingsUiState {
    if (preferences == null || originalLanguage.isNullOrBlank()) return this
    return withTitleLanguagePlan(resolveTitleLanguagePlan(preferences, deviceLanguages, originalLanguage, null))
}

private data class TitleLanguageInputs(
    val settings: PlayerSettingsUiState,
    val preferences: LanguagePreferences,
    val originalLanguage: String?,
    val playingAudioLanguage: String?,
)

/**
 * Recompute [PlayerScreenRuntime.playerSettingsUiState] from the stored settings for this title and
 * the audio that is playing. Called when the screen's inputs are handed over and whenever the track
 * list is refreshed; a no-op unless an input changed.
 *
 * A changed subtitle answer re-opens the automatic subtitle pick (unless the user chose one), which
 * an audio switch already does by itself - this covers the title's language arriving after the first
 * pick, when the audio does not change.
 */
internal fun PlayerScreenRuntime.applyTitleLanguagePlan() {
    // Only `PlayerScreenContent` hands the preferences over, and it always does; a runtime driven
    // without it (the player tests) keeps the settings it was given.
    val preferences = languagePreferences ?: return
    val raw = rawPlayerSettingsUiState
    val inputs = TitleLanguageInputs(
        settings = raw,
        preferences = preferences,
        originalLanguage = contentLanguage,
        playingAudioLanguage = audioTracks.firstOrNull { it.index == selectedAudioIndex }?.language,
    )
    if (inputs == titleLanguageInputs) return
    titleLanguageInputs = inputs
    val plan = resolveTitleLanguagePlan(
        preferences = inputs.preferences,
        deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
        originalLanguage = inputs.originalLanguage,
        playingAudioLanguage = inputs.playingAudioLanguage,
    )
    val previous = titleLanguagePlan
    titleLanguagePlan = plan
    playerSettingsUiState = raw.withTitleLanguagePlan(plan)
    val subtitleAnswerChanged = previous != null && (
        previous.subtitleKind != plan.subtitleKind ||
            previous.preferredSubtitle != plan.preferredSubtitle ||
            previous.secondarySubtitle != plan.secondarySubtitle
        )
    if (subtitleAnswerChanged && !isUserExplicitSubtitleSelection) {
        preferredSubtitleSelectionApplied = false
    }
}

/**
 * The subtitle style to write back from the player's menu: what it shows, but with the *stored*
 * forced switch - [PlayerScreenRuntime.subtitleStyle] carries this title's plan, which must never be
 * persisted as the user's setting.
 */
internal val PlayerScreenRuntime.writableSubtitleStyle: SubtitleStyleState
    get() = subtitleStyle.copy(useForcedSubtitles = rawPlayerSettingsUiState.subtitleStyle.useForcedSubtitles)
