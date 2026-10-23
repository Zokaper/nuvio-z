package com.nuvio.app.features.social

/**
 * Whether the social surface is showing something a working session would replace.
 *
 * [SocialRepository.activate] runs once per profile. When it ran while the official session was still
 * loading or refreshing, or with the network down, it left the feed on its cache with an error and no
 * Realtime channel - and nothing asked again, so the Social tab told a signed-in user to sign in until
 * the app was restarted. This is the test for "activation did not finish"; it must stay false for a
 * healthy surface, because every trigger that consults it (the official session returning, the network
 * returning, the screen being opened) would otherwise refetch the feed for nothing.
 *
 * [capabilitiesLoaded] is separate from [SocialCapabilities.socialEnabled] because a failed capabilities
 * call falls back to the defaults, which read exactly like a backend with social switched off.
 */
internal fun socialNeedsRecovery(
    state: SocialUiState,
    capabilitiesLoaded: Boolean,
    realtimeOpen: Boolean,
): Boolean {
    if (state.activeProfileId == null || state.isLoading) return false
    if (!capabilitiesLoaded) return true
    if (!state.capabilities.socialEnabled) return false
    return state.errorMessage != null || state.isOfflineCache || !realtimeOpen
}

/**
 * Whether the open Social channel has just come back from being down, which is when to fetch by hand.
 *
 * A broadcast sent while the socket or the channel is down is gone, and the channel coming back does
 * not replay it. On 2026-10-23 a Watch Together invite was created at 13:37:16.8, inside the phone's
 * socket outage (heartbeat timeout 13:37:10.6, reconnected 13:37:18.3). The keep-alive's next tick
 * found the channel subscribed again, so nothing fetched, and the invite only appeared when the Social
 * tab was opened a minute later. The first time the channel goes live is not a return:
 * `openRealtime` fetches after subscribing.
 */
internal data class SocialChannelLiveness(val everLive: Boolean = false, val missed: Boolean = false) {
    /** The next state, and true when this observation is the channel coming back. */
    fun observe(socketConnected: Boolean, channelSubscribed: Boolean): Pair<SocialChannelLiveness, Boolean> =
        if (socketConnected && channelSubscribed) {
            SocialChannelLiveness(everLive = true, missed = false) to missed
        } else {
            copy(missed = everLive) to false
        }
}
