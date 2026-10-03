# Final RC P1 corrections and physical reacceptance

The maintainer confirmed on 2026-10-03 that no physical RC checks have passed since
Debug 76/82. Run one combined QC pass on the replacement candidates after packaging
verification. All five physical-QA flags remain open. Stable publication requires a
separate instruction after acceptance; mobile serial 127 remains reserved and ship
dates remain unset. Deferred performance work is excluded.

## Candidate and validation record

All local suite/focused gates pass; fresh Debug packaging and the separate iOS simulator/Xcode gate remain pending. Correction branches:
`codex/rc-p1-final-qa` (mobile) and `codex/rc-p1-desktop-final` (desktop). Exact bases:
mobile `9aea28b5c1de7fad288e9de2ef7d601bedb68cdf` (Debug 76), desktop
`53e67e6027c2d2a69b556734a4796f3a19033537` (Debug 82).

Automated validation: Mobile 3470 (zero failures, six separately covered skips), Desktop
3417 (zero failures/errors/skips/duplicates); Social 16 x five repetitions per repository;
What's New/storage 47/48. Scraper stress: eleven independent JVMs, 11000 rounds,
352000 executions, zero failures/timeouts/cache diagnostics. Android full Debug and
unsigned release/R8 pass. Changelog 23 + SideStore 6 Python tests pass. Desktop CI
retains the existing Linux native frame_copy_test.c:36 failure before Kotlin; Windows
MSI and global changelog jobs pass. Evidence is in `.rc-investigation/rc-p1-20261003`.

## Social authority

The repository previously read mutable active-profile state around asynchronous work
and published old completions without activation ownership. A profile-ID comparison
cannot distinguish the first and second A in A -> B -> A.

Each activation now creates a unique authority object. Coroutine context retains it
through nested calls, refreshes and retries. Authority checks fence RPC/session
suspensions, exceptional exits, publications, caches, presence registration and
realtime work. Activation and state publication share a lock so a switch cannot
interleave the check and write. Account-data wipes force a new authority even when
the active profile is already null. Historical server cleanup uses captured old
identity/channel data and remains ordered; useful independent refresh work need not
be globally canceled.

Real-repository regression tests use controlled session and HTTP neighbours with
deterministic suspension gates: A -> B, A -> B -> A, friends, invitations/requests,
Watching Now, stale error/retry, current refresh/retry, session failure (returned
and thrown), privacy, nested mutation refresh, account wipe, delayed capabilities,
and twenty repeated overlapping profile-switch rounds.

## Desktop scraper lifecycle

Before the fix, the original network-free 32-execution test passed six rounds and
timed out in round seven at the unchanged 60-second limit. The native diagnostic was
`Cannot get jni env because the vm is not cached.` The pinned quickjs-kt 1.0.15
source has unsynchronized process-global JNI VM/cache instance accounting; concurrent
native create/close can race that accounting and shared-reference cleanup.

Only creation and disposal now share a JVM lock. Evaluations and asynchronous host
work remain parallel, verified by a ten-runtime barrier test. No owner-thread
requirement was established, so no dedicated thread or single execution worker was
introduced. No timeout increase or retry masks a failure.

Proven: the prior reproduction, missing-VM diagnostic, unsynchronized source lifecycle
and passing repeated validation with lifecycle serialization. Inferred: the exact
lost-update/early-cleanup interleaving in the shipped native binary; it was not
instrumented directly. Source reference: quickjs-kt tag v1.0.15,
`a081755bcdb18ee6afa7ffee25b8fa976f06d31f`, `quickjs/native/jni/jni_globals.c`.

## Release tooling

The reusable SideStore workflow's prefix-only job filter skipped the numeric stable
tag `0.5.4-z1+127`. Strict app-tag routing now selects the stable feed/bundle for
that tag and the Debug feed/bundle for `debug-v0.4.13-z1.76` and `.77`. Unrelated
and installer/helper tags fail closed before download or mutation. Release metadata
cannot redirect a stable tag to Debug. The real feed updater is exercised with
synthetic IPAs and the opposite feed must remain byte-identical.

Stable changelog checks reject any selected shipping platform without its actual
ship date. Validation of unreleased Debug catalogs remains available. One parser
call now uses interspersed positional parsing; both CLI argument orders pass using
the exact CPython 3.12.3 argparse module that reproduced the earlier failure.
No release prose, dates, flags or catalogs are changed.

## Additional physical checks for these fixes

Record device/OS, installed candidate, exact test time and result. Give A and B
visibly different handles, friends, requests/invites, privacy and Watching Now data.

1. On Android, iPhone and Windows, start a slow Social refresh in A, switch to B,
   let B load, then restore the slow request/network. B's identity, friends,
   invitations, Watching Now, loading and errors must remain B's. Repeat twenty times.
2. Repeat A -> B -> A while the first A refresh is delayed. The second activation's
   current data must survive the old completion. Repeat with a delayed failed request,
   reconnect/auth recovery, privacy edit, friend request and logout/login to the same
   profile. No stale error, spinner, retry or cache may overwrite the current screen.
3. On Windows, repeatedly search titles and open source selection using multiple
   installed scrapers concurrently; cancel/reopen and repeat after other browsing
   and playback. Results should make independent progress without a 60-second stall.
   Keep logs for any stall; the automated network-free stress is the causal regression.
4. Install/update the new iPhone IPA through the official Debug SideStore source.
   Verify its Debug bundle identity and new build; check the stable source still
   advertises the pre-existing stable IPA. Do not trigger a stable publication.
5. Recheck Settings -> What's New on fresh and upgraded installs: fresh `New`
   indicator, legacy acknowledgement migration, global event #1, platform versions,
   date-first history, Advanced Setup action and Debug preview. Stable future-event
   gating remains an automated regression until a separately authorized stable release.

## Broader RC acceptance remains mandatory

Preserve every procedure and required outcome in [RC-DEVICE-QA.md](RC-DEVICE-QA.md):
installation/upgrade, all auth paths, setup/profile routing, Social OFF/ON, navigation,
all player modes, WT core/quiet foreground/Away ON and OFF/overlapping holds/network
interruptions, Downloads modes/lifecycle/offline/storage/constraints on Windows,
Android and iPhone. Use the new candidate pair; historical package links in that
document are evidence for the superseded pair only.

In particular, retain iPhone quiet foreground for 5+ minutes; Windows host with
iPhone guest Home/lock for 30-60s with Away ON and OFF; fresh readiness/catch-up and
any 12s fallback; another guest's buffer hold and manual host pause; iOS email auth;
active downloads plus ten Classic player/subtitle entry/exit cycles; lock, force-close,
restored downloads and offline completed-file playback. Export matching host/guest
diagnostics for failures. Physical acceptance is entirely pending.
