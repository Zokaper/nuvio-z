package com.nuvio.app.features.watchparty

/**
 * The party's shared clock, estimated from the **host** rather than from the database.
 *
 * Drift is measured against a clock, and until now that clock was the server's: three `party_clock`
 * PostgREST round trips at party start, best RTT wins, never measured again. Three things were
 * wrong with it. A PostgREST round trip is dominated by TLS and connection setup, so its asymmetry
 * - which is exactly the error in `(t0 + t2) / 2` - is large and not the same in both directions.
 * Two wall clocks drift apart across a two hour film, and nothing re-measured. And it put the
 * server between the host's position and the guest's arithmetic, when the host is the only machine
 * that knows both.
 *
 * So the host is the clock. Guests exchange [PartyClockSample]s with it over the party's own
 * websocket - the same socket the positions arrive on, so the measurement shares the path it is
 * correcting - and the host's offset is zero by definition. The server clock stays as the fallback
 * anchor for the polling path and for a host too old to answer.
 *
 * Import-free on purpose, like `core/media/ReleaseTags.kt` and `core/language/LanguageCodes.kt`:
 * Gradle cannot configure in the agent sandbox, so a file with no imports is the difference between
 * logic that is executed by `scripts/run-pure-suites.sh` and logic that is only parser-checked.
 */

/** How many exchanges are kept. Long enough to hold a good one, short enough to follow real drift. */
const val WatchPartyClockWindowSize = 8

/** Samples before the offset is trusted enough to be slew-limited rather than jumped to. */
const val WatchPartyClockLockSamples = 3

/**
 * The most an accepted offset may move in one step once locked.
 *
 * Real drift between two machines is measured in milliseconds per minute, so anything faster than
 * this is a queued packet rather than the clock, and following it would put a jump into playback
 * that no clock ever made.
 */
const val WatchPartyClockSlewLimitMs = 50L

/**
 * Past this, the offset did not drift - something stepped: an NTP correction on either machine, or
 * a new host after a transfer. Slewing 50ms at a time towards a second-sized step would take
 * twenty exchanges, so the window is thrown away and the estimate re-locks from the new sample.
 */
const val WatchPartyClockRelockThresholdMs = 1_000L

/** Exchanges at [WatchPartyClockFastPingIntervalMs] before settling to the steady interval. */
const val WatchPartyClockFastPingCount = 5

/** Opening cadence: a party has to be in sync in the first few seconds, not the first minute. */
const val WatchPartyClockFastPingIntervalMs = 1_000L

/** Steady cadence. Eight members at this rate is under two messages a second on the channel. */
const val WatchPartyClockPingIntervalMs = 5_000L

/** Past this with no answer the host is not exchanging, and the ladder drops to the server clock. */
const val WatchPartyClockStaleMs = 20_000L

/**
 * How old an exchange may be and still be chosen as the best in the window.
 *
 * The window is bounded by count as well, but count alone is not enough: at the steady cadence
 * eight exchanges span forty seconds, and a lucky short round trip taken before a host transfer
 * would go on winning the comparison for all of them - keeping the estimate on a clock that is no
 * longer the party's.
 */
const val WatchPartyClockSampleMaxAgeMs = 60_000L

/**
 * One completed round trip: what the offset looked like, how long the exchange took, and when it
 * landed.
 *
 * [rttMs] is kept because it is the *quality* of [offsetMs], not a statistic. The offset is only
 * exact for a symmetric path, and the error is bounded by half the round trip, so the shortest
 * exchange in the window is the most trustworthy one in it.
 */
data class PartyClockSample(
    val offsetMs: Long,
    val rttMs: Long,
    val receivedAtMs: Long,
)

/**
 * Turns one ping/pong into a sample.
 *
 * [sentAtMs] and [receivedAtMs] are read from this machine's clock, [hostAtMs] from the host's when
 * it answered. The midpoint is the instant this machine believes the host stamped, so the
 * difference is the offset between the two clocks.
 */
