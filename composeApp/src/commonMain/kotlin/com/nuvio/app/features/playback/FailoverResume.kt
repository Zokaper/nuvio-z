package com.nuvio.app.features.playback

// No imports: compiled standalone by group 2 of scripts/run-pure-suites.sh.

/**
 * Where the next source in an automatic failure chain starts, when the one before it failed.
 *
 * ⚠ **The route used to relaunch at the launch's own resume point.** That is right for a source
 * that never started - the viewer has not moved - and wrong for one that played forty minutes and
 * then died: the replacement opened at the start of the episode (or wherever it was resumed from)
 * and the viewer had to find their place again, which is exactly the moment a failover is
 * supposed to be invisible. The position the dead source reached is carried to the relaunch
 * instead, a few seconds early so the viewer re-enters mid-sentence rather than after it.
 *
 * Keyed by video so a position noted for one episode can never seed another: the chain can end
 * on the source list, and the next play from there may be a different episode altogether.
 */
object FailoverResume {

    /** Rewound from the failure point so the viewer re-enters with a little context. */
    const val REWIND_MS = 3_000L

    /** Positions this close to the start are no different from starting where the launch said. */
    const val MEANINGFUL_PROGRESS_MS = 10_000L

    data class Noted(val videoId: String, val positionMs: Long)

    /**
     * Left by the player as a source fails fatally; taken by the route on the failover retry that
     * failure causes. Always overwritten - a startup failure leaves null - so it can only ever
     * describe the most recent failure, and the route keeps the chain's own copy.
     */
    var pending: Noted? = null

    fun takePending(): Noted? = pending.also { pending = null }

    /**
     * What to note when a source fails: the position it reached, or null when it never really
     * played (startup failures keep the launch's own resume point).
     */
    fun note(videoId: String?, hasStarted: Boolean, lastPlayedMs: Long): Noted? {
        val id = videoId?.takeIf { it.isNotBlank() } ?: return null
        if (!hasStarted || lastPlayedMs < MEANINGFUL_PROGRESS_MS) return null
        return Noted(id, (lastPlayedMs - REWIND_MS).coerceAtLeast(0L))
    }

    /** The relaunch's start position: the noted failure point for this video, else the launch's. */
    fun resumePosition(noted: Noted?, videoId: String?, launchResumeMs: Long?): Long? =
        if (noted != null && noted.videoId == videoId) noted.positionMs else launchResumeMs
}
