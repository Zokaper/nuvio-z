# RC convergence and release hardening — 2026-10-01

Release owner: Codex. Audit only; no stable publication, debug dispatch, auth integration,
performance merge, backend deployment or product behavior change in this pass.
**Physical iPhone lock/Away acceptance remains a release blocker.** This is a release audit,
not a new roadmap phase. The maintainer's supplied QA state is the starting evidence.

## Frozen source snapshot

Both repositories use `claude/ios-watch-together-hardening`. Origin refs were refreshed.
The following heads are the audit inputs; later audit commits change documentation only.

| Repository | Audited RC head | Published build source |
| --- | --- | --- |
| Mobile | `e77954662e8d9cefffcc38ecd5da0d2c45cecead` | Debug 74: `d2bb3f542afaa4d5d5b0654ba45a8a463f9e8351` |
| Desktop | `9085e881b936be7dd2c5bd15fd2eccf7937fc975` | Debug 80: `5b75a12e72fdb5e79e6c6e818e85486dc5c00cad` |

Diff from each build source to its audited head is documentation only. Both working trees
were clean. GitHub release targets match these build sources; both releases are non-draft
prereleases. Mobile run `36822400793` and desktop run `36822781351` succeeded. Asset digests
still match the four-package verification record in
`../.rc-investigation/wt-ios/away-release-verification.json`; this pass re-read GitHub metadata,
not the entire installers. Prior package downloads/checksums remain the artifact evidence.

Mobile `origin/main` is `6ee2a89dd3c878a3888be85cb08bc6a55dffb429`; desktop `origin/Dev` is
`ca11c0cb7a7969f373aee21bb0f31fde34141e78`. RC product work is **not promoted to either trunk**.
Main's canonical debug feed has build 74, size 92,887,668 and IPA SHA-256
`0de5c5bc1876f766c6f7b543966f26c106a941b4a39eb532f48c4e2b28198827`.
The RC branch's feed differs from main: retain main's newer feed when merging; do not
overwrite it with the branch copy. Stable feed/publication has not advanced.

## What has converged

- Mobile reconnect recovery includes `ac121af02`, `8aedfd9f5` and `8658c6576`:
  configured Ktor sockets, SDK-owned reconnect/rejoin, retained authority/peer collectors,
  callback restoration, presence re-track, connected-socket recovery deadline (30 s),
  outstanding-heartbeat resend requiring an actual reply, and lifecycle revision guards.
  Desktop keeps its platform transport; mobile-only SDK recovery is deliberately not copied.
- Durable Away includes mobile `7846909dc` and desktop merge `371e71dcf` via `c3822e4b0`.
  Active ten-minute leases include nonterminal disconnected/unready guests; left/failed and
  expired leases are excluded. Durable return invalidates older peer Away reports.
- Return/hold coordination includes mobile `211ed26a3` and desktop merge `889209050` via
  `5a0384cdc`. `PartyAwayRecovery`, `WatchPartyBarrier` and `PlayerWatchPartyEffect` are
  CR-normalized identical. Skipped hold edges retry; one hold cannot resume through another.
  Return withdraws stale readiness across catch-up/channel cleanup, requires fresh engine
  readiness, and uses the existing 400 ms settle and 12 s ceiling. The ceiling remains a
  bounded fallback; “only resumes when ready” is not an unconditional guarantee.
- Source-descriptor bounds/RPC failure presentation, Classic source-watchdog ownership,
  setup upload/import retry and Downloads navigation are in the RC. Mobile
  `codex/final-mobile-qa-cleanup` has patch-equivalent commits in HEAD (`git cherry`).
- Social session recovery/V2 rejection guard/V3 install-scoped desktop storage are in both
  RCs. Their original branches are not ancestors, but all production patches are equivalent
  (`git cherry`), and the current official-session seam files were checked. Do not remerge
  those branches merely because their commit IDs are absent from ancestry.
- The upstream sync/UX convergence/setup architecture branches are ancestors of the RCs.
  Their older “unmerged” handoffs refer to trunk promotion, not missing RC implementation.

## Backend contract and deployment evidence

Backend head is `2ce5405`; migration code is `9d6376c`,
`202609300001_broadcast_party_away.sql`. It adds `away_since` to the member-update trigger
predicate, so entry, return and lease expiry invalidate authority observers; unchanged
heartbeat stamps do not. The ten-minute lease/RLS/source contract is not weakened.
The earlier deployment record confirms backup, one-migration dry-run, applied history and
read-only trigger verification on **Z project `pzbpghmmordvzcfbayoh` only**, with 347/347
local pgTAP assertions. This pass inspected tracked migration/handoff evidence; it did not
make a fresh live database query. Reconfirm linked project/history/trigger read-only before
promotion if deployment state changes. No migration or Edge Function deployment is needed
for the current client changes. Never deploy to upstream `api.nuvio.tv`.

