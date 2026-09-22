package com.nuvio.app.features.player

import com.nuvio.app.features.details.MetaVideo

/** What the player's opening surface draws: the backdrop, the centred logo, and the text fallback. */
internal data class PlayerOpeningPresentation(
    val artwork: String?,
    val logo: String?,
    val title: String,
)

/**
 * The opening surface's identity, for the first launch and for an in-player next episode alike.
 *
 * ⚠ **The logo is the show's, whichever episode is starting.** All three call sites used to spell
 * `logo = if (startingEpisode != null) null else logo` and `title = startingEpisode?.title ?: title`,
 * so every next episode - ordinary playback and Watch Together, manual and automatic - dropped the
 * logo artwork and printed the *episode* title as plain text on the loading screen, while a first
 * launch of the very same episode drew the centred logo. That is hardware Bug 4 (2026-09-15), and it
 * was never a party bug: the party path only inherited it.
 *
 * The logo and the fallback title are the canonical content identity a normal launch hands
 * `PlaybackLoadingController` (`launch.logo`, `launch.title`), so a next episode now draws the same
 * thing. What stays episode-specific is what always was: the backdrop prefers the starting
 * episode's still, and the "starting next episode" message the caller supplies carries the episode.
 * One function rather than three copies, because three copies is how they came to agree on the
 * wrong answer.
 */
internal fun playerOpeningPresentation(
    showLogo: String?,
    showTitle: String,
    background: String?,
    poster: String?,
    startingEpisode: MetaVideo?,
): PlayerOpeningPresentation = PlayerOpeningPresentation(
    artwork = startingEpisode?.thumbnail?.takeIf { it.isNotBlank() } ?: background ?: poster,
    logo = showLogo?.takeIf { it.isNotBlank() },
    title = showTitle,
)
