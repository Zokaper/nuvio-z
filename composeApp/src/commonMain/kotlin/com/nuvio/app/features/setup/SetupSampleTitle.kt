package com.nuvio.app.features.setup

import com.nuvio.app.features.home.MetaPreview

/**
 * The example content the wizard's specimens render.
 *
 * ## Why the artwork is fetched rather than bundled
 *
 * Poster and backdrop art is copyrighted. `Zokaper/nuvio-z` is public - it has to be, because
 * the in-app updater reads its releases unauthenticated - and every release ships a signed APK
 * and an MSI. Committing promotional artwork would put third-party images in all three. So the
 * wizard names titles and fetches their artwork at display time, exactly as every other screen
 * in the app does.
 *
 * ## Why this host
 *
 * [artworkHost] needs no API key and no installed addon, and is keyed by IMDb id, so one id
 * yields the poster, the backdrop **and** the logo - which is the whole triple the stage needs.
 * TMDB cannot do this job: `TmdbService.currentApiKey()` returns null until the user enters a
 * personal key, so on a first launch - the only launch that matters here - there is no TMDB
 * access at all.
 *
 * Coil 3 already loads every card's `String?` URL, so nothing about the card composables or
 * the image loader changes to support this.
 *
 * ⚠ **Artwork is decoration and the wizard must not depend on it.** With no network every
 * option still has to be legible and distinguishable: every specimen draws the title's name
 * behind its artwork so a failed load leaves a labelled card rather than a grey box, and
 * `SetupSpecimenBand` paints a token gradient floor so a missing backdrop reads as intentional.
 * Test this with aeroplane mode, not by hoping.
 *
 * ⚠ **Everything here is plain data over `MetaPreview` and `String`.** It deliberately no longer
 * builds a `HomeCatalogSection` or a `MetaDetails`: revision 2's stage needed those to feed the
 * real row and hero composables, and those types are among the ones that diverge between the
 * two repositories. The specimens draw from primitives, so this file can stay identical in both.
 */
object SetupSampleTitle {

    private const val artworkHost = "https://images.metahub.space"

    /** Poster art, 2:3. Used by the poster card previews. */
    fun posterUrl(imdbId: String): String = "$artworkHost/poster/medium/$imdbId/img"

    /**
     * Backdrop art, 16:9. Used by the landscape cards, the hero banner, the details hero and
     * the Continue Watching card - which is why a title with no backdrop is unusable here.
     */
    fun backgroundUrl(imdbId: String): String = "$artworkHost/background/medium/$imdbId/img"

    /**
     * Transparent wordmark. This is the one that makes the *landscape* card look different
     * from the poster card rather than merely wider, so a title without one is a poor sample.
     */
    fun logoUrl(imdbId: String): String = "$artworkHost/logo/medium/$imdbId/img"

    /**
     * A **per-episode** still.
     *
     * ⚠ A different host from [artworkHost], and that is the whole point. `images.metahub.space`
     * is keyed by the *show's* IMDb id, so it has no episode-level images for any title - which
     * is why every row of the episode specimen used to show the same picture.
     * `episodes.metahub.space` is keyed by show/season/episode and is equally keyless.
     *
     * ⚠ **This host has never answered here** - the sandbox blocks metahub, exactly as it does
     * for [artworkHost]. If the stills come back identical on a device, this URL shape is wrong
     * and the fallback below is doing its job silently. Device check.
     *
     * Callers fall back to [backgroundUrl] on failure, which is the same chain the real app
     * uses (`DetailSeriesContent.kt`: `video.thumbnail ?: meta.background ?: meta.poster`). So a
     * dead host degrades this preview to precisely what the app itself shows for a series with
     * no episode artwork.
     */
    fun episodeStillUrl(imdbId: String, season: Int, episode: Int): String =
        "$episodeStillHost/$imdbId/$season/$episode.jpg"

    private const val episodeStillHost = "https://episodes.metahub.space"

    /**
     * The title the details step previews.
     *
     * Breaking Bad rather than the sitcom that was here before: it is one of the best-covered
     * titles on the artwork hosts, so it is the safest bet for per-episode stills actually
     * existing - and the maintainer specifically wanted the episode list to stop looking like
     * the same frame six times.
     */
    const val featuredImdbId: String = "tt0903747"

