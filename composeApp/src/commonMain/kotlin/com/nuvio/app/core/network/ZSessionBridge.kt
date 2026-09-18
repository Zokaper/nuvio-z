package com.nuvio.app.core.network

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns a live official Nuvio session into a Nuvio Z session.
 *
 * Users sign in once, to official Nuvio. Nuvio Z's backend cannot be configured to trust that
 * issuer directly - Supabase third-party auth only accepts five named providers - so the Z backend
 * runs a `z-session` function that verifies the official token against Nuvio's published ES256
 * JWKS, confirms with the official API that the profile really belongs to the caller, and has
 * Supabase issue a Z session for it.
 *
 * Nothing here holds or needs a credential belonging to NuvioMedia. The only thing sent is the
 * user's own official access token, to the user's own Z backend, which verifies it against a public
 * key.
 *
 * The exchange is bound to a specific profile: the Z token carries the profile the function
 * verified, and the server reads the identity from that claim rather than from any argument the
 * client passes. Switching profile therefore means a new exchange, not a new argument.
 */
object ZSessionBridge {

    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient()
    private val mutex = Mutex()

    private var boundProfileId: String? = null

    /**
     * Why the last exchange failed, for the UI to show.
     *
     * The exchange can fail for reasons the user can act on - not being signed in to Nuvio - and
     * reasons they cannot, and a bare false told them apart from neither. Every return path below
     * sets this before giving up.
     */
    @Volatile
    var lastFailure: String? = null
        private set

    /** True when a Z session is currently installed for [profileId]. */
    fun hasSessionFor(profileId: String): Boolean =
        boundProfileId == profileId && ZSupabaseProvider.client.auth.currentSessionOrNull() != null

    /**
     * Ensures a Z session exists for [profileId], exchanging one if needed.
     *
     * Returns false rather than throwing when the social surface simply cannot be reached - an
     * unconfigured Z backend, no official session, or a refused exchange. Callers treat that as
     * "social is unavailable", which is the same state an undeployed backend produces, so a failure
     * here hides the surface instead of surfacing an error the user cannot act on.
     */
    suspend fun ensureSession(profileId: String): Boolean {
        if (!ZSupabaseProvider.isConfigured) {
            lastFailure = "This build has no Nuvio Z backend configured."
            return false
        }
        if (hasSessionFor(profileId)) return true
        return mutex.withLock {
            // Another caller may have completed the exchange while this one waited for the lock.
            if (hasSessionFor(profileId)) return@withLock true
            exchange(profileId)
        }
    }

    /**
     * Drops the current Z session so the next call exchanges a fresh one.
     *
     * Called when the backend rejects a Z token, which normally means it expired. The official
     * session is the source of truth and is still live, so re-exchanging is always the recovery.
     */
    suspend fun invalidate() {
        mutex.withLock {
            boundProfileId = null
            runCatching { ZSupabaseProvider.client.auth.clearSession() }
        }
    }

    /**
     * Replaces a rejected session without first publishing a NotAuthenticated state.
     *
     * Realtime observes the Auth state and tears down every private channel on session loss. The
     * exchange itself uses the independent official token, so clearing the old Z token first is
     * unnecessary and creates a race in which concurrent PostgREST calls fall back to the public
     * key. Importing the replacement directly also lets Realtime update the live channel's JWT.
     */
    suspend fun reexchange(profileId: String): Boolean = mutex.withLock {
        val exchanged = exchange(profileId)
        if (!exchanged) boundProfileId = null
        exchanged
    }

