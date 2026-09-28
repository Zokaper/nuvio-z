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
