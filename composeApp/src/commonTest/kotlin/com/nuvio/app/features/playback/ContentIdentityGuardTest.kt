package com.nuvio.app.features.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The content-identity gate, and - more importantly - the set of things it must **not** reject.
 *
 * A guard like this fails dangerously rather than loudly: a false positive discards a good
 * source silently and presents as "no sources found", which is indistinguishable from the
 * problem it was added to fix. So the false-positive table below is the point of this file, and
 * it is deliberately longer than the true-positive one.
 */
class ContentIdentityGuardTest {

    // --- The reported case ---------------------------------------------------------------

    /**
     * ⚠ **The reported case is deliberately NOT rejected any more, and this test pins that.**
     *
     * Both Daredevil entries are genuinely S02E06, so only the year separated them - and a code
     * review showed the year check was unsafe for series: the requested year comes from the
     * show's *first-air* date while the release name carries the year that *episode* shipped.
     * Grey's Anatomy (first air 2005) would have had every correct `…2024.S20E01…` release
     * demoted. That false positive is silent and looks like "the good sources are missing",
     * which is strictly worse than the bug it was catching.
     *
     * The release name printed on the loading screen is what catches this now.
     */
    @Test
    fun `a wrong-show release with matching numbering is no longer rejected on year`() {
        assertNull(
            ContentIdentityGuard.evaluate(
                releaseName = "Daredevil.Born.Again.2026.S02E06.Requiem.2160p.DSNP.WEB-DL.mkv",
                requestedSeason = 2,
                requestedEpisode = 6,
                requestedYear = 2016,
            ),
        )
    }

    /**
     * The false positive that forced the rule above. A correct release of a long-running show,
     * where the show's first-air year and the episode's year are two decades apart.
     */
    @Test
    fun `a long-running show's current season is not rejected on year`() {
        assertNull(
            ContentIdentityGuard.evaluate(
                releaseName = "Greys.Anatomy.2024.S20E01.2160p.WEB-DL.mkv",
                requestedSeason = 20,
                requestedEpisode = 1,
                requestedYear = 2005,
            ),
        )
    }

    /** For a film the two years describe the same thing, so the check still stands there. */
    @Test
    fun `a film with a disagreeing year is still rejected`() {
        assertEquals(
            ContentIdentityGuard.Rejection.WRONG_YEAR,
            ContentIdentityGuard.evaluate(
                releaseName = "The.Thing.1982.2160p.BluRay.REMUX.mkv",
                requestedSeason = null,
                requestedEpisode = null,
                requestedYear = 2011,
            ),
        )
    }

    /** `1920x1080` is a resolution, not a year. It used to parse as 1920 and reject outright. */
    @Test
    fun `a WxH resolution token is not read as a year`() {
        assertNull(ContentIdentityGuard.parseYear("Film.Name.1920x1080.x264.mkv"))
        assertNull(
            ContentIdentityGuard.evaluate(
                releaseName = "Film.Name.1920x1080.x264.mkv",
                requestedSeason = null,
                requestedEpisode = null,
                requestedYear = 2019,
            ),
        )
    }

    /**
     * A season pack contains the requested episode, and in a mixed list these are exactly the
     * releases a debrid user is most likely to have cached already.
     */
    @Test
    fun `a season pack covering the requested episode passes`() {
        assertNull(ContentIdentityGuard.evaluate("Daredevil.S02E01-E13.COMPLETE.1080p.mkv", 2, 6))
        assertNull(ContentIdentityGuard.evaluate("Show.S02E01-13.1080p.mkv", 2, 6))
    }

    @Test
    fun `a season pack that does not cover the requested episode is still rejected`() {
        assertEquals(
            ContentIdentityGuard.Rejection.WRONG_EPISODE,
            ContentIdentityGuard.evaluate("Show.S02E01-E05.1080p.mkv", 2, 6),
        )
    }

    @Test
    fun `a pack from the wrong season is rejected on the season`() {
        assertEquals(
            ContentIdentityGuard.Rejection.WRONG_SEASON,
            ContentIdentityGuard.evaluate("Show.S03E01-E13.1080p.mkv", 2, 6),
        )
    }