    private suspend fun exchange(profileId: String): Boolean {
        val officialToken = runCatching {
            SupabaseProvider.client.auth.currentAccessTokenOrNull()
        }.getOrNull()
        if (officialToken.isNullOrBlank()) {
            // Social identity is the official Nuvio identity, so there is nothing to exchange until
            // the user has signed in there. This is the one failure they can act on themselves.
            lastFailure = "Sign in to your Nuvio account to use Social."
            return false
        }

        // One account can hold several profiles, and the verified profile lives on the shared user
        // record, so two devices exchanging at once can each be issued the other's profile. The
        // function detects that and refuses rather than handing back a wrong identity; the loser
        // simply goes again, by which point the other write has settled.
        var response = runCatching { postExchange(officialToken, profileId) }.getOrElse { cause ->
            lastFailure = "Could not reach Nuvio Z: ${cause.message ?: "network error"}"
            return false
        }
        var body = runCatching { response.bodyAsText() }.getOrDefault("")
        if (response.status.value == HTTP_CONFLICT && body.contains("concurrent profile exchange")) {
            delay(CONCURRENT_RETRY_DELAY_MS)
            response = runCatching { postExchange(officialToken, profileId) }.getOrElse { cause ->
                lastFailure = "Could not reach Nuvio Z: ${cause.message ?: "network error"}"
                return false
            }
            body = runCatching { response.bodyAsText() }.getOrDefault("")
        }

        if (!response.status.isSuccess()) {
            // The function distinguishes its refusals, so the status and body together say which
            // step failed rather than collapsing all of them into "unavailable".
            lastFailure = "Nuvio Z refused the sign-in exchange (${response.status.value}): $body"
            return false
        }

        val payload = runCatching {
            json.parseToJsonElement(body) as JsonObject
        }.getOrNull()
        if (payload == null) {
            lastFailure = "Nuvio Z returned a response that could not be read."
            return false
        }

        val accessToken = payload["access_token"]?.jsonPrimitive?.content
        if (accessToken == null) {
            lastFailure = "Nuvio Z returned no access token."
            return false
        }
        val refreshToken = payload["refresh_token"]?.jsonPrimitive?.content.orEmpty()
        val expiresAt = payload["expires_at"]?.jsonPrimitive?.content?.toLongOrNull()

        val installed = runCatching {
            ZSupabaseProvider.client.auth.importSession(
                UserSession(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresIn = expiresAt?.let { it - currentEpochSeconds() }?.coerceAtLeast(0) ?: DEFAULT_EXPIRY_SECONDS,
                    tokenType = "bearer",
                    user = null,
                ),
            )
        }.isSuccess
        if (!installed) {
            lastFailure = "Could not install the Nuvio Z session."
            return false
        }

        boundProfileId = profileId
        lastFailure = null
        return true
    }

    private suspend fun postExchange(officialToken: String, profileId: String) =
        http.post("${ZSupabaseConfig.URL}/functions/v1/z-session") {
            header("apikey", ZSupabaseConfig.PUBLISHABLE_KEY)
            // Set explicitly: this endpoint authenticates the *official* token, not a Z one, so it
            // must not carry whatever session the Z client happens to hold.
            header("Authorization", "Bearer $officialToken")
            contentType(ContentType.Application.Json)
            setBody("""{"profile_id":"$profileId"}""")
        }

    private fun currentEpochSeconds(): Long = kotlin.time.Clock.System.now().epochSeconds

    private const val HTTP_CONFLICT = 409
    private const val CONCURRENT_RETRY_DELAY_MS = 600L

    private const val DEFAULT_EXPIRY_SECONDS = 3600L
}

/**
 * Only an HTTP authentication rejection proves that the derived Z session is unusable.
 *
 * In particular, Postgres authorization errors (normally HTTP 400 with SQLSTATE 42501), network
 * failures, and server failures must not clear the shared Auth session. Realtime observes that Auth
 * state and intentionally disconnects every private channel when it becomes unauthenticated.
 */
internal fun shouldReexchangeZSession(error: Throwable): Boolean {
    val statusCode = generateSequence(error as Throwable?) { it.cause }
        .filterIsInstance<RestException>()
        .firstOrNull()
        ?.statusCode
    return shouldReexchangeZSession(statusCode)
}

internal fun shouldReexchangeZSession(statusCode: Int?): Boolean = statusCode == HTTP_UNAUTHORIZED

private const val HTTP_UNAUTHORIZED = 401
