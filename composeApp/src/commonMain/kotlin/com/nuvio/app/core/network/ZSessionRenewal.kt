package com.nuvio.app.core.network

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * How much life an installed Z session has left.
 *
 * The Z session is never auto-refreshed (see [ZSupabaseProvider]): it is derived from the official
 * one, so recovering it means re-exchanging. Until 2026-09-18 that happened only after a request had
 * already been refused with 401, and only on paths that knew to retry. The Watch Together poll did
 * not, so about an hour into a party every heartbeat failed and the server's stale-host rule handed
 * the host's authority to a guest. Renewing ahead of expiry means no request needs to fail first.
 */
internal enum class ZSessionFreshness {
    /** Comfortably valid. Nothing to do - the common case, decided without any I/O. */
    Fresh,

    /** Still accepted by the server, but close enough to expiry that it should be replaced now. */
    RenewSoon,

    /** Past its expiry. A request carrying it will be refused. */
    Expired,
}

/**
 * Renew this far ahead of expiry: comfortably more than a heartbeat interval and a slow exchange,
 * so a renewal that fails once has several more chances before the token stops working.
 */
internal val ZSessionRenewAhead: Duration = 2.minutes

internal fun zSessionFreshness(
    expiresAt: Instant,
    now: Instant,
    renewAhead: Duration = ZSessionRenewAhead,
): ZSessionFreshness = when {
    now >= expiresAt -> ZSessionFreshness.Expired
    expiresAt - now <= renewAhead -> ZSessionFreshness.RenewSoon
    else -> ZSessionFreshness.Fresh
}

/** No usable Z session could be established. Transient: the caller's normal liveness handling applies. */
internal class ZSessionUnavailableException : IllegalStateException("Nuvio Z session unavailable")

/**
 * Runs [block] with a usable Z session: ensured before the call, and re-exchanged once if the
 * backend refuses the token anyway (a clock skewed past the renewal margin, or a session revoked
 * server-side).
 *
 * A failure to establish a session is returned, never thrown, so a background loop treats it as
 * one more failed contact - exactly what a network failure is - rather than as a departure.
 */
internal suspend fun <T> runWithZSession(
    ensure: suspend () -> Boolean,
    reexchange: suspend () -> Boolean,
    needsReexchange: (Throwable) -> Boolean = ::shouldReexchangeZSession,
    block: suspend () -> T,
): Result<T> {
    if (!ensure()) return Result.failure(ZSessionUnavailableException())
    val first = runCatching { block() }
    val failure = first.exceptionOrNull() ?: return first
    if (!needsReexchange(failure)) return first
    if (!reexchange()) return first
    return runCatching { block() }
}
