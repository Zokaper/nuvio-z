package com.nuvio.app.features.setup

// No imports, and none may be added: like SetupWizardSteps.kt, this is the only part of Advanced
// Setup a test can reach, and the pure suites compile it on its own.

/**
 * Advanced Setup: an optional hub of curated categories, each one to three short panels over a live
 * preview, every control writing the same repository its Settings row writes.
 *
 * **Not "Settings one page at a time".** A category is here only when a guided explanation or a
 * preview earns its place; plumbing (custom poster URL patterns, API keys, decoder options, P2P,
 * stream auto-play rules, TMDB modules) stays in Settings. And, like the setup wizard, no panel
 * carries more than four controls, so none scrolls on a phone.
 *
 * **The hub is the structure, the tour a convenience over it.** "Take the full tour" walks the same
 * panels in hub order; it is not a second flow.
 */
enum class AdvancedSetupGroup { Playback, Downloads, Look, Screens, SocialAndServices }

enum class AdvancedSetupCategory(val group: AdvancedSetupGroup) {
    PlaybackMode(AdvancedSetupGroup.Playback),
    Player(AdvancedSetupGroup.Playback),
    Skipping(AdvancedSetupGroup.Playback),
    Subtitles(AdvancedSetupGroup.Playback),
    Downloads(AdvancedSetupGroup.Downloads),
    Navigation(AdvancedSetupGroup.Look),
    Theme(AdvancedSetupGroup.Look),
    Posters(AdvancedSetupGroup.Look),
    Home(AdvancedSetupGroup.Screens),
    DetailPage(AdvancedSetupGroup.Screens),
    SourceList(AdvancedSetupGroup.Screens),
    Social(AdvancedSetupGroup.SocialAndServices),
    Metadata(AdvancedSetupGroup.SocialAndServices),
    Tracking(AdvancedSetupGroup.SocialAndServices),
}

/** One screen of a category, in the order the category shows them. */
enum class AdvancedSetupPanel(val category: AdvancedSetupCategory) {
    PlaybackModeChoice(AdvancedSetupCategory.PlaybackMode),
    PlaybackSourcePreferences(AdvancedSetupCategory.PlaybackMode),
    PlaybackSourceFormat(AdvancedSetupCategory.PlaybackMode),
    PlayerControls(AdvancedSetupCategory.Player),
    PlayerTouch(AdvancedSetupCategory.Player),
    SkipSegments(AdvancedSetupCategory.Skipping),
    NextEpisode(AdvancedSetupCategory.Skipping),
    SubtitleLook(AdvancedSetupCategory.Subtitles),
    SubtitlePlacement(AdvancedSetupCategory.Subtitles),
    DownloadModeChoice(AdvancedSetupCategory.Downloads),
    DownloadPreferences(AdvancedSetupCategory.Downloads),
    DownloadDevice(AdvancedSetupCategory.Downloads),
    NavigationStyle(AdvancedSetupCategory.Navigation),
    ThemePalette(AdvancedSetupCategory.Theme),
    PosterShape(AdvancedSetupCategory.Posters),
    PosterEffects(AdvancedSetupCategory.Posters),
    PosterHover(AdvancedSetupCategory.Posters),
    HomeHero(AdvancedSetupCategory.Home),
    HomeContinueWatching(AdvancedSetupCategory.Home),
    DetailLayout(AdvancedSetupCategory.DetailPage),
    DetailEpisodes(AdvancedSetupCategory.DetailPage),
    SourceListLook(AdvancedSetupCategory.SourceList),
    SocialSharing(AdvancedSetupCategory.Social),
    SocialIdentity(AdvancedSetupCategory.Social),
    EnhancedMetadata(AdvancedSetupCategory.Metadata),
    TrackingServices(AdvancedSetupCategory.Tracking),
}

/**
 * Every control a panel can show. The Compose bodies render from [advancedSetupControls]; they do
 * not decide for themselves which rows a platform gets, for the same reason the wizard's branch
 * rules live in `SetupWizardSteps.kt`.
 */