fun partyClockSample(sentAtMs: Long, hostAtMs: Long, receivedAtMs: Long): PartyClockSample {
    val rtt = (receivedAtMs - sentAtMs).coerceAtLeast(0L)
    return PartyClockSample(
        offsetMs = hostAtMs - (sentAtMs + receivedAtMs) / 2,
        rttMs = rtt,
        receivedAtMs = receivedAtMs,
    )
}

/**
 * The accepted estimate, and the window it was chosen from.
 *
 * Immutable, so [accept] is a function of the state and the sample and nothing else - which is what
 * lets the slew limit and the re-lock be tested without a socket or a second machine.
 */
data class PartyClock(
    val offsetMs: Long = 0L,
    /** Whether enough exchanges have landed for the estimate to be defended rather than replaced. */
    val locked: Boolean = false,
    val samples: List<PartyClockSample> = emptyList(),
) {
    /** The quality of the current estimate: half of this bounds its error. */
    val bestRttMs: Long get() = samples.minOfOrNull { it.rttMs } ?: -1L

    val lastSampleAtMs: Long get() = samples.lastOrNull()?.receivedAtMs ?: 0L

    fun isStale(localNowMs: Long): Boolean =
        samples.isEmpty() || localNowMs - lastSampleAtMs > WatchPartyClockStaleMs

    /** This machine's clock, read in the party's terms. */
    fun partyNowMs(localNowMs: Long): Long = localNowMs + offsetMs

    fun accept(sample: PartyClockSample): PartyClock {
        val window = (samples + sample)
            .filter { sample.receivedAtMs - it.receivedAtMs <= WatchPartyClockSampleMaxAgeMs }
            .takeLast(WatchPartyClockWindowSize)
        // Ties go to the most recent exchange. `minByOrNull` keeps the first minimum, and on a
        // steady link every round trip measures the same - so the very first sample won every
        // comparison for the life of the window and the estimate could not follow the clock at all.
        var best = window.firstOrNull() ?: sample
        for (candidate in window) if (candidate.rttMs <= best.rttMs) best = candidate
        if (!locked) {
            // Nothing to defend yet, so take the best exchange outright: an unlocked clock has to
            // converge in the first few seconds, and slewing towards the answer 50ms at a time
            // would leave the opening minute of the film measurably out.
            return PartyClock(
                offsetMs = best.offsetMs,
                locked = window.size >= WatchPartyClockLockSamples,
                samples = window,
            )
        }
        // A sample cannot *appear* to be off by more than half its own round trip, so a short
        // exchange that disagrees this much has measured a step - an NTP correction, or a host
        // transfer - and waiting for it to win the window comparison first would leave the party
        // correcting against the wrong clock for as long as a stale low-RTT sample outlived it.
        val credibleStep = absMs(sample.offsetMs - offsetMs) > WatchPartyClockRelockThresholdMs &&
            sample.rttMs < 2 * WatchPartyClockRelockThresholdMs
        val delta = best.offsetMs - offsetMs
        if (credibleStep || absMs(delta) > WatchPartyClockRelockThresholdMs) {
            // Re-locked from the *new* sample rather than from `best`, which may well be the one
            // taken before whatever stepped.
            return PartyClock(offsetMs = sample.offsetMs, locked = false, samples = listOf(sample))
        }
        val slewed = delta.coerceIn(-WatchPartyClockSlewLimitMs, WatchPartyClockSlewLimitMs)
        return PartyClock(offsetMs = offsetMs + slewed, locked = true, samples = window)
    }
}

/** Fast while the estimate is still forming, then rare enough to be free. */
fun watchPartyClockPingDelayMs(samplesTaken: Int): Long =
    if (samplesTaken < WatchPartyClockFastPingCount) {
        WatchPartyClockFastPingIntervalMs
    } else {
        WatchPartyClockPingIntervalMs
    }

/** Kept here rather than imported, so this file compiles outside Gradle with no classpath at all. */
private fun absMs(value: Long): Long = if (value < 0) -value else value
