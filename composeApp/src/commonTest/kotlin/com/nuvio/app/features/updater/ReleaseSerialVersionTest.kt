package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Release ordering by serial, and the fallback that keeps old installs orderable.
 *
 * Nuvio Z is adopting vanilla's numbering, which means the version name can go
 * BACKWARDS: an install sitting on `0.4.14-beta` must be offered `0.4.9-z1`. The
 * string comparator refuses that by construction - it is numeric and `0.4.9` is
 * lower - so ordering moves to a monotonic serial published in the tag.
 *
 * The transition is the delicate part, and most of these cases are about it. Old
 * installs run the OLD updater, so they can only be moved by a bridge release that
 * ranks above them under the old rule; and a build that predates the serial has no
 * local serial to compare, so it must keep ordering exactly as it always did.
 *
 * `DebugChannelVersionTest` pins the string comparator itself and still passes
 * unchanged - none of this alters it.
 */
class ReleaseSerialVersionTest {

    @Test
    fun `a serial is read off the tag suffix`() {
        assertEquals(127, VersionUtils.parseReleaseSerial("v0.6.0-z1+127"))
        assertEquals(128, VersionUtils.parseReleaseSerial("debug-v0.6.0-z1.3+128"))
        assertEquals(125, VersionUtils.parseReleaseSerial("0.5.0-beta+125"))
    }

    @Test
    fun `a tag with no serial has none`() {
        assertNull(VersionUtils.parseReleaseSerial("v0.4.14-beta"))
        assertNull(VersionUtils.parseReleaseSerial("debug-v0.4.14-beta.22"))
        assertNull(VersionUtils.parseReleaseSerial(""))
        assertNull(VersionUtils.parseReleaseSerial(null))
        // A "+" with nothing usable after it is not a serial.
        assertNull(VersionUtils.parseReleaseSerial("v0.6.0-z1+"))
        assertNull(VersionUtils.parseReleaseSerial("v0.6.0-z1+beta"))
    }

    @Test
    fun `the serial decides when both sides have one`() {
        assertTrue(VersionUtils.isRemoteNewer("v0.6.0-z1+127", "0.6.0-z1", localSerial = 126))
        assertFalse(VersionUtils.isRemoteNewer("v0.6.0-z1+126", "0.6.0-z1", localSerial = 127))
        assertFalse(VersionUtils.isRemoteNewer("v0.6.0-z1+127", "0.6.0-z1", localSerial = 127))
    }

    @Test
    fun `a lower version name is still offered when its serial is higher`() {
        // THE CASE THE SERIAL EXISTS FOR. Adopting vanilla's numbering means the
        // first mod release is 0.4.9-z1 while installs sit on 0.4.14-beta. The
        // string comparator refuses it; the serial does not.
        assertTrue(VersionUtils.isRemoteNewer("v0.4.9-z1+126", "0.5.0-beta", localSerial = 125))
        // And the string comparator really would have refused it.
        assertFalse(VersionUtils.isRemoteNewer("v0.4.9-z1", "0.5.0-beta"))
    }

    @Test
    fun `a build with no serial orders by the version string exactly as before`() {
        // An install that predates the bridge. It must not be stranded, and it must
        // not be offered something older either.
        assertTrue(VersionUtils.isRemoteNewer("v0.5.0-beta+125", "0.4.14-beta", localSerial = null))
        assertFalse(VersionUtils.isRemoteNewer("v0.4.9-z1+126", "0.4.14-beta", localSerial = null))
    }

    @Test
    fun `a zero serial means no serial, not serial zero`() {
        // A checkout predating DesktopReleaseSerial.properties generates RELEASE_SERIAL = 0
        // from the gradle default. That must fall through to the string, not compare
        // as a real serial - otherwise every serialled release would outrank it on
        // the serial alone, including ones that are genuinely older.
        assertFalse(VersionUtils.isRemoteNewer("v0.4.9-z1+126", "0.4.14-beta", localSerial = 0))
        assertTrue(VersionUtils.isRemoteNewer("v0.5.0-beta+125", "0.4.14-beta", localSerial = 0))
    }

    @Test
    fun `a remote with no serial falls back even when the local build has one`() {
        // Reading an older release, or a release cut before the workflow wrote serials.
        assertFalse(VersionUtils.isRemoteNewer("v0.4.14-beta", "0.5.0-beta", localSerial = 125))
        assertTrue(VersionUtils.isRemoteNewer("v0.6.0-beta", "0.5.0-beta", localSerial = 125))
    }

    @Test
    fun `the bridge release is offered to every install under the old rule`() {
        // Old installs run the OLD updater, which has no serial at all. This is the
        // constraint that forces 0.5.0-beta to exist: it must rank above 0.4.14-beta
        // by the string, which it does, while carrying the new rule.
        assertTrue(VersionUtils.isRemoteNewer("v0.5.0-beta", "0.4.14-beta"))
    }

    @Test
    fun `the debug line orders by serial too`() {
        assertTrue(
            VersionUtils.isRemoteNewer("debug-v0.6.0-z1.4+129", "0.6.0-z1.3", localSerial = 128),
        )
        assertFalse(
            VersionUtils.isRemoteNewer("debug-v0.6.0-z1.3+128", "0.6.0-z1.4", localSerial = 129),
        )
    }

    @Test
    fun `the serial does not disturb the parsed version parts`() {
        // The suffix must be invisible to the string comparator, so the fallback path
        // behaves identically whether or not a serial happens to be present.
        assertEquals(listOf(0, 6, 0), VersionUtils.parseVersionParts("v0.6.0+127"))
        assertEquals(listOf(0, 6, 0, 3), VersionUtils.parseVersionParts("debug-v0.6.0-z1.3+128"))
    }
}
