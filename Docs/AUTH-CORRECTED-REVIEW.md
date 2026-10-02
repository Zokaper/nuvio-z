# Corrected Gemini auth review — 2026-10-01

Current verdict: **corrected production implementation passes review**. Exact unmodified
Gemini tips failed review; the findings below are historical red evidence. They were integrated
through normal history with Codex-owned corrections. The final product-head and fresh Debug 75/81 test matrices pass. Coordinated release
build-only IPA and regular debug APK/IPA/MSI/DMG all pass; no stable publication. Physical RC
acceptance remains open. See RC-DEVICE-QA.md for the frozen sources and recent missed checks.

Reviewed exact heads:

- Mobile `ce987581e0a8cced00af82f6d7b7ea67dcd21555`.
- Desktop `16577dba5bf8941d2986e1e98e9692948cd99ede`.
- RC inputs: mobile `92adec601323e4f201f06da65c16fb75d146a52f`, desktop
  `c18989fe67fdf318b760766130526e6df684ba3b`.
- Merge bases: mobile `e77954662`, desktop `9085e881b`. The new branches retain
  current WT production code; no performance/native/updater/version/backend code additions.
  They precede the latest audit documentation; a future merge must retain current audit
  corrections, feature ledger, agent pointers and main's newer SideStore feeds.
- AuthRepository and AuthStateMachine blobs are identical between both supplied heads.
  All shared findings below apply to both repositories.

## Blocking production findings

1. **Publication still escapes the authority lock** (AuthRepository lines 103–126,
   264–277, 336–346). A validation can update the machine, release the lock, then
   lose authority to sign-out or login B, yet still clear anonymous storage and publish
   its captured A state. Reproduced: suspend A's second anonymous-storage clearing call
   after validation reconciliation; finish explicit sign-out; release A. Public state
   becomes authenticated A while the machine remains unauthenticated. The reducer's
   epoch check therefore does not prove checklist items 1–3 in production.
   Rejected outcomes likewise release the lock before destructive clearing, whose helper
   allocates a fresh epoch rather than preserving/checking the rejected request's authority.

2. **Late sign-out wipes the newer account** (lines 364–389; also server-switch and
   invalidation cleanup). Reproduced: start sign-out A, suspend its SDK sign-out, finish
   email login B, release sign-out. B's local data is wiped; public state becomes
   unauthenticated while the machine remains B. Remote sign-out/fallback clear also needs
   SDK operation ownership. Epoch allocation outside the lock leaves ordering gaps at
   operation start, too. Serialize the actual effects and publication with authority;
   a pure-machine lock alone is insufficient.

3. **RefreshFailure is assigned the current identity, not the failed identity**
   (lines 170–190). Production reads `currentSessionOrNull()?.user?.id` after an awaited
   rejection/refresh check and passes that as `failingUserId`. The test supplies A
   manually; the real handler supplies B or null for a delayed A event. Reproduced:
   login B, deliver a delayed definitive failure cause from A, make the current refresh
   return a definitive 400; B is cleared/wiped. The SDK's failure status has no originating
   session field. With an inconclusive refresh this is retained, but the stale failure
   still reaches current-session refresh before ownership is established. Checklist 4 fails.

4. **Remote validation is not side-effect free** (lines 205–215 plus
   OfficialSessionRejection lines 40–58). `retrieveUserForCurrentSession(false)` avoids
   its own user update, but `isSessionGone` calls `refreshCurrentSession`, which imports
   and saves a session in SDK 3.4.1. Reproduced: suspend A validation; login B; return
   A's 401. B's SDK refresh runs before A's eventual outcome is dropped. Validation
   also reads the live SDK token and ignores the retrieved user's identity rather than
   validating an immutable request session. Checklist 8 fails.

5. **Late login failure still publishes a stale UI error** (lines 324–331, 349–358;
   sign-up lines 285–296). Captured `opId` correctly protects attempt 2's in-flight
   intent. Error assignment is unguarded. Reproduced: start attempts 1 and 2; fail 1
   late while 2 is pending; succeed 2. Its guard survives, but the stale error remains
   even after success. Resolve/authorize localized errors before publishing them.