## Convergence gaps and documentation corrections

1. Gemini auth remains branch-only; see review below. Performance remains excluded.
2. Mobile `claude/ios-setup-1.1.2` carries the separately released setup helper; RC's
   `iosSetup` still says 1.0.3. Do not call the helper's newer code part of Debug 74.
   The mobile release attaches the tracked SideStore shell/PowerShell setup scripts,
   not a newly compiled `iosSetup` helper. Keep the helper separate unless explicitly scoped.
3. Desktop `claude/phase-6-pointer-seam` refactor is branch-only; its new desktop actual
   is absent from RC. Existing pointer behavior is implemented inline. Do not import a
   refactor into this RC just to tidy it.
4. iOS Phase 9 experimental branches 10a/10b retain alternative orchestration commits;
   their opt-in fixes are patch-equivalent, their experiments are not RC requirements.
   Archived convergence/old debug branches are not merge candidates.
5. S10's “no readiness downgrade” and “Away writes no member row” claims were stale:
   the current code deliberately withdraws return readiness and persists `away_since`.
   Corrected in the feature ledger, while iOS remains `defer`. C16's exclusive-compiler
   claim was also corrected: debug/stable IPA workflows compile iOS too.
6. Agent base pointers still named 0.4.13/0.1.23-alpha despite the integrated sync;
   corrected to the actual named source bases. Debug names intentionally retain the old
   release-line metadata until the final stable bump.

## Provisional Gemini auth review — NOT approved to integrate

Observed origin tips (not a final rebased handoff): mobile
`e81280ffb55d0c542e24f5f67fc7a1722c14e741`, desktop
`02524bb2e9ca15a7a635597417de1df901a76207`; code commits `b45e5a75e` / `07328065f`.
Their bases are `49c6a562e` / `88a3b451f`; they miss the latest docs-only RC commits,
but contain all current WT code. The diff is limited to AuthRepository, new AuthStateMachine
and tests, AppGate logging, ProfileRepository logging and STATUS. No WT, native player,
updater, version, backend or performance files change. AuthRepository/machine/tests match
between repositories; AppGate/ProfileRepository retain their per-repository differences.
Mobile auth CI `36829637030` passes; desktop `36829662346` fails at the same native Linux
fixture before Kotlin tests. No iOS auth CI result is recorded; commonMain auth paths do
not trigger `ios-build.yml` automatically.

During the audit, local Gemini refs rebased to mobile `fd8cd7cc8` (code `f7e7561ec`)
and desktop `7744155da` (code `3c8297af4`), based on the audited RC heads above.
Comparison with the recorded origin auth tips changes documentation only: all auth
production/test content is unchanged, so the following findings still apply. These refs
are not a final tested handoff or integration approval; Gemini may continue revising them.

Review findings that must be resolved or superseded in the final handoff:

- **Stale validation is not epoch/identity guarded.** `onRemoteValidationCompleted` can
  reauthenticate an old account after explicit sign-out, or sign out a newer account on an
  old rejection. `validateRemoteSession` also mutates validated identity and can clear/wipe
  local state before stale-result reconciliation. Guard the real asynchronous effects,
  not just the pure result.
- **In-flight login adopts any authenticated status identity.**
  `onSessionStatusAuthenticated` treats any session received during a mutation as its result;
  an older account status can enter AppGate/profile loading before the requested login ends.
- **Failed operations use the latest epoch.** Email sign-in/sign-up `onFailure` reads
  `operationEpoch.value` instead of the failed operation's captured ID, so an old failure
  can clear a newer login's in-flight guard and publish its error.
- **Publication is outside the lock.** Transition computation/machine update is synchronized,
  but `_state` and storage writes happen later; overlapping explicit calls/status work can
  publish an older transition after a newer one. Sign-out also retains a pre-await transition
  and publishes/wipes after remote work. Add repository-level ordering/cancellation coverage.
- **Auth logging exposes full identity.** AppGate interpolates `$authState` (the data class
  includes user ID and email) despite separately computing a masked ID. Other auth logs do
  the same; profile success logging names the active profile. Redact the entire logged
  state; mask helpers alone do not make these lines private.
- `decideAppGateTransition` is tested but production AppGate does not use it; AppGate changes
  are logging only. Those pure tests do not verify actual gate/profile-loading interactions.

The first three state-machine sequences were reproduced with the actual branch machine and
RC AuthModels, stubbing only its NuvioProfile dependency. Evidence/source harness:
`../.rc-investigation/auth-provisional-audit/`. This is defect reproduction, not an integration
test pass. Gemini's branch remains untouched. Final rebased SHAs and complete test evidence
are requested; review the final diff again and merge only after these blockers are closed.

