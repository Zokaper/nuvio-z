package com.nuvio.app.features.player

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.SourceFactsExtractor
import com.nuvio.app.features.playback.PlaybackQualityOption
import com.nuvio.app.features.playback.PlaybackQualityOptions
import com.nuvio.app.features.playback.PlaybackSelectionContext
import com.nuvio.app.features.playback.PlaybackSelectionResult
import com.nuvio.app.features.playback.PlaybackSourceCandidate
import com.nuvio.app.features.playback.PlaybackSourceSelector
import com.nuvio.app.features.playback.automaticEmbeddedSubtitleLanguage
import com.nuvio.app.features.playback.playbackSelectionContextOf
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState

/**
 * Streamlined's in-player next-episode question, for a player that cannot draw the Compose sheet.
 *
 * ⚠ **Hardware, 2026-09-15: Next episode auto-selected in Streamlined.** `c28493cc` answered the
 * Compose quality sheet being unreachable over desktop's native video surface by routing desktop
 * Streamlined to automatic selection - which removed the one question Streamlined asks. The native
 * controls layer already draws the episode source list; this puts Streamlined's own rows (the same
 * [PlaybackQualityOptions] the sheet shows, picked through the same [PlaybackSourceSelector]) into that
 * list, plus the sheet's own "Choose source manually" escape.
 *
 * Automatic next-episode (the countdown, under "Auto-play next episode") is unchanged: nobody is
 * there to answer a question mid-binge, which is what that setting opts into.
 */
internal sealed interface EpisodeQualityChoice {
    data class Option(val option: PlaybackQualityOption) : EpisodeQualityChoice
    data object ChooseManually : EpisodeQualityChoice
}

internal sealed interface EpisodeQualityPick {
    data class Play(val stream: StreamItem, val fallbacks: List<StreamItem>) : EpisodeQualityPick
    data object ShowSourceList : EpisodeQualityPick
}

/** The selection context Streamlined picks the next episode with - also the autoplay-next selector's. */
internal fun streamlinedEpisodeSelectionContext(
    settings: PlayerSettingsUiState,
    episode: MetaVideo,
    displayMaxHeight: Int? = com.nuvio.app.platformDisplayMaxHeight(),
    contentOriginalLanguage: String? = null,
): PlaybackSelectionContext = playbackSelectionContextOf(
    settings = settings,
    isEpisode = true,
    runtimeMinutes = episode.runtime,
    contentOriginalLanguage = contentOriginalLanguage,
    displayMaxHeight = displayMaxHeight,
    // Picking the next episode's source here is automatic; the mode check inside keeps Classic out.
    preferredEmbeddedSubtitleLanguage = automaticEmbeddedSubtitleLanguage(
        enabled = settings.playbackPreferEmbeddedSubtitles,
        mode = settings.playbackMode,
        manualSelection = false,
        downloadIntent = false,
        primarySubtitleTarget = if (settings.playbackPreferEmbeddedSubtitles) settings.primarySubtitleTarget else null,
    ),
)

/**
 * The rows, or none while the catalogue is still arriving.
 *
 * Rows are derived from the whole catalogue, so an addon answering late would reshuffle them under
 * the pointer. Nothing is offered until it settles; the list shows its loading state instead.
 */
internal fun episodeQualityChoices(
    catalogue: StreamsUiState,
    context: PlaybackSelectionContext,
): List<EpisodeQualityChoice> {
    if (catalogue.isAnyLoading) return emptyList()
    val candidates = catalogue.groups.flatMapIndexed { addonOrder, group ->
        group.streams.map { stream ->
            PlaybackSourceCandidate(stream = stream, facts = SourceFactsExtractor.extract(stream), addonOrder = addonOrder)
        }
    }
    return PlaybackQualityOptions.build(candidates, context).map(EpisodeQualityChoice::Option) +
        EpisodeQualityChoice.ChooseManually
}

/** What choosing a row does - exactly what the Compose sheet's `onOptionSelected` does. */
internal fun decideEpisodeQualityPick(
    choice: EpisodeQualityChoice,
    context: PlaybackSelectionContext,
): EpisodeQualityPick = when (choice) {
    EpisodeQualityChoice.ChooseManually -> EpisodeQualityPick.ShowSourceList
    is EpisodeQualityChoice.Option -> when (val result = PlaybackSourceSelector.select(choice.option, context)) {
        is PlaybackSelectionResult.Play -> EpisodeQualityPick.Play(result.stream, result.fallbacks)
        // An uncached debrid link needs a question the native layer has no dialog for; the list
        // is where the user can see and choose it.
        is PlaybackSelectionResult.AskUncached,
        is PlaybackSelectionResult.NeedsManual -> EpisodeQualityPick.ShowSourceList
    }
}
