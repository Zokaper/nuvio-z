# Final iOS Watch Together RC investigation — 2026-09-30

Scope: the two iOS blockers reported on mobile debug 72 with desktop host debug 78. Maintainer
says Android guests and the rest of physical RC QA are passing. Initial small join drift that
converges is accepted; no source matching, controls, Downloads, setup or buffering-loop work here.

## Away delay and missing host pause

**Two separate defects, in one lost-packet path.** UIKit's DidEnterBackground callback queued
the peer Away send at the point iOS could suspend its socket. That packet cannot be relied upon.
The durable `party_set_away` RPC already ran under an iOS background task, and UI already used
`away_since` as fallback. However:

1. A read-only query of the live **Z** backend confirmed the member-change trigger's WHEN predicate
   did not include `away_since`. An Away-only update therefore did not invalidate observers'
   snapshots. The normal heartbeat/snapshot poll is five seconds, explaining delayed durable Away
   visibility when the fast packet loses the race. Existing exports do not capture the packet
   delivery or exact RPC duration, so that particular race is not claimed as a packet-level trace.
2. `partyAwayHoldMembers` required membership in the peer roster, ignoring `member.awaySince`.
   The UI eventually displayed durable Away but the host hold remained empty. This is the
   split between presentation and policy described in the report.

Fixes:

- Move iOS publication to WillResignActive, before suspension; retain DidEnterBackground fallback,
  background-task protection and serialized foreground clearing. Task completion is bound to the
  task it started, so an older completion cannot end a newer task. Transient iOS interruptions
  also publish Away until DidBecomeActive, consistent with inactive playback being paused.
- Add `away_since` to the existing backend member-change broadcast predicate. Its authority-plane
  invalidation causes a coalesced snapshot refresh immediately; bare heartbeat stamps stay quiet.
  Entry, return and reaper expiry all notify. **Migration deployed** only to Z project
  `pzbpghmmordvzcfbayoh`, after a public-schema backup and dry-run confirming this was the only
  pending migration. Read-only live trigger verification and migration-history check passed.
- Host holds for fresh realtime Away **or** an active durable Away lease, excluding self, left,
  failed and ended parties. A leased suspended guest may have its heartbeat marked disconnected;
  that does not release the Away hold until return/lease expiry. Without a lease, the existing
  disconnected exclusion still applies. Evaluate expiry with the repository's server clock offset.
- Expire peer Away evidence after its existing telemetry freshness window. A durable return/expiry
  clears older peer Away and rejects delayed pre-return Away packets; a later absence can still
  enter the fast roster. No cached durable absence survives a null/expired lease.
- Merge only the five shared Away policy/presence/test files into desktop: that is necessary for
  the reported desktop-host/iOS-guest arrangement. Desktop transport recovery is otherwise untouched.

## Foreground reconnects — still an RC blocker

### Corrected diagnostic retest (2026-09-30, 23:06–23:12 Arabia time)

Maintainer tested diagnostic source `8aedfd9f5`, run `36766427491` (success), and reports roughly
two banners, probably only after returning. Export: `../ios-reconn-logs/2/nuvio_diagnostics/`
`watchparty-1790798761121.log`. The laptop's installed Debug configuration identifies **desktop
79**, version `1.45.79`, source `71c8386d4`; that host predates Away policy merge `371e71dcf`.

| Local time | Evidence |
| --- | --- |
| 23:06:21–23:09:01 | Both planes subscribed, Live, regular successful heartbeat replies; no foreground drop before the first inactive transition. This is under five minutes and does not complete the original foreground acceptance. |
| 23:09:01–23:09:02 | Brief active/inactive flap: a queued return RPC executes after a newer background event. Away and return need symmetric revision guards. |
| 23:09:25–23:09:34 | Away RPC succeeds; native receive fails with numeric code 53 after backgrounding. Return clears durable Away in 174 ms. No auth/session loss or identity churn. Numeric code alone is not a proved network diagnosis. |
| 23:09:41–23:09:57 | SDK reconnect opens a socket; adapter subscription competes with automatic SDK rejoin. Repeated subscribed/subscribing transitions and server `phx_close` follow, then subscription timeout. |
| 23:09:57–23:09:58 | Second receive failure, with heartbeat ref outstanding for 112 ms. Return clears durable Away in 110 ms. |
| 23:10:04.961–23:10:19.966 | Third socket receives traffic, then closes exactly 15.005 seconds after opening, without sending its own heartbeat; last receive is only 128 ms old. |
| 23:10:27–23:10:45 | Fourth socket opens; channel churn continues until instance nine becomes Live. Later heartbeat replies remain healthy through the end of capture. |

