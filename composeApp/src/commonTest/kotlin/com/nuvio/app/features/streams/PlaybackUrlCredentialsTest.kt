package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The credential-refresh budget, and the loop it exists to stop.
 *
 * A debrid source that died about a second after starting used to re-mint its link forever:
 * the player reopened on the new URL, showed its logo overlay, played a second, died, and went
 * round again. It could not be escaped, and it could not be reported either - the refresh
 * swallowed every error before the player's fatal handler could see one.
 */
class PlaybackUrlCredentialsTest {

    private val signed = "https://cdn.example/file.mkv?token=abc&exp=123"
    private val resigned = "https://cdn.example/file.mkv?token=def&exp=456"
    private val plain = "https://cdn.example/file.mkv"

    @Test
    fun aFirstFailureOnASignedLinkEarnsARefresh() {
        assertEquals(
            CredentialRefreshDecision.Refresh,
            credentialRefreshDecision(
                failedUrl = signed,
                refreshesUsed = 0,
                isRefreshInFlight = false,
                lastAttemptedUrl = null,
            ),
        )
    }

    @Test
    fun aSecondFailureIsDeclinedEvenThoughTheUrlIsDifferent() {
        // The defect, stated exactly. Every re-mint returns a freshly signed URL, so a guard
        // that asked "have I already tried *this* URL?" never matched and the refresh had no
        // end. The budget is what bounds it; the URL cannot.
        assertEquals(
            CredentialRefreshDecision.Decline,
            credentialRefreshDecision(
                failedUrl = resigned,
                refreshesUsed = 1,
                isRefreshInFlight = false,
                lastAttemptedUrl = signed,
            ),
        )
    }

    @Test
    fun decliningIsWhatLetsTheFailureChainRun() {
        // Not a cosmetic distinction: `Decline` is the only answer that lets the error reach
        // `onFatalPlaybackError`, which is where the source gets named and stepped past. While
        // the refresh kept answering, an exhausted chain could never even be reached.
        val decision = credentialRefreshDecision(
            failedUrl = resigned,
            refreshesUsed = MAX_CREDENTIAL_REFRESHES,
            isRefreshInFlight = false,
            lastAttemptedUrl = null,
        )
        assertEquals(CredentialRefreshDecision.Decline, decision)
    }

    @Test
    fun aRefreshInFlightSwallowsTheErrorRatherThanStartingASecond() {
        assertEquals(
            CredentialRefreshDecision.AwaitInFlight,
            credentialRefreshDecision(
                failedUrl = signed,
                refreshesUsed = 0,
                isRefreshInFlight = true,
                lastAttemptedUrl = null,
            ),
        )
    }

    @Test
    fun theSameUrlFailingTwiceIsStillDeclined() {
        // The one case the old URL comparison answered honestly, kept: a mint that handed back
        // the link that just failed must not be retried on.
        assertEquals(
            CredentialRefreshDecision.Decline,
            credentialRefreshDecision(
                failedUrl = signed,
                refreshesUsed = 0,
                isRefreshInFlight = false,
                lastAttemptedUrl = signed,
            ),
        )
    }

    @Test
    fun aLinkWithNoCredentialsIsNeverRefreshed() {
        assertEquals(
            CredentialRefreshDecision.Decline,
            credentialRefreshDecision(
                failedUrl = plain,
                refreshesUsed = 0,
                isRefreshInFlight = false,
                lastAttemptedUrl = null,
            ),
        )
        assertEquals(
            CredentialRefreshDecision.Decline,
            credentialRefreshDecision(
                failedUrl = null,
                refreshesUsed = 0,
                isRefreshInFlight = false,
                lastAttemptedUrl = null,
            ),
        )
    }

    @Test
    fun theCredentialHeuristicRecognisesOrdinaryDebridLinks() {
        // Why the loop was so easy to hit: essentially every debrid link qualifies, including
        // ones whose only signed parameter is a single letter.
        assertTrue(signed.hasLikelyExpiringPlaybackCredentials())
        assertTrue("https://h.example/v.mkv?t=1&e=2".hasLikelyExpiringPlaybackCredentials())
        assertTrue("https://h.example/v.mkv?X-Amz-Signature=zz".hasLikelyExpiringPlaybackCredentials())
        assertFalse(plain.hasLikelyExpiringPlaybackCredentials())
        assertFalse("https://h.example/v.mkv?quality=1080p".hasLikelyExpiringPlaybackCredentials())
    }
}
