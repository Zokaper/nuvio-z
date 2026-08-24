package com.nuvio.app.core.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NuvioZVersionTest {
    @Test
    fun `a Z release names its vanilla base`() {
        assertEquals("0.6.0", NuvioZVersion.vanillaBaseVersion("0.6.0-z2"))
        assertEquals("0.6.0-beta", NuvioZVersion.vanillaBaseVersion("0.6.0-beta-z1"))
    }

    @Test
    fun `the debug counter is not part of the vanilla base`() {
        assertEquals("0.6.0", NuvioZVersion.vanillaBaseVersion("0.6.0-z2.3"))
        assertEquals("0.6.0-beta", NuvioZVersion.vanillaBaseVersion("0.6.0-beta-z1.12"))
    }

    @Test
    fun `a pre-scheme or malformed version does not guess a base`() {
        assertNull(NuvioZVersion.vanillaBaseVersion("0.5.0-beta"))
        assertNull(NuvioZVersion.vanillaBaseVersion("0.6.0-z0"))
        assertNull(NuvioZVersion.vanillaBaseVersion("Nuvio-z1"))
    }
}
