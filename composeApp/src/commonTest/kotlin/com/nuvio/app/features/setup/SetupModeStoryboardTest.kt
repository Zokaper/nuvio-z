package com.nuvio.app.features.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The playback-mode animation's sequences.
 *
 * The drawing itself is Compose and unreachable from a test here, so this is the only executable
 * proof that each mode's loop says what the mode does - and the claims are not decorative. The
 * step is the one place in the wizard that changes behaviour rather than appearance, and a loop
 * that shows Streamlined asking the user to pick a release would be describing Classic.
 */
class SetupModeStoryboardTest {

    private val classic = setupStoryboardFrames("CLASSIC")
    private val streamlined = setupStoryboardFrames("STREAMLINED")
    private val instant = setupStoryboardFrames("INSTANT")

    private val allModes = listOf(classic, streamlined, instant)

    // --- the loop itself -----------------------------------------------------------------

    @Test
    fun everyModeHasFramesAndEveryFrameIsHeldForARealDuration() {
        allModes.forEach { frames ->
            assertTrue(frames.isNotEmpty())
            frames.forEach { frame -> assertTrue(frame.holdMillis > 0) }
        }
    }

    @Test
    fun everyModeStartsOnATitleAndEndsOnPlaying() {
        allModes.forEach { frames ->
            assertEquals(SetupStoryboardStage.Title, frames.first().stage)
            assertEquals(SetupStoryboardStage.Playing, frames.last().stage)
        }
    }

    @Test
    fun advancingFromTheLastFrameWrapsToTheFirst() {
        allModes.forEach { frames ->
            assertEquals(0, nextSetupStoryboardFrame(frames.lastIndex, frames.size))
        }
    }

    @Test
    fun walkingForwardFromAnyFrameReturnsToTheStart() {
        // The loop has to close from wherever it is entered. The drawing re-seeds its counter on
        // a mode change, but a recomposition that keeps the counter and swaps the list does not,
        // so entering part-way through is reachable.
        allModes.forEach { frames ->
            frames.indices.forEach { start ->
                var index = start
                var steps = 0
                while (index != 0 && steps <= frames.size) {
                    index = nextSetupStoryboardFrame(index, frames.size)
                    steps++
                }
                assertEquals(0, index)
            }
        }
    }

    @Test
    fun aStaleIndexOutsideTheFrameListRestartsRatherThanCrashing() {
        assertEquals(0, nextSetupStoryboardFrame(index = 99, frameCount = classic.size))
        assertEquals(0, nextSetupStoryboardFrame(index = -1, frameCount = classic.size))
        assertEquals(0, nextSetupStoryboardFrame(index = 0, frameCount = 0))
    }

    @Test
    fun anUnknownModeNameFallsBackToClassicRatherThanToAnEmptyBand() {
        // The step gates the app. A blank band is a worse answer than the wrong mode's loop.
        assertEquals(classic, setupStoryboardFrames("SOMETHING_ELSE"))
        assertEquals(classic, setupStoryboardFrames(""))
    }

    // --- what each mode claims ------------------------------------------------------------

    @Test
    fun classicMakesTheUserReadTheWholeSourceList() {
        val sources = classic.filter { it.stage == SetupStoryboardStage.Sources }
        assertTrue(sources.isNotEmpty())
        sources.forEach { frame ->
            assertEquals(SETUP_STORYBOARD_SOURCE_ROWS, frame.visibleRows)
        }
    }

    @Test
    fun classicNeverAsksForAQuality() {
        // Picking a quality instead of a release is the whole of what Streamlined adds.
        assertTrue(classic.none { it.stage == SetupStoryboardStage.Quality })
    }

    @Test
    fun classicsReleaseIsPickedByTheUser() {
        val chosen = classic.first { it.stage == SetupStoryboardStage.Chosen }
        assertTrue(chosen.pointerVisible)
        assertNotNull(chosen.highlightedRow)
    }

    @Test
    fun streamlinedAsksForAQualityBeforeItSettlesOnARelease() {
        val quality = streamlined.indexOfFirst { it.stage == SetupStoryboardStage.Quality }
        val chosen = streamlined.indexOfFirst { it.stage == SetupStoryboardStage.Chosen }
        assertTrue(quality >= 0)
        assertTrue(chosen >= 0)
        assertTrue(quality < chosen)
    }

    @Test
    fun classicNeverShowsAQualityChip() {
        assertTrue(classic.none { it.chipsVisible })
    }

    @Test
    fun streamlinedNeverShowsTheSourceList() {
        // "Quality without the wall of releases" is the card's own subtitle. If this loop showed
        // the wall, it would be drawing Classic. `visibleRows` as well as the stage, because the
        // drawing keys the release list off the count.
        assertTrue(streamlined.none { it.stage == SetupStoryboardStage.Sources })
        assertTrue(streamlined.all { it.visibleRows == 0 })
    }

