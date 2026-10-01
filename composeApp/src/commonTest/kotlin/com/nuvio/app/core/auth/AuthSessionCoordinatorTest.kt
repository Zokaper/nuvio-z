package com.nuvio.app.core.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuthSessionCoordinatorTest {
    private fun session(id: String, email: String = "$id@example.test", token: String = id) =
        OwnedAuthSession(id, email, "access-$token", "refresh-$token")

    private class Rejected : Exception("synthetic secret: password=secret token=secret person@example.test")

    private class Port : AuthSessionPort {
        var session: OwnedAuthSession? = null
        var anonymous: String? = null
        var wipes = 0
        var clears = 0
        var imports = 0
        var refreshes = 0
        var noCredentialSession = false
        var loginHook: suspend (String) -> Unit = { email -> session = OwnedAuthSession(email.substringBefore('@'), email, "access-$email", "refresh-$email") }
        var signOutHook: suspend () -> Unit = {}
        var validateHook: suspend (OwnedAuthSession) -> SessionValidation = { SessionValidation.Valid }
        var refreshHook: suspend (OwnedAuthSession) -> OwnedAuthSession = { it }
        var importHook: suspend (OwnedAuthSession) -> Unit = {}
        var loadStoredHook: suspend () -> OwnedAuthSession? = { null }
        var clearHook: suspend () -> Unit = {}
        override fun currentSession() = session
        override suspend fun loadStoredSession() = loadStoredHook()
        override fun loadAnonymousId() = anonymous
        override fun saveAnonymousId(id: String) { anonymous = id }
        override fun clearAnonymousId() { anonymous = null }
        override fun wipeAccountData() { wipes++ }
        override suspend fun login(email: String, password: String, signUp: Boolean): OwnedAuthSession? {
            loginHook(email)
            return if (noCredentialSession) null else session
        }
        override suspend fun signOut() { signOutHook() }
        override suspend fun clearSession() { clears++; session = null; clearHook() }
        override suspend fun deleteAccount() {}
        override suspend fun validate(session: OwnedAuthSession) = validateHook(session)
        override suspend fun refresh(session: OwnedAuthSession): OwnedAuthSession { refreshes++; return refreshHook(session) }
        override suspend fun importSession(session: OwnedAuthSession) { imports++; this.session = session; importHook(session) }
        override fun isDefinitiveRefreshRejection(error: Throwable) = error is Rejected
    }

    @Test fun validationCannotPublishAfterSignOut() = runTest {
        val port = Port().also { it.session = session("A") }
        val auth = AuthSessionCoordinator(port).also { it.initialize() }
        val finish = CompletableDeferred<SessionValidation>()
        port.validateHook = { finish.await() }
        val validation = launch { auth.authenticated(port.session!!) }
        runCurrent()
        assertTrue(auth.signOut("failure").isSuccess)
        finish.complete(SessionValidation.Valid); validation.join()
        assertEquals(AuthState.Unauthenticated, auth.state.value)
        assertEquals(0, port.refreshes)
    }

    @Test fun validationCannotOverwriteNewLoginOrRefreshItsSession() = runTest {
        for (result in listOf(SessionValidation.Valid, SessionValidation.NeedsRefresh, SessionValidation.Unavailable)) {
            val port = Port().also { it.session = session("A") }
            val auth = AuthSessionCoordinator(port).also { it.initialize() }
            val finish = CompletableDeferred<SessionValidation>()
            port.validateHook = { finish.await() }
            val validation = launch { auth.authenticated(port.session!!) }
            runCurrent()
            assertTrue(auth.login("B@example.test", "secret", false, "failure").isSuccess)
            finish.complete(result); validation.join()
            assertEquals("B", (auth.state.value as AuthState.Authenticated).userId)
            assertEquals(0, port.clears)
            assertEquals(0, port.wipes)
            assertEquals(0, port.refreshes)
        }
    }

    @Test fun signOutCleanupFinishesBeforeNewSdkLogin() = runTest {
        val port = Port().also { it.session = session("A") }
        val auth = AuthSessionCoordinator(port).also { it.initialize(); it.authenticated(port.session!!) }
        val finish = CompletableDeferred<Unit>()
        port.signOutHook = { finish.await() }
        val logout = launch { auth.signOut("failure") }
        runCurrent()
        val login = launch { auth.login("B@example.test", "secret", false, "failure") }
        runCurrent()
        assertEquals(AuthState.Unauthenticated, auth.state.value)
        finish.complete(Unit); logout.join(); login.join()
        assertEquals("B", (auth.state.value as AuthState.Authenticated).userId)
        assertEquals("B", port.session?.userId)
        assertEquals(1, port.wipes)
    }

    @Test fun staleRefreshFailureChecksCurrentCapturedTokenWithoutUsingOldCause() = runTest {
        val port = Port()
        val auth = AuthSessionCoordinator(port).also { it.initialize() }
        auth.login("B@example.test", "secret", false, "failure")
        var validatedToken: String? = null
        port.validateHook = { validatedToken = it.accessToken; SessionValidation.Valid }
        port.refreshHook = { throw Rejected() }
        auth.refreshFailure() // No failing-user ID exists in the real SDK event.
        assertEquals(port.session!!.accessToken, validatedToken)
        assertEquals("B", (auth.state.value as AuthState.Authenticated).userId)
        assertEquals(0, port.clears)
        assertEquals(0, port.wipes)
        assertEquals(0, port.refreshes)
    }

    @Test fun sameAndDifferentEmailStatusCannotCompletePendingWrongPassword() = runTest {
        for (email in listOf("A@example.test", "B@example.test")) {
            val port = Port().also { it.session = session("old", email) }
            val auth = AuthSessionCoordinator(port).also { it.initialize() }
            val finish = CompletableDeferred<Unit>()
            port.loginHook = { finish.await(); throw Rejected() }
            val login = launch { assertTrue(auth.login("B@example.test", "wrong", false, "wrong password").isFailure) }
            runCurrent()
            auth.authenticated(port.session!!)
            assertEquals(AuthState.Loading, auth.state.value)
            finish.complete(Unit); login.join()
            assertEquals(AuthState.Loading, auth.state.value)
            assertEquals("wrong password", auth.error.value)
        }
    }

    @Test fun lateFailureCannotPublishErrorOrClearNewAttempt() = runTest {
        val port = Port()
        val messages = mutableListOf<String>()
        val auth = AuthSessionCoordinator(port, messages::add).also { it.initialize() }
        val first = CompletableDeferred<Unit>(); val second = CompletableDeferred<Unit>()
        port.loginHook = { email ->
            if (email.startsWith("A")) { first.await(); throw Rejected() }
            else { second.await(); port.session = session("B") }
        }
        val attempt1 = launch { auth.login("A@example.test", "wrong", false, "old failure") }
        runCurrent()
        val attempt2 = launch { auth.login("B@example.test", "correct", false, "new failure") }
        runCurrent()
        first.complete(Unit); attempt1.join(); runCurrent()
        assertNull(auth.error.value)
        auth.authenticated(session("C"))
        assertEquals(AuthState.Loading, auth.state.value)
        second.complete(Unit); attempt2.join()
        assertEquals("B", (auth.state.value as AuthState.Authenticated).userId)
        assertNull(auth.error.value)
        assertTrue(messages.none { "secret" in it || "@" in it || "password=" in it || "token=" in it })
    }

    @Test fun staleDefinitiveRefreshCompletionCannotClearQueuedLogin() = runTest {
        val port = Port().also { it.session = session("A") }
        val auth = AuthSessionCoordinator(port).also { it.initialize(); it.authenticated(port.session!!) }
        val finish = CompletableDeferred<Unit>()
        port.validateHook = { SessionValidation.NeedsRefresh }
        port.refreshHook = { finish.await(); throw Rejected() }
        val failure = launch { auth.refreshFailure() }
        runCurrent()
        val login = launch { auth.login("B@example.test", "secret", false, "failure") }
        runCurrent()
        finish.complete(Unit); failure.join(); login.join()
        assertEquals("B", (auth.state.value as AuthState.Authenticated).userId)
        assertEquals(0, port.clears)
        assertEquals(0, port.wipes)
    }

    @Test fun transientRefreshRetainsAuthAndCurrentDefinitiveRejectionClearsOnce() = runTest {
        for (definitive in listOf(false, true)) {
            val port = Port().also { it.session = session("A") }
            val auth = AuthSessionCoordinator(port).also { it.initialize(); it.authenticated(port.session!!) }
            port.validateHook = { SessionValidation.NeedsRefresh }
            port.refreshHook = { if (definitive) throw Rejected() else throw IllegalStateException("offline") }
            auth.refreshFailure()
            if (definitive) { assertEquals(AuthState.Unauthenticated, auth.state.value); assertEquals(1, port.wipes) }
            else { assertTrue(auth.state.value is AuthState.Authenticated); assertEquals(0, port.clears) }
        }
    }

    @Test fun anonymousAndExternalCodeLoginRemainSupported() = runTest {
        val port = Port()
        val auth = AuthSessionCoordinator(port).also { it.initialize() }
        auth.completeAnonymous(auth.beginAnonymous(), "anonymous")
        assertTrue((auth.state.value as AuthState.Authenticated).isAnonymous)
        assertTrue(auth.login("  b@EXAMPLE.test ", "secret", false, "failure").isSuccess)
        assertNull(port.anonymous)
        assertFalse((auth.state.value as AuthState.Authenticated).isAnonymous)
        auth.signOut("failure")
        assertTrue(auth.importExternalSession(session("code-user"), "failure").isSuccess)
        assertEquals("code-user", (auth.state.value as AuthState.Authenticated).userId)
    }

    @Test fun explicitLoginCannotAdoptDifferentPreexistingSession() = runTest {
        val port = Port().also { it.session = session("A"); it.loginHook = {} }
        val auth = AuthSessionCoordinator(port).also { it.initialize() }
        assertTrue(auth.login("B@example.test", "secret", false, "failure").isFailure)
        assertFalse(auth.state.value is AuthState.Authenticated)
    }

    @Test fun sameEmailStoredSessionDoesNotStandInForCredentialResponse() = runTest {
        val port = Port().also { it.session = session("A"); it.loginHook = {}; it.noCredentialSession = true }
        val auth = AuthSessionCoordinator(port).also { it.initialize() }
        assertTrue(auth.login("A@example.test", "secret", false, "failure").isFailure)
        assertFalse(auth.state.value is AuthState.Authenticated)
        assertEquals(0, port.imports)
        // Confirmation-required signup also cannot adopt the old stored session.
        assertTrue(auth.login("A@example.test", "secret", true, "failure").isSuccess)
        assertFalse(auth.state.value is AuthState.Authenticated)
    }

    @Test fun staleSuccessfulCredentialResponseCannotImportAfterSignOut() = runTest {
        val port = Port()
        val auth = AuthSessionCoordinator(port).also { it.initialize() }
        val finish = CompletableDeferred<Unit>()
        port.loginHook = { finish.await(); port.session = session("A") }
        val login = launch { auth.login("A@example.test", "secret", false, "failure") }
        runCurrent()
        val logout = launch { auth.signOut("failure") }
        runCurrent()
        finish.complete(Unit); login.join(); logout.join()
        assertEquals(AuthState.Unauthenticated, auth.state.value)
        assertNull(port.session)
        assertEquals(0, port.imports)
    }

    @Test fun lateTokenConsumerRefreshCannotImportOverNewLogin() = runTest {
        val port = Port().also { it.session = session("A") }
        val auth = AuthSessionCoordinator(port).also { it.initialize(); it.authenticated(port.session!!) }
        val captured = port.session!!
        val finish = CompletableDeferred<Unit>()
        port.refreshHook = { finish.await(); session("A", token = "new-A") }
        var token: String? = "not-completed"
        val refresh = launch { token = auth.accessToken(captured, refresh = true) }
        runCurrent()
        val login = launch { auth.login("B@example.test", "secret", false, "failure") }
        runCurrent()
        finish.complete(Unit); refresh.join(); login.join()
        assertNull(token)
        assertEquals("B", port.session!!.userId)
        assertEquals("B", (auth.state.value as AuthState.Authenticated).userId)
        assertEquals(1, port.imports, "Only the new credential response is imported")
        assertNull(auth.accessToken(captured, refresh = false))
    }

    @Test fun tokenConsumerRefreshUsesCapturedSessionAndNeverClearsOnFailure() = runTest {
        val port = Port().also { it.session = session("A") }
        val auth = AuthSessionCoordinator(port).also { it.initialize(); it.authenticated(port.session!!) }
        val original = port.session!!
        assertEquals(original.accessToken, auth.accessToken(original, refresh = false))
        port.refreshHook = { assertTrue(it === original); session("A", token = "new-A") }
        assertEquals("access-new-A", auth.accessToken(original, refresh = true))
        assertEquals("access-new-A", port.session!!.accessToken)
        port.refreshHook = { throw Rejected() }
        assertNull(auth.accessToken(port.session!!, refresh = true))
        assertTrue(auth.state.value is AuthState.Authenticated)
        assertEquals(0, port.clears)
        assertEquals(0, port.wipes)
    }

    @Test fun scheduledRefreshUsesExpiryAndSurvivesItsSdkStatusEcho() = runTest {
        fun expiring(token: String, expiry: Long) = OwnedAuthSession("A", "A@example.test", "access-$token", "refresh-$token", expiresAtEpochMilliseconds = expiry)
        val port = Port().also { it.session = expiring("old", 100_000) }
        val auth = AuthSessionCoordinator(port).also { it.initialize(); it.authenticated(port.session!!) }
        port.refreshHook = { expiring("new", 200_000) }
        port.importHook = { auth.authenticated(it) }
        auth.refreshDue(0)
        assertEquals(0, port.refreshes)
        auth.refreshDue(40_000)
        assertEquals(1, port.refreshes)
        assertEquals("access-new", port.session!!.accessToken)
        auth.refreshDue(140_000)
        assertEquals(2, port.refreshes, "A status echo must not leave an active validation that blocks the timer")
    }

    @Test fun scheduledRefreshCannotImportOrClearAfterNewLogin() = runTest {
        for (reject in listOf(false, true)) {
            val port = Port().also { it.session = OwnedAuthSession("A", "A@example.test", "access-A", "refresh-A", expiresAtEpochMilliseconds = 0) }
            val auth = AuthSessionCoordinator(port).also { it.initialize(); it.authenticated(port.session!!) }
            val finish = CompletableDeferred<Unit>()
            port.refreshHook = { finish.await(); if (reject) throw Rejected() else session("A", token = "late") }
            val refresh = launch { auth.refreshDue(0) }
            runCurrent()
            val login = launch { auth.login("B@example.test", "secret", false, "failure") }
            runCurrent()
            finish.complete(Unit); refresh.join(); login.join()
            assertEquals("B", port.session!!.userId)
            assertEquals("B", (auth.state.value as AuthState.Authenticated).userId)
            assertEquals(1, port.imports)
            assertEquals(0, port.clears)
            assertEquals(0, port.wipes)
        }
    }

    @Test fun storedSessionRestoresThroughValidationAndEmptyStorageSettlesStartup() = runTest {
        val port = Port()
        val auth = AuthSessionCoordinator(port).also { it.initialize() }
        auth.notAuthenticated(false)
        assertEquals(AuthState.Loading, auth.state.value)
        port.loadStoredHook = { session("A") }
        port.validateHook = { SessionValidation.Unavailable } // Offline startup retains a stored session.
        auth.restoreStoredSession()
        assertEquals("A", (auth.state.value as AuthState.Authenticated).userId)
        assertEquals(1, port.imports)
        val empty = AuthSessionCoordinator(Port()).also { it.initialize() }
        empty.restoreStoredSession()
        assertEquals(AuthState.Unauthenticated, empty.state.value)
    }

    @Test fun delayedStorageCannotImportAfterSignOutOrNewLogin() = runTest {
        for (signOut in listOf(false, true)) {
            val port = Port()
            val auth = AuthSessionCoordinator(port).also { it.initialize() }
            val finish = CompletableDeferred<Unit>()
            port.loadStoredHook = { finish.await(); session("A") }
            val restore = launch { auth.restoreStoredSession() }
            runCurrent()
            val next = launch {
                if (signOut) auth.signOut("failure") else auth.login("B@example.test", "secret", false, "failure")
            }
            runCurrent()
            finish.complete(Unit); restore.join(); next.join()
            assertEquals(if (signOut) 0 else 1, port.imports)
            if (signOut) {
                assertEquals(AuthState.Unauthenticated, auth.state.value)
                assertNull(port.session)
            } else {
                assertEquals("B", port.session!!.userId)
                assertEquals("B", (auth.state.value as AuthState.Authenticated).userId)
            }
        }
    }

    @Test fun definitiveClearCannotBeReenteredByItsEmptySdkStatus() = runTest {
        for (refreshFailureStatus in listOf(false, true)) {
            val port = Port().also { it.session = OwnedAuthSession("A", "A@example.test", "access-A", "refresh-A", expiresAtEpochMilliseconds = 0) }
            val auth = AuthSessionCoordinator(port).also { it.initialize(); it.authenticated(port.session!!) }
            port.refreshHook = { throw Rejected() }
            port.clearHook = { if (refreshFailureStatus) auth.refreshFailure() else auth.notAuthenticated(false) }
            auth.refreshDue(0)
            assertEquals(AuthState.Unauthenticated, auth.state.value)
            assertNull(port.session)
            assertEquals(1, port.clears)
            assertEquals(1, port.wipes)
        }
    }
}
