package com.nuvio.app.features.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdvancedSetupModelTest {
    private val android = AdvancedSetupFacts(isAndroid = true, navGlowSupported = true)
    private val iosOld = AdvancedSetupFacts(isIos = true, liquidGlassSupported = false)
    private val iosNew = AdvancedSetupFacts(isIos = true, liquidGlassSupported = true)
    private val desktop = AdvancedSetupFacts(isDesktop = true)

    /** Every platform and every state that moves panels in and out. */
    private val everyFacts: List<AdvancedSetupFacts> = buildList {
        listOf(android, android.copy(navGlowSupported = false), iosOld, iosNew, desktop).forEach { base ->
            listOf("CLASSIC", "STREAMLINED", "INSTANT").forEach { mode ->
                listOf("AUTOMATIC", "ASSISTED", "MANUAL").forEach { downloads ->
                    listOf(true, false).forEach { social ->
                        listOf(true, false).forEach { tracking ->
                            listOf(true, false).forEach { external ->
                                add(
                                    base.copy(
                                        playbackModeName = mode,
                                        downloadModeName = downloads,
                                        socialEnabled = social,
                                        offerSocialIdentity = social,
                                        hasTrackingCredentials = tracking,
                                        externalPlayer = external,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun noPanelEverCarriesMoreThanFourControls() {
        everyFacts.forEach { facts ->
            AdvancedSetupPanel.entries.forEach { panel ->
                val controls = advancedSetupControls(panel, facts)
                assertTrue(controls.size <= ADVANCED_SETUP_MAX_CONTROLS, "$panel under $facts has ${controls.size}")
                assertEquals(controls.toSet().size, controls.size, "$panel repeats a control")
            }
        }
    }

    @Test
    fun randomEpisodeIsMobileOnlyAndLastOnItsPanel() {
        listOf(android, iosNew).forEach { facts ->
            assertEquals(AdvancedSetupControl.RandomEpisode, advancedSetupControls(AdvancedSetupPanel.DetailEpisodes, facts).last())
        }
        assertFalse(AdvancedSetupControl.RandomEpisode in advancedSetupControls(AdvancedSetupPanel.DetailEpisodes, desktop))
    }

    @Test
    fun trackingIsHiddenWithoutCredentials() {
        assertFalse(AdvancedSetupCategory.Tracking in advancedSetupCategories(android))
        assertTrue(AdvancedSetupCategory.Tracking in advancedSetupCategories(android.copy(hasTrackingCredentials = true)))
    }

    @Test
    fun navigationOnIosNeedsLiquidGlass() {
        assertFalse(AdvancedSetupCategory.Navigation in advancedSetupCategories(iosOld))
        assertEquals(
            listOf(AdvancedSetupControl.LiquidGlassTabBar),
            advancedSetupControls(AdvancedSetupPanel.NavigationStyle, iosNew),
        )
        assertEquals(
            listOf(AdvancedSetupControl.NavBarStyle, AdvancedSetupControl.NavBarGlow),
            advancedSetupControls(AdvancedSetupPanel.NavigationStyle, android),
        )
        assertEquals(
            listOf(AdvancedSetupControl.NavBarStyle),
            advancedSetupControls(AdvancedSetupPanel.NavigationStyle, android.copy(navGlowSupported = false)),
        )
        assertEquals(
            listOf(AdvancedSetupControl.DesktopNavigationLayout, AdvancedSetupControl.NavBarStyle),
            advancedSetupControls(AdvancedSetupPanel.NavigationStyle, desktop),
        )
    }

    @Test
    fun desktopHasHoverAndNoTouchOrLegacyLayout() {
        assertTrue(AdvancedSetupPanel.PosterHover in advancedSetupPanels(AdvancedSetupCategory.Posters, desktop))
        assertFalse(AdvancedSetupPanel.PosterHover in advancedSetupPanels(AdvancedSetupCategory.Posters, android))
        assertFalse(AdvancedSetupPanel.PlayerTouch in advancedSetupPanels(AdvancedSetupCategory.Player, desktop))
        assertFalse(AdvancedSetupControl.LegacyPlayerLayout in advancedSetupControls(AdvancedSetupPanel.PlayerControls, desktop))
        assertTrue(AdvancedSetupControl.LegacyPlayerLayout in advancedSetupControls(AdvancedSetupPanel.PlayerControls, iosNew))
    }

    @Test
    fun hoverTrailerSwitchesOnlyWhereATrailerCanPlay() {
        val trailerControls = listOf(AdvancedSetupControl.HoverTrailer, AdvancedSetupControl.HoverTrailerSound)
        // Windows: the hover card exists, a trailer surface does not.
        assertEquals(listOf(AdvancedSetupControl.HoverPreview), advancedSetupControls(AdvancedSetupPanel.PosterHover, desktop))
        assertEquals(
            listOf(AdvancedSetupControl.HoverPreview) + trailerControls,
            advancedSetupControls(AdvancedSetupPanel.PosterHover, desktop.copy(inAppTrailers = true)),
        )
        assertTrue(advancedSetupControls(AdvancedSetupPanel.PosterHover, android.copy(inAppTrailers = true)).isEmpty())
    }

    @Test
    fun classicKeepsTheModeChoiceButDropsTheSourcePreferences() {
        val classic = android.copy(playbackModeName = "CLASSIC")
        assertEquals(listOf(AdvancedSetupPanel.PlaybackModeChoice), advancedSetupPanels(AdvancedSetupCategory.PlaybackMode, classic))
        assertTrue(AdvancedSetupCategory.PlaybackMode in advancedSetupCategories(classic))
        assertEquals(3, advancedSetupPanels(AdvancedSetupCategory.PlaybackMode, android.copy(playbackModeName = "INSTANT")).size)
    }

    @Test
    fun downloadPreferencesFollowTheWizardsCut() {
        assertEquals(
            listOf(AdvancedSetupControl.DownloadResolution, AdvancedSetupControl.DownloadSize, AdvancedSetupControl.DownloadFallback),
            advancedSetupControls(AdvancedSetupPanel.DownloadPreferences, android.copy(downloadModeName = "AUTOMATIC")),
        )
        assertEquals(
            listOf(AdvancedSetupControl.DownloadSize),
            advancedSetupControls(AdvancedSetupPanel.DownloadPreferences, android.copy(downloadModeName = "ASSISTED")),
        )
        assertTrue(advancedSetupControls(AdvancedSetupPanel.DownloadPreferences, android.copy(downloadModeName = "MANUAL")).isEmpty())
        assertEquals(listOf(AdvancedSetupControl.DownloadMobileData), advancedSetupControls(AdvancedSetupPanel.DownloadDevice, iosNew))
        assertEquals(listOf(AdvancedSetupControl.DownloadsAtOnce), advancedSetupControls(AdvancedSetupPanel.DownloadDevice, desktop))
        assertFalse(AdvancedSetupCategory.Downloads in advancedSetupCategories(android.copy(downloadsEnabled = false)))
    }

    @Test
    fun socialShowsOnlyTheMasterSwitchWhileOffAndDisappearsWithoutABackend() {
        assertEquals(listOf(AdvancedSetupControl.SocialFeatures), advancedSetupControls(AdvancedSetupPanel.SocialSharing, android))
        assertEquals(4, advancedSetupControls(AdvancedSetupPanel.SocialSharing, android.copy(socialEnabled = true)).size)
        assertFalse(AdvancedSetupCategory.Social in advancedSetupCategories(android.copy(socialAvailable = false)))
        assertTrue(
            AdvancedSetupPanel.SocialIdentity in advancedSetupPanels(
                AdvancedSetupCategory.Social,
                android.copy(socialEnabled = true, offerSocialIdentity = true),
            ),
        )
    }

    @Test
    fun theTourIsEveryListedPanelInHubOrder() {
        everyFacts.forEach { facts ->
            val tour = advancedSetupTour(facts)
            assertEquals(advancedSetupCategories(facts), tour.map { it.category }.distinct(), "$facts")
            tour.forEach { assertEquals(it.category, it.panel.category) }
            assertNull(previousAdvancedSetupTourStop(tour.first().panel, facts))
            assertNull(nextAdvancedSetupTourStop(tour.last().panel, facts))
        }
    }

    @Test
    fun walkingTheTourForwardVisitsEveryStopOnceAndBackwardReturns() {
        everyFacts.forEach { facts ->
            val tour = advancedSetupTour(facts)
            val walked = generateSequence(tour.first()) { nextAdvancedSetupTourStop(it.panel, facts) }.toList()
            assertEquals(tour, walked, "$facts")
            val back = generateSequence(tour.last()) { previousAdvancedSetupTourStop(it.panel, facts) }.toList()
            assertEquals(tour.reversed(), back, "$facts")
        }
    }

    @Test
    fun aPanelThatLeavesUnderTheUserHealsForward() {
        // Standing on the source preferences and choosing Classic removes them.
        val classic = android.copy(playbackModeName = "CLASSIC")
        val next = nextAdvancedSetupTourStop(AdvancedSetupPanel.PlaybackSourcePreferences, classic)
        assertEquals(AdvancedSetupCategory.Player, next?.category)
        assertNull(nextAdvancedSetupPanel(AdvancedSetupPanel.PlaybackSourcePreferences, classic))
        assertEquals(
            AdvancedSetupPanel.PlaybackModeChoice,
            previousAdvancedSetupPanel(AdvancedSetupPanel.PlaybackSourcePreferences, classic),
        )
    }

    @Test
    fun withinACategoryPanelsWalkAndEndBackAtTheHub() {
        val facts = desktop
        assertEquals(AdvancedSetupPanel.PosterEffects, nextAdvancedSetupPanel(AdvancedSetupPanel.PosterShape, facts))
        assertEquals(AdvancedSetupPanel.PosterHover, nextAdvancedSetupPanel(AdvancedSetupPanel.PosterEffects, facts))
        assertNull(nextAdvancedSetupPanel(AdvancedSetupPanel.PosterHover, facts))
        assertNull(nextAdvancedSetupPanel(AdvancedSetupPanel.PosterEffects, android))
    }

    @Test
    fun savedPanelNamesRestoreAndUnknownOnesFallBackToTheHub() {
        assertEquals(AdvancedSetupPanel.HomeHero, advancedSetupPanelForSavedName("HomeHero"))
        assertNull(advancedSetupPanelForSavedName("Withdrawn"))
        assertNull(advancedSetupPanelForSavedName(null))
    }

    // --- Device Setup's arrival steps ask what the matching panels ask ------------------------

    @Test
    fun deviceNavigationAsksTheNavigationPanelOnEveryPlatform() {
        listOf(android, android.copy(navGlowSupported = false), iosNew, desktop).forEach { facts ->
            assertEquals(
                advancedSetupControls(AdvancedSetupPanel.NavigationStyle, facts),
                deviceSetupControls(SetupStep.DeviceNavigation, facts),
                "$facts",
            )
            assertTrue(deviceSetupControls(SetupStep.DeviceNavigation, facts).isNotEmpty(), "$facts")
        }
        // iOS before 26 has no navigation choice, so its arrival skips the step.
        assertTrue(deviceSetupControls(SetupStep.DeviceNavigation, iosOld).isEmpty())
    }

    @Test
    fun devicePlayerIsMobileOnlyAndNeverWithAnExternalPlayer() {
        val expected = listOf(
            AdvancedSetupControl.LegacyPlayerLayout,
            AdvancedSetupControl.TouchGestures,
            AdvancedSetupControl.HoldToSpeed,
            AdvancedSetupControl.HoldSpeed,
        )
        listOf(android, iosOld, iosNew).forEach { facts ->
            assertEquals(expected, deviceSetupControls(SetupStep.DevicePlayer, facts), "$facts")
            assertTrue(deviceSetupControls(SetupStep.DevicePlayer, facts.copy(externalPlayer = true)).isEmpty())
        }
        assertTrue(deviceSetupControls(SetupStep.DevicePlayer, desktop).isEmpty())
        // Every control it asks is one the Player panels already carry.
        val playerPanels = (advancedSetupControls(AdvancedSetupPanel.PlayerControls, android) +
            advancedSetupControls(AdvancedSetupPanel.PlayerTouch, android)).toSet()
        assertTrue(playerPanels.containsAll(expected))
    }

    @Test
    fun deviceStepsCarryNoMoreThanAPanelMay() {
        everyFacts.forEach { facts ->
            listOf(SetupStep.DeviceNavigation, SetupStep.DevicePlayer).forEach { step ->
                assertTrue(deviceSetupControls(step, facts).size <= ADVANCED_SETUP_MAX_CONTROLS, "$step $facts")
            }
            SetupStep.entries.filter { it != SetupStep.DeviceNavigation && it != SetupStep.DevicePlayer }.forEach { step ->
                assertTrue(deviceSetupControls(step, facts).isEmpty(), "$step")
            }
        }
    }

    @Test
    fun externalPlayerDropsThePanelsItMakesInert() {
        val external = android.copy(externalPlayer = true)
        assertFalse(AdvancedSetupPanel.PlayerTouch in advancedSetupPanels(AdvancedSetupCategory.Player, external))
        assertFalse(AdvancedSetupCategory.Subtitles in advancedSetupCategories(external))
    }
}