    @Test
    fun streamlinedKeepsTheSameChipLitOnceItHasBeenPicked() {
        // The chips stay up when Streamlined settles, so a settled frame that lit a *different*
        // chip would read as the app overriding the quality the user just chose.
        val picked = streamlined.first { it.stage == SetupStoryboardStage.Quality && it.tapping }
        val settled = streamlined.first { it.stage == SetupStoryboardStage.Chosen }

        assertTrue(settled.chipsVisible)
        assertEquals(picked.highlightedRow, settled.highlightedRow)
    }

    @Test
    fun streamlinedPicksItsReleaseWithNoPointer() {
        // ⚠ The one assertion that carries the difference between the two modes. Both end with a
        // release settled on; only Classic has a finger on it.
        val chosen = streamlined.first { it.stage == SetupStoryboardStage.Chosen }
        assertFalse(chosen.pointerVisible)
        assertFalse(chosen.tapping)
        assertNotNull(chosen.highlightedRow)
    }

    @Test
    fun instantNeverAsksAnything() {
        assertTrue(instant.none { it.stage == SetupStoryboardStage.Sources })
        assertTrue(instant.none { it.stage == SetupStoryboardStage.Quality })
        assertTrue(instant.none { it.stage == SetupStoryboardStage.Chosen })
    }

    @Test
    fun instantReachesPlaybackInTheFewestFramesOfTheThree() {
        fun framesToPlaying(frames: List<SetupStoryboardFrame>) =
            frames.indexOfFirst { it.stage == SetupStoryboardStage.Playing }

        assertTrue(framesToPlaying(instant) < framesToPlaying(streamlined))
        assertTrue(framesToPlaying(streamlined) < framesToPlaying(classic))
    }

    @Test
    fun everyModeIsEnteredByATapAndNothingPlaysBeforeIt() {
        allModes.forEach { frames ->
            val firstTap = frames.indexOfFirst { it.tapping }
            val playing = frames.indexOfFirst { it.stage == SetupStoryboardStage.Playing }
            assertTrue(firstTap >= 0)
            assertTrue(firstTap < playing)
        }
    }

    @Test
    fun aTapAlwaysHasAPointerToTapWith() {
        allModes.forEach { frames ->
            frames.filter { it.tapping }.forEach { frame -> assertTrue(frame.pointerVisible) }
        }
    }

    @Test
    fun playingIsTheRestingFrameSoTheLoopDoesNotReadAsFrantic() {
        allModes.forEach { frames ->
            val playing = frames.last()
            val longestOther = frames.dropLast(1).maxOf { it.holdMillis }
            assertTrue(playing.holdMillis >= longestOther)
        }
    }

    @Test
    fun nothingIsHighlightedBeforeTheUserOrNuvioHasChosen() {
        allModes.forEach { frames ->
            assertNull(frames.first().highlightedRow)
        }
    }

    // --- the quality tokens ----------------------------------------------------------------

    @Test
    fun theQualityTokensAreThreeResolutions() {
        assertEquals(listOf("4K", "1080p", "720p"), setupStoryboardQualityTokens)
    }

    // --- the release strings ---------------------------------------------------------------

    @Test
    fun thereIsOneReleaseStringPerRowClassicDraws() {
        // The drawing iterates the strings and positions the pointer off the row count. If these
        // ever disagree, the pointer lands beside a row that is not there.
        assertEquals(setupStoryboardReleases.size, SETUP_STORYBOARD_SOURCE_ROWS)
    }

    @Test
    fun everyReleaseStringIsNonBlankAndShortEnoughToFitOneLine() {
        // The row is 140 dp wide at labelSmall and clips rather than wrapping, so a long string
        // silently loses its tail - which is the size, the part that makes the rows differ.
        setupStoryboardReleases.forEach { release ->
            assertTrue(release.isNotBlank())
            assertTrue(release.length <= 24)
        }
    }

    @Test
    fun theReleaseStringsDifferFromEachOther() {
        // Three identical-looking rows would say the choice does not matter, which is the
        // opposite of what Classic is for.
        assertEquals(setupStoryboardReleases.size, setupStoryboardReleases.toSet().size)
    }

    @Test
    fun classicsPointerVisitsEveryRowInOrderAndSkipsNone() {
        // ⚠ The point of the sequence. The pointer's offset is animated between consecutive
        // frames, so a skipped row is a visible jump - and a jump is what "reading the list"
        // must not look like. Revision 5 went 0 -> 2 and that is exactly how it read.
        val visited = classic
            .filter { it.stage == SetupStoryboardStage.Sources && it.pointerVisible }
            .mapNotNull { it.highlightedRow }
        assertEquals(setupStoryboardReleases.indices.toList(), visited)
    }

    @Test
    fun classicTapsTheRowItsPointerFinishedOn() {
        val lastVisited = classic
            .last { it.stage == SetupStoryboardStage.Sources && it.pointerVisible }
            .highlightedRow
        val tapped = classic.first { it.stage == SetupStoryboardStage.Chosen }.highlightedRow
        assertEquals(lastVisited, tapped)
    }

    @Test
    fun theQualityPickIsWithinTheTokensOffered() {
        val pick = streamlined.first { it.stage == SetupStoryboardStage.Quality && it.tapping }
        val row = pick.highlightedRow
        assertNotNull(row)
        assertTrue(row in setupStoryboardQualityTokens.indices)
    }
}
