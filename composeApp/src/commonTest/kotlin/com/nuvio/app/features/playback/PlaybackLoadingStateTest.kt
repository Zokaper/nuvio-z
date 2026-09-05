package com.nuvio.app.features.playback

import com.nuvio.app.features.downloads.SourceFacts
import com.nuvio.app.features.downloads.VideoResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The loading screen's state, covered without a Compose runtime.
 *
 * The screen itself is pixels and cannot be asserted on here; what *can* be pinned is that it
 * is derived rather than faked, and that it stays usable when the source told us nothing -
 * which is the common case among sources that are about to fail, and therefore exactly the
 * case the screen exists for.
 */
class PlaybackLoadingStateTest {

    private fun size(bytes: Long): String = "${bytes / 1_000_000_000}.0 GB"

    @Test
    fun `five slots always in order`() {
        val slots = PlaybackLoadingFacts.facts(null, ::size) { it.uppercase() }.map { it.slot }
        assertEquals(PlaybackFactSlot.entries, slots)
    }

    @Test
    fun `a full source fills every slot`() {
        val facts = SourceFacts(
            resolution = VideoResolution.UHD_2160,
            dynamicRange = emptySet(),
            audioCodecs = setOf("TRUEHD", "ATMOS"),
            audioChannels = 8,
            languages = setOf("en"),
            subtitleLanguages = setOf("en"),
            sizeBytes = 14_500_000_000L,
        )
        val result = PlaybackLoadingFacts.facts(facts, { "14.5 GB" }) { "English" }
        assertEquals(
            listOf(
                PlaybackLoadingFact(PlaybackFactSlot.RESOLUTION, "4K"),
                PlaybackLoadingFact(PlaybackFactSlot.LANGUAGE, "English / English"),
                PlaybackLoadingFact(PlaybackFactSlot.DYNAMIC_RANGE, "SDR"),
                PlaybackLoadingFact(PlaybackFactSlot.AUDIO, "Atmos 7.1"),
                PlaybackLoadingFact(PlaybackFactSlot.SIZE, "14.5 GB"),
            ),
            result,
        )
    }

    @Test
    fun `a bare source keeps the slots and nulls the values`() {
        val result = PlaybackLoadingFacts.facts(SourceFacts(), ::size) { it.uppercase() }
        assertEquals(
            listOf(
                PlaybackLoadingFact(PlaybackFactSlot.RESOLUTION, null),
                PlaybackLoadingFact(PlaybackFactSlot.LANGUAGE, null),
                PlaybackLoadingFact(PlaybackFactSlot.DYNAMIC_RANGE, "SDR"),
                PlaybackLoadingFact(PlaybackFactSlot.AUDIO, null),
                PlaybackLoadingFact(PlaybackFactSlot.SIZE, null),
            ),
            result,
        )
    }

    @Test
    fun `no source at all leaves the range slot null too`() {
        val result = PlaybackLoadingFacts.facts(null, ::size) { it.uppercase() }
        assertEquals(
            listOf(
                PlaybackLoadingFact(PlaybackFactSlot.RESOLUTION, null),
                PlaybackLoadingFact(PlaybackFactSlot.LANGUAGE, null),
                PlaybackLoadingFact(PlaybackFactSlot.DYNAMIC_RANGE, null),
                PlaybackLoadingFact(PlaybackFactSlot.AUDIO, null),
                PlaybackLoadingFact(PlaybackFactSlot.SIZE, null),
            ),
            result,
        )
    }

    @Test
    fun `an untagged release reads SDR`() {
        assertEquals("SDR", PlaybackLoadingFacts.dynamicRangeSlot(SourceFacts()))
    }

    @Test
    fun `a tagged release keeps its tag`() {
        val facts = SourceFacts(dynamicRange = setOf("HDR10_PLUS"))
        assertEquals("HDR10+", PlaybackLoadingFacts.dynamicRangeSlot(facts))
    }

    @Test
    fun `an unstated language yields a null slot`() {
        assertNull(PlaybackLoadingFacts.languagePairLabel(SourceFacts()) { it.uppercase() })
    }

    @Test
    fun `a subtitle-only claim does not become an audio claim`() {
        val facts = SourceFacts(subtitleLanguages = setOf("en"))
        assertEquals(
            "— / English",
            PlaybackLoadingFacts.languagePairLabel(facts) { "English" },
        )
    }