6. **Diagnostic exceptions remain unsanitized** (lines 170, 287, 353 and rejection helper
   line 45). `$status.cause`, `$e.message` and throwable logging can contain raw email,
   passwords, tokens or full IDs; masking the surrounding label does not sanitize these.
   Reproduced with synthetic exception contents: raw fixture email and token reach the
   logger. No real credentials were used. AppGate's formatted auth state is now masked,
   but checklist 10 is not satisfied across auth diagnostics. Profile success additionally
   logs the active profile's name (ProfileRepository line 150), the prior review's
   profile-identity logging concern.

7. **AppGate can select the prior identity's cached profile** (AppGate lines 561–592).
   The reducer now runs in production, satisfying checklist 9's wiring requirement.
   However, its decision captures `profileState.profiles` before
   `ProfileRepository.ensureLoaded(currentAuth.userId)`. The authenticated branch
   prefers that captured nonempty list over the newly loaded/cleared repository state.
   On anonymous→email/account change, ensureLoaded correctly clears a foreign cache,
   but enterProfileGate can still receive the previous identity's single or remembered
   profile and requestProfileSwitch it. The old code used the current repository list
   after ensureLoaded. This is a source-traced integration regression, not a rendered
   or physical AppGate test pass. Use the identity-loaded list and cover real gate effects.

## Acceptance checklist

| Item | Review result |
| --- | --- |
| 1. Old validation cannot restore auth after sign-out | FAIL: production publication interleaving reproduced |
| 2. Old validation cannot overwrite newer login | NOT satisfied: same publication gap applies after B |
| 3. Old definitive rejection cannot clear newer session | NOT satisfied: destructive cleanup outside guarded transition |
| 4. Stale RefreshFailure cannot invalidate newer account | FAIL: production handler reproduction |
| 5. Unrelated Authenticated cannot hijack credential login | Different-email event is dropped; same-email old session has no attempt provenance |
| 6. Credential login cannot adopt unrelated pre-existing session | Different-email result is rejected; live session read is not operation provenance |
| 7. Attempt 1 failure cannot clear attempt 2 operation | Captured epoch fix passes; stale error publication remains |
| 8. Validation side-effect free, clearing only under authority | FAIL: refresh/import before authority reconciliation and cleanup gap |
| 9. Production AppGate consumes tested reducer | YES by source inspection; profile-list behavior regresses |
| 10. Auth/AppGate logs have no raw identity/credential content | FAIL: raw exceptions/status causes remain |

## Email ownership semantics

Case-insensitive comparison of the **current primary email** is appropriate for normal
Supabase email/password accounts: the server stores lowercase email and its password
account lookup matches `LOWER(email)` with lowercased input. Trimming user input and
case differences should not reject legitimate normal email/password logins. Do not add
Gmail-style dot or plus alias normalization: distinct registered addresses are not aliases
for this purpose. Browser/code paths have separate status reconciliation.

This comparison identifies an account, not a particular credential request/session.
A same-email pre-existing session may emit Authenticated while a wrong-password attempt
is pending and is adopted before the mutation succeeds. The result path also reads global
currentSession/currentUser rather than a captured response. Session mutation ordering and
attempt provenance still need a production test. A legitimate account changing its primary
email concurrently requires an explicit ownership policy; it is not established by these
tests. No claims about custom upstream server alias behavior were verified live.

Primary sources:

- https://supabase.com/docs/reference/kotlin/auth-signinwithpassword
- https://github.com/supabase/auth/blob/master/internal/models/user.go
  (`FindUserByEmailAndAudience`, lowercase storage).
- https://github.com/supabase-community/supabase-kt/blob/3.4.1/Auth/src/commonMain/kotlin/io/github/jan/supabase/auth/AuthImpl.kt
  (sign-in import, retrieveUserForCurrentSession, refresh and failure status).

## Verification and publication state

Standalone JUnit runs the exact head's AuthStateMachineTest: **23/23 passed**.
Deterministic coroutine harness compiles the exact detached-worktree AuthRepository,
AuthStateMachine, AuthModels and OfficialSessionRejection without modifying them.
Six adverse assertions reproduced the findings above. Coroutines and atomicfu are real;
Supabase client/status/errors, storage/cleaner, profile model, resource strings and logger
are controlled neighbours. This is production-source orchestration evidence with stubbed
boundaries, **not** a real SDK/server/device or full Gradle pass. Harness and results:
`../.rc-investigation/auth-corrected-audit/{run.ps1,Audit.kt,results.txt,reducer-tests.txt}`.
The first harness attempts failed command/classpath setup; corrected final compilation
and both executions passed. No authored production fixes or branch rewrites.