enum class AdvancedSetupControl {
    PlaybackModeCards,
    QualityLimit,
    LanguageMatching,
    DynamicRange,
    CodecPreference,
    AudioPreference,
    PreferEmbeddedSubtitles,
    LegacyPlayerLayout,
    PauseOverlay,
    LoadingOverlay,
    ContentWarnings,
    TouchGestures,
    HoldToSpeed,
    HoldSpeed,
    SkipIntro,
    AutoSkipSegments,
    AutoPlayNextEpisode,
    NextEpisodeThreshold,
    SubtitleSize,
    SubtitleTextColor,
    SubtitleBackground,
    SubtitleOutline,
    SubtitleBold,
    SubtitleOffset,
    DownloadModeCards,
    DownloadResolution,
    DownloadSize,
    DownloadFallback,
    DownloadMobileData,
    DownloadsAtOnce,
    NavBarStyle,
    NavBarGlow,
    DesktopNavigationLayout,
    LiquidGlassTabBar,
    ThemePalette,
    AmoledBlack,
    LandscapePosters,
    PosterWidth,
    PosterCorners,
    HidePosterLabels,
    CardDepth,
    CardDepthPreset,
    HoverPreview,
    HoverTrailer,
    HoverTrailerSound,
    HeroSection,
    CatalogTypeLabels,
    ContinueWatchingVisible,
    ContinueWatchingStyle,
    EpisodeThumbnails,
    DetailBackground,
    DetailTabLayout,
    OverallRatings,
    EpisodeCardStyle,
    BlurUnwatchedEpisodes,
    EpisodeRatings,
    RandomEpisode,
    SourceBackground,
    SourceSizeBadges,
    SourceBadgePlacement,
    SourceAddonLogo,
    SocialFeatures,
    ShareWatchingNow,
    ShareWatched,
    JoinPolicy,
    SocialHandle,
    TmdbEnrichment,
    TmdbLanguage,
    MdbListRatingsLink,
    TrackingLink,
}

/** The most controls a panel may carry - the setup wizard's rule, so nothing scrolls on a phone. */
const val ADVANCED_SETUP_MAX_CONTROLS: Int = 4

/**
 * Everything eligibility depends on, as plain values so this file stays import-free.
 *
 * Some of it is fixed for the process (the platform flags), some of it can change while the user is
 * standing on a panel (the modes, social) - which is why panels, like wizard steps, can leave the
 * plan under the user and the tour heals forward rather than stranding them.
 */
data class AdvancedSetupFacts(
    val isAndroid: Boolean = false,
    val isIos: Boolean = false,
    val isDesktop: Boolean = false,
    /** Android 13+: the navigation bar's glow exists. */
    val navGlowSupported: Boolean = false,
    /** iPhone on iOS 26+: the native Liquid Glass tab bar exists. */
    val liquidGlassSupported: Boolean = false,
    val downloadsEnabled: Boolean = true,
    /** The Z backend is configured, so the social layer can exist at all. */
    val socialAvailable: Boolean = true,
    /** The build carries a Trakt or Simkl client id; without one the connect flows cannot work. */
    val hasTrackingCredentials: Boolean = false,
    val playbackModeName: String = "CLASSIC",
    val downloadModeName: String = "MANUAL",
    val socialEnabled: Boolean = false,
    val offerSocialIdentity: Boolean = false,
    /** An external player is chosen, so the in-app player's own settings do nothing. */
    val externalPlayer: Boolean = false,
    /**
     * `AppFeaturePolicy.trailerPlaybackMode` is `IN_APP`. Off on Windows, where there is no trailer
     * surface at all - Settings hides the hover-trailer switches there for the same reason.
     */
    val inAppTrailers: Boolean = false,
) {
    val isMobile: Boolean get() = isAndroid || isIos
}

