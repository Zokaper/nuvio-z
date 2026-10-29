package com.nuvio.app.features.player

import com.nuvio.app.features.watchparty.WatchPartyRepository
import com.nuvio.app.features.watchparty.WatchPartyState
import com.nuvio.app.features.watchparty.WatchPartyStatus
import com.nuvio.app.features.watchparty.matchesPlayback

/** Guest positions belong to the party synchronizer, once the local file has a duration. */
internal fun partyGuestOwnsPosition(
    party: WatchPartyState?,
    viewerProfileId: String?,
    contentId: String,
    videoId: String?,
): Boolean = viewerProfileId != null && party != null && party.status != WatchPartyStatus.ended &&
    party.hostProfileId != viewerProfileId && party.matchesPlayback(contentId, videoId)

internal fun PlayerScreenRuntime.partyGuestOwnsPosition(): Boolean {
    val ui = WatchPartyRepository.uiState.value
    return partyGuestOwnsPosition(ui.party, ui.activeProfileId, parentMetaId, playbackSession.videoId)
}

/** mpv's load-time `start` cannot validate a position against a file it has not opened. */
internal fun partyNativeStartPosition(requestedMs: Long, guestOwnsPosition: Boolean): Long? =
    requestedMs.takeIf { it > 0L && !guestOwnsPosition }
