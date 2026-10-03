package com.nuvio.app.features.social

import com.nuvio.app.core.network.ZSupabaseProvider
import com.nuvio.app.features.watchparty.PartyContent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.github.jan.supabase.annotations.SupabaseInternal
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real singleton/activation/HTTP decoding; only the session and HTTP neighbours are controlled. */
@OptIn(SupabaseInternal::class)
class SocialRepositoryAuthorityTest {
    private fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }

    private class Gate(val path: String, val profile: String? = null, val failure: Boolean = false) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private inner class Fixture(testScope: CoroutineScope) : CoroutineScope by testScope {
        val a = "social-authority-test-A"
        val b = "social-authority-test-B"
        var stamp = "first"
        var gate: Gate? = null
        val retries = AtomicInteger()
        val requests = AtomicInteger()
        var sessionGate: Gate? = null
        val access = object : SocialSessionAccess {
            override suspend fun ensure(profileId: String): Boolean {
                sessionGate?.also { sessionGate = null }?.let {
                    it.entered.complete(Unit); it.release.await()
                    if (it.failure) error("synthetic delayed session failure")
                    return false
                }
                return true
            }
            override suspend fun reexchange(profileId: String): Boolean { retries.incrementAndGet(); return true }
        }
        val client = createSupabaseClient("https://social-authority.example.test", "synthetic-public-key") {
            httpEngine = MockEngine { request ->
                requests.incrementAndGet()
                val path = request.url.encodedPath.substringAfterLast('/')
                val profile = runCatching { Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject["p_profile_id"]?.jsonPrimitive?.content }.getOrNull()
                val capturedStamp = stamp
                val held = gate?.takeIf { it.path == path && (it.profile == null || it.profile == profile) }
                if (held != null) {
                    gate = null
                    held.entered.complete(Unit)
                    held.release.await()
                    if (held.failure) return@MockEngine respond("""{"code":"PGRST301","message":"JWT expired"}""", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                val body = when (path) {
                    "get_social_capabilities" -> """{"social_enabled":true,"watch_party_enabled":false}"""
                    "social_get_state" -> Json.encodeToString(payload(requireNotNull(profile), capturedStamp))
                    "social_set_privacy", "social_publish_presence", "social_clear_presence", "social_send_friend_request" -> "{}"
                    else -> error("Unexpected synthetic RPC: $path")
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            install(Auth) {
                autoLoadFromStorage = false; autoSetupPlatform = false; alwaysAutoRefresh = false; enableLifecycleCallbacks = false
                sessionManager = MemorySessionManager(); codeVerifierCache = MemoryCodeVerifierCache()
            }
            install(Postgrest)
        }
        fun payload(profile: String, version: String): SocialStatePayload {
            val me = SocialProfileSummary(profile, version, version)
            val friend = SocialProfileSummary("$profile-friend", version, version)
            return SocialStatePayload(
                me = me, friends = listOf(friend),
                requests = listOf(FriendRequest("$profile-$version-request", friend, "2026-10-03")),
                partyInvites = listOf(SocialInboxItem("$profile-$version-invite", "party", friend, PartyContent("movie", "movie", "movie", "Movie"), "2026-10-03")),
                watchingNow = listOf(WatchingNowItem(friend, "movie", "movie", "movie", version, positionMs = 1, durationMs = 100, state = SocialPlaybackState.playing, heartbeatAt = "2026-10-03")),
            )
        }
        suspend fun activate(profile: String?) {
            SocialRepository.activate(profile)
            withTimeout(10_000) { (field(SocialRepository::class.java, "activation").get(null) as? Job)?.join() }
            if (profile != null) assertEquals(profile, SocialRepository.uiState.value.me?.profileId)
        }
        fun hold(path: String = "social_get_state", failure: Boolean = false) = Gate(path, a, failure).also { gate = it }
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val lazy = field(ZSupabaseProvider::class.java, "client\$delegate").get(null)
        val value = field(lazy.javaClass, "_value")
        val originalClient = value.get(lazy)
        val access = field(SocialRepository::class.java, "sessionAccess")
        val originalAccess = access.get(null)
        val fixture = Fixture(this)
        value.set(lazy, fixture.client)
        access.set(null, fixture.access)
        try {
            fixture.activate(null)
            fixture.activate(fixture.a)
            fixture.block()
        } finally {
            fixture.gate?.release?.complete(Unit)
            fixture.sessionGate?.release?.complete(Unit)
            fixture.activate(null)
            for (name in listOf("recoveryTriggers", "recovery")) {
                val job = field(SocialRepository::class.java, name)
                (job.get(null) as? Job)?.cancel(); job.set(null, null)
            }
            access.set(null, originalAccess)
            value.set(lazy, originalClient)
            fixture.client.close()
        }
    }

    private fun staleRefresh(returnToA: Boolean = false, failure: Boolean = false, verify: (SocialUiState, SocialUiState) -> Unit) = fixture {
        val hold = hold(failure = failure)
        val old = async(Dispatchers.Default) { runCatching { SocialRepository.refresh() } }
        withTimeout(10_000) { hold.entered.await() }
        stamp = "new"
        activate(b)
        if (returnToA) activate(a)
        val expected = SocialRepository.uiState.value
        val before = requests.get()
        hold.release.complete(Unit)
        withTimeout(10_000) { old.await() }
        val actual = SocialRepository.uiState.value
        verify(expected, actual)
        assertEquals(expected, actual, "stale completion changed current state")
        assertEquals(0, retries.get(), "stale generation re-exchanged the session")
        assertEquals(before, requests.get(), "stale completion started another RPC")
    }

    @Test fun delayedARefreshCannotOverwriteB() = staleRefresh { expected, actual -> assertEquals(expected.me, actual.me) }
    @Test fun firstACannotOverwriteSecondA() = staleRefresh(returnToA = true) { expected, actual -> assertEquals("new", actual.me?.handle); assertEquals(expected.me, actual.me) }
    @Test fun staleFriendsAreFenced() = staleRefresh { expected, actual -> assertEquals(expected.friends, actual.friends) }
    @Test fun staleInvitationsAndRequestsAreFenced() = staleRefresh { expected, actual -> assertEquals(expected.partyInvites, actual.partyInvites); assertEquals(expected.requests, actual.requests) }
    @Test fun staleWatchingNowIsFenced() = staleRefresh { expected, actual -> assertEquals(expected.watchingNow, actual.watchingNow) }
    @Test fun staleErrorsAndRetriesAreFenced() = staleRefresh(failure = true) { _, actual -> assertNull(actual.errorMessage); assertFalse(actual.isLoading) }
    @Test fun staleAErrorsCannotRetryInSecondA() = staleRefresh(returnToA = true, failure = true) { _, actual -> assertNull(actual.errorMessage) }
    @Test fun currentGenerationRefreshPublishes() = fixture {
        stamp = "current"
        SocialRepository.refresh()
        assertEquals(payload(a, "current").me, SocialRepository.uiState.value.me)
        assertEquals(payload(a, "current").friends, SocialRepository.uiState.value.friends)
        assertFalse(SocialRepository.uiState.value.isLoading)
    }
    @Test fun currentGenerationRetryStillPublishes() = fixture {
        val hold = hold(failure = true)
        val current = async(Dispatchers.Default) { SocialRepository.refresh() }
        hold.entered.await(); stamp = "retried"; hold.release.complete(Unit); current.await()
        assertEquals(1, retries.get())
        assertEquals("retried", SocialRepository.uiState.value.me?.handle)
    }
    @Test fun staleSessionFailureCannotPublish() = fixture {
        val hold = Gate("session").also { sessionGate = it }
        val old = async(Dispatchers.Default) { runCatching { SocialRepository.refresh() } }
        hold.entered.await(); stamp = "new"; activate(b)
        val expected = SocialRepository.uiState.value
        hold.release.complete(Unit); old.await()
        assertEquals(expected, SocialRepository.uiState.value)
    }
    @Test fun stalePrivacyMutationCannotChangeNewIdentity() = fixture {
        val hold = hold("social_set_privacy")
        val old = async(Dispatchers.Default) { runCatching { SocialRepository.setPrivacy(false, false) } }
        hold.entered.await(); activate(b)
        hold.release.complete(Unit)
        assertTrue(old.await().isFailure)
        assertTrue(SocialRepository.uiState.value.me!!.shareWatchingNow)
    }
    @Test fun staleThrownSessionFailureCannotReachNewScreen() = fixture {
        val hold = Gate("session", failure = true).also { sessionGate = it }
        val old = async(Dispatchers.Default) { runCatching { SocialRepository.setPrivacy(false, false) } }
        hold.entered.await(); activate(b)
        val expected = SocialRepository.uiState.value
        hold.release.complete(Unit)
        val result = old.await()
        assertTrue(result.exceptionOrNull() is kotlin.coroutines.cancellation.CancellationException)
        assertEquals(expected, SocialRepository.uiState.value)
    }
    @Test fun staleMutationCannotStartNewGenerationRefresh() = fixture {
        val hold = Gate("social_send_friend_request").also { gate = it }
        val old = async(Dispatchers.Default) { runCatching { SocialRepository.sendFriendRequest("synthetic-friend") } }
        hold.entered.await(); activate(b)
        val expected = SocialRepository.uiState.value; val before = requests.get()
        hold.release.complete(Unit); assertTrue(old.await().isFailure)
        assertEquals(before, requests.get()); assertEquals(expected, SocialRepository.uiState.value)
    }
    @Test fun repeatedSwitchingWithOverlappingRefreshes() = fixture {
        repeat(20) { round ->
            activate(a)
            val hold = hold()
            val old = async(Dispatchers.Default) { runCatching { SocialRepository.refresh() } }
            hold.entered.await(); stamp = "round-$round"; activate(b); activate(a)
            val expected = SocialRepository.uiState.value
            hold.release.complete(Unit); old.await()
            assertEquals(expected, SocialRepository.uiState.value)
        }
    }

    @Test fun accountWipeFencesAWhenSameProfileReturns() = fixture {
        val hold = hold()
        val old = async(Dispatchers.Default) { runCatching { SocialRepository.refresh() } }
        hold.entered.await()
        SocialRepository.onAccountWipe()
        stamp = "new-session"
        activate(a)
        val expected = SocialRepository.uiState.value
        hold.release.complete(Unit); old.await()
        assertEquals(expected, SocialRepository.uiState.value)
        assertEquals("new-session", SocialRepository.uiState.value.me?.handle)
    }

    @Test fun delayedOldActivationCannotPublishCapabilitiesOrCache() = fixture {
        activate(null)
        val hold = Gate("get_social_capabilities").also { gate = it }
        SocialRepository.activate(a)
        val previous = field(SocialRepository::class.java, "activation").get(null) as Job
        hold.entered.await()
        stamp = "new"
        activate(b)
        val expected = SocialRepository.uiState.value
        hold.release.complete(Unit)
        previous.join()
        // An extra current-generation refresh settles after the old response has been released.
        SocialRepository.refresh()
        assertEquals(expected, SocialRepository.uiState.value)
    }
}