/** The controls [panel] shows under [facts], in order. Empty means the panel is dropped. */
fun advancedSetupControls(panel: AdvancedSetupPanel, facts: AdvancedSetupFacts): List<AdvancedSetupControl> {
    val automaticPicker = playbackSetupVariant(facts.playbackModeName) != PlaybackSetupVariant.None
    return when (panel) {
        AdvancedSetupPanel.PlaybackModeChoice -> listOf(AdvancedSetupControl.PlaybackModeCards)
        // Every source preference feeds the automatic picker, which Classic does not have - the same
        // reason the Settings rows are greyed out and the wizard's playback step is dropped.
        AdvancedSetupPanel.PlaybackSourcePreferences ->
            if (automaticPicker) listOf(AdvancedSetupControl.QualityLimit, AdvancedSetupControl.LanguageMatching, AdvancedSetupControl.DynamicRange) else emptyList()
        AdvancedSetupPanel.PlaybackSourceFormat ->
            if (automaticPicker) listOf(AdvancedSetupControl.CodecPreference, AdvancedSetupControl.AudioPreference, AdvancedSetupControl.PreferEmbeddedSubtitles) else emptyList()
        AdvancedSetupPanel.PlayerControls -> buildList {
            if (facts.isMobile) add(AdvancedSetupControl.LegacyPlayerLayout)
            add(AdvancedSetupControl.PauseOverlay)
            add(AdvancedSetupControl.LoadingOverlay)
            add(AdvancedSetupControl.ContentWarnings)
        }
        AdvancedSetupPanel.PlayerTouch ->
            if (facts.isMobile && !facts.externalPlayer) listOf(AdvancedSetupControl.TouchGestures, AdvancedSetupControl.HoldToSpeed, AdvancedSetupControl.HoldSpeed) else emptyList()
        AdvancedSetupPanel.SkipSegments -> listOf(AdvancedSetupControl.SkipIntro, AdvancedSetupControl.AutoSkipSegments)
        AdvancedSetupPanel.NextEpisode -> listOf(AdvancedSetupControl.AutoPlayNextEpisode, AdvancedSetupControl.NextEpisodeThreshold)
        AdvancedSetupPanel.SubtitleLook ->
            if (facts.externalPlayer) emptyList()
            else listOf(AdvancedSetupControl.SubtitleSize, AdvancedSetupControl.SubtitleTextColor, AdvancedSetupControl.SubtitleBackground, AdvancedSetupControl.SubtitleOutline)
        AdvancedSetupPanel.SubtitlePlacement ->
            if (facts.externalPlayer) emptyList() else listOf(AdvancedSetupControl.SubtitleBold, AdvancedSetupControl.SubtitleOffset)
        AdvancedSetupPanel.DownloadModeChoice ->
            if (facts.downloadsEnabled) listOf(AdvancedSetupControl.DownloadModeCards) else emptyList()
        // Assisted asks no resolution (the user picks one per download) and Manual asks nothing - the
        // wizard's download step makes the same cut, see `DownloadSetupVariant`.
        AdvancedSetupPanel.DownloadPreferences -> when {
            !facts.downloadsEnabled -> emptyList()
            facts.downloadModeName == "AUTOMATIC" -> listOf(AdvancedSetupControl.DownloadResolution, AdvancedSetupControl.DownloadSize, AdvancedSetupControl.DownloadFallback)
            facts.downloadModeName == "ASSISTED" -> listOf(AdvancedSetupControl.DownloadSize)
            else -> emptyList()
        }
        AdvancedSetupPanel.DownloadDevice -> buildList {
            if (!facts.downloadsEnabled) return@buildList
            if (facts.isMobile) add(AdvancedSetupControl.DownloadMobileData)
            if (!facts.isIos) add(AdvancedSetupControl.DownloadsAtOnce)
        }
        AdvancedSetupPanel.NavigationStyle -> buildList {
            when {
                facts.isAndroid -> {
                    add(AdvancedSetupControl.NavBarStyle)
                    if (facts.navGlowSupported) add(AdvancedSetupControl.NavBarGlow)
                }
                facts.isDesktop -> {
                    add(AdvancedSetupControl.DesktopNavigationLayout)
                    add(AdvancedSetupControl.NavBarStyle)
                }
                facts.isIos && facts.liquidGlassSupported -> add(AdvancedSetupControl.LiquidGlassTabBar)
            }
        }
        AdvancedSetupPanel.ThemePalette -> listOf(AdvancedSetupControl.ThemePalette, AdvancedSetupControl.AmoledBlack)
        AdvancedSetupPanel.PosterShape -> listOf(AdvancedSetupControl.LandscapePosters, AdvancedSetupControl.PosterWidth, AdvancedSetupControl.PosterCorners, AdvancedSetupControl.HidePosterLabels)
        AdvancedSetupPanel.PosterEffects -> listOf(AdvancedSetupControl.CardDepth, AdvancedSetupControl.CardDepthPreset)
        AdvancedSetupPanel.PosterHover -> buildList {
            if (!facts.isDesktop) return@buildList
            add(AdvancedSetupControl.HoverPreview)
            if (facts.inAppTrailers) {
                add(AdvancedSetupControl.HoverTrailer)
                add(AdvancedSetupControl.HoverTrailerSound)
            }
        }
        AdvancedSetupPanel.HomeHero -> listOf(AdvancedSetupControl.HeroSection, AdvancedSetupControl.CatalogTypeLabels)
        AdvancedSetupPanel.HomeContinueWatching ->
            listOf(AdvancedSetupControl.ContinueWatchingVisible, AdvancedSetupControl.ContinueWatchingStyle, AdvancedSetupControl.EpisodeThumbnails)
        AdvancedSetupPanel.DetailLayout -> listOf(AdvancedSetupControl.DetailBackground, AdvancedSetupControl.DetailTabLayout, AdvancedSetupControl.OverallRatings)
        // Random Episode is mobile-only and off by default: offered, last, never pushed.
        AdvancedSetupPanel.DetailEpisodes -> buildList {
            add(AdvancedSetupControl.EpisodeCardStyle)
            add(AdvancedSetupControl.BlurUnwatchedEpisodes)
            add(AdvancedSetupControl.EpisodeRatings)
            if (facts.isMobile) add(AdvancedSetupControl.RandomEpisode)
        }
        AdvancedSetupPanel.SourceListLook ->
            listOf(AdvancedSetupControl.SourceBackground, AdvancedSetupControl.SourceSizeBadges, AdvancedSetupControl.SourceBadgePlacement, AdvancedSetupControl.SourceAddonLogo)
        AdvancedSetupPanel.SocialSharing -> when {
            !facts.socialAvailable -> emptyList()
            facts.socialEnabled -> listOf(AdvancedSetupControl.SocialFeatures, AdvancedSetupControl.ShareWatchingNow, AdvancedSetupControl.ShareWatched, AdvancedSetupControl.JoinPolicy)
            else -> listOf(AdvancedSetupControl.SocialFeatures)
        }
        AdvancedSetupPanel.SocialIdentity ->
            if (facts.socialAvailable && facts.socialEnabled && facts.offerSocialIdentity) listOf(AdvancedSetupControl.SocialHandle) else emptyList()
        AdvancedSetupPanel.EnhancedMetadata -> listOf(AdvancedSetupControl.TmdbEnrichment, AdvancedSetupControl.TmdbLanguage, AdvancedSetupControl.MdbListRatingsLink)
        AdvancedSetupPanel.TrackingServices ->
            if (facts.hasTrackingCredentials) listOf(AdvancedSetupControl.TrackingLink) else emptyList()
    }
}