The extra third-socket disconnect is consistent with the pinned SDK retaining its prior pending
heartbeat reference across reconnect. Its next heartbeat timer sees that old reference and closes
the new socket. **Reproduced using the actual pinned SDK:** a server drops a socket while its
heartbeat is unanswered; the healthy replacement is incorrectly closed and a third socket opens.
That regression went red before the shim. SDK implementation:
[RealtimeImpl 3.4.1](https://github.com/supabase-community/supabase-kt/blob/3.4.1/Realtime/src/commonMain/kotlin/io/github/jan/supabase/realtime/RealtimeImpl.kt).

Mobile fixes in this follow-up:

- Re-send only the retained Phoenix heartbeat on a newly opened socket. A genuine server reply
  clears the SDK's reference. No synthetic acknowledgement, disabled timeout or increased
  heartbeat interval. Applies to both diagnostic and standard Z clients; Ktor setup stays explicit.
- Keep channel objects, receive collectors and protocol while SDK reconnects/rejoins. The adapter
  no longer removes/recreates channels at the first loss and competes with the automatic rejoin.
  Connected-socket join recovery is bounded at 30 seconds; offline time stays with SDK recovery.
  Restore callbacks after SDK resets, re-track presence and refresh durable state on recovery.
  Only validated receive traffic with both planes and socket connected establishes Live.
- Give queued return publication the same latest-revision check as Away. An obsolete return must
  not publish false after a later inactive/background event. In-flight writes remain serialized.

The laptop log separately confirms the stale host problem: it enters the Away hold at 23:09:27,
does not consume the durable return clear, and sees fresh peer false only at 23:10:11. Maintainer
turned waiting off at 23:10:07. That is the missing desktop host policy already fixed in source,
not a failed iPhone return RPC. Installed 79 lacks it, as did 78. Current Windows build-only MSI
on desktop source `d020c0c609` contains it:
[Windows artifact](https://github.com/Zokaper/NuvioZDesktop/actions/runs/36734266044/artifacts/11105899064).
It is a **stable-channel** MSI, separate from Debug, downloaded to
`../.rc-investigation/wt-ios/desktop-away-host/`; no installation performed. SHA-256:
`651c896ada71e1862f4d869f9d40a62cab3bbeb930ca57bfbf0dee901b8ba06b`.
The Windows job passed. The run's Linux native frame-copy test failed at `player != NULL` before
Kotlin tests; do not label the overall run green. Existing local host presence tests remain 51/51.

Final full Android host suite: **3,384 tests, zero failures/errors, 6 skipped**, including
**424/424** focused WT/diagnostic cases. Actual pinned-SDK regressions verify one join per plane
per socket, healthy replacement survival, genuine unanswered-heartbeat timeout, and durable state
delivery after callback reset. Lifecycle queue ordering regressions pass. XML/logs:
`../.rc-investigation/wt-ios/resume-recovery-full-results/` and `resume-recovery-full.log`.
Fix pushed as `8658c6576`. New diagnostic-only
[IPA run 36776265055](https://github.com/Zokaper/nuvio-z/actions/runs/36776265055) is queued/in
progress on that exact source, keeping debug 73 and skipping Android/release/feed publication.
Native compilation and SHA-named artifact/checksum/manifest verification are pending; do not
claim the IPA is downloadable yet. No backend, counter, release or feed change. Both physical
acceptance gates remain open; this capture explains return recovery churn, without claiming that
the old foreground-only report is cleared.

### Diagnostic IPA retest (2026-09-30, 22:08–22:10 Arabia time)

Maintainer sideloaded the diagnostic IPA from source `628737989` and reported the banner almost
immediately, with brief retry transitions. Export: `../ios-reconn-logs/nuvio_diagnostics/`
`watchparty-1790795293156.log`. This trace captures **a diagnostic-build regression**, not the
initiating cause of the older debug-72 intermittent reconnect:

- First socket failure is 22:08:17.142, before the desired party binds at 22:08:35.473.
- All **23 socket creations fail** with `IllegalStateException`, no nested cause/native code,
  within 0–2 ms. No socket opens, heartbeat exchanges or subscribed planes occur.
- Nine party subscription attempts remain unsubscribed; eight reach the 12-second timeout and
  the ninth is cancelled when the party is left. API polling succeeds and all nine auth observations
  remain Authenticated with a session present and no token change.
- The only inactive/background events follow departure at 22:10:43.893/22:10:44.991. The one
  generation update retains its channel. Neither explains the initial socket failure.

The probe assigned a custom `Realtime.Config.websocketFactory` but delegated to the SDK's Ktor
factory. In [Supabase 3.4.1 `Realtime.setup`](https://github.com/supabase-community/supabase-kt/blob/3.4.1/Realtime/src/commonMain/kotlin/io/github/jan/supabase/realtime/Realtime.kt),
a custom factory suppresses automatic installation of Ktor WebSockets and its JSON converter.
The probe therefore tried to open sockets on a client missing WebSockets. Earlier tests exercised
error redaction and adapter recovery, but did not open a real diagnostic socket.

The production setup is now shared with a localhost websocket regression for both diagnostic and
standard clients. Diagnostic setup must explicitly install the same SDK WebSockets/converter;
the factory wraps that client's transport rather than looking up the global provider. No heartbeat,
timeout or retry thresholds change. Verification and replacement artifact state are recorded below.

Local verification: the diagnostic regression first reproduced Ktor's exact missing-WebSockets
exception. After restoring setup, both diagnostic and standard clients open and exchange a
serialized heartbeat/reply over a real localhost websocket. Focused WT + diagnostics:
**416 tests, zero failures/errors/skips**. Added privacy-safe classification of this specific
configuration error. Logs and XML: `../.rc-investigation/wt-ios/socket-regression-{red,green}.*`
and `socket-green-results/`. Fix committed and pushed as `8aedfd9f5`. Replacement
[diagnostic-only IPA run 36766427491](https://github.com/Zokaper/nuvio-z/actions/runs/36766427491)
successfully built that exact source; Android and release/feed publication were skipped.
It retains debug 73. Native compilation and standard source CI `36766426697` passed. SHA-named
artifact/checksum/manifest verified, IPA SHA-256
`d292d612e1ed5758f1a8b4dc1d2fa7d72e9e093bde15b7f260bfa8264c62189a`.
Maintainer's completed retest on it is above. No release/feed change.

**Retest required:** the previous diagnostic IPA cannot supply evidence for the original intermittent
drop. Repeat the five-minute foreground capture on the corrected diagnostic IPA. Stable RC
publication and original-reconnect acceptance remain held.

**The earlier intermittent drop's exact initiating cause is not established.** The earlier export is
`ios-recparty-logs/nuvio_diagnostics/session-20260930-161855.log` (76,640 bytes). It contains Swift
lifecycle/view/player diagnostics, with no WatchPartySync, WatchPartyTrace or Supabase-Realtime
socket/auth/health events. The older exported sessions likewise do not contain those events.
It cannot distinguish a lost subscription from a websocket failure, heartbeat timeout, auth loss,
an app cancellation or the health monitor's stale-receive state. The banner alone proves none of
those individually: health degradation also maps to Reconnecting without channel recreation.

Audit findings:

- Desired channel identity is already only party ID + local profile ID. Sequence, permissions,
  host changes and content/source/authority generation changes do not restart `collectLatest`.
  Generation changes invalidate generation-scoped protocol state in place. Regression covers all
  of these inputs. No evidence supports attributing the observed banner to generation churn.
- In the pinned [Supabase 3.4.1 implementation](https://github.com/supabase-community/supabase-kt/blob/3.4.1/Realtime/src/commonMain/kotlin/io/github/jan/supabase/realtime/RealtimeImpl.kt),
  `setAuth()` sends access-token updates to subscribed channels; it does not recreate subscriptions.
  SDK auth loss can disconnect them, and socket/heartbeat failures invoke SDK reconnect. Z auto
  platform auth setup/refresh is already disabled; the bridge owns renewal. Runtime evidence is
  needed before attributing this report to an auth loss or SDK socket behavior.
- There **was** a concrete recovery defect: `maintainChannel` closed with `clearProtocol=true` on
  every loss, and `attach` reset again. A brief reconnect discarded the clock, latest timeline,
  peer state, command deduplication and command counter. That explains how useful status/sync
  evidence could be lost during recovery, but is not proof of the initiating drop.

Recovery changes:

- Detach collectors/socket references/outstanding clock requests without clearing protocol state
  while the same party/profile remains desired. Keep latest tick/clock, peer telemetry, local
  engine status/starvation/presence, command deduplication and counters. Departure and generation
  change still invalidate their scoped state; retained ticks, clock and peer evidence still age out.
- Observe each plane separately without dropping its current status. Losing either plane or the
  shared websocket degrades health. Traffic on the surviving plane cannot declare the whole
  transport Live. Re-subscription still needs validated traffic; the UI banner remains truthful.
- Retry unexpected SDK cancellation when the owning coroutine remains active. Cancellation of
  the owning lifecycle coroutine still exits normally. Subscription timeout remains retryable.

Diagnostics:

- In debug builds, wrap the SDK's existing Ktor socket, leaving its heartbeat/timeout/recovery
  policy unchanged. Record creation, receive/send failure type, classified timeout/cancellation/
  connection loss, numeric native error code, heartbeat send/ack timing, server close/error plane,
  auth-update transmission and disconnect context. Never persist raw messages, URLs or tokens.
- Observe authority/peer subscription statuses, shared websocket status, auth session class and
  token-change boolean, desired-channel changes, generation updates, stale health and explicit
  lifecycle events. Record durable Away RPC start/completion/failure duration and return clearing.
- Persist privacy-safe events to `Documents/nuvio_diagnostics/watchparty-<epoch>.log` on a serial
  file queue, retaining 12 sessions and allowing writes after first unlock. The existing Files
  export will now include Kotlin WT evidence. Global Supabase DEBUG logging is deliberately not
  enabled because it logs authenticated join payloads.

## Verification

- Fresh local backend reset + pgTAP: **347 assertions, all pass**. Four added cases cover durable
  Away notification, unchanged-Away heartbeat silence, immediate return notification and expiry.
- Final mobile focused WT + diagnostic run: **413 tests, zero failures/errors/skips**, including
  real adapter protocol preservation and delayed pre-return Away, per-plane receive-health guard,
  identity/generation stability and privacy-safe native error classification.
- Full authoritative `:composeApp:testAndroidHostTest --rerun`: **3,373 tests, zero failures/errors,
  6 skipped**. iOS framework/Xcode compiler check [36731939532](https://github.com/Zokaper/nuvio-z/actions/runs/36731939532)
  **passed** (Swift lifecycle regressions, device framework and unsigned Xcode app). All three source branches are pushed.
- Desktop compile and focused presence tests after the minimal shared merge: **51/51 pass**.
- Standard mobile source CI [36735101305](https://github.com/Zokaper/nuvio-z/actions/runs/36735101305) passed.
- Logs and copied XML: `../.rc-investigation/wt-ios/`.
- No physical result is inferred from a host test, and no debug release/version/feed was published.

## Minimum remaining physical work

The return recovery failure is now captured and reproduced. Foreground-only physical acceptance
still needs a full quiet interval on the corrected source before an RC build is published. A connected-device
console capture on the current build, filtered to WatchPartySync / WatchPartyTrace /
Supabase-Realtime, may identify it without publishing anything. The new persistent trace requires
a diagnostic artifact; build-only compiler CI is not an installable artifact. The maintainer
cannot use Xcode and explicitly authorized a diagnostic-only iOS IPA. The existing debug workflow
now has an opt-in artifact-only mode that skips Android and release/feed publication. It uses the
same full iOS builder, keeps the current debug counter, and identifies the source SHA in the IPA
filename and a checksum/manifest. Workflow validation with actionlint passed.
Diagnostic run [36735100392](https://github.com/Zokaper/nuvio-z/actions/runs/36735100392), source
`628737989`, **passed**. Android and release/feed publication jobs are skipped.
[Superseded diagnostic artifact](https://github.com/Zokaper/nuvio-z/actions/runs/36735100392/artifacts/11108185929).
**Do not use this candidate for another capture:** its probe lacks WebSockets setup, as shown
in the 22:08 retest above. It kept base debug 73; no release tag,
version-counter bump or feed update is made. Archive CRC, SHA-256, bundle/version, Files export
keys and compiled WT probe markers verified. IPA SHA-256:
`8ad38557125d442e2da628e787b064a5d3455d95d34971308dbca6925ee9fe4c`.
Do not declare the reconnect blocker fixed from code inspection alone.

The backend notification migration is applied. After the corrected diagnostic IPA is ready:

1. Sideload the IPA, then keep iOS foregrounded in a party for at least five minutes on stable
   network. After a banner, export the latest `watchparty-*.log` from Files → Nuvio Z Debug →
   `nuvio_diagnostics`, with the approximate banner time. This is the evidence needed to classify
   the initiating socket/plane/auth/health event without Xcode.
2. Interrupt the real network, restore it, then play/pause/seek. Reconnecting must appear during
   loss and recovery must preserve meaningful member/timeline state. Confirm Android remains stable.

Final Away acceptance after a desktop host containing the fix is installed:

1. Use a desktop host containing `371e71dcf` for the final Away fallback acceptance; existing
   desktop debug 78 **and 79** lack that host fix. Desktop host + Android/iOS guests, Pause for Away ON: press Home on iOS; Away and host pause
   should arrive immediately via peer or durable refresh. Stay away beyond 20 seconds to cross
   heartbeat disconnection; host should remain held under the ten-minute lease. Return: Away
   clears and existing resume policy runs. Repeat lock/unlock.
2. Repeat Home/return with Pause for Away OFF: host continues; returning iOS catches up.

RC publication remains held: spontaneous foreground reconnect cause and physical acceptance are open.
