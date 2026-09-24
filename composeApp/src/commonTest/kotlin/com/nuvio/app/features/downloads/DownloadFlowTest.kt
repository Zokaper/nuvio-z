package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamDebridCacheStatus
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 9 stage 6: the flows. How selector decisions become batch entries (and so what the
 * Downloads screen can offer), and where each mode sends a download request.
 */
class DownloadFlowTest {
    private val addon = AddonSourceKey("a", "https://a/manifest.json")
    private val gb = 1_000_000_000L

    private fun candidate(
        name: String,
        resolution: VideoResolution?,
        size: Long?,
        cached: Boolean? = null,
        debrid: Boolean = false,
        serviceSays: StreamDebridCacheState? = null,
    ): DownloadSourceCandidate {
        val url = "https://a/$name.mkv"
        val stream = StreamItem(
            name = name,
            url = url,
            addonName = "a",
            addonId = "a",
            debridCacheStatus = serviceSays?.let { StreamDebridCacheStatus("rd", "Real-Debrid", it) },
        )
        return DownloadSourceCandidate(
            stream = stream,
            addonKey = addon,
            facts = SourceFacts(
                resolution = resolution,
                sizeBytes = size,
                reportedSizes = listOfNotNull(size),
                isDebridReady = cached,
            ),
            resolvedUrl = if (debrid) null else url,
            sourceOrigin = if (debrid) DownloadSourceOrigin(stream) else null,
        )
    }

    private fun context(policy: DownloadPolicy = DownloadPolicy()) = DownloadSourceSelector.Context(
        policy = policy,
        runtimeMinutes = 60,
        isEpisode = true,
        rankingPreferences = SourceRankingPreferences(),
        playbackDynamicRange = DynamicRangePolicy.ANY,
        addonFilter = DownloadSourcePolicy(),
    )

    private val episode = DownloadTarget(videoId = "tt1:1:3", title = "Three", contentType = "series", season = 1, episode = 3)

    private fun entry(candidates: List<DownloadSourceCandidate>, policy: DownloadPolicy = DownloadPolicy()) =
        DownloadBatchCoordinator.entryFor(
            episode,
            DownloadSourceSelector.select(candidates, context(policy)),
            candidates,
            context(policy),
        )

    // --- cache evidence ------------------------------------------------------------------------

    @Test
    fun theServicesOwnCacheAnswerMakesAnUnmarkedTorrentUsable() {
        val torrent = candidate("t", VideoResolution.FULL_HD_1080, 1 * gb, debrid = true, serviceSays = StreamDebridCacheState.CACHED)
        assertEquals(DownloadCacheEvidence.CACHED, DownloadSourceSelector.cacheEvidence(torrent))
        assertEquals(DownloadEntryDecisionKind.NOTHING_CACHED, entry(listOf(torrent.copy(stream = torrent.stream.copy(debridCacheStatus = null)))).decision)
        assertEquals(DownloadBatchEntryState.READY, entry(listOf(torrent)).state)
    }

    @Test
    fun theServiceSayingNotCachedBeatsAnAddonClaim() {
        val torrent = candidate("t", VideoResolution.FULL_HD_1080, 1 * gb, cached = true, debrid = true, serviceSays = StreamDebridCacheState.NOT_CACHED)
        assertEquals(DownloadCacheEvidence.NOT_USABLE, DownloadSourceSelector.cacheEvidence(torrent))
        assertTrue(DownloadSourceSelector.isKnownNotCached(torrent))
    }

    // --- decisions become entries ------------------------------------------------------------

    @Test
    fun aPickIsReadyWithTheLevelAsItsCap() {
        val made = entry(listOf(candidate("fits", VideoResolution.FULL_HD_1080, 19 * gb / 10)))
        assertEquals(DownloadBatchEntryState.READY, made.state)
        val selected = assertIs<SourceSelectionResult.Selected>(made.selection)
        assertEquals(2 * gb, selected.calculatedCapBytes)
        assertTrue(made.hasUsableSources == true)
        assertNull(made.decision)
    }

    @Test
    fun overTheLimitWaitsForAllowWithTheSmallestFile() {
        val made = entry(listOf(candidate("big", VideoResolution.FULL_HD_1080, 9 * gb), candidate("bigger", VideoResolution.FULL_HD_1080, 12 * gb)))
        assertEquals(DownloadEntryDecisionKind.OVER_LIMIT, made.decision)
        assertTrue(made.canBeApproved)
        assertTrue(made.needsManualSource.not(), "an approvable entry is not a manual-pick entry")
        assertEquals(9 * gb, assertIs<SourceSelectionResult.ApprovalNeeded>(made.selection).facts.sizeBytes)
    }