/** The panels [category] shows under [facts], in order. */
fun advancedSetupPanels(category: AdvancedSetupCategory, facts: AdvancedSetupFacts): List<AdvancedSetupPanel> =
    AdvancedSetupPanel.entries.filter { it.category == category && advancedSetupControls(it, facts).isNotEmpty() }

/**
 * The categories the hub lists, in hub order.
 *
 * ⚠ A category is judged on its **fixed** facts, not on the ones that can move under the user: the
 * Playback mode category is always listed even though its preference panels vanish in Classic,
 * because the mode itself is always choosable there. Only a category with no panel on this platform
 * at all - Navigation on iOS before 26, Tracking without credentials - is left out.
 */
fun advancedSetupCategories(facts: AdvancedSetupFacts): List<AdvancedSetupCategory> =
    AdvancedSetupCategory.entries.filter { advancedSetupPanels(it, facts).isNotEmpty() }

/** One stop on the full tour. */
data class AdvancedSetupStop(val category: AdvancedSetupCategory, val panel: AdvancedSetupPanel)

/** Every panel of every listed category, in hub order: "Take the full tour". */
fun advancedSetupTour(facts: AdvancedSetupFacts): List<AdvancedSetupStop> =
    advancedSetupCategories(facts).flatMap { category ->
        advancedSetupPanels(category, facts).map { AdvancedSetupStop(category, it) }
    }

