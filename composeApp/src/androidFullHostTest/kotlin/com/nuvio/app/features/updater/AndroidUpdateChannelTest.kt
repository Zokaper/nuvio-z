package com.nuvio.app.features.updater

import com.nuvio.app.core.build.AppVersionConfig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Android debug update channel, as the real Android [AppUpdaterPlatform] wires it.
 *
 * ⚠ **Regression guard for shared-code convergence.** The channel worked from 770e7c0cb (tested
 * end to end `.1` -> `.2` in 51ea36395) until the Phase 6 convergence merge e606c2290 replaced this
 * repository's updater platform with NuvioZDesktop's copy. That copy constructs the release source
 * without `debugChannel = true` and compares against the plain `VERSION_NAME`, because in the
 * desktop repository the Android debug APK is not a channel build. Here it is: `com.nuvio.app.z.debug`
 * is exactly what `debug-release.yml` publishes as `debug-v*`. So `.29` - the first build cut after
 * the merge - sat on the stable line and never saw `.30`.
 *
 * These tests drive the actual `androidFull` object, not a copy of its logic, so taking the desktop
 * file again fails here rather than on a phone.
 */
class AndroidUpdateChannelTest {

    @AfterTest
    fun clearOverride() {
        AndroidAppUpdaterPlatform.debugBuildOverrideForTest = null
    }

    @Test
    fun debugApkReadsTheDebugChannel() {
        AndroidAppUpdaterPlatform.debugBuildOverrideForTest = true
        val source = AppUpdaterPlatform.releaseSource
        assertTrue(source.debugChannel, "the debug APK must read debug-v* prereleases")
        assertEquals("Zokaper", source.owner)
        assertEquals("nuvio-z", source.repo, "Android updates come from this repository, never the desktop one")
    }

    @Test
    fun debugApkComparesAgainstItsFourComponentVersion() {
        AndroidAppUpdaterPlatform.debugBuildOverrideForTest = true
        assertEquals(AppVersionConfig.DEBUG_VERSION_NAME, AppUpdaterPlatform.currentVersionName)
        // With the plain release name, every debug-v<release>.N would look like the same version.
        assertTrue(
            VersionUtils.isRemoteNewer(
                remote = "debug-v${AppVersionConfig.VERSION_NAME}.9999",
                local = AppUpdaterPlatform.currentVersionName,
                localSerial = AppVersionConfig.RELEASE_SERIAL,
            ),
        )
    }

    @Test
    fun releaseApkStaysOnTheReleaseLine() {
        AndroidAppUpdaterPlatform.debugBuildOverrideForTest = false
        assertFalse(AppUpdaterPlatform.releaseSource.debugChannel)
        assertEquals(AppVersionConfig.VERSION_NAME, AppUpdaterPlatform.currentVersionName)
    }

    @Test
    fun theTwoChannelsCannotSeeEachOther() {
        val debug = debugSource(debugChannel = true)
        val release = debugSource(debugChannel = false)
        val debugBuild = ChannelReleaseFacts(tag = "debug-v0.4.13-z1.31", draft = false, prerelease = true)
        val stable = ChannelReleaseFacts(tag = "0.5.0-beta+126", draft = false, prerelease = false)
        val stablePrerelease = ChannelReleaseFacts(tag = "v0.6.0-z1+127", draft = false, prerelease = true)

        assertTrue(isChannelEligible(debugBuild, debug))
        assertFalse(isChannelEligible(stable, debug), "a debug install must never be offered the release app")
        assertFalse(isChannelEligible(stablePrerelease, debug))

        assertFalse(isChannelEligible(debugBuild, release), "a release install must never be offered a debug APK")
        assertTrue(isChannelEligible(stable, release))
        assertTrue(isChannelEligible(stablePrerelease, release), "this repository's release line takes prereleases")
    }

    @Test
    fun draftsAndNonPrereleaseDebugTagsAreNobodysUpdate() {
        val debug = debugSource(debugChannel = true)
        assertFalse(isChannelEligible(ChannelReleaseFacts("debug-v0.4.13-z1.31", draft = true, prerelease = true), debug))
        // The workflow always publishes --prerelease; a debug tag published as a full release is a
        // mistake, and a debug install must not follow it.
        assertFalse(isChannelEligible(ChannelReleaseFacts("debug-v0.4.13-z1.31", draft = false, prerelease = false), debug))
    }

    private fun debugSource(debugChannel: Boolean) = AppUpdateReleaseSource(
        owner = "Zokaper",
        repo = "nuvio-z",
        includePrereleases = true,
        userAgent = "NuvioZ",
        debugChannel = debugChannel,
    )
}
