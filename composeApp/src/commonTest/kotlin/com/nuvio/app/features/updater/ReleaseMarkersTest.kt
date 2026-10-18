package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseMarkersTest {
    private val selfSigningDebug = AppUpdateReleaseSource(
        owner = "Zokaper",
        repo = "nuvio-z",
        includePrereleases = true,
        userAgent = "NuvioZ",
        debugChannel = true,
        requiredNotesMarker = releaseMarkerSelfSigning,
    )

    private fun debugRelease(body: String?) = ChannelReleaseFacts(
        tag = "debug-v0.5.4-z2.92",
        draft = false,
        prerelease = true,
        body = body,
    )

    @Test
    fun `self-signing build skips a release without the self-signing marker`() {
        assertFalse(isChannelEligible(debugRelease("Debug build from claude/social-v2."), selfSigningDebug))
        assertFalse(isChannelEligible(debugRelease(null), selfSigningDebug))
    }

    @Test
    fun `self-signing build takes a release with the marker`() {
        val body = "Debug build.\n\n$releaseMarkerSelfSigning"
        assertTrue(isChannelEligible(debugRelease(body), selfSigningDebug))
    }

    @Test
    fun `the marker never lets a release through the other channel rules`() {
        val body = releaseMarkerSelfSigning
        val stableTag = ChannelReleaseFacts(tag = "v0.5.5-z1+130", draft = false, prerelease = false, body = body)
        val draft = debugRelease(body).copy(draft = true)
        assertFalse(isChannelEligible(stableTag, selfSigningDebug))
        assertFalse(isChannelEligible(draft, selfSigningDebug))
    }

    @Test
    fun `sources without a required marker ignore the body`() {
        val android = selfSigningDebug.copy(requiredNotesMarker = null)
        assertTrue(isChannelEligible(debugRelease(null), android))
    }

    @Test
    fun `markers are stripped from the notes people read`() {
        val body = "## Fixes\n- Renewing works again\n\n$releaseMarkerSelfSigning\n$releaseMarkerSigningChanged\n"
        assertEquals("## Fixes\n- Renewing works again", stripReleaseMarkers(body))
    }

    @Test
    fun `a multi-line comment is stripped whole`() {
        assertEquals("Notes", stripReleaseMarkers("Notes\n<!-- one\ntwo -->"))
    }
}
