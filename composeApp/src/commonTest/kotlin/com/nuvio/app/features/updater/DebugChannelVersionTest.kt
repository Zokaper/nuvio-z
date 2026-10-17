package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Version ordering on the desktop debug update line.
 *
 * The comparator keeps the leading digits of each `.`/`-` separated token, which makes the tag
 * prefixes load-bearing: leave `debug-` on and the `v0` token contributes nothing, the leading
 * zero is lost, and every debug release outranks every local version permanently. These cases
 * pin that, and the four-component ordering the channel depends on.
 *
 * The mobile repository carries the same suite against the same shared comparator. Both are
 * needed: the two `AppUpdater.kt` files are hand-ported rather than copied, so a fix to one
 * proves nothing about the other.
 */
class DebugChannelVersionTest {

    @Test
    fun `debug tag prefix does not inflate the parsed version`() {
        // The bug this guards: [4, 14, 2] vs a local [0, 4, 14, 2] would compare 4 > 0.
        assertFalse(VersionUtils.isRemoteNewer("debug-v0.4.14-beta.2", "0.4.14-beta.2"))
    }

    @Test
    fun `a higher debug build is newer`() {
        assertTrue(VersionUtils.isRemoteNewer("debug-v0.4.14-beta.2", "0.4.14-beta.1"))
    }

    @Test
    fun `a lower debug build is not newer`() {
        assertFalse(VersionUtils.isRemoteNewer("debug-v0.4.14-beta.1", "0.4.14-beta.2"))
    }

    @Test
    fun `any debug build outranks the plain release version it was cut from`() {
        assertTrue(VersionUtils.isRemoteNewer("debug-v0.4.14-beta.1", "0.4.14-beta"))
    }

    @Test
    fun `a newer release line wins over an older line's debug builds`() {
        assertTrue(VersionUtils.isRemoteNewer("debug-v0.5.0-beta.1", "0.4.14-beta.9"))
        assertFalse(VersionUtils.isRemoteNewer("debug-v0.4.14-beta.9", "0.5.0-beta.1"))
    }

    @Test
    fun `the stable channel is unaffected by the added prefix handling`() {
        assertTrue(VersionUtils.isRemoteNewer("v0.4.14-beta", "0.4.13-beta"))
        assertFalse(VersionUtils.isRemoteNewer("v0.4.13-beta", "0.4.14-beta"))
        assertFalse(VersionUtils.isRemoteNewer("0.4.14-beta", "0.4.14-beta"))
    }

    @Test
    fun `a release version never offers itself to a debug build cut from it`() {
        // The debug build carries the higher version, so even if channel filtering were removed
        // the release MSI would not be offered as an update over it. Belt and braces: the
        // channel split is what actually prevents it, and this pins the ordering half.
        assertFalse(VersionUtils.isRemoteNewer("v0.4.14-beta", "0.4.14-beta.1"))
    }

    @Test
    fun `stable and debug channel eligibility is fail closed`() {
        val stable = channelSource(debug = false)
        val debug = channelSource(debug = true)
        val cases = listOf(
            ChannelReleaseFacts("0.4.13-z1+127", draft = false, prerelease = false) to (true to false),
            ChannelReleaseFacts("v0.4.13-z1+127", draft = false, prerelease = false) to (true to false),
            ChannelReleaseFacts("0.4.13-z1+127", draft = false, prerelease = true) to (false to false),
            ChannelReleaseFacts("debug-v0.4.13-z1.40", draft = false, prerelease = true) to (false to true),
            ChannelReleaseFacts("debug-v0.4.13-z1.40", draft = false, prerelease = false) to (false to false),
            ChannelReleaseFacts("debug-v0.4.13-z1.40", draft = true, prerelease = true) to (false to false),
        )

        cases.forEach { (release, expected) ->
            assertTrue(isChannelEligible(release, stable) == expected.first, "stable: $release")
            assertTrue(isChannelEligible(release, debug) == expected.second, "debug: $release")
        }
    }

    @Test
    fun `the newest debug build is chosen whatever order GitHub lists them in`() {
        // The order GitHub actually returned on 2026-10-17: 100 after 97.
        val releases = listOf(
            ChannelReleaseFacts("debug-v0.1.26-alpha-z2.99", draft = false, prerelease = true),
            ChannelReleaseFacts("debug-v0.1.26-alpha-z2.98", draft = false, prerelease = true),
            ChannelReleaseFacts("debug-v0.1.26-alpha-z2.97", draft = false, prerelease = true),
            ChannelReleaseFacts("debug-v0.1.26-alpha-z2.100", draft = false, prerelease = true),
            ChannelReleaseFacts("debug-v0.1.26-alpha-z2.96", draft = false, prerelease = true),
        )
        assertEquals(3, newestChannelReleaseIndex(releases, channelSource(debug = true)))
    }

    @Test
    fun `the newest release skips releases outside the channel`() {
        val releases = listOf(
            ChannelReleaseFacts("debug-v0.1.26-alpha-z2.101", draft = true, prerelease = true),
            ChannelReleaseFacts("v0.1.27-alpha-z1+140", draft = false, prerelease = false),
            ChannelReleaseFacts("debug-v0.1.26-alpha-z2.100", draft = false, prerelease = true),
            ChannelReleaseFacts("v0.1.26-alpha-z1+139", draft = false, prerelease = false),
        )
        assertEquals(2, newestChannelReleaseIndex(releases, channelSource(debug = true)))
        assertEquals(1, newestChannelReleaseIndex(releases, channelSource(debug = false)))
        assertNull(newestChannelReleaseIndex(releases.take(1), channelSource(debug = true)))
    }

    @Test
    fun `stable releases order by serial`() {
        val releases = listOf(
            ChannelReleaseFacts("v0.4.14-z1+128", draft = false, prerelease = false),
            ChannelReleaseFacts("v0.4.9-z1+131", draft = false, prerelease = false),
        )
        assertEquals(1, newestChannelReleaseIndex(releases, channelSource(debug = false)))
    }

    private fun channelSource(debug: Boolean) = AppUpdateReleaseSource(
        owner = "Zokaper",
        repo = "nuvio-z",
        includePrereleases = debug,
        userAgent = "NuvioZ",
        debugChannel = debug,
    )
}
