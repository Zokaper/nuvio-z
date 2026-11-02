package com.nuvio.app.features.playback

import com.nuvio.app.features.playback.PlaybackStallMonitor.Internet
import com.nuvio.app.features.playback.PlaybackStallMonitor.Link
import com.nuvio.app.features.playback.PlaybackStallMonitor.Reason
import com.nuvio.app.features.playback.PlaybackStallMonitor.Sample
import com.nuvio.app.features.playback.PlaybackStallMonitor.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The mid-playback starvation verdict.
 *
 * The cases fall in two halves. The first half is what it must catch - the re-buffering loop the
 * maintainer reported, a stall that never ends, a buffer that has stopped growing. The second half
 * is everything it must **not** blame on the source, and it is the longer half on purpose: a false
 * "starved" costs a swap, and the deleted P7 detector died of never having been held to this.
 */
class PlaybackStallMonitorTest {

    /**
     * A simulated player. [play] advances the playhead at real time with a healthy buffer; [stall]
     * freezes it with the engine reporting buffering. The buffer ahead is what the engine would
     * report: plenty while playing, nearly nothing while stalled.
     */
    private class Player(
        val pollMs: Long = 500L,
        var link: Link = Link.Up,
        var internet: Internet = Internet.Online,
        val isManifest: Boolean = false,
        val isLocal: Boolean = false,
    ) {
        var now = 0L
        var position = 60_000L
        var buffered = position + 30_000L
        var seekGeneration = 0L
        var state = PlaybackStallMonitor.initial(now, position)
        val verdicts = mutableListOf<Verdict>()

        private fun sample(wantsToPlay: Boolean, buffering: Boolean, held: Boolean = false) {
            state = PlaybackStallMonitor.observe(
                state,
                Sample(
                    elapsedMs = now,
                    positionMs = position,
                    bufferedPositionMs = buffered,
                    wantsToPlay = wantsToPlay,
                    engineSaysBuffering = buffering,
                    seekGeneration = seekGeneration,
                    isManifest = isManifest,
                    isLocal = isLocal,
                    isHeld = held,
                    link = link,
                    internet = internet,
                ),
            )
            verdicts += state.verdict
        }

        fun play(ms: Long) = repeat((ms / pollMs).toInt()) {
            now += pollMs
            position += pollMs
            buffered = maxOf(buffered, position + 30_000L)
            sample(wantsToPlay = true, buffering = false)
        }

        /** Frozen picture. [bufferGrowthPerPoll] > 0 is a trickle; 0 is a dead connection. */
        fun stall(ms: Long, bufferGrowthPerPoll: Long = 100L) {
            buffered = position + 500L
            repeat((ms / pollMs).toInt()) {
                now += pollMs
                buffered += bufferGrowthPerPoll
                sample(wantsToPlay = true, buffering = true)
            }
        }

        fun pause(ms: Long) = repeat((ms / pollMs).toInt()) {
            now += pollMs
            sample(wantsToPlay = false, buffering = false)
        }

        fun held(ms: Long, bufferGrowthPerPoll: Long) {
            buffered = position + 500L
            repeat((ms / pollMs).toInt()) {
                now += pollMs
                buffered += bufferGrowthPerPoll
                sample(wantsToPlay = false, buffering = true, held = true)
            }
        }

        fun seekTo(target: Long, viaGeneration: Boolean) {
            position = target
            buffered = target
            if (viaGeneration) seekGeneration++
        }

        val starved get() = state.verdict == Verdict.SourceStarved
    }

    // --- What it must catch -------------------------------------------------------------------

    @Test
    fun `the reported loop - plays, loads every few seconds - is chronic starvation`() {
        val player = Player()
        player.play(20_000L)
        repeat(3) {
            player.stall(3_000L)
            player.play(6_000L)
        }
        assertTrue(player.starved)
        assertEquals(Reason.Chronic, player.state.reason)
    }

    @Test
    fun `one stall that never ends is sustained starvation`() {
        val player = Player()
        player.play(20_000L)
        player.stall(16_000L, bufferGrowthPerPoll = 150L)
        assertTrue(player.starved)
        assertEquals(Reason.Sustained, player.state.reason)
    }

    @Test
    fun `a starving buffer that stops growing is dead before it is sustained`() {
        val player = Player()
        player.play(20_000L)
        player.stall(13_000L, bufferGrowthPerPoll = 0L)
        assertTrue(player.starved)
        assertEquals(Reason.Dead, player.state.reason)
    }

    @Test
    fun `a verdict latches until the caller rearms it`() {
        val player = Player()
        player.play(20_000L)
        player.stall(16_000L)
        assertTrue(player.starved)
        player.play(30_000L)
        assertTrue(player.starved, "a latched verdict must not quietly clear itself")
        player.state = PlaybackStallMonitor.rearm(player.state, player.now)
        player.play(1_000L)
        assertEquals(Verdict.Healthy, player.state.verdict)
    }

    @Test
    fun `the same story reaches the same verdict at Android's poll rate and desktop's`() {
        for (pollMs in listOf(250L, 500L)) {
            val player = Player(pollMs = pollMs)
            player.play(20_000L)
            repeat(3) {
                player.stall(3_000L)
                player.play(6_000L)
            }
            assertEquals(Reason.Chronic, player.state.reason, "poll $pollMs ms")
        }
    }

    // --- What it must not blame on the source -------------------------------------------------

    @Test
    fun `a single rebuffer is never a verdict`() {
        val player = Player()
        player.play(20_000L)
        player.stall(8_000L)
        assertEquals(Verdict.Rebuffering, player.state.verdict)
        player.play(60_000L)
        assertEquals(Verdict.Healthy, player.state.verdict)
        assertFalse(Verdict.SourceStarved in player.verdicts)
    }

