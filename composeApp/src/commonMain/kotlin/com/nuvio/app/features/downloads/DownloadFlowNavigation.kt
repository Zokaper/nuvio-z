package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamLaunch

/**
 * The stream-list launch for a manual download pick. `downloadIntent` makes a tap enqueue the
 * source, and `manualSelection` keeps the list from auto-playing - together they are why a
 * download entry point can never open the player (Phase 9, plan section 4.3).
 */
fun manualDownloadStreamLaunch(
    profileId: Int,
    title: DownloadTitleRef,
    target: DownloadTarget,
): StreamLaunch = StreamLaunch(
    profileId = profileId,
    type = target.contentType,
    videoId = target.videoId,
    parentMetaId = title.parentMetaId,
    parentMetaType = title.parentMetaType,
    title = title.title,
    logo = title.logo,
    poster = title.poster,
    background = title.background,
    seasonNumber = target.season,
    episodeNumber = target.episode,
    episodeTitle = target.title.takeIf { target.isEpisode },
    episodeThumbnail = target.thumbnail,
    runtimeMinutes = target.runtimeMinutes,
    manualSelection = true,
    startFromBeginning = false,
    downloadIntent = true,
)
