package com.nuvio.app.features.player

data class ExternalPlayerApp(
    val id: String,
    val name: String,
)

data class SubtitleInput(
    val url: String,
    val name: String,
    val lang: String,
)

data class ExternalPlayerPlaybackRequest(
    val sourceUrl: String,
    val title: String,
    val streamTitle: String? = null,
    val sourceHeaders: Map<String, String> = emptyMap(),
    val resumePositionMs: Long = 0L,
    val subtitles: List<SubtitleInput>? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    // JSON array of intro/outro skip segments, passed to players that support auto-skipping.
    val skipSegmentsJson: String? = null,
) {
    /**
     * Builds a display title for external players.
     * For series: "Show Name - S02E05" or "Show Name - S02E05 - Episode Title"
     * For movies: just the content name (title).
     */
    fun buildPlayerTitle(includeEpisodeTitle: Boolean = false): String {
        if (season == null || episode == null) return title
        val seasonEp = "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
        return if (includeEpisodeTitle && !episodeTitle.isNullOrBlank()) {
            "$title - $seasonEp - $episodeTitle"
        } else {
            "$title - $seasonEp"
        }
    }
}

enum class ExternalPlayerOpenResult {
    Opened,
    NotConfigured,
    NoPlayerAvailable,
    Failed,
}

/**
 * What happened when playback was handed to an external player.
 *
 * ⚠ **A `Boolean` was not enough, and the difference is a real bug.** "The player refused this
 * source" and "no external player is configured" both used to answer `false`, so an automatic
 * mode treated a *configuration* problem as a *source* problem: it stepped to the next
 * candidate, hit the identical failure, and burned the whole retry budget - toasting "external
 * player not configured" three times and finally blaming three innocent sources.
 */
enum class ExternalPlaybackOutcome {
    /** Handed off. */
    Opened,

    /** The player exists and would not take this source. Trying the next candidate is sensible. */
    SourceRejected,

    /**
     * No player is configured, or the intent could not be built at all.
     *
     * **Not retryable**: every candidate will fail the same way, so the chain must not be spent
     * on it. The user has already been told what is wrong by the toast this produces.
     */
    PlayerUnavailable,
}

sealed interface ExternalPlayerIntentResult {
    data class Success(val intent: Any) : ExternalPlayerIntentResult
    data object NotConfigured : ExternalPlayerIntentResult
    data object Failed : ExternalPlayerIntentResult
}

internal expect object ExternalPlayerPlatform {
    fun defaultPlayerId(): String?
    fun availablePlayers(): List<ExternalPlayerApp>
    fun open(
        request: ExternalPlayerPlaybackRequest,
        playerId: String?,
    ): ExternalPlayerOpenResult
    fun buildIntent(
        request: ExternalPlayerPlaybackRequest,
        playerId: String?,
    ): ExternalPlayerIntentResult
}
