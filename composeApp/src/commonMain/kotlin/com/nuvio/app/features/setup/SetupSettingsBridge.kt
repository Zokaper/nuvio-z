package com.nuvio.app.features.setup

import com.nuvio.app.core.ui.PosterCardStyleRepository
import com.nuvio.app.core.ui.PosterCardStyleUiState
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.details.MetaScreenSettingsUiState
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesUiState

/**
 * The only place that maps [SetupPresetValues] onto the real settings repositories.
 *
 * It is a separate file from the wizard screen on purpose: this mapping is the one part of the
 * preset mechanism a test cannot reach - the repositories are singletons that write to disk -
 * so it needs to be short enough to check by reading. Nine lines out, nine lines back.
 *
 * ⚠ **Reading and writing must stay symmetrical.** [currentSetupValues] is what decides which
 * preset card is highlighted, so a field written by [applySetupPreset] and not read back means
 * a preset that can never recognise itself the moment the wizard is reopened.
 */

/** Applies every value in [values] through the repositories' own setters. */
internal fun applySetupPreset(values: SetupPresetValues) {
    PosterCardStyleRepository.setCatalogLandscapeModeEnabled(values.catalogLandscapeMode)
    PosterCardStyleRepository.setHideLabelsEnabled(values.hideLabels)
    // setWidthDp also recomputes the height at 3:2; there is no separate height to write.
    PosterCardStyleRepository.setWidthDp(values.posterWidthDp)
    PosterCardStyleRepository.setCornerRadiusDp(values.posterCornerRadiusDp)
    HomeCatalogSettingsRepository.setHeroEnabled(values.heroEnabled)
    ContinueWatchingPreferencesRepository.setStyle(values.continueWatchingStyle)
    MetaScreenSettingsRepository.setBackgroundMode(values.detailsBackgroundMode)
    MetaScreenSettingsRepository.setEpisodeCardStyle(values.episodeCardStyle)
    MetaScreenSettingsRepository.setTabLayout(values.detailsTabLayout)
}

/**
 * The same nine values as they stand right now, for [matchingSetupPreset].
 *
 * Takes the three UI states as parameters rather than reading the singletons, so the caller
 * passes the ones it is already collecting and the highlight recomputes when they change - a
 * direct read would be a snapshot taken once at composition and then quietly wrong.
 */
internal fun currentSetupValues(
    poster: PosterCardStyleUiState,
    heroEnabled: Boolean,
    continueWatching: ContinueWatchingPreferencesUiState,
    meta: MetaScreenSettingsUiState,
): SetupPresetValues = SetupPresetValues(
    catalogLandscapeMode = poster.catalogLandscapeModeEnabled,
    hideLabels = poster.hideLabelsEnabled,
    posterWidthDp = poster.widthDp,
    posterCornerRadiusDp = poster.cornerRadiusDp,
    heroEnabled = heroEnabled,
    continueWatchingStyle = continueWatching.style,
    detailsBackgroundMode = meta.backgroundMode,
    episodeCardStyle = meta.episodeCardStyle,
    detailsTabLayout = meta.tabLayout,
)
