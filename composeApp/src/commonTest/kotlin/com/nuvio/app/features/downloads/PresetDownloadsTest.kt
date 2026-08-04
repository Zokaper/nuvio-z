package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PresetDownloadsTest {
    private val addonA = AddonSourceKey("a", "https://a/manifest.json")
    private val addonB = AddonSourceKey("b", "https://b/manifest.json")

    @Test
    fun builtInPresetsAndRuntimeCapsMatchSpecification() {
        assertEquals(VideoResolution.HD_720, DownloadPreset.Saver.targetResolution)
        assertEquals(562_500_000, DownloadPreset.Saver.sizeCapBytes(null, isEpisode = true))
        assertEquals(3_000_000_000, DownloadPreset.Balanced.sizeCapBytes(null, isEpisode = false))
        assertEquals(VideoResolution.UHD_2160, DownloadPreset.Quality.targetResolution)
    }

    @Test
    fun resolutionFallsDownAndTieIsDeterministic() {
        val candidates = listOf(
            candidate(addonA, "https://a/two.mkv", VideoResolution.HD_720, 500, addonOrder = 1),
            candidate(addonB, "https://b/one.mkv", VideoResolution.FULL_HD_1080, 500, addonOrder = 0),
        )

        val selected = PresetSourceSelector.select(
            candidates,
            DownloadPreset.Balanced.copy(gigabytesPerHourLimit = 10.0),
            DownloadSourcePolicy(),
            runtimeMinutes = 45,
            isEpisode = true,
        )

        assertIs<SourceSelectionResult.Selected>(selected)
        assertEquals("https://b/one.mkv", selected.streamUrl)
    }

    @Test
    fun unknownSizeNeedsApprovalAndOversizeIsRejected() {
        val unknown = candidate(addonA, "https://a/unknown.mkv", VideoResolution.FULL_HD_1080, null)
        val oversized = candidate(addonA, "https://a/large.mkv", VideoResolution.FULL_HD_1080, 9_000_000_000)
        val result = PresetSourceSelector.select(
            listOf(oversized, unknown),
            DownloadPreset.Balanced,
            DownloadSourcePolicy(),
            runtimeMinutes = 45,
            isEpisode = true,
        )
        assertIs<SourceSelectionResult.ApprovalNeeded>(result)
        assertEquals("https://a/unknown.mkv", result.streamUrl)
    }

    @Test
    fun disallowedAddonAndRestrictedUnknownAioProviderCannotBypassPolicy() {
        val torrentio = candidate(
            addonA,
            "https://a/torrentio.mkv",
            VideoResolution.FULL_HD_1080,
            100,
            provider = "Torrentio",
        )
        val unknown = candidate(addonA, "https://a/unknown.mkv", VideoResolution.FULL_HD_1080, 100)
        val otherAddon = candidate(addonB, "https://b/file.mkv", VideoResolution.FULL_HD_1080, 100)
        val policy = DownloadSourcePolicy(
            allowedAddons = setOf(addonA),
            allowedAioProviders = mapOf(addonA to setOf("Torrentio")),
        )

        val selected = PresetSourceSelector.select(
            listOf(unknown, otherAddon, torrentio),
            DownloadPreset.Balanced.copy(gigabytesPerHourLimit = 10.0),
            policy,
            runtimeMinutes = 45,
            isEpisode = true,
        )
        assertIs<SourceSelectionResult.Selected>(selected)
        assertEquals("https://a/torrentio.mkv", selected.streamUrl)
    }

    @Test
    fun downloadsCodecPersistsStructuredAddonKeysUsedByPresetEditor() {
        val policy = DownloadSourcePolicy(
            allowedAddons = setOf(addonA),
            allowedAioProviders = mapOf(addonA to setOf("Torrentio")),
            aioOverrides = setOf(addonA),
            discoveredAioProviders = mapOf(addonA to setOf("Torrentio", "Comet")),
        )

        val encoded = DownloadsCodec.encode(
            items = emptyList(),
            sourcePolicy = policy,
            batches = emptyList(),
            presets = DownloadPreset.BuiltIns,
        )
        val decoded = DownloadsCodec.decode(encoded)

        assertEquals(policy, decoded.sourcePolicy)
    }

    @Test
    fun disallowedAddonsAreRemovedBeforeDiscoveryRequests() {
        val targets = listOf(
            AutomaticAddonTarget("a", "Allowed", addonA.manifestUrl),
            AutomaticAddonTarget("b", "Blocked", addonB.manifestUrl),
        )
        val eligible = AutomaticDownloadDiscovery.eligibleTargets(
            targets,
            DownloadSourcePolicy(allowedAddons = setOf(addonA)),
        )

        assertEquals(listOf("a"), eligible.map { it.manifestId })
    }

    @Test
    fun unsupportedProtocolsRemainManual() {
        val result = PresetSourceSelector.select(
            listOf(
                candidate(addonA, "magnet:?xt=urn:btih:x", VideoResolution.FULL_HD_1080, 10),
                candidate(addonA, "https://a/video.m3u8", VideoResolution.FULL_HD_1080, 10),
            ),
            DownloadPreset.Balanced,
            DownloadSourcePolicy(),
            runtimeMinutes = null,
            isEpisode = true,
        )
        assertIs<SourceSelectionResult.NoMatch>(result)
    }

    @Test
    fun selectedSeasonsSkipSpecialsFutureUnavailableAndDuplicates() {
        val episodes = listOf(
            BatchEpisode("special", "Special", 0, 1),
            BatchEpisode("s1e1", "One", 1, 1),
            BatchEpisode("s1e2", "Future", 1, 2, released = false),
            BatchEpisode("s2e1", "Unavailable", 2, 1, available = false),
            BatchEpisode("s2e2", "Two", 2, 2),
            BatchEpisode("s2e2", "Duplicate", 2, 2),
        )
        val result = DownloadBatchPlanner.episodesForScope(
            episodes,
            DownloadScope.SelectedSeasons(setOf(1, 2)),
            existingLogicalKeys = setOf("show|1|1"),
            parentMetaId = "show",
        )
        assertEquals(listOf("s2e2"), result.map { it.videoId })
        assertEquals(setOf(2), DownloadBatchPlanner.defaultSelectedSeasons(2, setOf(0, 1, 2)))
    }

    @Test
    fun unwatchedSeasonScopeKeepsTheEpisodeInProgressAndEverythingAfterIt() {
        val episodes = listOf(
            BatchEpisode("s1e1", "One", 1, 1, watched = true),
            BatchEpisode("s1e2", "Two", 1, 2, watched = true),
            BatchEpisode("s1e3", "Three", 1, 3, watched = true),
            BatchEpisode("s1e4", "Four", 1, 4),
            BatchEpisode("s1e5", "Five", 1, 5),
            BatchEpisode("s1e6", "Unreleased", 1, 6, released = false),
            BatchEpisode("s2e1", "Other season", 2, 1),
        )

        val unwatched = DownloadBatchPlanner.episodesForScope(
            episodes,
            DownloadScope.SeasonUnwatched(1),
            existingLogicalKeys = emptySet(),
            parentMetaId = "show",
        )
        assertEquals(listOf("s1e4", "s1e5"), unwatched.map { it.videoId })

        val wholeSeason = DownloadBatchPlanner.episodesForScope(
            episodes,
            DownloadScope.Season(1),
            existingLogicalKeys = emptySet(),
            parentMetaId = "show",
        )
        assertEquals(listOf("s1e1", "s1e2", "s1e3", "s1e4", "s1e5"), wholeSeason.map { it.videoId })
    }

    @Test
    fun unwatchedSeasonScopeStillSkipsAlreadyDownloadedEpisodes() {
        val episodes = listOf(
            BatchEpisode("s1e1", "One", 1, 1, watched = true),
            BatchEpisode("s1e2", "Two", 1, 2),
            BatchEpisode("s1e3", "Three", 1, 3),
        )

        val result = DownloadBatchPlanner.episodesForScope(
            episodes,
            DownloadScope.SeasonUnwatched(1),
            existingLogicalKeys = setOf("show|1|2"),
            parentMetaId = "show",
        )

        assertEquals(listOf("s1e3"), result.map { it.videoId })
    }

    @Test
    fun reviewThresholdsCoverCountUnknownAndStorage() {
        val selected = SourceSelectionResult.Selected(
            "https://a/file",
            SourceFacts(sizeBytes = 600),
            addonA,
            calculatedCapBytes = 1_000,
        )
        val batch = DownloadBatch(
            id = "batch",
            scope = DownloadScope.Season(1),
            presetSnapshot = DownloadPreset.Balanced,
            sourcePolicySnapshot = DownloadSourcePolicy(),
            entries = listOf(DownloadBatchEntry("1", "v", "Title", selection = selected)),
            createdAtEpochMs = 1,
        )
        assertTrue(batch.requiresReview(freeStorageBytes = 1_000))
    }

    @Test
    fun skippedEntriesForceReviewSoManualSelectionIsReachable() {
        val batch = DownloadBatch(
            id = "manual",
            scope = DownloadScope.Episode(1, 1),
            presetSnapshot = DownloadPreset.Balanced,
            sourcePolicySnapshot = DownloadSourcePolicy(),
            entries = listOf(
                DownloadBatchEntry(
                    id = "entry",
                    videoId = "video",
                    title = "Episode",
                    state = DownloadBatchEntryState.SKIPPED,
                    selection = SourceSelectionResult.NoMatch("No direct source"),
                ),
            ),
            createdAtEpochMs = 1,
        )

        assertTrue(batch.requiresReview)
    }

    @Test
    fun qualityTakesTheLargestSourceThatStillFitsTheCap() {
        // The reported expectation: between a 5 GB and a 6 GB HDR 4K, both inside
        // the cap and equal on every other key, take the 6 GB. Ordering used to be
        // ascending, so the 5 GB won.
        val preset = DownloadPreset.Quality
        val cap = preset.sizeCapBytes(runtimeMinutes = 120, isEpisode = false)
        val result = PresetSourceSelector.select(
            candidates = listOf(
                candidate(addonA, "https://a/5gb", VideoResolution.UHD_2160, 5_000_000_000L),
                candidate(addonA, "https://a/6gb", VideoResolution.UHD_2160, 6_000_000_000L),
            ),
            preset = preset,
            policy = DownloadSourcePolicy(),
            runtimeMinutes = 120,
            isEpisode = false,
        )

        val selected = assertIs<SourceSelectionResult.Selected>(result)
        assertEquals("https://a/6gb", selected.streamUrl)
        assertTrue(6_000_000_000L <= cap, "the 6 GB candidate must be inside the cap for this test")
    }

    @Test
    fun anythingOverTheCapIsStillRefusedWhenTakingTheLargest() {
        val result = PresetSourceSelector.select(
            candidates = listOf(
                candidate(addonA, "https://a/huge", VideoResolution.UHD_2160, 400_000_000_000L),
            ),
            preset = DownloadPreset.Quality,
            policy = DownloadSourcePolicy(),
            runtimeMinutes = 60,
            isEpisode = false,
        )

        assertIs<SourceSelectionResult.NoMatch>(result)
    }

    @Test
    fun saverStillTakesTheSmallest() {
        val result = PresetSourceSelector.select(
            candidates = listOf(
                candidate(addonA, "https://a/big", VideoResolution.HD_720, 700_000_000L),
                candidate(addonA, "https://a/small", VideoResolution.HD_720, 400_000_000L),
            ),
            preset = DownloadPreset.Saver,
            policy = DownloadSourcePolicy(),
            runtimeMinutes = 60,
            isEpisode = false,
        )

        val selected = assertIs<SourceSelectionResult.Selected>(result)
        assertEquals("https://a/small", selected.streamUrl)
    }

    @Test
    fun aCachedSourceBreaksATieBetweenEqualCandidates() {
        val result = PresetSourceSelector.select(
            candidates = listOf(
                candidate(addonA, "https://a/cold", VideoResolution.UHD_2160, 5_000_000_000L, isDebridReady = false),
                candidate(addonA, "https://a/warm", VideoResolution.UHD_2160, 5_000_000_000L, isDebridReady = true),
            ),
            preset = DownloadPreset.Quality,
            policy = DownloadSourcePolicy(),
            runtimeMinutes = 120,
            isEpisode = false,
        )

        val selected = assertIs<SourceSelectionResult.Selected>(result)
        assertEquals("https://a/warm", selected.streamUrl)
    }

    @Test
    fun beingCachedNeverCostsAResolutionTier() {
        // A cached 1080p must not beat an uncached 4K. The 4K wins, and because it
        // is not cached it goes to review rather than starting on its own.
        val result = PresetSourceSelector.select(
            candidates = listOf(
                candidate(addonA, "https://a/1080-warm", VideoResolution.FULL_HD_1080, 3_000_000_000L, isDebridReady = true),
                candidate(addonA, "https://a/2160-cold", VideoResolution.UHD_2160, 5_000_000_000L, isDebridReady = false),
            ),
            preset = DownloadPreset.Quality,
            policy = DownloadSourcePolicy(),
            runtimeMinutes = 120,
            isEpisode = false,
        )

        val approval = assertIs<SourceSelectionResult.ApprovalNeeded>(result)
        assertEquals("https://a/2160-cold", approval.streamUrl)
    }

    @Test
    fun sourcesThatDoNotReportCachingAreUnaffected() {
        // Direct HTTP and addons that say nothing about caching must still be
        // selected automatically rather than pushed into review.
        val result = PresetSourceSelector.select(
            candidates = listOf(
                candidate(addonA, "https://a/direct", VideoResolution.UHD_2160, 5_000_000_000L, isDebridReady = null),
            ),
            preset = DownloadPreset.Quality,
            policy = DownloadSourcePolicy(),
            runtimeMinutes = 120,
            isEpisode = false,
        )

        assertIs<SourceSelectionResult.Selected>(result)
    }

    @Test
    fun unknownSizesStillSortLastUnderBothSizePreferences() {
        for (preset in listOf(DownloadPreset.Quality, DownloadPreset.Saver)) {
            val result = PresetSourceSelector.select(
                candidates = listOf(
                    candidate(addonA, "https://a/unknown", preset.targetResolution, null),
                    candidate(addonA, "https://a/known", preset.targetResolution, 400_000_000L),
                ),
                preset = preset,
                policy = DownloadSourcePolicy(),
                runtimeMinutes = 60,
                isEpisode = false,
            )

            val selected = assertIs<SourceSelectionResult.Selected>(result)
            assertEquals("https://a/known", selected.streamUrl, "unknown size should not win for ${preset.id}")
        }
    }

    private fun candidate(
        key: AddonSourceKey,
        url: String,
        resolution: VideoResolution,
        size: Long?,
        addonOrder: Int = 0,
        provider: String? = null,
        isDebridReady: Boolean? = null,
    ): DownloadSourceCandidate {
        val stream = StreamItem(
            url = url,
            addonName = key.manifestId,
            addonId = key.manifestId,
        )
        return DownloadSourceCandidate(
            stream = stream,
            addonKey = key,
            facts = SourceFacts(
                resolution = resolution,
                sizeBytes = size,
                reportedSizes = listOfNotNull(size),
                providerName = provider,
                codec = "HEVC",
                isDebridReady = isDebridReady,
            ),
            resolvedUrl = url,
            addonOrder = addonOrder,
        )
    }
}
