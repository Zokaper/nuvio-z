package com.nuvio.app.features.home.components

import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle

/** Neutral title data shared by Continue Watching-derived activity presentations. */
internal data class TitlePresentation(
    val title: String,
    val poster: String? = null,
    val background: String? = null,
    val episodeThumbnail: String? = null,
    val fallbackArtwork: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val progress: Float? = null,
)

internal fun TitlePresentation.artwork(
    style: ContinueWatchingSectionStyle,
    useEpisodeThumbnails: Boolean,
): String? = when (style) {
    ContinueWatchingSectionStyle.Poster -> firstTitleArtwork(poster, background, episodeThumbnail, fallbackArtwork)
    ContinueWatchingSectionStyle.Card,
    ContinueWatchingSectionStyle.Wide -> if (useEpisodeThumbnails) {
        firstTitleArtwork(episodeThumbnail, background, poster, fallbackArtwork)
    } else {
        firstTitleArtwork(background, poster, episodeThumbnail, fallbackArtwork)
    }
}

private fun firstTitleArtwork(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }?.trim()
