package com.nuvio.app.features.player

import com.nuvio.app.features.social.SocialPlaybackState
import kotlin.test.Test
import kotlin.test.assertEquals

class SocialEffectivePlaybackStateTest {
    @Test fun lifecyclePauseAndResumeKeepTheWatchingSession() {
        assertEquals(SocialPlaybackState.playing, effectiveSocialPlaybackState(true, true))
        assertEquals(SocialPlaybackState.paused, effectiveSocialPlaybackState(true, false))
        assertEquals(SocialPlaybackState.paused, effectiveSocialPlaybackState(false, true))
        assertEquals(SocialPlaybackState.playing, effectiveSocialPlaybackState(true, true))
    }
}
