package com.nuvio.app.features.playback

import androidx.compose.runtime.mutableStateListOf
import kotlin.time.TimeSource

/**
 * An in-memory record of every mid-playback source swap, for the debug build.
 *
 * This exists to answer one question with a number instead of an opinion: **how long is the
 * gap when quality changes?** A swap tears the stream down, opens a different file and seeks,
 * so unlike Netflix's in-manifest adaptation it cannot be invisible. Whether the interruption
 * is acceptable decides whether automatic downshift should be on by default, and nobody has
 * ever watched it happen. [SwapRecord.gapMs] is that measurement.
 *
 * Deliberately not persisted and deliberately capped: this is a debugging aid for one sitting,
 * not telemetry. Nothing is sent anywhere - [format] exists so the tester can copy the log out
 * and paste it back.
 */
object SwapDiagnosticsLog {
    const val CAPACITY = 50

    /**
     * Why a swap happened, so an automatic trigger is never confused with a button press.
     *
     * There is deliberately no `MANUAL`: a hand-picked source is not recorded here, and every
     * value listed is emitted by exactly one call site. A trigger nothing emits would make an
     * unresolved record eligible to be closed by an unrelated first frame - which is the bug
     * this enum is supposed to make visible, not cause.
     */
    enum class Trigger { AUTO_DOWNSHIFT, FORCED_DOWNSHIFT, FORCED_UPSHIFT }

    data class SwapRecord(
        val trigger: Trigger,
        val fromLabel: String,
        val toLabel: String,
        val fromHeight: Int?,
        val toHeight: Int?,
        val fromReleaseGroup: String? = null,
        val toReleaseGroup: String? = null,
        val fromProvider: String? = null,
        val toProvider: String? = null,
        val fromAddon: String? = null,
        val toAddon: String? = null,
        val bufferAheadMsAtTrigger: Long,
        val positionMsBefore: Long,
        /** Milliseconds since this in-memory log was created. */
        val timestampMs: Long = 0L,
        /** Null until the new source produces its first frame. */
        val gapMs: Long? = null,
        val positionMsAfter: Long? = null,
    ) {
        val isResolved: Boolean get() = gapMs != null
    }

    private val startedAt = TimeSource.Monotonic.markNow()
    private val records = mutableStateListOf<SwapRecord>()

    val entries: List<SwapRecord> get() = records.toList()

    /**
     * Records a swap that has just been requested. The gap is not known yet - the new source
     * has not started - so it is filled in later by [completePending].
     */
    fun record(record: SwapRecord) {
        records.add(record.copy(timestampMs = startedAt.elapsedNow().inWholeMilliseconds))
        while (records.size > CAPACITY) records.removeAt(0)
    }

    /**
     * Closes out the **most recent** unresolved swap with the measured gap.
     *
     * Matching on recency rather than an id keeps the call site at the point where the first
     * frame arrives, which has no swap identity to hand. Most recent is also the correct
     * answer rather than merely the convenient one: if a second swap is requested while the
     * first is still loading, the player tears the first one down, so the next frame belongs
     * to the newer source. The superseded swap stays unresolved forever - which is exactly
     * what happened to it, and is the finding worth seeing in the log.
     */
    fun completePending(gapMs: Long, positionMsAfter: Long? = null) {
        val index = records.indexOfLast { !it.isResolved }
        if (index < 0) return
        records[index] = records[index].copy(
            gapMs = gapMs.coerceAtLeast(0L),
            positionMsAfter = positionMsAfter?.coerceAtLeast(0L),
        )
    }

    fun clear() = records.clear()

    /** Newest first, one line per swap, for copying out of the debug screen. */
    fun format(): String {
        if (records.isEmpty()) return "No source swaps recorded."
        return records.reversed().joinToString("\n") { r ->
            val gap = r.gapMs?.let { "${it}ms" } ?: "never completed"
            val heights = "${r.fromHeight ?: "?"}p->${r.toHeight ?: "?"}p"
            val position = "${r.positionMsBefore}ms->${r.positionMsAfter?.let { "${it}ms" } ?: "?"}"
            "[t+${r.timestampMs}ms ${r.trigger}] $heights gap=$gap " +
                "buffer=${r.bufferAheadMsAtTrigger}ms pos=$position " +
                "group=${r.fromReleaseGroup ?: "-"}->${r.toReleaseGroup ?: "-"} " +
                "provider=${r.fromProvider ?: "-"}->${r.toProvider ?: "-"} " +
                "addon=${r.fromAddon ?: "-"}->${r.toAddon ?: "-"} | " +
                "${r.fromLabel} -> ${r.toLabel}"
        }
    }
}
