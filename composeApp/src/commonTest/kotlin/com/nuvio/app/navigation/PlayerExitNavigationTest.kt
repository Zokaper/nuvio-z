package com.nuvio.app.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.nuvio.app.features.playback.PlaybackLoadingController
import com.nuvio.app.features.playback.PlaybackProgressStep
import com.nuvio.app.features.playback.StreamRouteSurface
import com.nuvio.app.features.playback.StreamRouteSurfaceInputs
import com.nuvio.app.features.playback.streamRouteSurface
import com.nuvio.app.features.streams.StreamsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerExitNavigationTest {

    @Test
    fun intentionalExitFromFailureChainLaunchPopsDirectlyToPrecedingDestination() {
        val detailRoute = DetailRoute(type = "movie", id = "tt1234567")
        val streamRoute = StreamRoute(launchId = 100L)
        val playerRoute = PlayerRoute(launchId = 200L)

        val backStack = NavBackStack<NavKey>(detailRoute, streamRoute, playerRoute)
        val navigator = NuvioNavigator(backStack)

        assertEquals(playerRoute, navigator.currentRoute)
        assertEquals(3, backStack.size)

        // Intentional user exit from a failure-chain launch skips the transient retained StreamRoute
        val popped = navigator.popPlayerExit(
            expectedRoute = playerRoute,
            skipRetainedStreamRoute = true,
        )

        assertTrue(popped)
        assertEquals(detailRoute, navigator.currentRoute)
        assertEquals(1, backStack.size)
        assertFalse(backStack.contains(streamRoute))
        assertFalse(backStack.contains(playerRoute))
    }

    @Test
    fun fatalPlaybackErrorRetainsStreamRouteForFailover() {
        val detailRoute = DetailRoute(type = "movie", id = "tt1234567")
        val streamRoute = StreamRoute(launchId = 100L)
        val playerRoute = PlayerRoute(launchId = 200L)

        val backStack = NavBackStack<NavKey>(detailRoute, streamRoute, playerRoute)
        val navigator = NuvioNavigator(backStack)

        // Fatal errors do NOT skip StreamRoute - it must remain top so the failover chain can run
        val popped = navigator.popBackStack(expectedRoute = playerRoute)

        assertTrue(popped)
        assertEquals(streamRoute, navigator.currentRoute)
        assertEquals(2, backStack.size)
        assertTrue(backStack.contains(streamRoute))
        assertFalse(backStack.contains(playerRoute))
    }

    @Test
    fun chooseSourceManuallyRetainsStreamRoute() {
        val detailRoute = DetailRoute(type = "movie", id = "tt1234567")
        val streamRoute = StreamRoute(launchId = 100L)
        val playerRoute = PlayerRoute(launchId = 200L)

        val backStack = NavBackStack<NavKey>(detailRoute, streamRoute, playerRoute)
        val navigator = NuvioNavigator(backStack)

        // When user chooses source manually, a manual request is flagged
        StreamsRepository.signalManualSourceRequest()
        assertTrue(StreamsRepository.isManualSourceRequestPending)

        val autoPickedWithFailureChain = true
        val shouldSkip = autoPickedWithFailureChain && !StreamsRepository.isManualSourceRequestPending

        assertFalse(shouldSkip)

        val popped = navigator.popPlayerExit(
            expectedRoute = playerRoute,
            skipRetainedStreamRoute = shouldSkip,
        )

        assertTrue(popped)
        assertEquals(streamRoute, navigator.currentRoute)
        assertEquals(2, backStack.size)
        // Manual request remains pending so StreamRoute can consume it and reveal the source list
        assertTrue(StreamsRepository.consumeManualSourceRequest())
        assertFalse(StreamsRepository.isManualSourceRequestPending)
    }

    @Test
    fun intentionalPlayerExitCleansUpAutoPlayStateAndSuppressesLoadingOverlay() {
        // Open a loading session as would occur during playback startup
        val token = PlaybackLoadingController.open(
            step = PlaybackProgressStep.StartingPlayback,
            title = "Test Media",
            attempt = 1,
        )
        assertEquals(token, PlaybackLoadingController.activeToken)

        StreamsRepository.signalManualSourceRequest()
        assertTrue(StreamsRepository.isManualSourceRequestPending)

        // Perform the intentional exit cleanup sequence from PlayerDestination.kt
        StreamsRepository.abandonAutoPlay()
        StreamsRepository.cancelLoading()
        PlaybackLoadingController.activeToken?.let(PlaybackLoadingController::close)

        // Verify all state is reset cleanly
        assertNull(PlaybackLoadingController.session)
        assertNull(StreamsRepository.uiState.value.autoPlayStream)
        assertTrue(StreamsRepository.uiState.value.autoPlayCandidates.isEmpty())
        assertFalse(StreamsRepository.isManualSourceRequestPending)

        // Verify that post-exit state cannot evaluate to StreamRouteSurface.ProgressOverlay
        val surface = streamRouteSurface(
            StreamRouteSurfaceInputs(
                isClassic = false,
                isManualLaunch = false,
                manualSourceListRequested = false,
                hasNavigatedAway = false,
                isQualitySheetRoute = false,
                qualitySheetDismissed = false,
                isAutoPickRoute = false,
                isAutoPlaybackStarting = false,
                awaitingUserAnswer = false,
            )
        )
        assertFalse(surface == StreamRouteSurface.ProgressOverlay)
        assertFalse(surface == StreamRouteSurface.QualitySheet)
    }

    @Test
    fun intentionalExitWhenNotPrecededByStreamRoutePopsSingleDestination() {
        val detailRoute = DetailRoute(type = "movie", id = "tt1234567")
        val playerRoute = PlayerRoute(launchId = 200L)

        val backStack = NavBackStack<NavKey>(detailRoute, playerRoute)
        val navigator = NuvioNavigator(backStack)

        // Even with skipRetainedStreamRoute = true, if preceding destination is NOT StreamRoute,
        // it simply pops playerRoute normally
        val popped = navigator.popPlayerExit(
            expectedRoute = playerRoute,
            skipRetainedStreamRoute = true,
        )

        assertTrue(popped)
        assertEquals(detailRoute, navigator.currentRoute)
        assertEquals(1, backStack.size)
    }

    @Test
    fun popPlayerExitGuardsAgainstMismatchedRoute() {
        val detailRoute = DetailRoute(type = "movie", id = "tt1234567")
        val streamRoute = StreamRoute(launchId = 100L)
        val playerRoute = PlayerRoute(launchId = 200L)
        val otherRoute = DetailRoute(type = "tv", id = "tt9999999")

        val backStack = NavBackStack<NavKey>(detailRoute, streamRoute, playerRoute)
        val navigator = NuvioNavigator(backStack)

        val popped = navigator.popPlayerExit(
            expectedRoute = otherRoute,
            skipRetainedStreamRoute = true,
        )

        assertFalse(popped)
        assertEquals(playerRoute, navigator.currentRoute)
        assertEquals(3, backStack.size)
    }
}
