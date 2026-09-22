package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerExitOrderingTest {
    @Test
    fun systemBackDoesNotPopPlayerUntilGuardedReleaseCompletes() {
        val events = mutableListOf<String>()
        var completeRelease: (() -> Unit)? = null
        val playerBack = {
            releasePlayerBeforeNavigation(
                releasePlayer = { onReleased, _ ->
                    events += "release-started"
                    completeRelease = onReleased
                },
                navigateBack = { events += "pop" },
            )
        }

        dispatchNavigationBack(
            isPlayerRoute = true,
            playerBack = playerBack,
            pop = { events += "direct-pop" },
        )

        assertEquals(listOf("release-started"), events)
        completeRelease?.invoke()
        assertEquals(listOf("release-started", "pop"), events)
    }

    @Test
    fun systemBackFailsClosedDuringPlayerHandlerRegistrationGap() {
        val events = mutableListOf<String>()

        dispatchNavigationBack(
            isPlayerRoute = true,
            playerBack = null,
            pop = { events += "direct-pop" },
        )

        assertEquals(emptyList(), events)
    }

    @Test
    fun systemBackOnTheWatchTogetherLobbyGoesThroughTheLobbyNeverAPlainPop() {
        // ⚠ Regression, hardware Bug 1 (2026-09-15): Escape popped the lobby and orphaned the party.
        val events = mutableListOf<String>()

        dispatchNavigationBack(
            isPlayerRoute = false,
            playerBack = null,
            pop = { events += "direct-pop" },
            isPartyLobbyRoute = true,
            partyLobbyBack = { events += "lobby-back" },
        )
        assertEquals(listOf("lobby-back"), events)

        // And like the player, it fails closed during its registration gap.
        events.clear()
        dispatchNavigationBack(
            isPlayerRoute = false,
            playerBack = null,
            pop = { events += "direct-pop" },
            isPartyLobbyRoute = true,
            partyLobbyBack = null,
        )
        assertEquals(emptyList(), events)
    }

    @Test
    fun everyOtherRouteStillPopsDirectly() {
        val events = mutableListOf<String>()
        dispatchNavigationBack(
            isPlayerRoute = false,
            playerBack = { events += "player" },
            pop = { events += "direct-pop" },
            partyLobbyBack = { events += "lobby-back" },
        )
        assertEquals(listOf("direct-pop"), events)
    }

    @Test
    fun retainedControllerBarriersBackDuringActiveControllerGap() {
        val events = mutableListOf<String>()
        var complete: (() -> Unit)? = null
        val retained = testController { onReleased ->
            events += "release-started"
            complete = onReleased
        }

        releaseRetainedPlayerBeforeNavigation(
            controller = retained,
            navigateBack = { events += "navigate" },
        )

        assertEquals(listOf("release-started"), events)
        complete?.invoke()
        assertEquals(listOf("release-started", "navigate"), events)
    }

    @Test
    fun waitsForPlayerReleaseBeforeLeavingRoute() {
        val events = mutableListOf<String>()
        var completeRelease: (() -> Unit)? = null

        releasePlayerBeforeNavigation(
            releasePlayer = { onReleased, _ ->
                events += "release-started"
                completeRelease = onReleased
            },
            navigateBack = { events += "navigate" },
        )

        assertEquals(listOf("release-started"), events)
        completeRelease?.invoke()
        assertEquals(listOf("release-started", "navigate"), events)
    }

    private fun testController(release: (() -> Unit) -> Unit) = object : PlayerEngineController {
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(offsetMs: Long) = Unit
        override fun retry() = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun getAudioTracks() = emptyList<AudioTrack>()
        override fun getSubtitleTracks() = emptyList<SubtitleTrack>()
        override fun applyAudioLanguagePreferences(languages: List<String>) = Unit
        override fun selectAudioTrack(index: Int) = Unit
        override fun selectSubtitleTrack(index: Int) = Unit
        override fun setSubtitleUri(url: String) = Unit
        override fun clearExternalSubtitle() = Unit
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
        override fun releaseBeforeNavigation(
            onReleased: () -> Unit,
            onReleaseFailed: (String) -> Unit,
        ) = release(onReleased)
    }
}