    @Test
    fun `the correct Daredevil release passes the same request`() {
        assertNull(
            ContentIdentityGuard.evaluate(
                releaseName = "Daredevil.S02E06.Regrets.Only.2160p.WEB-DL.DTS-HD.MA-5.1.HDR.HEVC.mkv",
                requestedSeason = 2,
                requestedEpisode = 6,
                requestedYear = 2016,
            ),
        )
    }

    @Test
    fun `a wrong episode of the right show is rejected`() {
        assertEquals(
            ContentIdentityGuard.Rejection.WRONG_EPISODE,
            ContentIdentityGuard.evaluate("Show.S02E09.1080p.WEB-DL.mkv", 2, 6),
        )
    }

    @Test
    fun `a wrong season is rejected`() {
        assertEquals(
            ContentIdentityGuard.Rejection.WRONG_SEASON,
            ContentIdentityGuard.evaluate("Show.S03E06.1080p.WEB-DL.mkv", 2, 6),
        )
    }

    @Test
    fun `the 2x06 form is understood too`() {
        assertEquals(
            ContentIdentityGuard.Rejection.WRONG_EPISODE,
            ContentIdentityGuard.evaluate("Show 2x09 1080p WEB-DL.mkv", 2, 6),
        )
    }

    // --- The false-positive set: everything below must pass -------------------------------

    /** No name to read is not evidence of anything. */
    @Test
    fun `an absent or blank release name passes`() {
        assertNull(ContentIdentityGuard.evaluate(null, 2, 6, 2016))
        assertNull(ContentIdentityGuard.evaluate("   ", 2, 6, 2016))
    }

    /**
     * A release that names no season or episode is the ordinary shape for a film, and common
     * for badly-tagged series releases. Silence must not be read as disagreement.
     */
    @Test
    fun `a name with no season or episode passes`() {
        assertNull(ContentIdentityGuard.evaluate("Some.Release.2160p.WEB-DL.mkv", 2, 6))
    }

    /** Foreign and transliterated titles carry no signal this guard reads. */
    @Test
    fun `a foreign title with the right numbering passes`() {
        assertNull(ContentIdentityGuard.evaluate("Sorvegliato.Speciale.S02E06.ITA.1080p.mkv", 2, 6))
    }

    /** A movie request has no season or episode to compare, so numbering never applies. */
    @Test
    fun `a movie request ignores numbering entirely`() {
        assertNull(
            ContentIdentityGuard.evaluate("Film.Name.2019.2160p.BluRay.REMUX.mkv", null, null),
        )
    }

    /** A release with no year cannot disagree about one. */
    @Test
    fun `a name with no year passes a year check`() {
        assertNull(
            ContentIdentityGuard.evaluate("Show.S02E06.2160p.WEB-DL.mkv", 2, 6, 2016),
        )
    }

    /** We do not know the requested year for most launches, and that must be harmless. */
    @Test
    fun `an unknown requested year passes`() {
        assertNull(
            ContentIdentityGuard.evaluate("Show.Born.Again.2026.S02E06.mkv", 2, 6, null),
        )
    }

    /**
     * A December air date published in January is the ordinary case, not a wrong show. The
     * tolerance exists so the guard does not become a seasonal nuisance.
     */
    @Test
    fun `a year one out is within tolerance`() {
        assertNull(ContentIdentityGuard.evaluate("Film.2017.mkv", null, null, 2016))
        assertNull(ContentIdentityGuard.evaluate("Film.2015.mkv", null, null, 2016))
    }

    /**
     * `2160p` and `x265` must never be read as years. This is the exact reason the scan takes
     * the *first* plausible year rather than the last.
     */
    @Test
    fun `encoder tags are not mistaken for years`() {
        assertNull(ContentIdentityGuard.parseYear("Show.S02E06.2160p.x265.DDP5.1.mkv"))
    }

    @Test
    fun `the title year is preferred over anything later in the name`() {
        assertEquals(2016, ContentIdentityGuard.parseYear("Show.2016.S02E06.2160p.x265.mkv"))
    }

    /** A three-digit absolute episode number is real, and common for anime. */
    @Test
    fun `a three digit episode number parses`() {
        assertEquals(1 to 105, ContentIdentityGuard.parseSeasonEpisode("Anime.S01E105.1080p.mkv"))
    }

    @Test
    fun `an unparseable name yields no numbering rather than a wrong one`() {
        assertNull(ContentIdentityGuard.parseSeasonEpisode("just some words here.mkv"))
    }
}