## Exact post-integration validation matrix

Freeze both final auth SHAs and their intended RC parents; verify clean worktrees and inspect
`git diff --ignore-space-at-eol <RC>...<auth>` and `git range-diff` against the reviewed auth
commits. Confirm no performance, WT overwrite, native, backend or release-counter additions.
Preserve per-repository AppGate/profile behavior. Merge auth independently into each RC; use
shared-history merges, never copy whole divergent files. Tests below run on the resulting
merge SHAs, not just Gemini's pre-merge tips. Never run two Gradle jobs in one checkout.

Windows environment: JDK 21 at
`C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot` (includes jpackage);
Android SDK `C:/Users/Rayoa/AppData/Local/Android/Sdk`. Set JAVA_HOME, PATH and ANDROID_HOME
per invocation. Preserve ignored local.properties; do not print secrets. In a separate
checkout, supply its ignored runtime properties before builds.

| Gate | Exact invocation / required evidence |
| --- | --- |
| Full mobile host | From mobile: `./gradlew.bat :composeApp:testAndroidHostTest --rerun --console=plain --max-workers=4`. Clear only that checkout's prior host-test XML first. Archive all XML and aggregate tests/failures/errors/skips. Baseline 3,395 total, zero failures/errors, six existing skips; new auth tests must be represented. |
| Full desktop | From desktop in Git Bash: `bash scripts/run-desktop-tests-split.sh ../.rc-investigation/auth-integrated-desktop`. Require rest/playback/downloads/e2e all rc=0, zero failures/errors, zero duplicate cases. Archive each part's XML and HEAD-bearing summary. Latest full baseline is 3,315 on Debug 78; 416 focused on Debug 80 is not a full-suite substitute. |
| Auth focused, both | Mobile `:composeApp:testAndroidHostTest`, desktop `:composeApp:desktopTest`, with `--tests 'com.nuvio.app.core.auth.*' --tests 'com.nuvio.app.core.network.OfficialSessionAccessTest' --tests 'com.nuvio.app.core.network.ZSession*Test' --tests 'com.nuvio.app.features.social.SocialPresenceSessionTest' --rerun`. Desktop additionally `--tests 'com.nuvio.app.core.network.InstallScopedSessionManagerTest'`. Include real repository async tests for all review findings above, not only pure machine tests. |
| WT focused, both | Same respective test tasks with `--tests 'com.nuvio.app.features.watchparty.*' --tests 'com.nuvio.app.features.player.*' --rerun`. Must include mobile WatchPartyTransportRecoveryTest real-SDK heartbeat/rejoin cases, PartyAwayRecoveryTest, lifecycle/route/authority/barrier/source tests and desktop WatchPartyAwayReturnTest. Confirm no skipped targeted cases. |
| Profile/AppGate/setup, both | Same tasks with `--tests 'com.nuvio.app.AppGateOverlayRulesTest' --tests 'com.nuvio.app.features.profiles.*' --tests 'com.nuvio.app.core.sync.CrossFamily*Test' --tests 'com.nuvio.app.features.setup.*' --rerun`. Add actual Auth → profile gate integration coverage: cached anonymous → email; startup cached/offline; account A → sign-out → B; stale profile pull; cancellation; profile PIN/selection; server switch; cross-family import; Social identity/departure boundaries. |
| Android compile + release | `./gradlew.bat :androidApp:assembleFullDebug --console=plain --max-workers=4`; then `:androidApp:assembleFullRelease -Pnuvio.android.unsignedRelease=true` for local release/R8 feasibility. CI build-only must verify real signing configuration separately; local unsigned success is not signed-release evidence. No counter bump/public debug release required. |
| Desktop compile/package | `./gradlew.bat :composeApp:compileKotlinDesktop :composeApp:packageReleaseMsi -Pcompose.desktop.packaging.checkJdkVendor=false --no-configuration-cache --console=plain --max-workers=4`; `desktop-release.yml` on integrated RC with `mode=build-only,target=windows-macos,macos_signing=unsigned`. Verify Windows x64 plus macOS arm64 AND x86_64 packages, runtime/launch checks and hashes; Debug 80 verifies arm64 only. |
| iOS compilation/lifecycle | Explicitly dispatch `ios-build.yml` on integrated mobile RC. Require Swift notification/executor regression job, device and simulator Kotlin frameworks and unsigned Xcode build. Windows cannot run Xcode. No Apple account required. |
| Mobile coordinated artifacts | `android-release.yml` on integrated RC, `mode=build-only`: Android ABI artifacts plus unsigned release IPA. Verify executable/widget, version/build, bundle IDs, runtime config, signing/unsigned policy and checksums. No publication/feed mutation. |
| Updater/version/feed | Both tasks with `--tests 'com.nuvio.app.features.updater.*' --tests 'com.nuvio.app.core.build.NuvioZVersionTest' --tests 'com.nuvio.app.features.whatsnew.*' --rerun`. Verify stable rejects prerelease/debug, debug accepts only debug prerelease, serial ordering, asset architecture, What's New and feed bundle/build/hash. |
| Auth device smoke | Android/iPhone/desktop: existing session cold start, anonymous → account, wrong password then success, offline/transient refresh then recovery, sign-out then another account, profile switch/PIN, cached profile restore and Social/Z exchange. Confirm gate exits Auth and credentials/profile data do not cross accounts. |
| Physical WT gate | Desktop host + Android/iPhone guests; five quiet foreground minutes; repeated Home/return and lock/unlock with Pause for Away ON/OFF; return during another guest's buffer hold; paused-host return; fresh catch-up readiness; no stale seek/resume/rejoin churn. Capture paired logs proving hold ownership and normal `all-ready` release. Unexpected `ceiling` release requires investigation, not a claimed readiness pass. See IOS-WATCH-TOGETHER-RC.md. |

