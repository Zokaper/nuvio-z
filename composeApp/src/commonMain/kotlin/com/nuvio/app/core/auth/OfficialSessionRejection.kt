package com.nuvio.app.core.auth

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** What a refresh said about a session the official backend had just refused. */
internal enum class OfficialRefreshOutcome {
    /** A new token was issued: the account exists and the session is live. */
    Refreshed,

    /** The auth server refused the refresh token itself. The session really is gone. */
    Rejected,

    /** No answer: offline, a timeout, a server error, rate limiting, or no session in memory to refresh. */
    Inconclusive,
}

/**
 * A 4xx from the token endpoint is the auth server's answer about this refresh token - revoked, already
 * used, or its user deleted. 429 is the exception: it is about the caller's rate, not the session.
 */
internal fun refreshOutcomeForStatus(statusCode: Int?): OfficialRefreshOutcome = when {
    statusCode == null -> OfficialRefreshOutcome.Inconclusive
    statusCode == HTTP_TOO_MANY_REQUESTS -> OfficialRefreshOutcome.Inconclusive
    statusCode in 400..499 -> OfficialRefreshOutcome.Rejected
    else -> OfficialRefreshOutcome.Inconclusive
}

internal fun refreshedRecently(
    lastRefreshedAt: Instant?,
    now: Instant,
    window: Duration = RecentRefreshWindow,
): Boolean = lastRefreshedAt != null && now - lastRefreshedAt < window

private const val HTTP_TOO_MANY_REQUESTS = 429
private val RecentRefreshWindow: Duration = 30.seconds