The conditional merge gate failed. Consequently there are **no merge SHAs, merged-head
matrix results, new debug tags, artifact checksums or source-SHA publication claims**.
Full mobile host/regressions/debug/release, four desktop partitions/updater/WT and Windows
package, iOS CI/Xcode, coordinated release build-only and macOS both architectures remain
required on a future approved merge. Do not substitute the 23 pure tests for that matrix.
Current WT RC product code, main/Dev product code and feeds remain untouched; performance
is excluded. Existing mobile 74 / desktop 80 remain the published debug pair.

## Linux native fixture — separate result

Read GitHub run `36829662346` on **older** desktop auth SHA `02524bb2e`, not the supplied
corrected head. Windows MSI passed; Linux failed `:composeMediaPlayer:buildNativeLinux`
at `frame_copy_test.c:36: player != NULL` before Kotlin tests. Compilation/linking found
GStreamer 1.24.2 and succeeded. nvp_create returns null if allocation, `playbin` or
`appsink` construction fails. CI installs dev packages with `--no-install-recommends`
and does not explicitly install runtime plugin packages. Missing runtime factories are
the leading hypothesis, not proven on that runner. Next diagnostic: install/query
`gst-inspect-1.0 playbin` and `gst-inspect-1.0 appsink`, inspect plugin search/registry,
then rerun the unskipped fixture. No fixture skip or native source change was made.
This result proves neither product success nor an auth-induced native regression.
https://github.com/Zokaper/NuvioZDesktop/actions/runs/36829662346

## Open physical blockers

- iPhone WT: Home→Away pauses host; return waits for fresh guest readiness and resumes
  smoothly; lock→Away pauses host; unlock/return recovers smoothly; quiet foreground
  period has no spontaneous reconnect. All five remain acceptance requirements.
- Auth on desktop+iOS and Android where practical: wrong→correct password,
  logout→email login, authenticated restart, anonymous→email login, browser/code login.
- RC is **not accepted**. iPhone physical QA remains mandatory after a corrected merge
  and verified regular debug pair. No stable publication is authorized.

## Codex production correction pass (2026-10-01, merged-head validation pending)

The unmodified Gemini tips remain rejected. The RC integrates their reducer and AppGate
wiring with additional fixes owned by Codex, without modifying Gemini's branches.

- Actual production `AuthSessionCoordinator` is injected only at the SDK/storage boundary
  for coroutine race tests. Public state, reducer and storage publication share one lock.
- SDK login/import/sign-out/clear/delete mutations and account cleanup share one mutex.
  Explicit authority is allocated before suspension; old errors and completions cannot
  clear a newer operation. Delayed sign-out cleanup completes before the newer SDK login.
- Validation sends the captured access token to retrieveUser and performs no SDK mutation.
  Refresh confirmation uses the captured refresh token and imports/clears only with current
  authority. The unused refresh-current-session rejection helper has been removed.
- SDK RefreshFailure has no originating identity: its cause is never relabelled as B's
  rejection. It instead triggers independent validation of the accepted captured session.
- In-flight SDK Authenticated events never complete an explicit operation. Credential login
  captures Email provider's own response before import rather than reading currentSession.
  Case-insensitive trimmed email is an additional identity check, not request provenance.
- AppGate calls the same tested transition reducer and uses the reloaded current profile
  list. Profile pull drops a response after an auth/cache identity change.
- Auth/AppGate diagnostics use fixed categories or masked identity, never raw exceptions,
  email, full ID, password or token. Short IDs are entirely masked.

