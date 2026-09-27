package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The organized on-disk layout (Phase 9 closeout): names, specials, sanitizing, collisions. */
class DownloadFileLayoutTest {

    private fun episode(
        show: String = "tt-mf",
        title: String = "Modern Family",
        season: Int = 1,
        episode: Int = 1,
        episodeTitle: String? = "Pilot",
        id: String = "$show-$season-$episode",
        fileName: String = "Modern Family S01E01 Pilot_k3x.mkv",
    ) = DownloadItem(
        id = id,
        contentType = "series",
        parentMetaId = show,
        parentMetaType = "series",
        videoId = "$show:$season:$episode",
        title = title,
        seasonNumber = season,
        episodeNumber = episode,
        episodeTitle = episodeTitle,
        streamTitle = "s",
        providerName = "p",
        fileName = fileName,
        status = DownloadStatus.Completed,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )

    private fun movie(id: String = "tt-dune2", title: String = "Dune: Part Two", fileName: String = "Dune_Part_Two_k3x.mkv") =
        DownloadItem(
            id = id,
            contentType = "movie",
            parentMetaId = id,
            parentMetaType = "movie",
            videoId = id,
            title = title,
            streamTitle = "s",
            providerName = "p",
            fileName = fileName,
            status = DownloadStatus.Completed,
            createdAtEpochMs = 0L,
            updatedAtEpochMs = 0L,
        )

    private fun target(item: DownloadItem, year: Int? = null, placed: List<DownloadFileLayout.Placed> = emptyList(), exists: Set<String> = emptySet()) =
        DownloadFileLayout.target(item, year, placed) { it in exists }

    @Test
    fun anEpisodeGoesUnderItsShowAndSeason() {
        assertEquals("Modern Family/Season 01/S01E01 - Pilot.mkv", target(episode()))
        assertEquals(
            "Modern Family/Season 06/S06E12 - Farm Strong.mp4",
            target(episode(season = 6, episode = 12, episodeTitle = "Farm Strong", fileName = "x.mp4")),
        )
    }

    @Test
    fun anEpisodeWithoutATitleIsItsCode() {
        assertEquals("Modern Family/Season 01/S01E03.mkv", target(episode(episode = 3, episodeTitle = null)))
    }

    @Test
    fun aFilmGoesUnderItsNameAndYear() {
        assertEquals("Dune - Part Two (2024)/Dune - Part Two.mkv", target(movie(), year = 2024))
        assertEquals("Dune - Part Two/Dune - Part Two.mkv", target(movie()))
    }

    @Test
    fun seasonZeroIsSpecials() {
        assertEquals("Modern Family/Specials/S00E02 - Behind the Scenes.mkv", target(episode(season = 0, episode = 2, episodeTitle = "Behind the Scenes")))
    }

    @Test
    fun unsafeCharactersNeverReachTheDisk() {
        val name = DownloadFileLayout.safeName("""What/If\...? <A|B> "C*": D.""", 80)
        listOf('/', '\\', ':', '*', '?', '"', '<', '>', '|').forEach { assertFalse(it in name, "'$it' in $name") }
        assertEquals("What-If-... A-B 'C' - D", name)
        assertEquals("CON_", DownloadFileLayout.safeName("CON", 80))
        assertEquals("", DownloadFileLayout.safeName("..", 80))
        assertEquals("Line break", DownloadFileLayout.safeName("Line\nbreak", 80))
        assertEquals("Untitled/Season 01/S01E01.mkv", target(episode(title = "???", episodeTitle = "***")))
    }

    @Test
    fun unicodeIsKeptAndLongNamesAreCutCleanly() {
        assertEquals("Café 東京", DownloadFileLayout.safeName("Café 東京", 80))
        val emoji = "a".repeat(79) + "😀"
        val cut = DownloadFileLayout.safeName(emoji, 80)
        assertEquals("a".repeat(79), cut) // never half a surrogate pair
        assertTrue(DownloadFileLayout.safeName("x".repeat(300), 80).length <= 80)
    }

    @Test
    fun anotherTitleWithTheSameNameGetsItsOwnFolder() {
        val original = DownloadFileLayout.Placed("tt-original", "Shogun/Season 01/S01E01 - Anjin.mkv")
        val remake = episode(show = "tt-remake", title = "Shogun", episodeTitle = "Anjin")
        assertEquals("Shogun (2024)/Season 01/S01E01 - Anjin.mkv", target(remake, year = 2024, placed = listOf(original)))
        val tag = DownloadFileLayout.shortTag("tt-remake")
        assertEquals("Shogun [$tag]/Season 01/S01E01 - Anjin.mkv", target(remake, placed = listOf(original)))
        // Case differences do not make two folders on Windows or macOS.
        val lower = DownloadFileLayout.Placed("tt-original", "shogun/Season 01/x.mkv")
        assertEquals("Shogun [$tag]/Season 01/S01E01 - Anjin.mkv", target(remake, placed = listOf(lower)))
    }

    @Test
    fun twoFilmsOfOneNameAreSeparatedByYear() {
        val dune1984 = DownloadFileLayout.Placed("tt-1984", "Dune (1984)/Dune.mkv")
        assertEquals("Dune (2021)/Dune.mkv", target(movie(id = "tt-2021", title = "Dune"), year = 2021, placed = listOf(dune1984)))
    }

    @Test
    fun theSameTitleSharesItsFolder() {
        val first = DownloadFileLayout.Placed("tt-mf", "Modern Family/Season 01/S01E01 - Pilot.mkv")
        assertEquals("Modern Family/Season 01/S01E02 - The Bicycle Thief.mkv", target(episode(episode = 2, episodeTitle = "The Bicycle Thief"), placed = listOf(first)))
    }

    @Test
    fun aTakenFileNameIsNumberedNeverOverwritten() {
        val otherProfile = DownloadFileLayout.Placed("tt-mf", "Modern Family/Season 01/S01E01 - Pilot.mkv")
        assertEquals("Modern Family/Season 01/S01E01 - Pilot (2).mkv", target(episode(id = "second"), placed = listOf(otherProfile)))
        // Something already on disk that the store does not know about is not overwritten either.
        assertEquals(
            "Modern Family/Season 01/S01E01 - Pilot (3).mkv",
            target(episode(id = "third"), placed = listOf(otherProfile), exists = setOf("Modern Family/Season 01/S01E01 - Pilot (2).mkv")),
        )
    }

    @Test
    fun theTargetIsDeterministic() {
        val placed = listOf(DownloadFileLayout.Placed("tt-other", "Modern Family/Season 01/a.mkv"))
        assertEquals(target(episode(), placed = placed), target(episode(), placed = placed))
    }

    @Test
    fun yearsComeFromTheReleaseLine() {
        assertEquals(2009, DownloadFileLayout.yearOf("2009-2020"))
        assertEquals(2024, DownloadFileLayout.yearOf("2024"))
        assertNull(DownloadFileLayout.yearOf("TBA"))
        assertNull(DownloadFileLayout.yearOf(null))
    }
}
