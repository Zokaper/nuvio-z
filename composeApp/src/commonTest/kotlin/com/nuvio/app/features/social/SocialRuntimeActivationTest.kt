package com.nuvio.app.features.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SocialRuntimeActivationTest {

    @Test
    fun disabledSocialDoesNotExposeAnActiveRuntimeProfile() {
        assertNull(socialRuntimeProfileId("profile-id", socialEnabled = false))
    }

    @Test
    fun enabledSocialUsesTheNonBlankActiveProfile() {
        assertEquals("profile-id", socialRuntimeProfileId("profile-id", socialEnabled = true))
        assertNull(socialRuntimeProfileId("", socialEnabled = true))
        assertNull(socialRuntimeProfileId(null, socialEnabled = true))
    }
}