    @Test
    fun `MULTi survives`() {
        val facts = SourceFacts(isMultiLanguage = true)
        assertEquals(
            "MULTi / —",
            PlaybackLoadingFacts.languagePairLabel(facts) { it.uppercase() },
        )
    }

    @Test
    fun `extra languages are counted, not listed`() {
        val facts = SourceFacts(languages = setOf("en", "it", "fr"))
        assertEquals(
            "English +2 / —",
            PlaybackLoadingFacts.languagePairLabel(facts) { if (it == "en") "English" else it.uppercase() },
        )
    }

    /**
     * One codec word, best first - a release routinely carries Atmos over a TrueHD base layer,
     * and naming both spends a chip saying the same thing twice.
     */
    @Test
    fun `audio names the best codec only`() {
        val facts = SourceFacts(audioCodecs = setOf("TRUEHD", "ATMOS"), audioChannels = 8)
        assertEquals("Atmos 7.1", PlaybackLoadingFacts.audioLabel(facts))
    }

    @Test
    fun `channels alone are still reported`() {
        assertEquals("5.1", PlaybackLoadingFacts.audioLabel(SourceFacts(audioChannels = 6)))
    }

    /**
     * `isDebridReady` is tri-state on purpose: a service that names its cache state only inside
     * a display string leaves it null. "Cached" over a link that then needs minting is the
     * untruth the tri-state exists to prevent, so only `true` prints the word.
     */
    @Test
    fun `cached is printed from a positive answer only`() {
        assertEquals(
            "TorBox · Cached",
            PlaybackLoadingFacts.providerLine(
                SourceFacts(debridService = "TorBox", isDebridReady = true),
            ),
        )
        assertEquals(
            "TorBox",
            PlaybackLoadingFacts.providerLine(
                SourceFacts(debridService = "TorBox", isDebridReady = null),
            ),
        )
        assertEquals(
            "TorBox",
            PlaybackLoadingFacts.providerLine(
                SourceFacts(debridService = "TorBox", isDebridReady = false),
            ),
        )
    }

    @Test
    fun `the debrid service outranks the addon for the provider line`() {
        assertEquals(
            "TorBox",
            PlaybackLoadingFacts.providerLine(
                SourceFacts(providerName = "Torrentio", debridService = "TorBox"),
            ),
        )
    }

    /** "Attempt 5 of 3" must be unreachable however the counter got there. */
    @Test
    fun `the displayed attempt never exceeds the budget`() {
        val state = PlaybackLoadingState(
            step = PlaybackProgressStep.StartingPlayback,
            attempt = 9,
        )
        assertEquals(PLAYBACK_MAX_ATTEMPTS, state.displayAttempt)
        assertTrue(state.showsAttempt)
    }

    @Test
    fun `a first attempt does not announce itself`() {
        val state = PlaybackLoadingState(step = PlaybackProgressStep.FindingSources)
        assertEquals(1, state.displayAttempt)
        assertTrue(!state.showsAttempt)
    }

    /**
     * The release name is the user-facing half of the content-identity guard: it is how a
     * *Daredevil* request answered with a *Born Again* release is caught before it plays.
     */
    @Test
    fun `the release name is carried through for the user to read`() {
        val name = "Daredevil.Born.Again.2026.S02E06.Requiem.2160p.DSNP.WEB-DL.mkv"
        val state = PlaybackLoadingState(
            step = PlaybackProgressStep.StartingPlayback,
            facts = SourceFacts(filename = name),
        )
        assertEquals(name, state.releaseName)
    }

    @Test
    fun `a blank release name is absent rather than empty`() {
        val state = PlaybackLoadingState(
            step = PlaybackProgressStep.StartingPlayback,
            facts = SourceFacts(filename = "   "),
        )
        assertNull(state.releaseName)
    }

    @Test
    fun `a failure is carried with its reason`() {
        val state = PlaybackLoadingState(
            step = PlaybackProgressStep.StartingPlayback,
            attempt = 2,
            failure = PlaybackProgressFailure(label = "4K · WEB-DL · TorBox", reason = "stream not found"),
        )
        assertEquals("stream not found", state.failure?.reason)
        assertTrue(state.showsAttempt)
    }
}
