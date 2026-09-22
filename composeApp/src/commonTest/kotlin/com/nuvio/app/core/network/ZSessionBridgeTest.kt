package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ZSessionBridgeTest {

    @Test
    fun onlyUnauthorizedResponseReexchangesSharedSession() {
        assertTrue(shouldReexchangeZSession(401))
        assertFalse(shouldReexchangeZSession(400), "Postgres rejection must preserve Realtime auth")
        assertFalse(shouldReexchangeZSession(403), "permission failure does not prove an expired token")
        assertFalse(shouldReexchangeZSession(500))
        assertFalse(shouldReexchangeZSession(null))
    }

    @Test
    fun configuredZClientIsCanonicalSeparateAndInitializes() {
        val expectedConfigured =
            ZSupabaseConfig.URL.isNotBlank() && ZSupabaseConfig.PUBLISHABLE_KEY.isNotBlank()
        assertEquals(expectedConfigured, ZSupabaseProvider.isConfigured)
        if (!expectedConfigured) return

        assertTrue(ZSupabaseConfig.URL.contains("pzbpghmmordvzcfbayoh"))
        assertNotEquals(SupabaseConfig.URL, ZSupabaseConfig.URL)
        assertNotNull(ZSupabaseProvider.client)
    }
}