    @Test
    fun `occasional rebuffers spread over a long episode are not chronic`() {
        val player = Player()
        player.play(20_000L)
        repeat(4) {
            player.stall(3_000L)
            player.play(70_000L)
        }
        assertFalse(Verdict.SourceStarved in player.verdicts)
    }

    @Test
    fun `stalls during the first-frame grace do not count`() {
        val player = Player()
        repeat(3) {
            player.stall(2_000L)
            player.play(1_000L)
        }
        player.play(30_000L)
        assertFalse(Verdict.SourceStarved in player.verdicts)
    }

    @Test
    fun `the device being offline is never the source's fault, however long it lasts`() {
        val player = Player()
        player.play(20_000L)
        player.link = Link.Down
        player.stall(90_000L, bufferGrowthPerPoll = 0L)
        assertEquals(Verdict.DeviceOffline, player.state.verdict)
        assertFalse(Verdict.SourceStarved in player.verdicts)
    }

    @Test
    fun `a probe that says offline is as good as a link that says down`() {
        val player = Player(link = Link.Unknown)
        player.play(20_000L)
        player.internet = Internet.Offline
        player.stall(40_000L, bufferGrowthPerPoll = 0L)
        assertEquals(Verdict.DeviceOffline, player.state.verdict)
    }

    @Test
    fun `stalls just before a drop are forgotten, and the source is re-graced on return`() {
        val player = Player()
        player.play(20_000L)
        player.stall(3_000L)
        player.play(4_000L)
        player.stall(3_000L)
        player.link = Link.Down
        player.stall(20_000L, bufferGrowthPerPoll = 0L)
        player.link = Link.Up
        // The reconnect refill: it would be the third stall, and the second dead buffer.
        player.stall(4_000L)
        player.play(20_000L)
        assertFalse(Verdict.SourceStarved in player.verdicts)
        assertTrue(player.state.episodeStartsMs.isEmpty())
    }

    @Test
    fun `unconfirmed connectivity asks for a probe instead of convicting the source`() {
        val player = Player(link = Link.Unknown, internet = Internet.Unknown)
        player.play(20_000L)
        player.stall(16_000L)
        assertEquals(Verdict.AwaitingConnectivity, player.state.verdict)
        assertTrue(player.state.wantsConnectivityProbe)
        player.internet = Internet.Online
        player.stall(500L)
        assertTrue(player.starved)
    }

    @Test
    fun `a probe is requested early in a stall while connectivity is unknown`() {
        val player = Player(link = Link.Unknown, internet = Internet.Unknown)
        player.play(20_000L)
        player.stall(3_000L)
        assertTrue(player.state.wantsConnectivityProbe)
        assertNotEquals(Verdict.SourceStarved, player.state.verdict)
    }

    @Test
    fun `buffering after a seek belongs to the seek`() {
        for (viaGeneration in listOf(true, false)) {
            val player = Player()
            player.play(20_000L)
            player.seekTo(1_800_000L, viaGeneration)
            player.stall(12_000L)
            player.play(30_000L)
            assertFalse(Verdict.SourceStarved in player.verdicts, "viaGeneration=$viaGeneration")
            assertTrue(Verdict.SeekBuffering in player.verdicts, "viaGeneration=$viaGeneration")
        }
    }

    @Test
    fun `a seek that takes far too long is still eventually sustained`() {
        val player = Player()
        player.play(20_000L)
        player.seekTo(1_800_000L, viaGeneration = true)
        player.stall(PlaybackStallMonitor.SEEK_GRACE_MS + 10_000L)
        assertNotEquals(Verdict.SourceStarved, player.state.verdict, "grace time is not charged")
        player.stall(6_000L)
        assertEquals(Reason.Sustained, player.state.reason)
    }

    @Test
    fun `a slow poll during a stall is not mistaken for a seek`() {
        val player = Player()
        player.play(20_000L)
        player.stall(2_000L)
        // Ten seconds between samples - an app briefly backgrounded - with the picture frozen.
        player.now += 10_000L
        player.stall(4_000L)
        assertEquals(Reason.Sustained, player.state.reason)
    }

    @Test
    fun `a paused viewer is never starved`() {
        val player = Player()
        player.play(20_000L)
        player.pause(120_000L)
        player.play(10_000L)
        assertFalse(Verdict.SourceStarved in player.verdicts)
    }

    @Test
    fun `a party hold freezes the experience clocks`() {
        val player = Player()
        player.play(20_000L)
        player.held(40_000L, bufferGrowthPerPoll = 200L)
        player.play(10_000L)
        assertFalse(Verdict.SourceStarved in player.verdicts)
    }

    @Test
    fun `a party hold does not make a dead source immortal`() {
        val player = Player()
        player.play(20_000L)
        player.held(13_000L, bufferGrowthPerPoll = 0L)
        assertEquals(Reason.Dead, player.state.reason)
    }

    @Test
    fun `adaptive streams are left to adapt but not to hang`() {
        val chronic = Player(isManifest = true)
        chronic.play(20_000L)
        repeat(4) {
            chronic.stall(3_000L)
            chronic.play(6_000L)
        }
        assertFalse(Verdict.SourceStarved in chronic.verdicts)

        val hung = Player(isManifest = true)
        hung.play(20_000L)
        hung.stall(16_000L)
        assertEquals(Reason.Sustained, hung.state.reason)
    }

    @Test
    fun `a downloaded file is never blamed`() {
        val player = Player(isLocal = true)
        player.play(20_000L)
        player.stall(30_000L, bufferGrowthPerPoll = 0L)
        assertFalse(Verdict.SourceStarved in player.verdicts)
    }
}
