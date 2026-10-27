package com.nuvio.app.features.player

import com.nuvio.app.features.watchparty.PartySourceRealizer
import com.nuvio.app.features.watchparty.WatchPartyState
import com.nuvio.app.features.watchparty.partyRealizationCompletedByLaunch
import com.nuvio.app.features.watchparty.partyLocalSourceChoiceForLaunch
import com.nuvio.app.features.watchparty.PartySourceDescriptorV2
import com.nuvio.app.features.watchparty.WatchPartyRepository
import com.nuvio.app.features.watchparty.toPartySourceDescriptor
import com.nuvio.app.features.streams.StreamItem

internal fun initialPartySourceChoice(args: PlayerScreenArgs) = partyLocalSourceChoiceForLaunch(
    WatchPartyRepository.uiState.value.party,
    args.parentMetaId, args.videoId, args.partySourceDescriptor,
    userSelectedSource = !args.automaticSourceSelection,
)

internal fun PlayerScreenRuntime.recordLocalPartySourceChoice(stream: StreamItem) {
    partyPositionUnreachable = false
    partyLocalSourceChoice = partyLocalSourceChoiceForLaunch(
        WatchPartyRepository.uiState.value.party,
        parentMetaId, playbackSession.videoId, stream.toPartySourceDescriptor(),
        userSelectedSource = true,
    )
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
