package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaVideo

/**
 * A player episode list built from what is on disk, for when the real one cannot be fetched.
 *
 * The rule for which episodes belong is [LocalPlaybackPolicy.offlineEpisodeRun]; this only maps
 * finished downloads onto the [MetaVideo]s the player's next-episode logic already understands,
 * so offline autoplay reuses that logic - and its "a downloaded next episode plays locally"
 * branch - rather than growing a second one. `released` is left null, which the player reads as
 * "has aired".
 */
object OfflineEpisodeList {
    fun fromDownloads(
        items: List<DownloadItem>,
        parentMetaId: String,
        currentSeason: Int?,
        currentEpisode: Int?,
    ): List<MetaVideo> {
        val byEpisode = items
            .filter {
                it.parentMetaId == parentMetaId.trim() &&
                    it.isEpisode &&
                    it.status == DownloadStatus.Completed
            }
            .associateBy { it.seasonNumber!! to it.episodeNumber!! }
        return LocalPlaybackPolicy.offlineEpisodeRun(
            downloaded = byEpisode.keys.toList(),
            currentSeason = currentSeason,
            currentEpisode = currentEpisode,
        ).mapNotNull { key ->
            val item = byEpisode[key] ?: return@mapNotNull null
            MetaVideo(
                id = item.videoId,
                title = item.episodeTitle.orEmpty(),
                thumbnail = item.episodeThumbnail,
                season = key.first,
                episode = key.second,
            )
        }
    }
}
