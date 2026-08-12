package com.nuvio.app.features.setup

import com.nuvio.app.features.details.MetaEpisodeCardStyle
import com.nuvio.app.features.details.MetaScreenBackgroundMode
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle

// Only enum imports, deliberately. This file stays runnable outside Gradle through the
// neighbour stubs in `scripts/pure-suite-stubs/` (`AGENTS.md`, "Verifying without Gradle",
// item 2). Importing the *real* enums rather than mirroring them as local copies is the whole
// point: a preset that names a value the app no longer has must fail to compile, not fail
// silently through a mapping layer no test can see.

/**
 * Every appearance value a preset decides, and therefore every value the fine-tuning steps
 * can change.
 *
 * **One data class with no defaults.** A preset with a defaulted field is a preset that
 * silently inherits whatever the previous profile happened to leave behind, which is exactly
 * the half-configured mix the presets exist to prevent.
 *
 * `SetupWizardPresetsTest` pins two properties over it: the three presets are pairwise
 * distinct, and **no field holds the same value in all three**. The second is the one that
 * catches a real mistake - a field every preset agrees on is a field the choice does not
 * actually control, so it either belongs in the defaults or the presets are wrong.
 */
data class SetupPresetValues(
    /** `PosterCardStyleRepository.setCatalogLandscapeModeEnabled` */
    val catalogLandscapeMode: Boolean,
    /** `PosterCardStyleRepository.setHideLabelsEnabled` */
    val hideLabels: Boolean,
    /** `PosterCardStyleRepository.setWidthDp`. Height follows at 3:2; do not set it here. */
    val posterWidthDp: Int,
    /** `PosterCardStyleRepository.setCornerRadiusDp` */
    val posterCornerRadiusDp: Int,
    /** `HomeCatalogSettingsRepository` hero banner */
    val heroEnabled: Boolean,
    /** `ContinueWatchingPreferencesRepository.setStyle` */
    val continueWatchingStyle: ContinueWatchingSectionStyle,
    /** `MetaScreenSettingsRepository.setBackgroundMode` */
    val detailsBackgroundMode: MetaScreenBackgroundMode,
    /** `MetaScreenSettingsRepository.setEpisodeCardStyle` */
    val episodeCardStyle: MetaEpisodeCardStyle,
    /** `MetaScreenSettingsRepository.setTabLayout` */
    val detailsTabLayout: Boolean,
)

/**
 * The three looks offered at [SetupStep.Look].
 *
 * ⚠ **A preset does not touch the theme.** Accent palette and AMOLED are their own step, and
 * folding them in here would mean that picking a layout silently recolours the app - a change
 * the user did not ask for, in the one place they are least equipped to notice it happened.
 * Layout and colour are orthogonal and the wizard treats them that way.
 *
 * ⚠ **A preset does not touch the playback mode either**, for the stronger version of the same
 * reason: that is a behaviour decision the user has already made two steps earlier, and no
 * appearance choice may quietly overwrite it.
 */
enum class SetupPreset {
    /**
     * Closest to how the app ships today, and the least motion.
     *
     * This is the one that must be safe to pick without reading anything, so it changes as
     * little as possible from the defaults: posters, labels on, no hero, plain backgrounds.
     */
    Simple,

    /** Artwork-forward: landscape cards with logos, a hero banner, blurred backdrops. */
    Cinematic,

    /** More titles per screen: dense posters, a wide Continue Watching strip, tabbed sections. */
    Compact,
    ;

    val values: SetupPresetValues
        get() = when (this) {
            Simple -> SetupPresetValues(
                catalogLandscapeMode = false,
                hideLabels = false,
                posterWidthDp = 126,
                posterCornerRadiusDp = 12,
                heroEnabled = false,
                continueWatchingStyle = ContinueWatchingSectionStyle.Card,
                detailsBackgroundMode = MetaScreenBackgroundMode.Normal,
                episodeCardStyle = MetaEpisodeCardStyle.List,
                detailsTabLayout = false,
            )

            Cinematic -> SetupPresetValues(
                // Landscape cards carry the logo overlay, which is the whole reason to pick
                // this look - and the reason the sample title needs a real logo to preview it.
                catalogLandscapeMode = true,
                hideLabels = true,
                posterWidthDp = 134,
                posterCornerRadiusDp = 16,
                heroEnabled = true,
                continueWatchingStyle = ContinueWatchingSectionStyle.Card,
                detailsBackgroundMode = MetaScreenBackgroundMode.Cinematic,
                episodeCardStyle = MetaEpisodeCardStyle.Horizontal,
                detailsTabLayout = true,
            )

            Compact -> SetupPresetValues(
                catalogLandscapeMode = false,
                hideLabels = false,
                posterWidthDp = 112,
                posterCornerRadiusDp = 8,
                heroEnabled = true,
                continueWatchingStyle = ContinueWatchingSectionStyle.Wide,
                detailsBackgroundMode = MetaScreenBackgroundMode.DominantColor,
                episodeCardStyle = MetaEpisodeCardStyle.List,
                detailsTabLayout = true,
            )
        }
}

/**
 * The preset whose values match [current] exactly, or null for a hand-tuned mix.
 *
 * Used to light up the right card when the wizard is re-run from Settings, and to drop the
 * highlight the moment a fine-tuning step moves a value away from the preset. Answering "still
 * Cinematic" over a layout the user has since changed is worse than answering nothing.
 */
fun matchingSetupPreset(current: SetupPresetValues): SetupPreset? =
    SetupPreset.entries.firstOrNull { it.values == current }
