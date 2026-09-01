package com.nuvio.app.features.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SocialModelsTest {
    @Test fun handlesNormalizeAndValidate() {
        assertEquals("rayo_42", normalizeSocialHandle(" RAYO_42 "))
        assertTrue(isValidSocialHandle("rayo_42"))
        assertFalse(isValidSocialHandle("ab"))
        assertFalse(isValidSocialHandle("rayo-42"))
    }

    @Test fun progressIsBounded() {
        val item = WatchingNowItem(
            profile = SocialProfileSummary("p", "rayo", "Rayo"), contentId = "m", contentType = "movie",
            videoId = "m", title = "Movie", positionMs = 150, durationMs = 100,
            state = SocialPlaybackState.playing, heartbeatAt = "now",
        )
        assertEquals(100, item.roundedProgressPercent)
    }
}

