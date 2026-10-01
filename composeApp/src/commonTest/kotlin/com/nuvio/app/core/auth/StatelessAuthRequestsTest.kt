package com.nuvio.app.core.auth

import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.*

@OptIn(SupabaseInternal::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StatelessAuthRequestsTest {
    private fun session(id: String) = UserSession("access-$id", "refresh-$id", expiresIn = 3600,
        tokenType = "bearer", user = UserInfo(aud = "authenticated", id = id, email = "$id@example.test"))
    private val missingSession = """{"error":"session_not_found","error_code":"session_not_found","error_description":"synthetic private response"}"""

    @Test fun sdkErrorParserReallyCanClearAnUnrelatedSession() = runTest {
        val client = createSupabaseClient("https://auth.example.test", "public-key") {
            httpEngine = MockEngine { respond(missingSession, HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json")) }
            coroutineDispatcher = StandardTestDispatcher(testScheduler)
            install(Auth) { autoLoadFromStorage = false; autoSetupPlatform = false; alwaysAutoRefresh = false; enableLifecycleCallbacks = false; sessionManager = MemorySessionManager(); codeVerifierCache = MemoryCodeVerifierCache() }
        }
        try {
            client.auth.importSession(session("B"), autoRefresh = false)
            try { client.auth.retrieveUser("access-A"); fail("Expected rejection") } catch (_: io.github.jan.supabase.exceptions.RestException) { }
            runCurrent()
            assertNull(client.auth.currentSessionOrNull(), "Pinned SDK parser schedules a destructive clear outside request ownership")
        } finally { client.close() }
    }

    @Test fun capturedRequestsCannotInvokeSdkErrorParserOrClearNewAccount() = runTest {
        val paths = mutableListOf<String>()
        val authorization = mutableListOf<String?>()
        val client = createSupabaseClient("https://auth.example.test", "public-key") {
            httpEngine = MockEngine { request ->
                paths += request.url.encodedPath
                authorization += request.headers[HttpHeaders.Authorization]
                respond(missingSession, HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            coroutineDispatcher = StandardTestDispatcher(testScheduler)
            install(Auth) { autoLoadFromStorage = false; autoSetupPlatform = false; alwaysAutoRefresh = false; enableLifecycleCallbacks = false; sessionManager = MemorySessionManager(); codeVerifierCache = MemoryCodeVerifierCache() }
        }
        try {
            val newer = session("B")
            client.auth.importSession(newer, autoRefresh = false)
            val requests = StatelessAuthRequests(client)
            for (request in listOf<suspend () -> Unit>(
                { requests.user("access-A") }, { requests.refresh("refresh-A") },
                { requests.login("A@example.test", "secret", false) },
            )) {
                val error = try { request(); fail("Expected rejection") } catch (error: AuthHttpFailure) { error }
                assertTrue(error.invalidSession)
                assertFalse(error.toString().contains("private"))
                runCurrent()
                assertEquals(newer, client.auth.currentSessionOrNull())
            }
            requests.signOut("access-A") // Already invalid is tolerated, without SDK cleanup.
            runCurrent()
            assertEquals(newer, client.auth.currentSessionOrNull())
            assertEquals(listOf("/auth/v1/user", "/auth/v1/token", "/auth/v1/token", "/auth/v1/logout"), paths)
            assertEquals<List<String?>>(listOf("Bearer access-A", "Bearer public-key", "Bearer public-key", "Bearer access-A"), authorization)
        } finally { client.close() }
    }

    @Test fun credentialAndRefreshResponsesRemainUnimportedAndSignupConfirmationAdoptsNothing() = runTest {
        var confirmation = false
        val client = createSupabaseClient("https://auth.example.test", "public-key") {
            httpEngine = MockEngine {
                val body = if (confirmation) """{"id":"A","aud":"authenticated","email":"A@example.test"}"""
                    else """{"access_token":"access-A","refresh_token":"refresh-A","expires_in":3600,"token_type":"bearer","user":{"id":"A","aud":"authenticated","email":"A@example.test"}}"""
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            coroutineDispatcher = StandardTestDispatcher(testScheduler)
            install(Auth) { autoLoadFromStorage = false; autoSetupPlatform = false; alwaysAutoRefresh = false; enableLifecycleCallbacks = false; sessionManager = MemorySessionManager(); codeVerifierCache = MemoryCodeVerifierCache() }
        }
        try {
            val newer = session("B")
            client.auth.importSession(newer, autoRefresh = false)
            val requests = StatelessAuthRequests(client)
            assertEquals("A", requests.login("A@example.test", "secret", false)?.user?.id)
            assertEquals("A", requests.refresh("refresh-A").user?.id)
            confirmation = true
            assertNull(requests.login("A@example.test", "secret", true))
            runCurrent()
            assertEquals(newer, client.auth.currentSessionOrNull())
        } finally { client.close() }
    }
}
