package com.nuvio.app.features.player

/**
 * libmpv's own cache verdict, which is what decides when it will resume - not a buffer constant
 * chosen here.
 *
 * `paused-for-cache` is the engine saying it stopped because the cache ran dry, and it is reported
 * independently of the `pause` flag, so a member the party has paused still tells the truth about its
 * cache. `cache-buffering-state` is the percentage of cache fill until playback may run, and it is
 * read whatever the pause flag says.
 *
 * ⚠ **That last part used to carry `&& !paused`, on the theory that a deliberate pause could
 * leave a stale percentage standing.** Measured against the shipped libmpv on 2026-09-20 and it does
 * not: the property reads 100 whenever mpv is not buffering - playing and paused alike - and during a
 * refill *while the player was held paused from outside* it climbed 0 → 96 → 100 live, which is
 * exactly the case the guard was invented for. Keeping the guard cost the readiness barrier its whole
 * answer: a member seeked into an unbuffered region while the party holds it paused reported `Ready`
 * with an empty cache, which is the one thing the barrier exists to not do.
 *
 * A seek in flight is a transition rather than an answer, and the caller's fallback reads it better
 * than either verdict would.
 *
 * In `commonMain` since the mobile release-hardening pass: Android's libmpv and iOS's are the same
 * engine and give the same answer through the same properties, so one mapping serves both.
 */
internal fun mpvEngineReadiness(
    pausedForCache: Boolean,
    cacheBuffering: Boolean,
    paused: Boolean,
    seeking: Boolean,
    idle: Boolean,
    durationMs: Long,
): PlayerEngineReadiness = when {
    durationMs <= 0L && idle -> PlayerEngineReadiness.NoSource
    pausedForCache -> PlayerEngineReadiness.Buffering
    cacheBuffering -> PlayerEngineReadiness.Buffering
    seeking -> PlayerEngineReadiness.Unknown
    else -> PlayerEngineReadiness.Ready
}

/**
 * `cache-buffering-state` as the flag [mpvEngineReadiness] takes: the fill percentage while mpv is
 * filling towards a target, 100 when it is not. Null - the property unavailable, as before a file
 * has opened - is not buffering; the idle and duration checks answer that case.
 */
internal fun mpvCacheBuffering(cacheBufferingState: Int?): Boolean =
    cacheBufferingState != null && cacheBufferingState in 0 until 100