    @Test
    fun aMissingResolutionOffersTheNearestAsUseNearest() {
        val made = entry(listOf(candidate("hd", VideoResolution.HD_720, 1 * gb), candidate("uhd", VideoResolution.UHD_2160, 7 * gb)))
        assertEquals(DownloadEntryDecisionKind.RESOLUTION_MISSING, made.decision)
        assertTrue(made.canBeApproved)
        val nearest = assertIs<SourceSelectionResult.ApprovalNeeded>(made.selection)
        assertEquals(720, nearest.facts.resolution?.height, "lower first")
        assertTrue(nearest.reason.contains("1080p") && nearest.reason.contains("720p"))
    }

    @Test
    fun nothingCachedOffersCheckAgainAndNeverTheManualList() {
        val made = entry(listOf(candidate("t", VideoResolution.FULL_HD_1080, 1 * gb, cached = false, debrid = true)))
        assertEquals(DownloadEntryDecisionKind.NOTHING_CACHED, made.decision)
        assertEquals(false, made.hasUsableSources)
        assertTrue(made.canCheckAgain)
        assertFalse(made.needsManualSource)
    }

    @Test
    fun noSourcesOffersCheckAgain() {
        val made = entry(emptyList())
        assertEquals(DownloadEntryDecisionKind.NO_SOURCES, made.decision)
        assertTrue(made.canCheckAgain)
        assertFalse(made.needsManualSource)
    }

    @Test
    fun anAssistedRowIsApprovedEvenOverTheLimit() {
        val all = listOf(candidate("big", VideoResolution.UHD_2160, 40 * gb), candidate("hd", VideoResolution.FULL_HD_1080, 1 * gb))
        val made = DownloadBatchCoordinator.resolutionEntry(episode, all, 2160, context())
        assertEquals(DownloadBatchEntryState.READY, made.state)
        val selected = assertIs<SourceSelectionResult.Selected>(made.selection)
        assertEquals(0L, selected.calculatedCapBytes, "the user saw the size: no cap may re-ask")
    }

    @Test
    fun anAssistedChoiceTheEpisodeLacksBecomesUseNearest() {
        val made = DownloadBatchCoordinator.resolutionEntry(
            episode,
            listOf(candidate("hd", VideoResolution.HD_720, 1 * gb)),
            1080,
            context(),
        )
        assertEquals(DownloadEntryDecisionKind.RESOLUTION_MISSING, made.decision)
    }

    @Test
    fun aManualPickEntryWaitsForThePick() {
        val made = DownloadBatchCoordinator.manualPickEntry(episode)
        assertEquals(DownloadEntryDecisionKind.MANUAL_PICK, made.decision)
        assertTrue(made.needsManualSource)
        assertTrue(made.isAwaitingPick)
    }

    // --- the manual source list never plays -------------------------------------------------

    @Test
    fun theManualDownloadLaunchIsADownloadIntent() {
        val launch = manualDownloadStreamLaunch(
            profileId = 1,
            title = DownloadTitleRef("tt1", "series", "series", "Show"),
            target = episode,
        )
        assertTrue(launch.downloadIntent, "a tap enqueues")
        assertTrue(launch.manualSelection, "the list never auto-plays")
        assertEquals(1, launch.seasonNumber)
        assertEquals(3, launch.episodeNumber)
    }

    // --- routing ------------------------------------------------------------------------------

    private val film = MetaDetails(id = "tt9", type = "movie", name = "Film", runtime = "100 min")
    private val show = MetaDetails(
        id = "tt1",
        type = "series",
        name = "Show",
        videos = (1..3).map { MetaVideo(id = "tt1:1:$it", title = "E$it", season = 1, episode = it, released = "2001-01-01") },
    )

    private val events = mutableListOf<DownloadFlowEvent>()
    private val notices = object : DownloadFlowNotices {
        val log = mutableListOf<String>()
        override fun findingSource() { log += "finding" }
        override fun started(item: DownloadItem?, height: Int?, bytes: Long?, onChange: (() -> Unit)?) { log += "started" }
        override fun startedMany(count: Int, needAttention: Int) { log += "many" }
        override fun needsAttention() { log += "attention" }
        override fun nothingNew() { log += "nothing" }
    }

