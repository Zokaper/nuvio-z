package com.nuvio.app.features.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The four causes of a black picture, kept apart.
 *
 * The case this file was written from: debug `.32`, a clean Matroska remux, audio for twenty-seven
 * minutes across three attempts, `onRenderedFirstFrame` never once. The tempting rule -
 * `STATE_READY` and no frame means the codec is unsupported - is true of all four causes below and
 * distinguishes none of them, which is why every test here is about the census and not the clock.
 */
class VideoPresentationTest {

    private fun census(
        groups: Int = 0,
        tracks: Int = 0,
        supported: Int = 0,
        selected: Int = 0,
        selectedSupported: Int = 0,
    ) = VideoTrackCensus(
        groupCount = groups,
        trackCount = tracks,
        supportedTrackCount = supported,
        selectedTrackCount = selected,
        selectedSupportedTrackCount = selectedSupported,
    )

    @Test
    fun `nothing is decided before the tracks have settled`() {
        assertEquals(
            VideoPresentationVerdict.Unknown,
            classifyVideoPresentation(
                census = census(),
                tracksSettled = false,
                hasRenderedFirstFrame = false,
            ),
        )
        // Even a census that looks damning is only "not yet" before the demuxer has answered.
        assertEquals(
            VideoPresentationVerdict.Unknown,
            classifyVideoPresentation(
                census = census(groups = 1, tracks = 1),
                tracksSettled = false,
                hasRenderedFirstFrame = false,
            ),
        )
    }

    @Test
    fun `a presented frame ends the question`() {
        assertEquals(
            VideoPresentationVerdict.Rendering,
            classifyVideoPresentation(
                census = census(groups = 1, tracks = 1, supported = 1, selected = 1, selectedSupported = 1),
                tracksSettled = true,
                hasRenderedFirstFrame = true,
            ),
        )
        // And it outranks even an unsettled track list: the picture is on the screen.
        assertEquals(
            VideoPresentationVerdict.Rendering,
            classifyVideoPresentation(
                census = census(),
                tracksSettled = false,
                hasRenderedFirstFrame = true,
            ),
        )
    }

    /** Case A. Audio-only content is legitimate, so this is a statement and not an accusation. */
    @Test
    fun `no video groups is not a codec verdict`() {
        val verdict = classifyVideoPresentation(
            census = census(),
            tracksSettled = true,
            hasRenderedFirstFrame = false,
        )
        assertEquals(VideoPresentationVerdict.NoVideoTracks, verdict)
        assertFalse(isFatalVideoPresentation(verdict))
    }

    /** Case B. The only fatal one, and the only one that is about a codec. */
    @Test
    fun `video groups with no supported track is fatal`() {
        val verdict = classifyVideoPresentation(
            census = census(groups = 1, tracks = 3, supported = 0, selected = 0),
            tracksSettled = true,
            hasRenderedFirstFrame = false,
        )
        assertEquals(VideoPresentationVerdict.NoSupportedVideoTrack, verdict)
        assertTrue(isFatalVideoPresentation(verdict))
    }

    /** Case C. A selection failure. Failing the source over would hide the bug, not fix it. */
    @Test
    fun `a supported track that was not selected is a selection failure`() {
        val verdict = classifyVideoPresentation(
            census = census(groups = 1, tracks = 4, supported = 2, selected = 0, selectedSupported = 0),
            tracksSettled = true,
            hasRenderedFirstFrame = false,
        )
        assertEquals(VideoPresentationVerdict.NoSelectedVideoTrack, verdict)
        assertFalse(isFatalVideoPresentation(verdict))
    }

    /**
     * Case C again, the shape a group-level check gets wrong: the selector chose a track, but not
     * one the renderers can decode. Nothing here is playable, and it is still not case B - a
     * decodable track exists and was passed over.
     */
    @Test
    fun `a selected track that is not supported is still a selection failure`() {
        val verdict = classifyVideoPresentation(
            census = census(groups = 1, tracks = 2, supported = 1, selected = 1, selectedSupported = 0),
            tracksSettled = true,
            hasRenderedFirstFrame = false,
        )
        assertEquals(VideoPresentationVerdict.NoSelectedVideoTrack, verdict)
        assertFalse(isFatalVideoPresentation(verdict))
    }

    /** Case D. The reported run's most likely shape, and the one a timer would have mislabelled. */
    @Test
    fun `selected and supported with no frame is a presentation failure`() {
        val verdict = classifyVideoPresentation(
            census = census(groups = 1, tracks = 1, supported = 1, selected = 1, selectedSupported = 1),
            tracksSettled = true,
            hasRenderedFirstFrame = false,
        )
        assertEquals(VideoPresentationVerdict.SelectedNotRendering, verdict)
        assertFalse(isFatalVideoPresentation(verdict))
    }

    @Test
    fun `only the codec verdict is fatal`() {
        val fatal = VideoPresentationVerdict.entries.filter(::isFatalVideoPresentation)
        assertEquals(listOf(VideoPresentationVerdict.NoSupportedVideoTrack), fatal)
    }

    @Test
    fun `every verdict has its own log code`() {
        val codes = VideoPresentationVerdict.entries.map(::videoPresentationLogCode)
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.none { it.isBlank() })
    }
}
