package com.nuvio.app.features.downloads

/**
 * What pressing Play does when a download may be involved. Import-free, so the rule is tested
 * outside Gradle with the rest of the pure download logic.
 *
 * Phase 9 found that a "downloaded" title was not reliably playable offline, and a large part of
 * why was here: every path that failed to match a local file fell through to the network source
 * list, which on a plane can only fail - and a tap on a Downloads row whose file had gone did
 * nothing at all. The invariant this protects is the offline half of "Completed means playable":
 * **with no connection, a play request either opens the local file or says plainly why it
 * cannot. It never reaches for the network.**
 */
enum class LocalPlaybackDecision {
    /** Open the downloaded file. */
    PlayLocal,

    /** Nothing local applies and there is a connection: the normal source list or mode. */
    OpenSources,

    /** Offline, and this episode or film was never downloaded (or is not finished). */
    ExplainNotDownloadedOffline,

    /** A finished download exists, but its file is gone or unreadable. */
    ExplainFileMissing,
}

object LocalPlaybackPolicy {
    fun decide(
        manualSelection: Boolean,
        hasCompletedDownload: Boolean,
        localFileAvailable: Boolean,
        offline: Boolean,
    ): LocalPlaybackDecision {
        val local = hasCompletedDownload && localFileAvailable
        return when {
            // "Choose a source" means the network list - unless the network is gone, when the
            // downloaded file is the only source there is.
            local && (!manualSelection || offline) -> LocalPlaybackDecision.PlayLocal
            !offline -> LocalPlaybackDecision.OpenSources
            hasCompletedDownload -> LocalPlaybackDecision.ExplainFileMissing
            else -> LocalPlaybackDecision.ExplainNotDownloadedOffline
        }
    }

    /**
     * The downloaded episodes an offline player may treat as its episode list, as
     * `(season, episode)` pairs in order.
     *
     * Offline after a restart the player has no episode list at all - metadata is fetched, and
     * cached in memory only - so there was no next-episode card and no autoplay. Built from the
     * downloads instead, autoplay continues through downloaded episodes as decided at the Phase 9
     * opening, **and stops at a gap** rather than silently skipping an episode the user does not
     * have: everything downloaded before the current episode is kept (the episode panel lists
     * it), but after it only the unbroken run - the next episode, or episode 1 of the next season.
     */
    fun offlineEpisodeRun(
        downloaded: List<Pair<Int, Int>>,
        currentSeason: Int?,
        currentEpisode: Int?,
    ): List<Pair<Int, Int>> {
        val ordered = downloaded.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        if (currentSeason == null || currentEpisode == null) return ordered
        val current = currentSeason to currentEpisode
        val before = ordered.filter { it.first < currentSeason || (it.first == currentSeason && it.second < currentEpisode) }
        val available = ordered.toSet()
        val run = mutableListOf<Pair<Int, Int>>()
        if (current in available) run += current
        var cursor = current
        while (true) {
            val sameSeason = cursor.first to cursor.second + 1
            val nextSeason = cursor.first + 1 to 1
            cursor = when {
                sameSeason in available -> sameSeason
                // A season boundary counts as unbroken only when nothing later in this season
                // was downloaded - otherwise the missing episode is a gap inside the season.
                nextSeason in available && ordered.none { it.first == cursor.first && it.second > cursor.second } ->
                    nextSeason
                else -> break
            }
            run += cursor
        }
        return before + run
    }

    /**
     * Whether a player source is a file on this device rather than a network URL.
     *
     * A downloaded file that fails to decode used to surface the engine's own words - on desktop
     * *"The connection to the source was lost"* - which on a plane reads as "you need internet",
     * the one thing that cannot be true of a local file.
     */
    fun isLocalSource(url: String?): Boolean {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return false
        if (value.startsWith("file:", ignoreCase = true)) return true
        if (value.startsWith("/")) return true
        // A Windows absolute path: a drive letter, a colon, a separator.
        return value.length >= 3 && value[0].isLetter() && value[1] == ':' && (value[2] == '\\' || value[2] == '/')
    }
}
