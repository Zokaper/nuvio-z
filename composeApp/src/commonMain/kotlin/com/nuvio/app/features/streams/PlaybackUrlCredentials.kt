package com.nuvio.app.features.streams

private val credentialQueryKeys = setOf(
    "accesskey",
    "accesssignature",
    "accesssig",
    "access_token",
    "accesstoken",
    "auth",
    "authkey",
    "authsig",
    "authsignature",
    "auth_token",
    "authtoken",
    "e",
    "exp",
    "expiration",
    "expire",
    "expires",
    "expiresat",
    "expiresin",
    "expires_in",
    "expiry",
    "hmac",
    "jwt",
    "keypairid",
    "policy",
    "sig",
    "signature",
    "signed",
    "st",
    "t",
    "token",
)

private val credentialKeyFragments = listOf(
    "token",
    "signature",
    "expires",
    "expiry",
)

/**
 * How many times one playing item may re-mint an expiring link before the failure is real.
 *
 * One, and the premise is the whole argument: this exists for a link that expired *while
 * playing*, so a fresh one fixes it. If the replacement also dies, the link was never the
 * problem - the source is - and the right answer is the failure chain, which will name it and
 * move to the next candidate.
 */
internal const val MAX_CREDENTIAL_REFRESHES = 1

/** What to do with a playback error on a URL that carries expiring credentials. */
internal enum class CredentialRefreshDecision {
    /** Re-mint and swap the source in place. */
    Refresh,

    /** One is already running. Swallow this error; the running one will answer. */
    AwaitInFlight,

    /**
     * Let the error through to the player's fatal handler.
     *
     * Which is the point: that handler is where the failure chain lives, so declining here is
     * how a genuinely dead source gets named and stepped past.
     */
    Decline,
}

/**
 * Whether a failed playback URL earns another mint.
 *
 * **The budget, not the URL, is what bounds this.** The guard used to be "have I already tried
 * *this* URL?", which cannot work for two independent reasons: a re-mint returns a freshly
 * signed URL every time, so the comparison never matches; and swapping the source in place is
 * itself what resets the player state the guard lived in. So a debrid source that died a second
 * after starting re-minted forever, the opening overlay reappearing on each new URL, and the
 * player's fatal handler was never reached because the refresh swallowed every error.
 *
 * [lastAttemptedUrl] is kept for the one case it does answer honestly - the same URL failing
 * twice without a successful mint in between - but it can only ever refuse, never permit.
 */
internal fun credentialRefreshDecision(
    failedUrl: String?,
    refreshesUsed: Int,
    isRefreshInFlight: Boolean,
    lastAttemptedUrl: String?,
): CredentialRefreshDecision {
    if (failedUrl == null || !failedUrl.hasLikelyExpiringPlaybackCredentials()) {
        return CredentialRefreshDecision.Decline
    }
    if (isRefreshInFlight) return CredentialRefreshDecision.AwaitInFlight
    if (refreshesUsed >= MAX_CREDENTIAL_REFRESHES) return CredentialRefreshDecision.Decline
    if (lastAttemptedUrl == failedUrl) return CredentialRefreshDecision.Decline
    return CredentialRefreshDecision.Refresh
}

internal fun String.hasLikelyExpiringPlaybackCredentials(): Boolean {
    val query = substringAfter('?', missingDelimiterValue = "")
        .substringBefore('#')
        .takeIf { it.isNotBlank() }
        ?: return false

    return query
        .split('&', ';')
        .any { rawParameter ->
            val rawKey = rawParameter
                .substringBefore('=', missingDelimiterValue = "")
                .trim()
                .lowercase()
            if (rawKey.isBlank()) return@any false

            val compactKey = rawKey
                .replace("-", "")
                .replace("_", "")
                .replace(".", "")

            rawKey in credentialQueryKeys ||
                compactKey in credentialQueryKeys ||
                credentialKeyFragments.any { fragment ->
                    rawKey.contains(fragment) || compactKey.contains(fragment)
                }
        }
}
