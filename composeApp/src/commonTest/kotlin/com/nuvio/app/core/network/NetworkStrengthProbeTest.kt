package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkStrengthProbeTest {

    @Test
    fun aMeteredConnectionIsProbedToo() {
        // Skipping metered left mobile data - the connection whose speed varies most and
        // matters most - as the one case still decided entirely by a preset, which is the
        // fault this path exists to remove.
        assertNotNull(NetworkStrengthProbe.plan(inputs(isMetered = true)))
    }

    @Test
    fun anOfflineConnectionIsNeverProbed() {
        assertNull(NetworkStrengthProbe.plan(inputs(isOffline = true)))
    }

    @Test
    fun aFreshEstimateIsBetterEvidenceThanANewProbe() {
        assertNull(NetworkStrengthProbe.plan(inputs(lineEstimateAgeMs = 4L * 60L * 1_000L)))
        // Past the window it is worth re-measuring: the user may be on a different network
        // that the platform reports under the same identity.
        assertNotNull(NetworkStrengthProbe.plan(inputs(lineEstimateAgeMs = 30L * 60L * 1_000L)))
    }

    @Test
    fun aFreshLineEstimateDoesNotVouchForAnUnmeasuredHost() {
        // The whole reason estimates are keyed per provider. A two-minute-old line-wide reading
        // says nothing about a debrid host nobody has pulled a byte from, and treating it as
        // fresh meant that host was never probed and borrowed a figure measured elsewhere.
        val plan = assertNotNull(
            NetworkStrengthProbe.plan(
                inputs(
                    sourceUrl = "https://cdn.torbox.example/file.mkv",
                    providerId = "torbox",
                    lineEstimateAgeMs = 2L * 60L * 1_000L,
                    sourceEstimateAgeMs = null,
                ),
            ),
        )

        assertEquals("torbox", plan.providerId)
    }

    @Test
    fun aFreshHostEstimateDoesSuppressItsOwnHost() {
        assertNull(
            NetworkStrengthProbe.plan(
                inputs(
                    sourceUrl = "https://cdn.torbox.example/file.mkv",
                    providerId = "torbox",
                    sourceEstimateAgeMs = 2L * 60L * 1_000L,
                ),
            ),
        )
    }

    @Test
    fun aCdnBoundProbeIsJudgedByTheLineNotTheHost() {
        // A CDN result is filed under no provider, so the host's entry never fills. Judging this
        // probe by that empty entry would re-probe on every single sheet open, for ever.
        assertNull(
            NetworkStrengthProbe.plan(
                inputs(
                    sourceUrl = null,
                    providerId = "torbox",
                    sourceEstimateAgeMs = null,
                    lineEstimateAgeMs = 2L * 60L * 1_000L,
                ),
            ),
        )
    }

    @Test
    fun aDirectUrlWithNoProviderIsAlsoJudgedByTheLine() {
        // Nothing to file it under, so it refreshes the line-wide entry and must be gated on it.
        assertNull(
            NetworkStrengthProbe.plan(
                inputs(
                    sourceUrl = "https://cdn.example/file.mkv",
                    providerId = null,
                    lineEstimateAgeMs = 2L * 60L * 1_000L,
                ),
            ),
        )
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
    fun anUnmeteredProbeSpendsItsWholeBudgetSoTheFigureIsTheLineNotTheStop() {
        // ⚠ **The rate a probe stops at is the rate it records.** An early exit at 200 Mb/s
        // therefore writes ~200 for a line doing 380, and the user is shown a figure capped by
        // the measurement rather than by their connection - the same complaint this path exists
        // to answer, arriving from the other direction. Reported once already, at 211 against a
        // line curl measured at 239-377.
        assertNull(
            assertNotNull(
                NetworkStrengthProbe.plan(
                    inputs(sourceUrl = "https://cdn.example/file.mkv", requiredMbps = 160.0),
                ),
            ).stopAboveMbps,
        )
    }

    @Test
    fun aMeteredProbeStopsEarlyButNeverBelowWhatEveryDecisionNeedsToKnow() {
        // Thrift is worth a worse number only where the bytes cost something. Even then the stop
        // is floored: scaling it to whatever is on screen would let a title whose most expensive
        // release is a 5 Mb/s encode stop the probe at 7.5 and write "your connection: 8 Mb/s"
        // for a line carrying 200.
        val cheap = assertNotNull(
            NetworkStrengthProbe.plan(
                inputs(isMetered = true, sourceUrl = "https://cdn.example/file.mkv", requiredMbps = 5.0),
            ),
        )
        assertEquals(200.0, assertNotNull(cheap.stopAboveMbps), 1e-9)

        // The margin is over the requirement, not the file's bitrate, so it already includes the
        // headroom. A 160 Mb/s remux has to be provably affordable, which needs a higher stop.
        val expensive = assertNotNull(
            NetworkStrengthProbe.plan(
                inputs(isMetered = true, sourceUrl = "https://cdn.example/file.mkv", requiredMbps = 160.0),
            ),
        )
        assertEquals(240.0, assertNotNull(expensive.stopAboveMbps), 1e-9)
    }

    @Test
    fun theBudgetHasToBeAbleToHoldAWindow() {
        // The fault this file's constants were re-derived for. A budget is only meaningful
        // against the window it has to contain: 32 MiB is ~0.9 s at 300 Mb/s, which holds two
        // 250 ms windows past the ramp. The predecessors - 4 MiB, then 8 MiB - could not hold
        // one above ~44 and ~89 Mb/s respectively, so the faster the line the more certainly the
        // measurement fell back to the ramp-contaminated mean.
        val budget = assertNotNull(NetworkStrengthProbe.plan(inputs())).maxBytes
        val secondsAt300Mbps = budget.toDouble() * 8.0 / 300_000_000.0

        assertEquals(NetworkStrengthProbe.MAX_BYTES, budget)
        assertTrue(
            secondsAt300Mbps * 1_000.0 > ThroughputWindow.DEFAULT_WINDOW_MS * 2,
            "a $budget byte budget is $secondsAt300Mbps s at 300 Mb/s, too short for two windows",
        )
        assertTrue(budget > ThroughputWindow.DEFAULT_MIN_WINDOW_BYTES * 4)
    }

    @Test
    fun theNeutralEndpointIsAskedForABodyItWillActuallyServeAndThatOutlastsTheBudget() {
        // ⚠ **Two bounds, and each has already been broken once - silently, and with the same
        // symptom both times: a figure that will not update.**
        //
        // Too small: `?bytes=` fixes the resource size, so a body under the budget *becomes* the
        // budget and raising MAX_BYTES changes nothing. A 4 MiB body under an 8 MiB budget made
        // every reading a 585 ms pull, too short to hold a window, reading 56 Mb/s on a line
        // carrying 211.
        //
        // Too large: the endpoint 403s. Asking for 128 MB - on the theory that more headroom
        // could only help - meant `httpMeasureThroughput` reported a zero-byte sample and the
        // probe recorded nothing at all, on every single attempt.
        val served = NetworkStrengthProbe.CDN_FALLBACK_URL
            .substringAfter("bytes=")
            .toLong()

        assertTrue(
            served > NetworkStrengthProbe.MAX_BYTES,
            "the endpoint serves $served bytes, at or under the ${NetworkStrengthProbe.MAX_BYTES} budget",
        )
        assertTrue(
            served < NetworkStrengthProbe.CDN_ENDPOINT_MAX_BYTES,
            "the endpoint refuses $served bytes - measured: 96,000,000 answers 200 and " +
                "100,000,000 answers 403",
        )
    }

    @Test
    fun aMeteredLinkSpendsLess() {
        // Metered is probed - see `aMeteredConnectionIsProbedToo` - but on half the budget. That
        // still reads honestly past anything a metered link sustains, and `isMetered` stops
        // being a field that is carried and ignored.
        assertEquals(
            NetworkStrengthProbe.METERED_MAX_BYTES,
            assertNotNull(NetworkStrengthProbe.plan(inputs(isMetered = true))).maxBytes,
        )
        assertTrue(NetworkStrengthProbe.METERED_MAX_BYTES < NetworkStrengthProbe.MAX_BYTES)
    }

    @Test
    fun aRequestedRetestIsNotAnsweredWithTheFigureBeingQuestioned() {
        // Tapping re-test means the stored number is not believed. The freshness gate exists to
        // stop the app spending bytes on an answer it already has; it must not stop the user
        // asking for a different one.
        assertNull(NetworkStrengthProbe.plan(inputs(lineEstimateAgeMs = 60_000L)))

        val forced = assertNotNull(
            NetworkStrengthProbe.plan(inputs(lineEstimateAgeMs = 60_000L, force = true)),
        )

        assertTrue(forced.isForced, "a forced plan must carry the flag that stops the 50/50 blend")
    }

    private fun inputs(
        isMetered: Boolean = false,
        isOffline: Boolean = false,
        sourceEstimateAgeMs: Long? = null,
        lineEstimateAgeMs: Long? = null,
        sourceUrl: String? = null,
        sourceHeaders: Map<String, String> = emptyMap(),
        providerId: String? = null,
        requiredMbps: Double? = null,
        force: Boolean = false,
    ) = NetworkStrengthProbe.Inputs(
        isMetered = isMetered,
        isOffline = isOffline,
        sourceEstimateAgeMs = sourceEstimateAgeMs,
        lineEstimateAgeMs = lineEstimateAgeMs,
        sourceUrl = sourceUrl,
        sourceHeaders = sourceHeaders,
        providerId = providerId,
        requiredMbps = requiredMbps,
        force = force,
    )
}
