package com.nuvio.app.features.debrid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DebridProviderTest {
    @Test
    fun `torbox exposes local addon capabilities`() {
        assertTrue(DebridProviders.Torbox.authMethod == DebridProviderAuthMethod.DeviceCode)
        assertTrue(DebridProviders.Torbox.supports(DebridProviderCapability.ClientResolve))
        assertTrue(DebridProviders.Torbox.supports(DebridProviderCapability.LocalTorrentCacheCheck))
        assertTrue(DebridProviders.Torbox.supports(DebridProviderCapability.LocalTorrentResolve))
        assertTrue(DebridProviders.Torbox.supports(DebridProviderCapability.CloudLibrary))
    }

    @Test
    fun `premiumize exposes oauth and cloud service capabilities`() {
        assertTrue(DebridProviders.Premiumize.visibleInUi)
        assertTrue(DebridProviders.Premiumize.authMethod == DebridProviderAuthMethod.DeviceCode)
        assertTrue(DebridProviders.Premiumize.supports(DebridProviderCapability.ClientResolve))
        assertTrue(DebridProviders.Premiumize.supports(DebridProviderCapability.LocalTorrentCacheCheck))
        assertTrue(DebridProviders.Premiumize.supports(DebridProviderCapability.LocalTorrentResolve))
        assertTrue(DebridProviders.Premiumize.supports(DebridProviderCapability.CloudLibrary))
    }

    @Test
    fun `real debrid stays hidden from local addon capability paths`() {
        assertTrue(DebridProviders.RealDebrid.authMethod == DebridProviderAuthMethod.ApiKey)
        assertFalse(DebridProviders.RealDebrid.visibleInUi)
        assertTrue(DebridProviders.RealDebrid.supports(DebridProviderCapability.ClientResolve))
        assertFalse(DebridProviders.RealDebrid.supports(DebridProviderCapability.LocalTorrentCacheCheck))
        assertFalse(DebridProviders.RealDebrid.supports(DebridProviderCapability.LocalTorrentResolve))
        assertFalse(DebridProviders.RealDebrid.supports(DebridProviderCapability.CloudLibrary))
    }

    @Test
    fun `names services an addon may report without registering them`() {
        // Display only: registering these would add dead api-key entries to every storage actual.
        assertEquals(3, DebridProviders.all().size)
        assertNull(DebridProviders.byId("alldebrid"))

        assertEquals("AD", DebridProviders.shortName("alldebrid"))
        assertEquals("AllDebrid", DebridProviders.displayName("alldebrid"))
        assertEquals("PIO", DebridProviders.shortName("put.io"))
        assertEquals("TB", DebridProviders.shortName(DebridProviders.TORBOX_ID))
    }

    @Test
    fun `short names for unknown services stay short`() {
        // "ALLDEBRID" in a name template is the failure this replaces.
        assertTrue(DebridProviders.shortName("some-unknown-host").length <= 3)
        assertTrue(DebridProviders.shortName("mysteryservice").length <= 3)
        assertEquals("", DebridProviders.shortName(null))
    }
}
