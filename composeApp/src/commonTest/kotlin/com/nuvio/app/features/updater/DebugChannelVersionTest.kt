package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Version ordering on the debug update line.
 *
 * The comparator keeps the leading digits of each `.`/`-` separated token, which makes the tag
 * prefixes load-bearing: leave `debug-` on and the `v0` token contributes nothing, the leading
 * zero is lost, and every debug release outranks every local version permanently. These cases
 * pin that, and the four-component ordering the channel depends on.
 */
class DebugChannelVersionTest {

    @Test
    fun `debug tag prefix does not inflate the parsed version`() {
        // The bug this guards: [4, 9, 2] vs a local [0, 4, 9, 2] would compare 4 > 0.
        assertFalse(VersionUtils.isRemoteNewer("debug-v0.4.9-beta.2", "0.4.9-beta.2"))
    }

    @Test
    fun `a higher debug build is newer`() {
        assertTrue(VersionUtils.isRemoteNewer("debug-v0.4.9-beta.2", "0.4.9-beta.1"))
    }

    @Test
    fun `a lower debug build is not newer`() {
        assertFalse(VersionUtils.isRemoteNewer("debug-v0.4.9-beta.1", "0.4.9-beta.2"))
    }

    @Test
    fun `any debug build outranks the plain release version it was cut from`() {
        assertTrue(VersionUtils.isRemoteNewer("debug-v0.4.9-beta.1", "0.4.9-beta"))
    }

    @Test
    fun `a newer release line wins over an older line's debug builds`() {
        assertTrue(VersionUtils.isRemoteNewer("debug-v0.5.0-beta.1", "0.4.9-beta.9"))
        assertFalse(VersionUtils.isRemoteNewer("debug-v0.4.9-beta.9", "0.5.0-beta.1"))
    }

    @Test
    fun `the stable channel is unaffected by the added prefix handling`() {
        assertTrue(VersionUtils.isRemoteNewer("v0.4.9-beta", "0.4.8-beta"))
        assertFalse(VersionUtils.isRemoteNewer("v0.4.8-beta", "0.4.9-beta"))
        assertFalse(VersionUtils.isRemoteNewer("0.4.9-beta", "0.4.9-beta"))
    }
}
