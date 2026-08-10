package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkStrengthProbeTest {

    @Test
    fun aMeteredConnectionIsNeverProbed() {
        // The user's allowance is not ours to spend on a measurement they did not ask for.
        assertNull(NetworkStrengthProbe.plan(inputs(isMetered = true)))
    }

    @Test
    fun anOfflineConnectionIsNeverProbed() {
        assertNull(NetworkStrengthProbe.plan(inputs(isOffline = true)))
    }

    @Test
    fun aFreshEstimateIsBetterEvidenceThanANewProbe() {
        assertNull(NetworkStrengthProbe.plan(inputs(estimateAgeMs = 4L * 60L * 1_000L)))
        // Past the window it is worth re-measuring: the user may be on a different network
        // that the platform reports under the same identity.
        assertNotNull(NetworkStrengthProbe.plan(inputs(estimateAgeMs = 30L * 60L * 1_000L)))
    }

    @Test
    fun theSourcesOwnHostIsMeasuredAndCreditedToIt() {
        // Throughput on debrid belongs to the provider. A fast line behind a slow host that
        // read as "4K is fine" is the mistake that makes an automatic pick worse than a manual
        // one, so the reading is filed under the host it came from.
        val plan = assertNotNull(
            NetworkStrengthProbe.plan(
                inputs(
                    sourceUrl = "https://cdn.torbox.example/file.mkv",
                    sourceHeaders = mapOf("Authorization" to "Bearer token"),
                    providerId = "torbox",
                ),
            ),
        )

        assertEquals("https://cdn.torbox.example/file.mkv", plan.url)
        assertEquals("torbox", plan.providerId)
        // The source's own request headers, exactly as the download stack's size check sends
        // them - a host that needs them answers 403 without.
        assertEquals("Bearer token", plan.headers["Authorization"])
    }

    @Test
    fun aSourceNeedingResolvingFallsBackToTheCdnAndCreditsNobody() {
        // No debrid link is minted to run a probe, so there is no URL here. The neutral
        // endpoint measures the line and must not be allowed to vouch for the host: a fast CDN
        // filed under "torbox" would be exactly the untruth the per-provider keying prevents.
        val plan = assertNotNull(
            NetworkStrengthProbe.plan(inputs(sourceUrl = null, providerId = "torbox")),
        )

        assertEquals(NetworkStrengthProbe.CDN_FALLBACK_URL, plan.url)
        assertNull(plan.providerId)
        assertTrue(plan.headers.isEmpty())
    }

    @Test
    fun aManifestIsNotABytePipe() {
        // A few kilobytes of playlist pointing at segments elsewhere. A ranged GET against one
        // measures the playlist server and finishes below the sample floor.
        assertFalse(NetworkStrengthProbe.isProbeableUrl("https://cdn.example/master.m3u8"))
        assertFalse(NetworkStrengthProbe.isProbeableUrl("https://cdn.example/manifest.mpd?x=1"))
        assertFalse(NetworkStrengthProbe.isProbeableUrl("https://cdn.example/file.torrent"))
        assertFalse(NetworkStrengthProbe.isProbeableUrl("magnet:?xt=urn:btih:abc"))
        assertFalse(NetworkStrengthProbe.isProbeableUrl(null))
        assertTrue(NetworkStrengthProbe.isProbeableUrl("https://cdn.example/file.mkv"))
    }

    @Test
    fun aManifestSourceStillGetsTheCdnRatherThanNoMeasurement() {
        val plan = assertNotNull(
            NetworkStrengthProbe.plan(
                inputs(sourceUrl = "https://cdn.example/master.m3u8", providerId = "torbox"),
            ),
        )

        assertEquals(NetworkStrengthProbe.CDN_FALLBACK_URL, plan.url)
        assertNull(plan.providerId)
    }

    @Test
    fun theEarlyExitIsSetAboveWhatTheOptionActuallyNeeds() {
        // Proving a fast line is fast does not need the whole budget. The margin is over the
        // requirement, not over the file's bitrate, so it already includes the headroom.
        val plan = assertNotNull(
            NetworkStrengthProbe.plan(
                inputs(sourceUrl = "https://cdn.example/file.mkv", requiredMbps = 24.0),
            ),
        )

        assertEquals(36.0, assertNotNull(plan.stopAboveMbps), 1e-9)
    }

    @Test
    fun anOptionWithNoCostSpendsTheWholeBudget() {
        // Best available quotes no bucket cost, and a null here means "no early exit" rather
        // than "exit immediately", which would return a sample below the floor every time.
        val plan = assertNotNull(
            NetworkStrengthProbe.plan(
                inputs(sourceUrl = "https://cdn.example/file.mkv", requiredMbps = null),
            ),
        )

        assertNull(plan.stopAboveMbps)
    }

    private fun inputs(
        isMetered: Boolean = false,
        isOffline: Boolean = false,
        estimateAgeMs: Long? = null,
        sourceUrl: String? = null,
        sourceHeaders: Map<String, String> = emptyMap(),
        providerId: String? = null,
        requiredMbps: Double? = null,
    ) = NetworkStrengthProbe.Inputs(
        isMetered = isMetered,
        isOffline = isOffline,
        estimateAgeMs = estimateAgeMs,
        sourceUrl = sourceUrl,
        sourceHeaders = sourceHeaders,
        providerId = providerId,
        requiredMbps = requiredMbps,
    )
}
