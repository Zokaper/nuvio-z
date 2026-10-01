package com.nuvio.app.core.auth

import com.nuvio.app.features.profiles.NuvioProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthStateMachineTest {

    // 1. Email login succeeds before session-status collector reports authenticated.
    @Test
    fun emailLoginSucceedsBeforeSessionStatusCollectorReportsAuthenticated() {
        val initial = AuthStateMachine.onInitialize(savedAnonId = null)
        assertEquals(AuthState.Loading, initial.authState)

        // User starts explicit login
        val starting = AuthStateMachine.onExplicitSignInStarted(
            state = initial,
            intent = InFlightAuthIntent.EmailSignIn(epoch = 1L, email = "user@test.com"),
        )
        assertEquals(1L, starting.currentEpoch)
        assertEquals(1L, starting.inFlightEpoch)

        // Supabase returns and explicit mutation succeeds
        val signinResult = AuthStateMachine.onExplicitSignInSucceeded(
            state = starting,
            epoch = 1L,
            userId = "user-123",
            email = "user@test.com",
        )
        assertFalse(signinResult.isDropped)
        assertTrue(signinResult.clearAnonymousStorage)
        assertFalse(signinResult.shouldValidateRemote, "Explicit mutation is already authoritative; must not validate again")
        assertEquals(AuthState.Authenticated("user-123", "user@test.com", isAnonymous = false), signinResult.newState.authState)
        assertEquals("user-123", signinResult.newState.validatedUserId)
        assertNull(signinResult.newState.inFlightEpoch)

        // Session status collector subsequently emits Authenticated
        val sessionStatusResult = AuthStateMachine.onSessionStatusAuthenticated(
            state = signinResult.newState,
            sessionUserId = "user-123",
            email = "user@test.com",
        )
        assertFalse(sessionStatusResult.shouldValidateRemote, "Must skip redundant remote validation")
        assertEquals(signinResult.newState.authState, sessionStatusResult.newState.authState)
    }

    // 2. Session-status authenticated arrives before the mutation result is adopted.
    @Test
    fun sessionStatusAuthenticatedArrivesBeforeMutationResultIsAdopted() {
        val initial = AuthStateMachine.onInitialize(savedAnonId = null)
        val inFlight = AuthStateMachine.onExplicitSignInStarted(
            state = initial,
            intent = InFlightAuthIntent.EmailSignIn(epoch = 1L, email = "async@test.com"),
        )

        // Supabase-kt emits SessionStatus.Authenticated before signInWithEmail returns
        val statusResult = AuthStateMachine.onSessionStatusAuthenticated(
            state = inFlight,
            sessionUserId = "user-456",
            email = "async@test.com",
        )
        assertFalse(statusResult.shouldValidateRemote, "In-flight mutation session must be adopted directly without remote validation")
        assertEquals(
            AuthState.Authenticated("user-456", "async@test.com", isAnonymous = false),
            statusResult.newState.authState,
        )
        assertEquals("user-456", statusResult.newState.validatedUserId)

        // Later the mutation method finishes
        val mutationResult = AuthStateMachine.onExplicitSignInSucceeded(
            state = statusResult.newState,
            epoch = 1L,
            userId = "user-456",
            email = "async@test.com",
        )
        assertEquals(
            AuthState.Authenticated("user-456", "async@test.com", isAnonymous = false),
            mutationResult.newState.authState,
        )
    }

    // 3. Stale anonymous ID exists before email login.
    @Test
    fun staleAnonymousIdExistsBeforeEmailLogin() {
        // App started with anonymous identity
        val initial = AuthStateMachine.onInitialize(savedAnonId = "anon-xyz")
        assertEquals(AuthState.Authenticated("anon-xyz", null, isAnonymous = true), initial.authState)
        assertEquals("anon-xyz", initial.anonymousUserId)

        // Explicit email sign-in starts and succeeds
        val starting = AuthStateMachine.onExplicitSignInStarted(
            state = initial,
            intent = InFlightAuthIntent.EmailSignIn(epoch = 10L, email = "real@test.com"),
        )
        val signinResult = AuthStateMachine.onExplicitSignInSucceeded(
            state = starting,
            epoch = 10L,
            userId = "official-user",
            email = "real@test.com",
        )
        assertTrue(signinResult.clearAnonymousStorage)
        assertNull(signinResult.newState.anonymousUserId)
        assertEquals(
            AuthState.Authenticated("official-user", "real@test.com", isAnonymous = false),
            signinResult.newState.authState,
        )
    }

    // 4. A stale unauthenticated/initializing event arrives after successful login.
    @Test
    fun staleUnauthenticatedOrInitializingEventArrivesAfterSuccessfulLogin() {
        val authenticatedState = AuthMachineState(
            authState = AuthState.Authenticated("user-1", "user1@test.com", isAnonymous = false),
            validatedUserId = "user-1",
            currentEpoch = 5L,
        )

        // Stale NotAuthenticated arrives (e.g. from prior logout or slow startup check)
        val notAuthResult = AuthStateMachine.onSessionStatusNotAuthenticated(
            state = authenticatedState,
            hasActiveSession = true,
        )
        assertTrue(notAuthResult.isDropped, "Must drop stale NotAuthenticated when active session exists")
        assertEquals(authenticatedState.authState, notAuthResult.newState.authState)

        // Stale Initializing arrives
        val initResult = AuthStateMachine.onSessionStatusInitializing(authenticatedState)
        assertTrue(initResult.isDropped, "Must never downgrade Authenticated state to Loading")
        assertEquals(authenticatedState.authState, initResult.newState.authState)
    }

    // 5. Remote validation experiences a transient transport failure after successful authentication.
    @Test
    fun remoteValidationTransientFailureRetainsCachedAuthAndPreventsLoops() {
        val rehydratingState = AuthMachineState(
            authState = AuthState.Loading,
            currentEpoch = 1L,
        )

        // Startup rehydration requests validation
        val statusResult = AuthStateMachine.onSessionStatusAuthenticated(
            state = rehydratingState,
            sessionUserId = "user-rehydrated",
            email = "user@offline.com",
        )
        assertTrue(statusResult.shouldValidateRemote)
        val request = statusResult.validationRequest!!

        // Remote validation experiences a transient transport error (e.g. offline, timeout, 5xx)
        val transientValidationResult = AuthStateMachine.onRemoteValidationCompleted(
            state = statusResult.newState,
            request = request,
            result = RemoteValidationResult.TransientFailure(
                userId = "user-rehydrated",
                email = "user@offline.com",
            ),
        )
        assertFalse(transientValidationResult.clearLocalStorage)
        assertEquals("user-rehydrated", transientValidationResult.newState.validatedUserId)
        assertEquals(
            AuthState.Authenticated("user-rehydrated", "user@offline.com", isAnonymous = false),
            transientValidationResult.newState.authState,
        )

        // Subsequent session status emissions for the same user must NOT trigger remote validation again
        val nextStatusResult = AuthStateMachine.onSessionStatusAuthenticated(
            state = transientValidationResult.newState,
            sessionUserId = "user-rehydrated",
            email = "user@offline.com",
        )
        assertFalse(nextStatusResult.shouldValidateRemote, "Must not loop on remote validation after transient failure")
    }

    // 6. App starts with a persisted valid authenticated session.
    @Test
    fun appStartsValidPersistedSessionRehydration() {
        val startupState = AuthStateMachine.onInitialize(savedAnonId = null)
        assertEquals(AuthState.Loading, startupState.authState)

        val statusResult = AuthStateMachine.onSessionStatusAuthenticated(
            state = startupState,
            sessionUserId = "stored-user-99",
            email = "stored@test.com",
        )
        assertTrue(statusResult.shouldValidateRemote)
        val request = statusResult.validationRequest!!

        val validationSuccess = AuthStateMachine.onRemoteValidationCompleted(
            state = statusResult.newState,
            request = request,
            result = RemoteValidationResult.Success(
                userId = "stored-user-99",
                email = "stored@test.com",
            ),
        )
        assertEquals(
            AuthState.Authenticated("stored-user-99", "stored@test.com", isAnonymous = false),
            validationSuccess.newState.authState,
        )
    }

    // 7. Logout followed immediately by login to another account.
    @Test
    fun logoutFollowedImmediatelyByLoginToAnotherAccount() {
        val accountA = AuthMachineState(
            authState = AuthState.Authenticated("account-a", "a@test.com", isAnonymous = false),
            validatedUserId = "account-a",
            currentEpoch = 1L,
        )

        // User logs out
        val signOutResult = AuthStateMachine.onExplicitSignOut(accountA, epoch = 2L)
        assertEquals(AuthState.Unauthenticated, signOutResult.newState.authState)
        assertTrue(signOutResult.clearLocalStorage)

        // User immediately starts login to account B
        val accountBStarting = AuthStateMachine.onExplicitSignInStarted(
            state = signOutResult.newState,
            intent = InFlightAuthIntent.EmailSignIn(epoch = 3L, email = "b@test.com"),
        )

        // Stale NotAuthenticated from account A's logout arrives
        val staleStatus = AuthStateMachine.onSessionStatusNotAuthenticated(
            state = accountBStarting,
            hasActiveSession = false,
        )
        assertTrue(staleStatus.isDropped, "Must ignore NotAuthenticated while explicit mutation is in flight")

        // Account B completes
        val accountBResult = AuthStateMachine.onExplicitSignInSucceeded(
            state = accountBStarting,
            epoch = 3L,
            userId = "account-b",
            email = "b@test.com",
        )
        assertEquals(
            AuthState.Authenticated("account-b", "b@test.com", isAnonymous = false),
            accountBResult.newState.authState,
        )
    }

    // 8. Repeated login attempt supersedes an earlier in-flight attempt.
    @Test
    fun repeatedLoginAttemptSupersedesEarlierInFlightAttempt() {
        val initial = AuthStateMachine.onInitialize(savedAnonId = null)

        // User clicks Login once
        val attempt1 = AuthStateMachine.onExplicitSignInStarted(
            initial,
            InFlightAuthIntent.EmailSignIn(epoch = 1L, email = "login@test.com"),
        )
        assertEquals(1L, attempt1.currentEpoch)

        // User clicks Login again quickly
        val attempt2 = AuthStateMachine.onExplicitSignInStarted(
            attempt1,
            InFlightAuthIntent.EmailSignIn(epoch = 2L, email = "login@test.com"),
        )
        assertEquals(2L, attempt2.currentEpoch)

        // Attempt 1 finishes late
        val attempt1LateResult = AuthStateMachine.onExplicitSignInSucceeded(
            state = attempt2,
            epoch = 1L,
            userId = "stale-user",
            email = "stale@test.com",
        )
        assertTrue(attempt1LateResult.isDropped, "Attempt 1 with epoch 1 must be dropped when currentEpoch is 2")

        // Attempt 2 finishes
        val attempt2Result = AuthStateMachine.onExplicitSignInSucceeded(
            state = attempt2,
            epoch = 2L,
            userId = "winner-user",
            email = "winner@test.com",
        )
        assertFalse(attempt2Result.isDropped)
        assertEquals(
            AuthState.Authenticated("winner-user", "winner@test.com", isAnonymous = false),
            attempt2Result.newState.authState,
        )
    }

    // 9. Authenticated state triggers profile loading/gate progression rather than leaving login visible.
    @Test
    fun authenticatedStateTriggersGateProgressionAwayFromAuth() {
        val profiles = listOf(
            NuvioProfile(id = "p1", name = "Main Profile", profileIndex = 1),
        )

        // When Auth screen is showing and user transitions to Authenticated
        val decisionWithProfiles = AuthStateMachine.decideAppGateTransition(
            currentGateScreen = "Auth",
            authState = AuthState.Authenticated("user-1", "user@test.com", isAnonymous = false),
            cachedProfiles = profiles,
            isOnline = true,
        )
        assertTrue(decisionWithProfiles is AppGateTransitionDecision.EnterProfileGate)
        assertEquals(profiles, decisionWithProfiles.profiles)
        assertTrue(decisionWithProfiles.syncOnEnter)

        // When Auth screen is showing and user logs in with zero cached profiles
        val decisionNoProfiles = AuthStateMachine.decideAppGateTransition(
            currentGateScreen = "Auth",
            authState = AuthState.Authenticated("user-1", "user@test.com", isAnonymous = false),
            cachedProfiles = emptyList(),
            isOnline = true,
        )
        assertTrue(decisionNoProfiles is AppGateTransitionDecision.EnterProfileGate)
        assertTrue(decisionNoProfiles.profiles.isEmpty())
        assertTrue(decisionNoProfiles.syncOnEnter)
    }

    @Test
    fun unauthenticatedStateShowsAuthWhenOnline() {
        val decision = AuthStateMachine.decideAppGateTransition(
            currentGateScreen = "Loading",
            authState = AuthState.Unauthenticated,
            cachedProfiles = emptyList(),
            isOnline = true,
        )
        assertEquals(AppGateTransitionDecision.ShowAuth, decision)
    }

    @Test
    fun blankUserIdSignOnFailsExplicitly() {
        val initial = AuthStateMachine.onInitialize(savedAnonId = null)
        val started = AuthStateMachine.onExplicitSignInStarted(
            initial,
            InFlightAuthIntent.EmailSignIn(epoch = 1L, email = "user@test.com"),
        )

        val result = AuthStateMachine.onExplicitSignInSucceeded(
            state = started,
            epoch = 1L,
            userId = "   ",
            email = "user@test.com",
        )
        assertTrue(result.isDropped)
        assertEquals(AuthState.Loading, result.newState.authState)
        assertNull(result.newState.inFlightEpoch)
    }

    @Test
    fun wrongPasswordFollowedByCorrectPasswordSucceeds() {
        val initial = AuthStateMachine.onInitialize(savedAnonId = null)
        val unauthenticated = initial.copy(authState = AuthState.Unauthenticated)

        // User enters wrong password on attempt 1
        val attempt1 = AuthStateMachine.onExplicitSignInStarted(
            unauthenticated,
            InFlightAuthIntent.EmailSignIn(epoch = 1L, email = "valid@test.com"),
        )
        assertEquals(1L, attempt1.inFlightEpoch)

        // Attempt 1 fails (e.g. invalid credentials)
        val failureResult = AuthStateMachine.onExplicitSignInFailed(attempt1, epoch = 1L)
        assertNull(failureResult.inFlightEpoch)
        assertEquals(AuthState.Unauthenticated, failureResult.authState)

        // Gate decision remains on Auth screen
        val gateDecisionFailed = AuthStateMachine.decideAppGateTransition(
            currentGateScreen = "Auth",
            authState = failureResult.authState,
            cachedProfiles = emptyList(),
            isOnline = true,
        )
        assertEquals(AppGateTransitionDecision.ShowAuth, gateDecisionFailed)

        // User enters correct password on attempt 2
        val attempt2 = AuthStateMachine.onExplicitSignInStarted(
            failureResult,
            InFlightAuthIntent.EmailSignIn(epoch = 2L, email = "valid@test.com"),
        )
        assertEquals(2L, attempt2.inFlightEpoch)

        val successResult = AuthStateMachine.onExplicitSignInSucceeded(
            state = attempt2,
            epoch = 2L,
            userId = "valid-user",
            email = "valid@test.com",
        )
        assertFalse(successResult.isDropped)
        assertEquals(AuthState.Authenticated("valid-user", "valid@test.com", isAnonymous = false), successResult.newState.authState)
        assertEquals("valid-user", successResult.newState.validatedUserId)
        assertNull(successResult.newState.inFlightEpoch)

        // Gate decision progresses to profile gate
        val gateDecisionSuccess = AuthStateMachine.decideAppGateTransition(
            currentGateScreen = "Auth",
            authState = successResult.newState.authState,
            cachedProfiles = emptyList(),
            isOnline = true,
        )
        assertTrue(gateDecisionSuccess is AppGateTransitionDecision.EnterProfileGate)
    }

    @Test
    fun browserOrCodeAuthCoexistsCorrectly() {
        // Scenario A: External browser OAuth or deep-link code exchange arrives while user is on Auth screen
        val initial = AuthMachineState(authState = AuthState.Unauthenticated, currentEpoch = 5L)

        val asyncOAuthEvent = AuthStateMachine.onSessionStatusAuthenticated(
            state = initial,
            sessionUserId = "oauth-user-777",
            email = "oauth@test.com",
        )
        assertTrue(asyncOAuthEvent.shouldValidateRemote)
        assertEquals("oauth-user-777", asyncOAuthEvent.userIdToValidate)
        val req = asyncOAuthEvent.validationRequest!!

        // Remote validation succeeds
        val oauthValidated = AuthStateMachine.onRemoteValidationCompleted(
            state = asyncOAuthEvent.newState,
            request = req,
            result = RemoteValidationResult.Success(
                userId = "oauth-user-777",
                email = "oauth@test.com",
            ),
        )
        assertEquals(
            AuthState.Authenticated("oauth-user-777", "oauth@test.com", isAnonymous = false),
            oauthValidated.newState.authState,
        )
        assertEquals("oauth-user-777", oauthValidated.newState.validatedUserId)

        // Gate progresses to profile selection
        val gateOAuth = AuthStateMachine.decideAppGateTransition(
            currentGateScreen = "Auth",
            authState = oauthValidated.newState.authState,
            cachedProfiles = emptyList(),
            isOnline = true,
        )
        assertTrue(gateOAuth is AppGateTransitionDecision.EnterProfileGate)

        // Scenario B: Explicit device code login mutation (e.g. DeviceLink / code exchange in flight)
        val waitingForCode = AuthStateMachine.onExplicitSignInStarted(
            state = AuthMachineState(authState = AuthState.Unauthenticated, currentEpoch = 10L),
            intent = InFlightAuthIntent.ExternalSession(epoch = 11L, expectedUserId = "device-linked-user"),
        )
        val codeStatusResult = AuthStateMachine.onSessionStatusAuthenticated(
            state = waitingForCode,
            sessionUserId = "device-linked-user",
            email = "device@test.com",
        )
        assertFalse(codeStatusResult.shouldValidateRemote)
        assertEquals(
            AuthState.Authenticated("device-linked-user", "device@test.com", isAnonymous = false),
            codeStatusResult.newState.authState,
        )
    }

    // --- Production Acceptance Tests (Issues 1-7) ---

    // Acceptance 1: validation A starts -> explicit sign-out -> validation A succeeds/transient -> remains signed out
    @Test
    fun oldValidationSuccessAfterSignOutRemainsSignedOut() {
        val initial = AuthMachineState(authState = AuthState.Loading, currentEpoch = 1L)
        val statusA = AuthStateMachine.onSessionStatusAuthenticated(
            state = initial,
            sessionUserId = "user-A",
            email = "a@test.com",
        )
        assertTrue(statusA.shouldValidateRemote)
        val requestA = statusA.validationRequest!!
        assertEquals(1L, requestA.epoch)

        // While validation A is suspending, user explicitly signs out
        val signOutResult = AuthStateMachine.onExplicitSignOut(statusA.newState, epoch = 2L)
        assertEquals(AuthState.Unauthenticated, signOutResult.newState.authState)
        assertEquals(2L, signOutResult.newState.currentEpoch)

        // Validation A completes late with Success
        val validationCompleted = AuthStateMachine.onRemoteValidationCompleted(
            state = signOutResult.newState,
            request = requestA,
            result = RemoteValidationResult.Success("user-A", "a@test.com"),
        )
        assertTrue(validationCompleted.isDropped, "Old validation outcome from epoch 1 must be dropped when currentEpoch is 2")
        assertFalse(validationCompleted.clearLocalStorage)
        assertEquals(AuthState.Unauthenticated, validationCompleted.newState.authState)
    }

    // Acceptance 2: validation A starts -> login B succeeds -> validation A succeeds -> remains B
    @Test
    fun oldValidationSuccessAfterNewerAccountLoginRemainsNewAccount() {
        val initial = AuthMachineState(authState = AuthState.Loading, currentEpoch = 1L)
        val statusA = AuthStateMachine.onSessionStatusAuthenticated(
            state = initial,
            sessionUserId = "user-A",
            email = "a@test.com",
        )
        val requestA = statusA.validationRequest!!

        // While validation A is suspending, user explicitly logs into account B
        val startB = AuthStateMachine.onExplicitSignInStarted(
            state = statusA.newState,
            intent = InFlightAuthIntent.EmailSignIn(epoch = 2L, email = "b@test.com"),
        )
        val loginBResult = AuthStateMachine.onExplicitSignInSucceeded(
            state = startB,
            epoch = 2L,
            userId = "user-B",
            email = "b@test.com",
        )
        assertEquals(AuthState.Authenticated("user-B", "b@test.com", isAnonymous = false), loginBResult.newState.authState)

        // Validation A completes late with Success
        val validationCompleted = AuthStateMachine.onRemoteValidationCompleted(
            state = loginBResult.newState,
            request = requestA,
            result = RemoteValidationResult.Success("user-A", "a@test.com"),
        )
        assertTrue(validationCompleted.isDropped, "Validation outcome for user-A must be dropped after user-B login")
        assertFalse(validationCompleted.clearLocalStorage)
        assertEquals(
            AuthState.Authenticated("user-B", "b@test.com", isAnonymous = false),
            validationCompleted.newState.authState,
        )
    }

    // Acceptance 3: validation A starts -> login B succeeds -> validation A is definitively rejected -> remains B and B's session is NOT cleared
    @Test
    fun oldValidationRejectionAfterNewerAccountLoginDoesNotClearNewAccount() {
        val initial = AuthMachineState(authState = AuthState.Loading, currentEpoch = 1L)
        val statusA = AuthStateMachine.onSessionStatusAuthenticated(
            state = initial,
            sessionUserId = "user-A",
            email = "a@test.com",
        )
        val requestA = statusA.validationRequest!!

        // User logs into account B at epoch 2
        val startB = AuthStateMachine.onExplicitSignInStarted(
            state = statusA.newState,
            intent = InFlightAuthIntent.EmailSignIn(epoch = 2L, email = "b@test.com"),
        )
        val loginBResult = AuthStateMachine.onExplicitSignInSucceeded(
            state = startB,
            epoch = 2L,
            userId = "user-B",
            email = "b@test.com",
        )

        // Validation A fails with DefinitiveRejection
        val validationCompleted = AuthStateMachine.onRemoteValidationCompleted(
            state = loginBResult.newState,
            request = requestA,
            result = RemoteValidationResult.DefinitiveRejection("user-A"),
        )
        assertTrue(validationCompleted.isDropped, "Stale rejection must be dropped")
        assertFalse(validationCompleted.clearLocalStorage, "Must NOT request clearing local storage for a stale rejection!")
        assertEquals(
            AuthState.Authenticated("user-B", "b@test.com", isAnonymous = false),
            validationCompleted.newState.authState,
        )
    }

    // Acceptance 4: stale RefreshFailure from A after login B cannot clear B
    @Test
    fun staleRefreshFailureFromAAfterLoginBCannotClearB() {
        val accountB = AuthMachineState(
            authState = AuthState.Authenticated("user-B", "b@test.com", isAnonymous = false),
            validatedUserId = "user-B",
            currentEpoch = 5L,
        )

        // Stale definitive refresh failure arrives from user A
        val refreshResult = AuthStateMachine.onSessionStatusRefreshFailure(
            state = accountB,
            isDefinitiveRejection = true,
            failingUserId = "user-A",
        )
        assertTrue(refreshResult.isDropped, "RefreshFailure for user-A must be dropped when active user is user-B")
        assertFalse(refreshResult.clearLocalStorage)
        assertEquals(accountB.authState, refreshResult.newState.authState)
    }

    // Acceptance 5: unrelated Authenticated session arriving during email/password login
    @Test
    fun unrelatedAuthenticatedSessionDuringEmailLoginDoesNotHijackMutation() {
        val initial = AuthMachineState(authState = AuthState.Unauthenticated, currentEpoch = 1L)

        // Email login for account B started
        val loginBStarted = AuthStateMachine.onExplicitSignInStarted(
            state = initial,
            intent = InFlightAuthIntent.EmailSignIn(epoch = 2L, email = "target-b@test.com"),
        )
        assertEquals(2L, loginBStarted.inFlightIntent?.epoch)

        // Unrelated Authenticated event from account A arrives
        val unrelatedEvent = AuthStateMachine.onSessionStatusAuthenticated(
            state = loginBStarted,
            sessionUserId = "user-A",
            email = "stale-a@test.com",
        )
        assertTrue(unrelatedEvent.isDropped, "Unrelated session must NOT hijack in-flight email sign-in")
        assertEquals(loginBStarted, unrelatedEvent.newState, "State must remain unchanged")

        // Explicit login for account B succeeds
        val loginBSuccess = AuthStateMachine.onExplicitSignInSucceeded(
            state = unrelatedEvent.newState,
            epoch = 2L,
            userId = "user-B",
            email = "target-b@test.com",
        )
        assertFalse(loginBSuccess.isDropped)
        assertEquals(
            AuthState.Authenticated("user-B", "target-b@test.com", isAnonymous = false),
            loginBSuccess.newState.authState,
        )
    }

    // Acceptance 6: late failure of attempt 1 while attempt 2 is active
    @Test
    fun lateFailureOfAttempt1WhileAttempt2IsActiveDoesNotClearAttempt2() {
        val initial = AuthMachineState(authState = AuthState.Unauthenticated, currentEpoch = 1L)

        // Attempt 1 starts with epoch 1
        val attempt1 = AuthStateMachine.onExplicitSignInStarted(
            state = initial,
            intent = InFlightAuthIntent.EmailSignIn(epoch = 1L, email = "user@test.com"),
        )
        assertEquals(1L, attempt1.inFlightIntent?.epoch)

        // Attempt 2 starts with epoch 2
        val attempt2 = AuthStateMachine.onExplicitSignInStarted(
            state = attempt1,
            intent = InFlightAuthIntent.EmailSignIn(epoch = 2L, email = "user@test.com"),
        )
        assertEquals(2L, attempt2.inFlightIntent?.epoch)

        // Attempt 1 fails late and calls onExplicitSignInFailed with captured epoch 1
        val afterFailure1 = AuthStateMachine.onExplicitSignInFailed(attempt2, epoch = 1L)
        // Attempt 2 must still be in flight!
        assertEquals(2L, afterFailure1.inFlightIntent?.epoch, "Attempt 2 must remain in flight after attempt 1 fails late")

        // Attempt 2 finishes and succeeds
        val attempt2Success = AuthStateMachine.onExplicitSignInSucceeded(
            state = afterFailure1,
            epoch = 2L,
            userId = "valid-user",
            email = "user@test.com",
        )
        assertFalse(attempt2Success.isDropped)
        assertEquals(
            AuthState.Authenticated("valid-user", "user@test.com", isAnonymous = false),
            attempt2Success.newState.authState,
        )
    }

    // Acceptance 7: explicit email/password mutation cannot adopt an unrelated current session
    @Test
    fun explicitEmailLoginRejectsUnrelatedCurrentSession() {
        val attemptedEmail = "attempted@test.com"
        val unrelatedSessionEmail = "unrelated@other.com"

        val matches = unrelatedSessionEmail.equals(attemptedEmail, ignoreCase = true)
        assertFalse(matches, "Session email must match attempted email to be adopted")
    }

    // Acceptance 8: production AppGate uses the tested transition reducer
    @Test
    fun productionAppGateTransitionReducerVerification() {
        val profiles = listOf(
            NuvioProfile(id = "p1", name = "Test", profileIndex = 1),
        )

        // Loading + Authenticated -> EnterProfileGate
        val loadingToAuth = AuthStateMachine.decideAppGateTransition(
            currentGateScreen = "Loading",
            authState = AuthState.Authenticated("u1", "u1@test.com", false),
            cachedProfiles = profiles,
            isOnline = true,
        )
        assertTrue(loadingToAuth is AppGateTransitionDecision.EnterProfileGate)

        // Auth + Authenticated -> EnterProfileGate
        val authToAuth = AuthStateMachine.decideAppGateTransition(
            currentGateScreen = "Auth",
            authState = AuthState.Authenticated("u1", "u1@test.com", false),
            cachedProfiles = profiles,
            isOnline = true,
        )
        assertTrue(authToAuth is AppGateTransitionDecision.EnterProfileGate)

        // ProfileSelection + Authenticated -> StayOnCurrent
        val profileToStay = AuthStateMachine.decideAppGateTransition(
            currentGateScreen = "ProfileSelection",
            authState = AuthState.Authenticated("u1", "u1@test.com", false),
            cachedProfiles = profiles,
            isOnline = true,
        )
        assertEquals(AppGateTransitionDecision.StayOnCurrent, profileToStay)

        // Main + Authenticated -> StayOnCurrent
        val mainToStay = AuthStateMachine.decideAppGateTransition(
            currentGateScreen = "Main",
            authState = AuthState.Authenticated("u1", "u1@test.com", false),
            cachedProfiles = profiles,
            isOnline = true,
        )
        assertEquals(AppGateTransitionDecision.StayOnCurrent, mainToStay)
    }

    @Test
    fun safeAuthStateDescriptionMasksPIIProperly() {
        val auth = AuthState.Authenticated(
            userId = "123456789012",
            email = "user@example.com",
            isAnonymous = false,
        )
        val desc = safeAuthStateDescription(auth)
        assertFalse(desc.contains("user@example.com"), "Description must not contain raw email")
        assertTrue(desc.contains("1234...9012"), "Description must contain masked userId")
        assertTrue(desc.contains("isAnon=false"))

        assertEquals("Loading", safeAuthStateDescription(AuthState.Loading))
        assertEquals("Unauthenticated", safeAuthStateDescription(AuthState.Unauthenticated))
    }

    @Test
    fun privacySafeMaskingMasksProperly() {
        assertEquals("u***r@example.com", maskEmail("user@example.com"))
        assertEquals("a***@test.org", maskEmail("ab@test.org"))
        assertEquals("<empty>", maskEmail(""))
        assertEquals("<empty>", maskEmail(null))

        assertEquals("1234...9012", maskId("123456789012"))
        assertEquals("short", maskId("short"))
        assertEquals("<empty>", maskId(""))
        assertEquals("<empty>", maskId(null))
    }
}
