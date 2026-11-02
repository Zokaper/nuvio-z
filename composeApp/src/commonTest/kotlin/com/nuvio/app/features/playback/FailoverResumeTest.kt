package com.nuvio.app.features.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FailoverResumeTest {

    @Test
    fun `a source that died mid-episode hands its position to the next one`() {
        val noted = FailoverResume.note("tt1:1:2", hasStarted = true, lastPlayedMs = 2_400_000L)
        assertEquals(
            2_400_000L - FailoverResume.REWIND_MS,
            FailoverResume.resumePosition(noted, "tt1:1:2", launchResumeMs = 1_320_000L),
        )
    }

    @Test
    fun `a source that never started keeps the launch's resume point`() {
        val noted = FailoverResume.note("tt1:1:2", hasStarted = false, lastPlayedMs = 1_320_000L)
        assertNull(noted)
        assertEquals(1_320_000L, FailoverResume.resumePosition(noted, "tt1:1:2", 1_320_000L))
    }

    @Test
    fun `a few seconds of play is not worth overriding the launch for`() {
        assertNull(FailoverResume.note("tt1", hasStarted = true, lastPlayedMs = 4_000L))
    }

    @Test
    fun `a position noted for one episode never seeds another`() {
        val noted = FailoverResume.note("tt1:1:2", hasStarted = true, lastPlayedMs = 2_400_000L)
        assertEquals(0L, FailoverResume.resumePosition(noted, "tt1:1:3", launchResumeMs = 0L))
        assertNull(FailoverResume.resumePosition(noted, "tt1:1:3", launchResumeMs = null))
    }

    @Test
    fun `a pending failure is taken once`() {
        FailoverResume.pending = FailoverResume.note("tt1", hasStarted = true, lastPlayedMs = 600_000L)
        assertEquals(597_000L, FailoverResume.takePending()?.positionMs)
        assertNull(FailoverResume.takePending())
    }

    @Test
    fun `no video id means nothing is noted`() {
        assertNull(FailoverResume.note(null, hasStarted = true, lastPlayedMs = 2_400_000L))
        assertNull(FailoverResume.note(" ", hasStarted = true, lastPlayedMs = 2_400_000L))
    }
}
