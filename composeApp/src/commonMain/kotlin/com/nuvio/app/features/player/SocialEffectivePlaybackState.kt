package com.nuvio.app.features.player

import com.nuvio.app.features.social.SocialPlaybackState

/** Presence follows effective playback even while the engine is finishing a lifecycle pause. */
internal fun effectiveSocialPlaybackState(enginePlaying: Boolean, appForeground: Boolean): SocialPlaybackState =
    if (enginePlaying && appForeground) SocialPlaybackState.playing else SocialPlaybackState.paused
