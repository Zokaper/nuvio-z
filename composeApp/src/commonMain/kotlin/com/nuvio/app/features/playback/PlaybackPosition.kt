package com.nuvio.app.features.playback

/**
 * Every position the app derives from a duration, in one place.
 *
 * The same `durationMs × progressFraction` expression was written out by hand at three sites -
 * the resume seek, the auto-skip baseline and the startup watchdog's baseline - and **none of
 * them bounded the result**, while `PlayerScreenRuntimeUi` bounds the very same computation
 * with `coerceAtMost(durationMs - 1)` two files away. That is an inconsistency, not a design,
 * and it is why "it plays, then the position leaps to the end and sticks" had no single place
 * to be fixed.
 *
 * Pure and import-free so the pure suite can execute it, which matters more here than usual:
 * the inputs that break it are values a real engine reports on a bad link and a test can
 * produce for free - zero, negative, and absurdly large durations.
 */
object PlaybackPosition {

    /**
     * Above this, a reported duration is not believed.
     *
     * Twenty-five hours. Long enough for any real title including the longest concert films and
     * a season pack opened as one file, short enough to catch the values that actually cause
     * the bug: a progressive HTTP source through a bridge that reports its duration in the
     * wrong unit, or a container header the engine misparsed before any bytes arrived.
     *
     * ⚠ **This is a floor on absurdity, not a content policy.** Refusing to *seek* into an
     * implausible duration is safe; refusing to *play* it would not be, and nothing here does.
     */
    const val MAX_PLAUSIBLE_DURATION_MS: Long = 25L * 60L * 60L * 1_000L

    /**
     * How far from the end a derived seek is allowed to land.
     *
     * A position computed from a fraction that rounds to 1.0 is arithmetically correct and
     * useless: it drops the user on the credits with nothing left to play, which is
     * indistinguishable from the failure this file exists to stop. Ten seconds leaves something
     * to watch and still honours a genuine near-the-end resume.
     */
    const val END_GUARD_MS: Long = 10_000L

    /**
     * Whether a reported duration can be the basis of any arithmetic.
     *
     * Zero means "not known yet" on every engine here, not "an empty video", and it is the
     * value present for the whole window before the first frame - which is exactly when the
     * resume seek runs.
     */
    fun isDurationUsable(durationMs: Long): Boolean =
        durationMs > 0L && durationMs <= MAX_PLAUSIBLE_DURATION_MS

    /**
     * The furthest a programmatic seek may land, given a duration.
     *
     * Returns the position unchanged when the duration is unusable: a caller with an explicit
     * position and no trustworthy duration is better served by its own number than by one this
     * function invented from a value it does not believe.
     */
    fun clampSeekTarget(positionMs: Long, durationMs: Long): Long {
        val floored = positionMs.coerceAtLeast(0L)
        if (!isDurationUsable(durationMs)) return floored
        val ceiling = (durationMs - END_GUARD_MS).coerceAtLeast(0L)
        return floored.coerceAtMost(ceiling)
    }

    /**
     * Where a play should begin, or null when nothing trustworthy says.
     *
     * The precedence is the one the player already used - an explicit position beats a fraction
     * - with the bounds it never had. **Null means "do not seek"**, and it is a normal answer:
     * a fraction with no usable duration behind it is not a position, and seeking into one is
     * how a dead link gets mistaken for a playing one.
     */
    fun resolveStartPositionMs(
        initialPositionMs: Long,
        progressFraction: Float?,
        durationMs: Long,
    ): Long? {
        if (initialPositionMs > 0L) {
            // ⚠ **An explicit position is never rewritten against a duration that contradicts
            // it.** Clamping used to run unconditionally, and the caller latches on the result:
            // an engine reporting a small-but-positive duration on the first non-loading
            // snapshot - a rolling/DVR window, or a header parsed before the index - dragged a
            // 22-minute resume down to `duration - 10s`, frequently 0, seeked there and latched,
            // so the correct duration arriving a moment later could not recover it. A duration
            // shorter than the position we were handed is evidence the *duration* is wrong, not
            // the position, so this refuses and waits for a better one.
            if (isDurationUsable(durationMs) && durationMs <= initialPositionMs) return null
            return clampSeekTarget(initialPositionMs, durationMs)
        }
        val fraction = progressFraction?.takeIf { it > 0f && it.isFinite() }?.coerceIn(0f, 1f)
            ?: return null
        if (!isDurationUsable(durationMs)) return null
        val raw = (durationMs.toDouble() * fraction.toDouble()).toLong()
        val clamped = clampSeekTarget(raw, durationMs)
        return clamped.takeIf { it > 0L }
    }

    /**
     * Why a seek was refused, for the log line. Null when it was not.
     *
     * Split from [resolveStartPositionMs] so the refusal can be *named* rather than inferred
     * from a null - the whole complaint behind this phase is that the app's recoveries are
     * invisible while they run.
     */
    fun refusalReason(
        initialPositionMs: Long,
        progressFraction: Float?,
        durationMs: Long,
    ): String? = when {
        initialPositionMs > 0L ->
            if (isDurationUsable(durationMs) && durationMs <= initialPositionMs) {
                "duration_shorter_than_resume"
            } else {
                null
            }
        progressFraction == null || progressFraction <= 0f -> null
        !progressFraction.isFinite() -> "non_finite_fraction"
        durationMs <= 0L -> "unknown_duration"
        durationMs > MAX_PLAUSIBLE_DURATION_MS -> "implausible_duration"
        else -> null
    }
}
