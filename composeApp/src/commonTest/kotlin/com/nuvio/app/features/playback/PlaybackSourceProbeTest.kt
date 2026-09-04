package com.nuvio.app.features.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackSourceProbeTest {

    private fun verdict(
        status: Int = 206,
        contentType: String? = "video/mp4",
        total: Long? = null,
        expected: Long? = null,
    ) = PlaybackSourceProbe.verdict(status, contentType, total, expected)

    @Test
    fun `a ranged video reply passes`() {
        assertEquals(PlaybackProbeVerdict.Pass, verdict(total = 3_000_000_000L, expected = 3_100_000_000L))
    }

    @Test
    fun `a refused range is dead rather than merely unjudged`() {
        // The 20-second wait this replaces: the engine is handed a URL that answers 403 and sits
        // on it until the startup watchdog fires, reporting `engine=Unknown duration=0`.
        assertEquals(PlaybackProbeVerdict.Dead("http_403"), verdict(status = 403))
        assertEquals(PlaybackProbeVerdict.Dead("http_500"), verdict(status = 500))
        assertEquals(PlaybackProbeVerdict.Dead("http_404"), verdict(status = 404))
    }

    @Test
    fun `an error page returned with a 200 is dead`() {
        assertEquals(
            PlaybackProbeVerdict.Dead("content_type_text/html"),
            verdict(status = 200, contentType = "text/html; charset=utf-8"),
        )
        assertEquals(
            PlaybackProbeVerdict.Dead("content_type_application/json"),
            verdict(status = 200, contentType = "application/json"),
        )
    }

    @Test
    fun `the content types debrid hosts really send all pass`() {
        for (type in listOf(
            "video/mp4",
            "video/x-matroska",
            "audio/mpeg",
            "application/octet-stream",
            "binary/octet-stream",
            "application/x-mpegurl",
            "application/vnd.apple.mpegurl",
            "application/dash+xml",
        )) {
            assertEquals(PlaybackProbeVerdict.Pass, verdict(contentType = type), type)
        }
    }

    @Test
    fun `a two-minute slate against a claimed remux is a placeholder`() {
        // The Secret Woman, 2026-09-05: the provider's "being prepared" video played and the
        // chain stopped, satisfied.
        val result = verdict(total = 3_000_000L, expected = 20_000_000_000L)
        assertTrue(result is PlaybackProbeVerdict.Placeholder, "got $result")
    }

    @Test
    fun `a release that claimed no size is never called a placeholder`() {
        // Most plugin scrapers and plenty of addons report nothing. Guessing here would refuse
        // working sources, so an absent claim has to pass.
        assertEquals(PlaybackProbeVerdict.Pass, verdict(total = 3_000_000L, expected = null))
    }

    @Test
    fun `a large file is never a placeholder however wrong its claim`() {
        // Above the absolute ceiling the ratio is not consulted at all: a real file that is
        // merely much smaller than advertised is still a real file.
        assertEquals(
            PlaybackProbeVerdict.Pass,
            verdict(total = 900_000_000L, expected = 20_000_000_000L),
        )
    }

    @Test
    fun `an honest size mismatch inside the ratio passes`() {
        assertEquals(
            PlaybackProbeVerdict.Pass,
            verdict(total = 40_000_000L, expected = 300_000_000L),
        )
    }

    @Test
    fun `content range yields the whole file, not the range`() {
        assertEquals(2_952_790_016L, PlaybackSourceProbe.parseContentRangeTotal("bytes 0-1/2952790016"))
        // A server declining to state the total is a legitimate answer and must not read as zero.
        assertEquals(null, PlaybackSourceProbe.parseContentRangeTotal("bytes 0-1/*"))
        assertEquals(null, PlaybackSourceProbe.parseContentRangeTotal(null))
    }

    @Test
    fun `content length is ignored on a 206`() {
        // ⚠ The whole gate turns on this. `Content-Length` on a ranged reply is the length of the
        // *range* - two bytes - so reading it would call every source in existence a placeholder.
        assertEquals(null, PlaybackSourceProbe.totalBytes(206, contentRange = null, contentLength = 2L))
        assertEquals(
            2_952_790_016L,
            PlaybackSourceProbe.totalBytes(206, "bytes 0-1/2952790016", contentLength = 2L),
        )
        // A server that ignored the range and sent the lot does mean its Content-Length.
        assertEquals(500L, PlaybackSourceProbe.totalBytes(200, contentRange = null, contentLength = 500L))
    }

    @Test
    fun `duration plausibility needs both a bad ratio and a short file`() {
        // The observed slate: 2:01 against a feature.
        assertTrue(PlaybackDurationPlausibility.isImplausiblyShort(120_960L, 100))
        // A mis-tagged runtime alone must never abandon a source the user is watching.
        assertFalse(PlaybackDurationPlausibility.isImplausiblyShort(45L * 60_000L, 90))
        // Short, but the runtime agrees - a genuine short film.
        assertFalse(PlaybackDurationPlausibility.isImplausiblyShort(120_960L, 2))
        // Bad ratio, but far too long to be a slate.
        assertFalse(PlaybackDurationPlausibility.isImplausiblyShort(30L * 60_000L, 600))
        // Both unknowns pass.
        assertFalse(PlaybackDurationPlausibility.isImplausiblyShort(120_960L, null))
        assertFalse(PlaybackDurationPlausibility.isImplausiblyShort(0L, 100))
    }

    @Test
    fun `verdict log keys are stable`() {
        assertEquals("pass", PlaybackProbeVerdict.Pass.logKey())
        assertEquals("dead:http_403", PlaybackProbeVerdict.Dead("http_403").logKey())
        assertEquals("placeholder:x", PlaybackProbeVerdict.Placeholder("x").logKey())
    }
}
