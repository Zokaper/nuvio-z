# Final RC P1 corrections and physical reacceptance

The maintainer confirmed on 2026-10-03 that no physical RC checks have passed since
Debug 76/82. Run one combined QC pass on the replacement candidates after packaging
verification. All five physical-QA flags remain open. Stable publication requires a
separate instruction after acceptance; mobile serial 127 remains reserved and ship
dates remain unset. Deferred performance work is excluded.


## Published final QA candidates

| Platform | Branch | Immutable tagged build SHA | Version / build | Workflow |
| --- | --- | --- | --- | --- |
| Mobile | `codex/rc-p1-final-qa` | `2f168c9b0010cd9c0e5bd98cf0981629962241c6` | `0.4.13-z1.77`; Android `125077`, iOS `77` | [37120475431](https://github.com/Zokaper/nuvio-z/actions/runs/37120475431) |
| Desktop | `codex/rc-p1-desktop-final` | `3031513b2f71c524f2c9dfb6162b7f1bd9a8fc73` | `0.1.23-alpha-z6.83`; Debug `83`, MSI `1.45.83` (base code `45`) | [37120482755](https://github.com/Zokaper/NuvioZDesktop/actions/runs/37120482755) |

Tags: `debug-v0.4.13-z1.77` and `debug-v0.1.23-alpha-z6.83`. Both workflows passed.
Tag SHAs are exact; subsequent branch handoff commits change documentation only.

- [androidApp-full-debug.apk](https://github.com/Zokaper/nuvio-z/releases/download/debug-v0.4.13-z1.77/androidApp-full-debug.apk) — 156262833 bytes; SHA-256 `bf141ad3969d9810ccd1965ced942ebd60563514de61a4046b9b13235c8bb90f`.
- [Nuvio-Z-iOS-0.4.13-z1-77-debug-unsigned.ipa](https://github.com/Zokaper/nuvio-z/releases/download/debug-v0.4.13-z1.77/Nuvio-Z-iOS-0.4.13-z1-77-debug-unsigned.ipa) — 93055666 bytes; SHA-256 `7946592695022306745ed7e3f4f97ea5ad6d763a7b5803d0e3e312f88948678e`.
- [Nuvio-Z-Debug-macOS-arm64-0.1.23-alpha-z6.83.dmg](https://github.com/Zokaper/NuvioZDesktop/releases/download/debug-v0.1.23-alpha-z6.83/Nuvio-Z-Debug-macOS-arm64-0.1.23-alpha-z6.83.dmg) — 251873009 bytes; SHA-256 `01a6c15aa0ad450708b3a6c1a1ed656d92cd5b4932f067c5d2291469b863a8bd`.
- [Nuvio-Z-Debug-Windows-x64-0.1.23-alpha-z6.83.msi](https://github.com/Zokaper/NuvioZDesktop/releases/download/debug-v0.1.23-alpha-z6.83/Nuvio-Z-Debug-Windows-x64-0.1.23-alpha-z6.83.msi) — 256631000 bytes; SHA-256 `a11ec4ceaf01017d9a128b416d5317dbc3b1d44a6dd8646aa51dc592d685ebef`.

All four downloaded packages match GitHub asset size/digest. Mobile hashes also match
SHA256SUMS-Debug.txt. APK identity/code/version and existing Debug signing certificate pass.
IPA CRC, unsigned app, bundle `com.nuvio.app.z.debug`, version/build `0.4.13-z1.77`/`77`,
minimum iOS 16.1 and Files sharing/open-in-place settings pass. MSI identity is
`Nuvio Z Debug` / `1.45.83`. macOS arm64 DMG mounting, Debug bundle, architecture and
pinned native runtime checks pass in its workflow.

Official Debug SideStore feed points to the new IPA, size **93055666**, SHA-256
`7946592695022306745ed7e3f4f97ea5ad6d763a7b5803d0e3e312f88948678e`.
Stable source blob remains `e1025b0386bbd8a16ade476fbc271a681c2ff542`, identical to Debug 76
and the pre-build snapshot. Stable and helper feeds were not modified.

Separate [iOS gate 37120559532](https://github.com/Zokaper/nuvio-z/actions/runs/37120559532)
passes Swift lifecycle regressions, device and simulator frameworks and unsigned Xcode on
the exact Debug 77 build SHA. Earlier gate 37118806451 was canceled by the counter push's
branch concurrency policy; this final full gate supersedes it.

Classification: **READY FOR FINAL DEVICE QA**. No physical acceptance has passed;
all five QA flags, mobile serial 127 and unreleased ship dates remain unchanged.
No stable publication or performance integration. Use the additional P1 tests and
the complete unchanged broader matrix in mobile `Docs/RC-P1-FINAL-QA.md`.

Automated validation: Mobile **3470** (zero failures, six separately covered skips),
Desktop **3417** (zero failures/errors/skips/duplicates); Social **16 x five repetitions
per repository**; What's New/storage **47/48**. Scraper stress: **eleven independent JVMs,
11000 rounds, 352000 executions**, zero failures/timeouts/cache diagnostics. Android full
Debug and unsigned release/R8 pass. Changelog **23** + SideStore **6** Python tests pass.
Desktop CI retains the existing Linux native frame_copy_test.c:36 failure before Kotlin;
Windows MSI and global changelog jobs pass. Evidence: `.rc-investigation/rc-p1-20261003`.

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