    /**
     * The row the card and home steps preview.
     *
     * ⚠ **Six entries, and the count matters.** The row has to overflow the stage at every
     * poster width the wizard offers, or making the cards smaller would end in empty space
     * rather than in more artwork and the preview would understate the choice. Six of the
     * widest cards overflow a phone; on a desktop window they fill it without looking sparse.
     *
     * Every one is a well-known title with complete artwork on the host above. The names are
     * shown to the user, so they are spelled the way the shows are.
     *
     * ⚠ **[featuredImdbId] must be first.** The Continue Watching specimen captions its
     * in-progress card with [continueWatchingCaption], which names a specific episode of the
     * featured title - so the first entry has to be the show that episode belongs to, or the
     * card claims one show is playing an episode of another.
     */
    val rowItems: List<MetaPreview> = listOf(
        preview("tt0903747", "Breaking Bad", "2008-2013", listOf("Crime", "Drama", "Thriller")),
        preview("tt0108778", "Friends", "1994-2004", listOf("Comedy", "Romance")),
        preview("tt0944947", "Game of Thrones", "2011-2019", listOf("Action", "Adventure", "Drama")),
        preview("tt1475582", "Sherlock", "2010-2017", listOf("Crime", "Drama", "Mystery")),
        preview("tt0417299", "Avatar: The Last Airbender", "2005-2008", listOf("Animation", "Action")),
        preview("tt2861424", "Rick and Morty", "2013-", listOf("Animation", "Comedy", "Sci-Fi")),
    )

    private fun preview(
        imdbId: String,
        name: String,
        releaseInfo: String,
        genres: List<String>,
    ) = MetaPreview(
        id = imdbId,
        type = "series",
        name = name,
        poster = posterUrl(imdbId),
        // `HomePosterCard` reads `banner` for the landscape mode and `poster` otherwise, and
        // overlays `logo` only in landscape. All three are filled so one item can serve both
        // card shapes without the stage having to know which is current.
        banner = backgroundUrl(imdbId),
        logo = logoUrl(imdbId),
        releaseInfo = releaseInfo,
        genres = genres,
    )

    /** One episode row in the details specimen. */
    data class SampleEpisode(
        val seasonNumber: Int,
        val episodeNumber: Int,
        val title: String,
        val runtime: String,
        val overview: String,
    ) {
        /** The per-episode still. See [episodeStillUrl] for why this is a second host. */
        val stillUrl: String get() = episodeStillUrl(featuredImdbId, seasonNumber, episodeNumber)

        /** What to draw if [stillUrl] does not load. The show's own backdrop, as the app does. */
        val fallbackStillUrl: String get() = backgroundUrl(featuredImdbId)
    }

    /** Enough episodes to show a card style as a list rather than as a single specimen. */
    val episodes: List<SampleEpisode> = listOf(
        SampleEpisode(
            seasonNumber = 5,
            episodeNumber = 14,
            title = "Ozymandias",
            runtime = "48 min",
            overview = "Everything Walt has built comes apart in a single afternoon in the " +
                "desert, and there is no version of it he can talk his way out of.",
        ),
        SampleEpisode(
            seasonNumber = 5,
            episodeNumber = 15,
            title = "Granite State",
            runtime = "53 min",
            overview = "Exile turns out to be its own kind of sentence, served in a cabin " +
                "with nothing to do but wait.",
        ),
        SampleEpisode(
            seasonNumber = 5,
            episodeNumber = 16,
            title = "Felina",
            runtime = "55 min",
            overview = "One last drive back, with a short list and no intention of leaving " +
                "any of it unfinished.",
        ),
    )

    /**
     * How far through the in-progress Continue Watching card is.
     *
     * Roughly a third: far enough along that the progress bar is unmistakably a progress bar,
     * short of the point where `WatchProgressCompletionPercentThreshold` would treat it as
     * finished.
     */
    const val continueWatchingProgress: Float = 22f / 48f

    /** The caption on the in-progress Continue Watching card. */
    const val continueWatchingCaption: String = "S5 E14 · Ozymandias"
}