/**
 * The panel after [current] within its own category, or null at the category's end (which returns
 * to the hub). A [current] that has left the plan answers with the next panel after it in
 * declaration order - the wizard's healing rule, so a panel that vanishes under the user (Classic
 * chosen on the mode panel) never strands them.
 */
fun nextAdvancedSetupPanel(current: AdvancedSetupPanel, facts: AdvancedSetupFacts): AdvancedSetupPanel? {
    val panels = advancedSetupPanels(current.category, facts)
    val index = panels.indexOf(current)
    if (index < 0) return panels.firstOrNull { it.ordinal > current.ordinal }
    return panels.getOrNull(index + 1)
}

/** The panel before [current] within its category, or null on the first. */
fun previousAdvancedSetupPanel(current: AdvancedSetupPanel, facts: AdvancedSetupFacts): AdvancedSetupPanel? {
    val panels = advancedSetupPanels(current.category, facts)
    val index = panels.indexOf(current)
    if (index < 0) return panels.lastOrNull { it.ordinal < current.ordinal }
    return panels.getOrNull(index - 1)
}

/** The tour stop after [current], or null when the tour is over (back to the hub). Heals forward. */
fun nextAdvancedSetupTourStop(current: AdvancedSetupPanel, facts: AdvancedSetupFacts): AdvancedSetupStop? {
    val tour = advancedSetupTour(facts)
    val index = tour.indexOfFirst { it.panel == current }
    if (index < 0) return tour.firstOrNull { it.panel.ordinal > current.ordinal }
    return tour.getOrNull(index + 1)
}

/** The tour stop before [current], or null on the first stop. */
fun previousAdvancedSetupTourStop(current: AdvancedSetupPanel, facts: AdvancedSetupFacts): AdvancedSetupStop? {
    val tour = advancedSetupTour(facts)
    val index = tour.indexOfFirst { it.panel == current }
    if (index < 0) return tour.lastOrNull { it.panel.ordinal < current.ordinal }
    return tour.getOrNull(index - 1)
}

/**
 * What Device Setup's arrival steps ask on this platform (setup polish, physical QA): the same controls
 * the matching Advanced Setup panels carry, so the two can never ask differently. Empty means the step
 * is not offered - `SetupWizardPlan.offerDeviceNavigation` / `offerDevicePlayer` are this, non-empty.
 *
 * - [SetupStep.DeviceNavigation]: exactly [AdvancedSetupPanel.NavigationStyle] - Android style (+ glow
 *   on 13+), Liquid Glass on iOS 26+, desktop layout + style; nothing on iOS before 26.
 * - [SetupStep.DevicePlayer]: the mobile player's layout and touch - legacy vs new controls, gestures,
 *   hold-to-speed and its speed. Mobile only, and not while an external player is chosen.
 */
fun deviceSetupControls(step: SetupStep, facts: AdvancedSetupFacts): List<AdvancedSetupControl> = when (step) {
    SetupStep.DeviceNavigation -> advancedSetupControls(AdvancedSetupPanel.NavigationStyle, facts)
    SetupStep.DevicePlayer ->
        if (facts.isMobile && !facts.externalPlayer) {
            listOf(
                AdvancedSetupControl.LegacyPlayerLayout,
                AdvancedSetupControl.TouchGestures,
                AdvancedSetupControl.HoldToSpeed,
                AdvancedSetupControl.HoldSpeed,
            )
        } else {
            emptyList()
        }
    else -> emptyList()
}

/** Restore a saved panel name; an unknown one (a later release removed it) answers null - the hub. */
fun advancedSetupPanelForSavedName(name: String?): AdvancedSetupPanel? =
    AdvancedSetupPanel.entries.firstOrNull { it.name == name }
