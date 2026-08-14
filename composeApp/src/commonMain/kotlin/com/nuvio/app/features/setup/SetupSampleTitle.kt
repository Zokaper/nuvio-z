package com.nuvio.app.features.setup

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.watchprogress.ContinueWatchingItem

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
 * ⚠ **Most of this is plain data over `MetaPreview` and `String`**, because the step specimens draw
 * from primitives and take every setting as a parameter. The three builders at the bottom are the
 * exception: revision 6's Welcome step shows a **real** still of the home screen, so it needs the
 * types the shipped `HomeHeroSection` / `HomeContinueWatchingSection` / `HomeCatalogRowSection`
 * actually take.
 *
 * ⚠ **Those three types do not diverge between the repositories** - `HomeCatalogSection`,
 * `CatalogTarget` and `ContinueWatchingItem` are the same declarations in the same packages in
 * both - so this file stays byte-identical. `SetupHomeStill.kt`, which *calls* the composables,
 * is the one that cannot; see its header.
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

    /**
     * Names for the details specimen's cast row.
     *
     * ⚠ **Names only, because there is no artwork to fetch.** `images.metahub.space` is keyed by
     * title, not by person, so a first launch has no way to reach a headshot at all - which is
     * exactly the state `DetailCastSection` already handles by drawing [initials] in a
     * `surfaceVariant` circle. The specimen shows the app's own no-photo state rather than
     * inventing a placeholder, so it cannot be wrong about it.
     *
     * These are the credited leads of [featuredImdbId]. Real names rather than "Cast 1", because
     * the row is showing what a cast row looks like and a row of placeholders does not.
     */
    val castNames: List<String> = listOf(
        "Bryan Cranston",
        "Aaron Paul",
        "Anna Gunn",
        "Dean Norris",
        "Betsy Brandt",
        "Bob Odenkirk",
    )

    /**
     * First and last initial, or the first two characters of a single-word name.
     *
     * ⚠ Mirrors the private `initials()` in `features/details/components/DetailCastSection.kt`.
     * If that changes, change this - the whole point of the cast specimen is that it draws the
     * same thing the real section draws when it has no photo.
     */
    fun initials(name: String): String {
        val parts = name.trim().split(" ").filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> ""
            parts.size == 1 -> parts.first().take(2).uppercase()
            else -> "${parts.first().first()}${parts.last().first()}".uppercase()
        }
    }

    // --- what the Welcome still feeds the real home composables ----------------------------
    //
    // Everything above is drawn by the step specimens, which take primitives. Everything below
    // exists only for `SetupHomeStill`, which calls the shipped home sections and therefore has
    // to hand them the types they declare.

    /**
     * [rowItems] wrapped as a catalog row for `HomeCatalogRowSection`.
     *
     * [title] arrives already resolved rather than being read from `Res.string` here, because
     * this object is plain data and stays out of composition - the same reason it holds no
     * `StringResource`. The still passes no `onViewAllClick`, so it grows no affordance that
     * does nothing.
     */
    fun catalogSection(title: String): HomeCatalogSection = HomeCatalogSection(
        key = "setup-home-still",
        title = title,
        subtitle = "",
        addonName = "",
        target = CatalogTarget.Addon(
            manifestUrl = "",
            contentType = "series",
            catalogId = "setup-home-still",
        ),
        items = rowItems,
    )

    /**
     * A second row, so the still reads as a home screen rather than as one shelf.
     *
     * ⚠ Its [HomeCatalogSection.key] must differ. `NuvioShelfSection` dedupes by key, so two rows
     * sharing one collapse into a single item.
     */
    fun secondCatalogSection(title: String): HomeCatalogSection = catalogSection(title).copy(
        key = "setup-home-still-2",
        title = "",
        items = rowItems.reversed(),
    )

    /**
     * Two Continue Watching entries for the still: one in progress, one queued up next.
     *
     * Two rather than one because a single card leaves the row looking like a mistake at the
     * width the real section lays out at, and because the second one being **unstarted** is what
     * makes the row read as a queue rather than as a second catalog shelf.
     */
    val continueWatching: List<ContinueWatchingItem> = listOf(
        continueWatchingItem(
            item = rowItems[0],
            season = 5,
            episode = 14,
            episodeTitle = "Ozymandias",
            runtimeMinutes = 48,
            watchedMinutes = 22,
        ),
        continueWatchingItem(
            item = rowItems[1],
            season = 3,
            episode = 9,
            episodeTitle = "The One With The Football",
            runtimeMinutes = 22,
            watchedMinutes = 0,
            isNextUp = true,
        ),
    )

    /**
     * ⚠ **`episodeThumbnail` is the field the section actually reads**, not `imageUrl`.
     * `continueWatchingArtworkUrl` puts `imageUrl` last in every one of its four branches, so an
     * item that carried the still there would show the show's own artwork whatever the "use
     * episode stills" toggle said - and the toggle would look broken on the very row it governs.
     * `poster` and `background` are filled too, because that toggle's *off* state reads them.
     */
    private fun continueWatchingItem(
        item: MetaPreview,
        season: Int,
        episode: Int,
        episodeTitle: String,
        runtimeMinutes: Int,
        watchedMinutes: Int,
        isNextUp: Boolean = false,
    ): ContinueWatchingItem = ContinueWatchingItem(
        parentMetaId = item.id,
        parentMetaType = "series",
        videoId = "${item.id}:$season:$episode",
        title = item.name,
        subtitle = "S$season E$episode · $episodeTitle",
        imageUrl = item.banner,
        logo = item.logo,
        poster = item.poster,
        background = item.banner,
        seasonNumber = season,
        episodeNumber = episode,
        episodeTitle = episodeTitle,
        episodeThumbnail = episodeStillUrl(item.id, season, episode),
        isNextUp = isNextUp,
        resumePositionMs = watchedMinutes * 60 * 1000L,
        durationMs = runtimeMinutes * 60 * 1000L,
        progressFraction = watchedMinutes.toFloat() / runtimeMinutes.toFloat(),
    )
}