Supabase password lookup compares LOWER(email); pending email changes are not login aliases.
The check preserves primary-email accounts, case differences and surrounding input whitespace;
OAuth/device-code flows do not use the email check. References:
[Supabase auth user lookup](https://github.com/supabase/auth/blob/master/internal/models/user.go),
[pinned SDK 3.4.1 signInWith implementation](https://github.com/supabase-community/supabase-kt/blob/3.4.1/Auth/src/commonMain/kotlin/io/github/jan/supabase/auth/AuthImpl.kt).

These are production-boundary tests with controlled transport/storage, not physical SDK/server
or iPhone acceptance. Full matrix and physical blockers from the audit still apply. No stable.
Further correction: OfficialSessionAccess's Social token-consumer refresh must also use
coordinator authority. Its old refreshCurrentSession call could import A after a newer login.
The replacement captures the consumer session, serializes refresh/import, checks epoch and
current SDK identity before/after suspension, and returns no token on supersession/failure.
It never clears an account from the token consumer's failure. Two production-boundary tests
cover old refresh completion behind B and current captured refresh/failure retention.

First integrated desktop full-suite run also exposed a test-only simulated-process-death
race: reset cancelled discovery jobs but resumed before they stopped. The cancelled jobs
could publish empty candidates into the restarted simulation. Reset now returns cancelled
jobs; restart fixtures await them before resuming, matching actual process death. Assertions
and production download behavior are unchanged. Promo rendering reached the existing task's
20-minute cap; retain that failure, then rerun every rest test with an external 90-minute
allowance, without exclusions. New corrections invalidate affected prior-head validation.
## SDK boundary correction discovered during final validation

Pinned Supabase 3.4.1 AuthImpl.checkErrorCodes schedules clearSession when an HTTP error
contains session_not_found. Consequently retrieveUser(capturedToken) and refreshSession
are not guaranteed side-effect free through the Auth plugin error parser. SDK automatic
refresh also imports/clears independently of coordinator authority. The current validation
heads are provisional; do not publish debug builds until those SDK boundaries are corrected
and the affected merged-head matrix reruns. No performance work or publication.

Correction in progress: StatelessAuthRequests sends raw requests through the existing
SupabaseHttpClient (same transport, rate-limit/fallback/retry configuration), with captured
Authorization tokens and Email.encodeCredentials. Its error contains only HTTP status and
an invalid-session category; no SDK Auth parseErrorResponse runs and no server body is
retained in diagnostics. Successful login/refresh sessions remain unimported until coordinator
authority is checked. Email-confirmation signup decodes a user response without adopting
the SDK session. Current supported email/password flow is IMPLICIT; no PKCE/custom redirect
is configured. Browser/code behavior is retained; code fallback lookup now bypasses Auth's
error parser as well. SDK alwaysAutoRefresh and Android enableLifecycleCallbacks are false;
captured near-expiry renewal belongs to the coordinator (30s check, 60s ahead). Storage
restoration remains supported through a coordinator-owned read/import, with SDK auto-loading
disabled so a delayed disk read cannot import A over B. The SDK's initial empty status is
ignored until that guarded read settles; empty/unreadable storage settles without deleting
stored credentials, and offline validation retains the restored session. The SDK
status echo after a same-account import clears obsolete validation ownership, allowing
subsequent timer refreshes. New real-SDK tests reproduce parser clearing an unrelated B,
then prove raw validation/refresh/credential/logout requests cannot trigger that parser.
New coordinator tests cover timer expiry, status echo, and stale success/rejection behind B.

Historical e68cff3 mobile validation: 3432 host cases, 0 failures/errors, six distribution
policy skips; auth 63, WT/player 764 (same six skips), profile 185, updater 53. All six service
cases pass under full distribution. Android debug and unsigned release/R8 pass. Historical
e8089d7: auth/fixture 88 pass; playback 1062 and downloads 457 pass; rest still times out at
20m (initial external override ran before the build's timeout assignment); e2e 49 has one
missing-file assertion in the quiet-source case. Windows/macOS arm64/x86_64 build-only
family and independently downloaded installer checksum comparison pass. iOS explicit
device+simulator/Xcode build-only succeeds, coordinated IPA remains pending when recorded.
All results are retained as historical proof, not approval of the new SDK correction.

The quiet-source e2e failure reproduced in one of three unchanged two-case probes. The
second failure was FileNotFoundException after resolving/existence-checking the flat file,
while completion's organizer moved it. The fixture read a captured URI outside the store
critical section; completion publishes before organizeLocked completes its rename under
that same lock. The test helper now acquires that lock, reads the current item and verifies
completion, existence, exact size and exact bytes without a concurrent rename. Production
downloads are unchanged. Five consecutive two-case probes passed with this coherent read;
the complete final-head e2e partition remains required.

Final callback reproduction: during coordinator-owned definitive rejection, SDK clearSession
emits NotAuthenticated while the public state is still A and cleanup is settling. That status
could start another captured validation, publish A again and supersede the rejection before
its final authority check. A new test reproduced Authenticated public state with null SDK
session against the unchanged implementation (one failing case, red evidence retained).
RefreshFailure can enter the same competing-validation path. Both handlers now defer while
an existing validation/confirmation owns the decision. The regression covers both callbacks,
requiring Unauthenticated, null SDK session, and exactly one clear/wipe. This correction is
shared by normal merges and requires another frozen merged-head matrix. Prior d21029e39
mobile full host passed 3439, focused groups passed, Android debug passed; pending local
runs were explicitly canceled at only their verified RC launcher trees. Shared daemons,
Hot Reload and other worktrees were left untouched. No debug release has been cut.

## Frozen corrected-head matrix — 2026-10-01

Product heads: mobile `439835c8ec4913e654cceafdfa6aaa56e2c679fb`, desktop
`dea69997826a2a57b805b65cb85ed7a18f623aec`, both on the existing RC branch.
Initial normal Gemini integration merges: mobile `1153105c6207dc67c5fe65e3fcd0d798541e336b`,
desktop `9780ded1a393c09194960769668a0fcd3c8d06e2`. Later SDK/clear guards also travel
through normal helper-history merges; no performance branch or whole divergent-file copying.

| Final-head check | Result |
| --- | --- |
| Mobile full host | 3440, zero failures/errors; six distribution-policy skips |
| Mobile focused auth / WT-player / profile-setup / updater | 71 / 764 / 185 / 53, zero failures/errors; WT same six skips |
| Mobile full-distribution service coverage | All six previously policy-skipped cases pass |
| Android debug / unsigned release-R8 | Both pass |
| iOS Swift lifecycle, device+sim frameworks, unsigned Xcode | [36897067682](https://github.com/Zokaper/nuvio-z/actions/runs/36897067682), success |
| Coordinated build-only mobile family | [36897072101](https://github.com/Zokaper/nuvio-z/actions/runs/36897072101), Android success; unsigned release IPA running; TestFlight skipped |
| Desktop complete rest / playback / downloads / e2e | 1814 / 1062 / 457 / 49 = 3382; zero failures/errors/skips/duplicates |
| Desktop focused auth / WT-player / profile-setup / updater | 76 / 709 / 233 / 75, zero failures/errors/skips |
| Local Windows compile / MSI | Both pass |
| Windows + both macOS architecture build-only packages | [36897075909](https://github.com/Zokaper/NuvioZDesktop/actions/runs/36897075909), all pass; publication skipped |

First final-head rest attempt had one PluginRuntimeDesktopTest 60s concurrent-scraper timeout.
The code, pinned dependency and test are unchanged vs the pre-integration RC. Four consecutive
unchanged focused probes and the entire rest partition rerun passed. No assertion, scraper
deadline, production code or filtering change. Rest's external overall task allowance is
90 minutes, applied after project configuration; all cases retained. Red evidence remains in
`../.rc-investigation/auth-final-guard-desktop/results-rest`; green full rerun in
`rest-rerun-results`. Test-only download fixture corrections described above are distinct.

Linux final-source [36897043064](https://github.com/Zokaper/NuvioZDesktop/actions/runs/36897043064)
fails at native frame_copy_test.c:36, `player != NULL`, before Kotlin tests. Native C compilation
succeeds, then CTest fails. Native Linux and CI source is unchanged vs the old RC. The fixture
uses playbin/appsink; missing runtime plugin factories remain the leading unproven hypothesis.
No test skip or native workaround was introduced. This job is not Kotlin success, product
success, or established auth regression. Local Windows full partitions provide Kotlin proof.

All four downloaded Android APK hashes match SHA256SUMS-Android.txt. All three desktop
installers independently match SHA256SUMS-Windows-macOS.txt. Exact source/run/hash records
are retained under `../.rc-investigation/auth-final-guard-{mobile,desktop}/`. No regular debug
pair/counter/feed change yet. Physical iPhone WT and desktop+iOS auth blockers stay open.
