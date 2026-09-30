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

**The exact initiating cause is not established.** The latest supplied export is
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
  is running; its Swift lifecycle regression job passed. All three source branches are pushed.
- Desktop compile and focused presence tests after the minimal shared merge: **51/51 pass**.
- Logs and copied XML: `../.rc-investigation/wt-ios/`.
- No physical result is inferred from a host test, and no debug release/version/feed was published.

## Minimum remaining physical work

The foreground drop needs a runtime trace before an RC build is published. A connected-device
console capture on the current build, filtered to WatchPartySync / WatchPartyTrace /
Supabase-Realtime, may identify it without publishing anything. The new persistent trace requires
a diagnostic artifact; build-only compiler CI is not an installable artifact. The maintainer
cannot use Xcode and explicitly authorized a diagnostic-only iOS IPA. The existing debug workflow
now has an opt-in artifact-only mode that skips Android and release/feed publication. It uses the
same full iOS builder, keeps the current debug counter, and identifies the source SHA in the IPA
filename and a checksum/manifest. Workflow validation with actionlint passed.
Do not declare the reconnect blocker fixed from code inspection alone.

The backend notification migration is applied. Once an installable candidate is authorized:

1. Desktop host + Android/iOS guests, Pause for Away ON: press Home on iOS; Away and host pause
   should arrive immediately via peer or durable refresh. Stay away beyond 20 seconds to cross
   heartbeat disconnection; host should remain held under the ten-minute lease. Return: Away
   clears and existing resume policy runs. Repeat lock/unlock.
2. Repeat Home/return with Pause for Away OFF: host continues; returning iOS catches up.
3. Keep iOS foregrounded for at least five minutes on stable network. If a banner appears, export
   `watchparty-*.log` with its timestamp; correlate initiating socket/plane/auth/health event.
4. Interrupt the real network, restore it, then play/pause/seek. Reconnecting must appear during
   loss and recovery must preserve meaningful member/timeline state. Confirm Android remains stable.

Publication remains held: spontaneous foreground reconnect cause and physical acceptance are open.
