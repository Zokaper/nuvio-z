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
     * Captured from a real profile: asking for Daredevil S2E6 returns Born Again releases
     * ranked *above* the correct ones. Both are genuinely S02E06, so season and episode agree -
     * only the year separates them, which is why the year signal earns its place.
     */
    @Test
    fun `a Born Again release is rejected for a Daredevil season two request`() {
        assertEquals(
            ContentIdentityGuard.Rejection.WRONG_YEAR,
            ContentIdentityGuard.evaluate(
                releaseName = "Daredevil.Born.Again.2026.S02E06.Requiem.2160p.DSNP.WEB-DL.mkv",
                requestedSeason = 2,
                requestedEpisode = 6,
                requestedYear = 2016,
            ),
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
        assertNull(ContentIdentityGuard.evaluate("Show.2017.S02E06.mkv", 2, 6, 2016))
        assertNull(ContentIdentityGuard.evaluate("Show.2015.S02E06.mkv", 2, 6, 2016))
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
