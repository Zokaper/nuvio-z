package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * ⚠ **Vanilla-bug patch, `drop-at-next-sync`** (Docs/VANILLA-BUGS.md V2, Docs/PATCH-SURFACE.md).
 *
 * Upstream's `AuthRepository.isInvalidRemoteSessionError` reads any 401, any 403, and any message
 * containing "jwt" and "expired" as *the account is gone*, and `signOutIfSessionInvalid` then clears the
 * official session and wipes local account data. An access token that has merely expired says nothing of
 * the kind - it is the normal state of a session for the few seconds after a computer wakes from sleep,
 * before the official client's refresh timer has run - and a profile pull or a provider-credential sync
 * that lands in that window signed the whole app out. On desktop the app then kept running on cached
 * profiles, so the only visible symptom was Social saying "Sign in to your Nuvio account".
 *
 * The auth server settles the question instead of an error string: a session it will still refresh
 * belongs to a live account. Only an outright refusal of the refresh token counts as gone. No answer at
 * all (offline, a timeout, a 5xx) keeps the session, because the official client goes on retrying its
 * own refresh and clears the session itself if that is ever refused.
 *
 * Upstream's classifier is kept and passed in unchanged, so this narrows its verdicts without editing it:
 * when upstream fixes the bug, delete this file and the two call sites in `AuthRepository`.
 */
internal object OfficialSessionRejection {
    private val log = Logger.withTag("OfficialSessionRejection")
    private val lock = Mutex()
    private var lastRefreshedAt: Instant? = null

    /** True only when [error] looks like a dead session *and* the auth server refuses to refresh it. */
    suspend fun isSessionGone(error: Throwable, looksInvalid: (Throwable) -> Boolean): Boolean {
        if (!looksInvalid(error)) return false
        val outcome = refreshAfterRejection()
        if (outcome != OfficialRefreshOutcome.Rejected) {
            log.i { "Official session rejected a request (${error.message}); refresh=$outcome, keeping the session" }
        }
        return outcome == OfficialRefreshOutcome.Rejected
    }

    private suspend fun refreshAfterRejection(): OfficialRefreshOutcome = lock.withLock {
        val now = Clock.System.now()
        // Several requests made on the old token fail together; one refresh answers all of them.
        if (refreshedRecently(lastRefreshedAt, now)) return@withLock OfficialRefreshOutcome.Refreshed
        val result = runCatching {
            withTimeout(OfficialRefreshTimeout) { SupabaseProvider.client.auth.refreshCurrentSession() }
        }
        currentCoroutineContext().ensureActive()
        result.fold(
            onSuccess = {
                lastRefreshedAt = now
                OfficialRefreshOutcome.Refreshed
            },
            onFailure = { failure -> refreshOutcomeForStatus(failure.restStatusCode()) },
        )
    }
}

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

private fun Throwable.restStatusCode(): Int? =
    generateSequence(this as Throwable?) { it.cause }
        .filterIsInstance<RestException>()
        .firstOrNull()
        ?.statusCode

private const val HTTP_TOO_MANY_REQUESTS = 429
private val OfficialRefreshTimeout: Duration = 10.seconds
private val RecentRefreshWindow: Duration = 30.seconds