    private val defaultMode = DownloadFlowController.modeProvider
    private val defaultPolicy = DownloadFlowController.policyProvider
    private val defaultMetered = DownloadFlowController.isMeteredNow
    private val defaultNotices = DownloadFlowController.notices

    @BeforeTest
    fun setUp() {
        DownloadFlowController.resetForTests(Dispatchers.Unconfined)
        DownloadFlowController.notices = notices
        DownloadFlowController.isMeteredNow = { false }
        DownloadFlowController.policyProvider = { DownloadPolicy() }
        DownloadBatchCoordinator.contextOverride = { _, policy -> context(policy) }
    }

    @AfterTest
    fun tearDown() {
        DownloadBatchCoordinator.discoverOverride = null
        DownloadBatchCoordinator.contextOverride = null
        DownloadFlowController.modeProvider = defaultMode
        DownloadFlowController.policyProvider = defaultPolicy
        DownloadFlowController.isMeteredNow = defaultMetered
        DownloadFlowController.notices = defaultNotices
        DownloadFlowController.resetForTests(Dispatchers.Default)
    }

    @Test
    fun manualSingleOpensTheDownloadSourceList() {
        DownloadFlowController.modeProvider = { DownloadMode.MANUAL }
        val event = runBlocking {
            val pending = async(Dispatchers.Unconfined) { DownloadFlowController.events.first() }
            DownloadFlowController.request(film, DownloadScope.Movie)
            withTimeout(2_000) { pending.await() }
        }
        val open = assertIs<DownloadFlowEvent.OpenManualSourceList>(event)
        assertEquals("tt9", open.target.videoId)
        assertEquals(DownloadFlowStep.Idle, DownloadFlowController.step.value)
    }

    @Test
    fun assistedSingleShowsOneRowPerResolutionPreselectingThePreferred() {
        DownloadFlowController.modeProvider = { DownloadMode.ASSISTED }
        DownloadBatchCoordinator.discoverOverride = {
            listOf(
                candidate("uhd", VideoResolution.UHD_2160, 7 * gb),
                candidate("hd", VideoResolution.FULL_HD_1080, 19 * gb / 10),
                candidate("hd-small", VideoResolution.FULL_HD_1080, 1 * gb),
                candidate("sd", VideoResolution.HD_720, 600_000_000L),
            )
        }
        DownloadFlowController.request(film, DownloadScope.Movie)
        val step = assertIs<DownloadFlowStep.ChooseResolution>(DownloadFlowController.step.value)
        assertEquals(listOf(2160, 1080, 720), step.rows.map { it.height })
        assertEquals(1080, step.preselectedHeight)
        assertEquals(19 * gb / 10, step.rows.first { it.height == 1080 }.totalBytes, "the best that fits, not the smallest")
        assertTrue(step.offersChooseManually)
    }

    @Test
    fun assistedWithNothingCachedSaysSoAndOffersNoManualList() {
        DownloadFlowController.modeProvider = { DownloadMode.ASSISTED }
        DownloadBatchCoordinator.discoverOverride = {
            listOf(candidate("t", VideoResolution.FULL_HD_1080, 1 * gb, cached = false, debrid = true))
        }
        DownloadFlowController.request(film, DownloadScope.Movie)
        val step = assertIs<DownloadFlowStep.NothingToDownload>(DownloadFlowController.step.value)
        assertEquals(DownloadEntryDecisionKind.NOTHING_CACHED, step.kind)
        assertFalse(step.offersChooseManually)
    }

    @Test
    fun theWholeShowAsksForSeasonsInEveryMode() {
        for (mode in DownloadMode.entries) {
            DownloadFlowController.modeProvider = { mode }
            DownloadFlowController.request(show, DownloadScope.SelectedSeasons(emptySet()))
            val step = assertIs<DownloadFlowStep.ChooseSeasons>(DownloadFlowController.step.value, "$mode")
            assertEquals(setOf(1), step.selected)
            assertEquals(3, step.episodeCount)
            DownloadFlowController.dismiss()
        }
    }

    @Test
    fun noEventEverOpensThePlayer() {
        // The flow's navigation has exactly two destinations, neither of them the player. The
        // `when` is exhaustive, so a third destination does not compile until it is named here.
        fun destination(event: DownloadFlowEvent): String = when (event) {
            is DownloadFlowEvent.OpenManualSourceList -> "download source list"
            is DownloadFlowEvent.OpenChooseSources -> "choose sources"
        }
        assertEquals("choose sources", destination(DownloadFlowEvent.OpenChooseSources("b")))
    }
}