Focused tests are useful before the full run; any subsequent code fix invalidates affected/full
evidence. Archive full-suite counts before focused tasks overwrite XML. Existing pure suites
are supplementary: this audit reran mobile group 1 (284 pass), then group 2 failed standalone
compilation on documented missing download dependencies. It is not a new app regression or
a full pure-suite green result; use real Gradle suites as the release gate.

## Remaining blockers and exact promotion order

Physical lock/Away and return acceptance remain open. Additional technical gates are final
auth review/integration and merged-head full regression/artifact verification. Desktop Linux
CI run `36822780484` fails at native `frame_copy_test.c:36: player != NULL` before Kotlin tests;
Windows MSI passes. The same failure occurs on auth CI. The fixture requires GStreamer
`playbin`/`appsink`; CI currently explicitly installs development libraries only. Diagnose
runtime plugin availability before changing the fixture or skipping it. Until repaired,
complete local Windows split evidence is mandatory; do not label desktop CI green.
No new proven WT code defect beyond the still-open device acceptance was found in this pass.

Once final auth and physical QA are green:

1. Freeze the integrated mobile/desktop RC SHAs and device builds. Any new product fix needs
   review/tests and affected physical retesting. No performance merge.
2. Record all tests, physical observations, deferred QA debt and curated release notes in
   STATUS/Z-FEATURES before the version bump. Historical setup/Phase 9 debt is not silently
   converted into a pass; confirm the maintainer's existing accepted matrix when QA resumes.
3. Merge each RC into its own trunk: mobile main, desktop Dev. Preserve main's canonical
   SideStore feeds; resolve docs against current handoffs and inspect the complete merge diff.
   Verify the product tree matches the approved RC except intentional release metadata.
4. Recheck upstream drift read-only. The RC already contains the named 0.5.4-beta/0.1.26-alpha
   sync; do not inject a new upstream sync into the frozen RC. A newly required sync reopens
   review and validation rather than inheriting the old QA pass.
   Cached upstream refs have one mobile commit (store publication) and 48 desktop commits
   outside RC; their current remote freshness was not established by the origin fetch.
   Do not mistake the named-base sync for convergence with every later upstream commit.
5. Make one final release-metadata commit per family, after all docs: proposed mobile
   `0.5.4-z1`, build **126**, serial **127** (127 is reserved/unpublished; live stable is
   `0.5.0-beta+126`); proposed desktop `0.1.26-alpha-z1`, code **46**, serial **132**
   (live stable `0.1.23-alpha-z6+131`). Recheck maxima before using these values. Keep debug
   counters 74/80 unless integration verification actually required a new debug build.
   Update matching changelog version labels/base attribution in that same bump commit.
   Preserve stable bundle IDs, signing and MSI upgrade UUID/ProductVersion `2.0.132`.
6. Push trunks and rehearse exact final artifacts with mobile `android-release.yml`
   `mode=dry-run` on main and desktop `desktop-release.yml` `mode=dry-run,target=windows-macos`
   on Dev (unsigned/notarized according to existing credentials). Require final-bump guard,
   unused tags, curated notes, complete package set, hashes and release configuration.
   Smoke final stable-channel artifacts, including install/update compatibility.
7. Stop for explicit stable-publication authorization. QA passing alone does not authorize
   publish in this task. After that authorization, use the same workflows/refs with
   `mode=publish`; unsigned macOS additionally requires `acknowledge_unsigned_macos=true`.
   Do not relabel Debug 74/80 as stable or replace assets under existing tags.
8. Verify published targets/assets/hashes, channel selection and mobile source.json update
   on main. Feed automation is an expected post-publish commit. Roll forward on failure;
   never reuse a published stable tag or lower counters.
