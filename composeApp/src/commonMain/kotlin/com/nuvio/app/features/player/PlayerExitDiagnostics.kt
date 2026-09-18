package com.nuvio.app.features.player

import co.touchlab.kermit.Logger

/**
 * Timestamped diagnostics for the player exit path.
 *
 * Requirements from ROADMAP.md Phase 2 follow-up:
 * - T0: Escape/back action received
 * - T1: Navigation pop/reveal requested
 * - T2: Previous Compose destination actually draws
 * - T3: Native player/video surface detached or hidden
 * - T4: Native player teardown/release completes
 */
object PlayerExitDiagnostics {
    private val log = Logger.withTag("PlayerExit")
    private val lock = Any()
    private val afterPreviousDraw = mutableListOf<() -> Unit>()

    @Volatile
    private var t0Nanos: Long = 0L

    @Volatile
    private var t1Nanos: Long = 0L

    @Volatile
    private var t2Nanos: Long = 0L

    @Volatile
    private var t3Nanos: Long = 0L

    @Volatile
    private var t4Nanos: Long = 0L

    fun recordT0(source: String = "back_action") {
        val now = System.nanoTime()
        synchronized(lock) {
            t0Nanos = now
            t1Nanos = 0L
            t2Nanos = 0L
            t3Nanos = 0L
            t4Nanos = 0L
            afterPreviousDraw.clear()
        }
        log.i { "T0 [0 ms] Escape/back received source=$source" }
    }

    fun recordT1(targetRoute: String = "") {
        val now = System.nanoTime()
        t1Nanos = now
        val elapsedMs = elapsedFromT0(now)
        log.i { "T1 [+$elapsedMs ms] Navigation pop requested target=$targetRoute" }
    }

    fun recordT2(destination: String = "") {
        val now = System.nanoTime()
        val callbacks = synchronized(lock) {
            if (t0Nanos == 0L || t2Nanos != 0L) return
            t2Nanos = now
            afterPreviousDraw.toList().also { afterPreviousDraw.clear() }
        }
        val elapsedMs = elapsedFromT0(now)
        val fromT1 = if (t1Nanos > 0L) (now - t1Nanos) / 1_000_000L else -1L
        log.i { "T2 [+$elapsedMs ms, +$fromT1 ms from T1] Previous destination drew: $destination" }
        callbacks.forEach { it() }
    }

    /** Defers work until the destination under the player has really drawn. */
    fun runAfterPreviousDraw(action: () -> Unit): Boolean = synchronized(lock) {
        if (t0Nanos == 0L || t2Nanos != 0L) return@synchronized false
        afterPreviousDraw += action
        true
    }

    fun recordT3(details: String = "") {
        if (t0Nanos == 0L || t3Nanos != 0L) return
        val now = System.nanoTime()
        t3Nanos = now
        val elapsedMs = elapsedFromT0(now)
        val fromT1 = if (t1Nanos > 0L) (now - t1Nanos) / 1_000_000L else -1L
        log.i { "T3 [+$elapsedMs ms, +$fromT1 ms from T1] Native player surface detached/hidden: $details" }
    }

    fun recordT4(handle: Long = 0L) {
        if (t0Nanos == 0L || t4Nanos != 0L) return
        val now = System.nanoTime()
        t4Nanos = now
        val elapsedMs = elapsedFromT0(now)
        val fromT1 = if (t1Nanos > 0L) (now - t1Nanos) / 1_000_000L else -1L
        log.i { "T4 [+$elapsedMs ms, +$fromT1 ms from T1] Native player teardown completed handle=$handle" }
    }

    private fun elapsedFromT0(nanos: Long): Long {
        if (t0Nanos == 0L) return 0L
        return (nanos - t0Nanos) / 1_000_000L
    }
}
