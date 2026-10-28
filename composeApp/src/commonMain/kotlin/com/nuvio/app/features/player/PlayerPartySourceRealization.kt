package com.nuvio.app.features.player

import com.nuvio.app.features.watchparty.PartySourceRealizer
import com.nuvio.app.features.watchparty.WatchPartyState
import com.nuvio.app.features.watchparty.partyRealizationCompletedByLaunch
import com.nuvio.app.features.watchparty.partyLocalSourceChoiceForLaunch
import com.nuvio.app.features.watchparty.PartySourceDescriptorV2
import com.nuvio.app.features.watchparty.WatchPartyRepository
import com.nuvio.app.features.watchparty.toPartySourceDescriptor
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.watchparty.WatchPartyStatus
import com.nuvio.app.features.watchparty.matchesPlayback

internal fun initialPartySourceChoice(args: PlayerScreenArgs) = partyLocalSourceChoiceForLaunch(
    WatchPartyRepository.uiState.value.party,
    args.parentMetaId, args.videoId, args.partySourceDescriptor,
    userSelectedSource = !args.automaticSourceSelection,
)

internal fun PlayerScreenRuntime.recordLocalPartySourceChoice(stream: StreamItem, videoId: String = playbackSession.videoId) {
    partyPositionUnreachable = false
    partyLocalSourceChoice = partyLocalSourceChoiceForLaunch(
        WatchPartyRepository.uiState.value.party,
        parentMetaId, videoId, stream.toPartySourceDescriptor(),
        userSelectedSource = true,
    )
}

internal fun partyEpisodeForSourceChoice(
    party: WatchPartyState?,
    contentId: String,
    localVideoId: String?,
    knownEpisodes: List<MetaVideo>,
): MetaVideo? {
    if (party == null || party.status == WatchPartyStatus.ended || party.content.contentId != contentId ||
        party.matchesPlayback(contentId, localVideoId) || party.content.season == null || party.content.episode == null
    ) return null
    return party.content.toPartyEpisodeVideo(knownEpisodes)
}

internal fun PlayerScreenRuntime.openPendingPartyEpisodeSources(): Boolean {
    val ui = WatchPartyRepository.uiState.value
    if (ui.party?.hostProfileId == ui.activeProfileId) return false
    val episode = partyEpisodeForSourceChoice(
        ui.party, parentMetaId, playbackSession.videoId, playerMetaVideos,
    ) ?: return false
    openEpisodeSourceList(episode)
    return true
}

internal fun PlayerScreenRuntime.switchToUserSelectedEpisodeSource(stream: StreamItem, episode: MetaVideo) {
    markSourceUserSelected()
    if (userSelectedSourceAwaitsP2pConsent(stream)) {
        pendingP2pSwitch = PendingPlayerP2pSwitch(stream, episode, isAutoPlay = false, userSelected = true)
        return
    }
    recordLocalPartySourceChoice(stream, episode.id)
    switchToEpisodeStream(stream, episode)
}

internal fun PlayerScreenRuntime.switchToUserSelectedEpisodeSourceAfterP2pConsent(stream: StreamItem, episode: MetaVideo) {
    markSourceUserSelected()
    recordLocalPartySourceChoice(stream, episode.id)
    switchToP2pEpisodeStream(stream, episode)
}

/** Local link preparation may enrich a filename; that does not undo the person's source choice. */
internal fun PlayerScreenRuntime.continuePartySourceChoice(
    original: PartySourceDescriptorV2?,
    resolved: PartySourceDescriptorV2?,
) {
    val choice = partyLocalSourceChoice ?: return
    partyLocalSourceChoice = choice.resolvedFor(WatchPartyRepository.uiState.value.party, original, resolved)
}

internal fun PlayerScreenRuntime.switchToResolvedSource(original: StreamItem, resolved: StreamItem) {
    continuePartySourceChoice(original.toPartySourceDescriptor(), resolved.toPartySourceDescriptor())
    switchToSource(resolved)
}

internal fun PlayerScreenRuntime.switchToResolvedEpisodeSource(
    original: StreamItem,
    resolved: StreamItem,
    episode: MetaVideo,
    serverResumeMs: Long? = null,
) {
    continuePartySourceChoice(original.toPartySourceDescriptor(), resolved.toPartySourceDescriptor())
    switchToEpisodeStream(resolved, episode, serverResumeMs)
}

/** In-place source/episode changes must complete realization just as a new PlayerRoute does. */
internal fun PlayerScreenRuntime.retainCurrentPartySource(party: WatchPartyState?) {
    val key = partyRealizationCompletedByLaunch(
        party, parentMetaId, playbackSession.videoId, activePartySourceDescriptor,
        userSelectedSource = partyLocalSourceChoice?.appliesTo(party, activePartySourceDescriptor) == true,
    ) ?: return
    val previous = args.launchId?.let(PlayerLaunchStore::get) ?: return
    val launch = previous.copy(
        sourceUrl = activeSourceUrl,
        sourceAudioUrl = activeSourceAudioUrl,
        sourceHeaders = activeSourceHeaders,
        sourceResponseHeaders = activeSourceResponseHeaders,
        externalSubtitles = externalSubtitles,
        streamType = activeStreamType,
        streamTitle = activeStreamTitle,
        streamSubtitle = activeStreamSubtitle,
        providerName = activeProviderName,
        providerAddonId = activeProviderAddonId,
        videoId = playbackSession.videoId,
        seasonNumber = activeSeasonNumber,
        episodeNumber = activeEpisodeNumber,
        episodeTitle = activeEpisodeTitle,
        episodeThumbnail = activeEpisodeThumbnail,
        torrentInfoHash = activeTorrentInfoHash,
        torrentFileIdx = activeTorrentFileIdx,
        torrentFilename = activeTorrentFilename,
        torrentTrackers = activeTorrentTrackers,
        initialPositionMs = playbackSnapshot.positionMs,
        initialProgressFraction = null,
        partySourceDescriptor = activePartySourceDescriptor,
        sourceFilename = activeSourceFilename,
        sourceFacts = activeSourceFacts,
        stream = activeStream,
        autoPickedWithFailureChain = activeSourceAutoPicked,
    )
    val retained = PartySourceRealizer.reusable(key)
    if (retained != null && retained.sourceUrl == launch.sourceUrl && retained.videoId == launch.videoId &&
        retained.partySourceDescriptor == launch.partySourceDescriptor
    ) return
    PartySourceRealizer.retain(key, launch)
    PartySourceRealizer.claimAutomaticLaunch(key)
}
