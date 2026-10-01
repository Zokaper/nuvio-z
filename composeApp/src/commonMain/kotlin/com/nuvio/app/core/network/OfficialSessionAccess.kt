package com.nuvio.app.core.network

import com.nuvio.app.core.auth.AuthRepository
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The official access token, in the only state the `z-session` exchange can use: present, settled
 * and unexpired.
 *
 * `currentAccessTokenOrNull()` answers none of those. It is null whenever the official client is not
 * `Authenticated` - which includes the moments it is loading from storage and the moments a failed
 * refresh is being retried - and when it *is* `Authenticated` it hands back the token without looking
 * at its expiry. The first put "Sign in to your Nuvio account" on a signed-in user's Social tab; the
 * second sent an expired token after a laptop woke from sleep, before the official client's own
 * refresh had run. Neither was ever retried, so both lasted until a restart.
 */
internal sealed interface OfficialAccessToken {
    data class Ready(val token: String) : OfficialAccessToken

    /** There is no official session. Only the user can fix this, by signing in. */
    data object SignedOut : OfficialAccessToken

    /** A session exists but is loading, refreshing, or cannot be refreshed right now. Transient. */
    data object Unavailable : OfficialAccessToken
}

internal object OfficialSessionAccess {

    /**
     * Waits briefly for the official session to settle, then returns its token - refreshed first when
     * it has expired or is about to, or unconditionally when [forceRefresh] is set because the token
     * was just refused.
     */
    suspend fun accessToken(forceRefresh: Boolean = false): OfficialAccessToken {
        val auth = SupabaseProvider.client.auth
        val settled = withTimeoutOrNull(OfficialSessionSettleTimeout) {
            auth.sessionStatus.first { it is SessionStatus.Authenticated || it is SessionStatus.NotAuthenticated }
        } ?: return OfficialAccessToken.Unavailable
        if (settled !is SessionStatus.Authenticated) return OfficialAccessToken.SignedOut

        val session = settled.session
        if (!forceRefresh && !officialTokenNeedsRefresh(session.expiresAt, Clock.System.now())) {
            return AuthRepository.officialAccessToken(session, refresh = false)
                ?.let(OfficialAccessToken::Ready) ?: OfficialAccessToken.Unavailable
        }

        return AuthRepository.officialAccessToken(session, refresh = true)
            ?.let(OfficialAccessToken::Ready)
            ?: OfficialAccessToken.Unavailable
    }
}

/**
 * Refresh this far ahead of expiry. The `z-session` function verifies the token after a network hop
 * and then uses it again against the official API, so one that is valid when it leaves may not be
 * when it is read.
 */
internal val OfficialTokenRefreshAhead: Duration = 60.seconds

internal fun officialTokenNeedsRefresh(
    expiresAt: Instant,
    now: Instant,
    refreshAhead: Duration = OfficialTokenRefreshAhead,
): Boolean = expiresAt - now <= refreshAhead

/**
 * Bounded because the Watch Together heartbeat can be waiting behind this, through
 * [ZSessionBridge.ensureSession]. Long enough to cover a load from storage. A failed refresh that
 * supabase-kt is still retrying answers [OfficialAccessToken.Unavailable] instead, and Social tries
 * again when the official session comes back (`SocialRepository.recoverIfStale`).
 */
private val OfficialSessionSettleTimeout: Duration = 5.seconds
