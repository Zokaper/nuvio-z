package com.nuvio.app.features.playback

/**
 * One line per playback attempt, in one format, under one tag.
 *
 * `adb logcat -s PlaybackStartup` (or the desktop log) already gives the *end* of a failed
 * start: the watchdog's abandon line. What it never gave was the beginning - which mode chose,
 * which candidate it chose, from which addon, whether the addon had errored, and where in the
 * budget the attempt sat. Without that, a chain burning three healthy sources and a chain
 * burning three dead ones produce the same log, and the only difference is in a device nobody
 * can attach a debugger to.
 *
 * **Formatted here rather than at the call sites**, so a session is greppable as a table rather
 * than as prose. The shape deliberately matches the watchdog's abandon line - `key=value`
 * pairs, space separated - so one `grep` reads both halves of an attempt.
 *
 * Pure and import-free by design: the pure suite can assert on the exact text, which is what
 * stops the format drifting the moment a second caller wants "just one more field".
 */
object PlaybackAttemptLog {

    /**
     * The attempt as it is armed.
     *
     * [uncoverReason] is null on the happy path and set when this attempt ends by handing the
     * source list back, which is the one thing bug 5 needs recorded: every path that uncovers
     * the list in an automatic mode does it silently today, so nobody can say which of the
     * eight fires in practice.
     */
    fun attempt(
        mode: String,
        attempt: Int,
        maxAttempts: Int,
        candidate: String?,
        addonId: String?,
        addonErrored: Boolean,
        cached: Boolean?,
        outcome: String,
        elapsedMs: Long? = null,
        positionMs: Long? = null,
        durationMs: Long? = null,
        uncoverReason: String? = null,
    ): String = buildString {
        append("mode=").append(mode)
        append(" attempt=").append(attempt).append('/').append(maxAttempts)
        append(" candidate=").append(candidate.orUnknown())
        append(" addon=").append(addonId.orUnknown())
        append(" addonErrored=").append(addonErrored)
        append(" cached=").append(cached?.toString() ?: "unknown")
        append(" outcome=").append(outcome)
        elapsedMs?.let { append(" elapsed=").append(it).append("ms") }
        positionMs?.let { append(" pos=").append(it).append("ms") }
        durationMs?.let { append(" duration=").append(it).append("ms") }
        uncoverReason?.let { append(" uncover=").append(it) }
    }

    /**
     * A programmatic seek, with the numbers it was derived from.
     *
     * Every seek the app performs on the user's behalf logs this. The jump-to-the-end bug is a
     * position computed from a duration the engine reported, and the duration is the value
     * nobody could see: `pos=7143000ms duration=7143000ms` names the fault instantly, where
     * "it jumped to the end" could be a dozen things.
     */
    fun seek(
        source: String,
        positionMs: Long,
        durationMs: Long?,
        fraction: Float?,
        accepted: Boolean,
        refusedReason: String? = null,
    ): String = buildString {
        append("seek=").append(source)
        append(" pos=").append(positionMs).append("ms")
        append(" duration=").append(durationMs?.let { "${it}ms" } ?: "unknown")
        fraction?.let { append(" fraction=").append(it) }
        append(" accepted=").append(accepted)
        refusedReason?.let { append(" refused=").append(it) }
    }

    private fun String?.orUnknown(): String =
        this?.takeIf { it.isNotBlank() }?.replace(' ', '_') ?: "unknown"
}
