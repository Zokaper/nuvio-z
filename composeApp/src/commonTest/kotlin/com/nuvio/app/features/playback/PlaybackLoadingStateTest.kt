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
    fun `chips read resolution, dynamic range, audio, language and size`() {
        val facts = SourceFacts(
            resolution = VideoResolution.UHD_2160,
            dynamicRange = setOf("HDR10"),
            audioCodecs = setOf("DD_PLUS"),
            audioChannels = 6,
            languages = setOf("en"),
            sizeBytes = 8_000_000_000L,
        )
        assertEquals(
            listOf("4K", "HDR10", "DD+ 5.1", "EN", "8.0 GB"),
            PlaybackLoadingFacts.chips(facts, ::size).map { it.label },
        )
    }

    /**
     * A source nothing could be parsed from still renders a screen.
     *
     * Not a nicety: the releases most likely to be malformed are the ones most likely to be
     * failing, so a band that only works with full metadata is a band that goes blank precisely
     * when the user needs to know which source just died.
     */
    @Test
    fun `no facts yields no chips rather than placeholders`() {
        assertTrue(PlaybackLoadingFacts.chips(null, ::size).isEmpty())
        assertTrue(PlaybackLoadingFacts.chips(SourceFacts(), ::size).isEmpty())
    }

    /**
     * `SourceFacts.languages` documents that an empty set is *unstated*, not "no English".
     * Drawing "EN" from silence would invent a claim the release never made.
     */
    @Test
    fun `an unstated language draws no chip`() {
        assertNull(PlaybackLoadingFacts.languageLabel(SourceFacts()))
    }

    @Test
    fun `a multi-language release with no named tracks says MULTi`() {
        assertEquals(
            "MULTi",
            PlaybackLoadingFacts.languageLabel(SourceFacts(isMultiLanguage = true)),
        )
    }

    @Test
    fun `extra languages are counted, not listed`() {
        val facts = SourceFacts(languages = setOf("en", "it", "fr"))
        assertEquals("EN +2", PlaybackLoadingFacts.languageLabel(facts))
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
    fun `channels alone are still worth a chip`() {
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
