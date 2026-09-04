# Nuvio Z Status

Last updated: 2026-09-04

| | |
| --- | --- |
| Active branch | `claude/upstream-sync-0.4.13` in **nuvio-z**, carrying the vanilla `0.4.13` merge. `claude/watch-together-sync-7ceki1` in **NuvioZDesktop**, off `codex/next-episode-debug-hotfix`, carrying the Watch Together sync rework and, since 2026-09-03, the social/lobby/player UI rebuild. `nuvio-z` stays on `codex/next-episode-debug-hotfix`; the branch of the same name here is this documentation only, because both are deliberately desktop-first until verified on two machines. |
| Version in the files | mobile **`0.4.13-z1`** with release serial **127** as of the sync below; desktop still `0.5.0-beta` (`VERSION_CODE=39`). `CURRENT_PROJECT_VERSION` stays 125 until a build is published. The two repositories deliberately disagree until the desktop sync lands. |
| Released | bridge `0.5.0-beta+126`, published in both KMP repositories on 2026-08-24 |
| Next version | mobile has adopted `0.4.13-z1`. Desktop adopts `<vanilla>-z1` when its own sync to `0.1.22-alpha` lands. Note that the debug channel carries no serial, so a debug install on `0.5.0-beta.25` will not be offered a `0.4.13-z1` debug build - it needs one manual sideload. |
| Verified | the Z backend is deployed and live: 8 migrations applied to `pzbpghmmordvzcfbayoh`, `get_social_capabilities()` now returns both flags **true** - `202609010009_enable_social.sql` enabled them, and the desktop client renders the invite-code field and the Watch Together action, which it only does when `watchPartyEnabled` is set, so the earlier "both flags false" reading in this table predates that migration - direct table reads return 401, the `z-session` function is deployed and rejects every unauthenticated path correctly, and 61 pgTAP assertions pass on matching Postgres 17. Both standalone suites pass (290 tests each); focused Android host and desktop Gradle runs compile the real source sets and pass all 16 next-episode tests. Desktop Watch Together propagation measured about 225 ms in a real two-client session; the buffering-race follow-up compiles and all 1,318 desktop tests pass. Mobile CI `33327792025`, repaired desktop CI `33328140034`, mobile debug publish `33328752860` and desktop debug publish `33615211655` all pass. For the sync rework: `scripts/run-pure-suites.sh` passes all six groups (131, 64, 49, 17, 29, 42) with the new group 6 compiling the shipped sync sources and no stubs, and desktop CI `33627311248` passes the full `:composeApp:desktopTest` run and the Windows MSI build. For the UI rebuild: 1,360/1,360 desktop tests pass locally, and the social tab and the party lobby were both driven in the running app over Compose Hot Reload - the lobby across three live stage transitions. |
| **Not** verified | **Nothing in the Watch Together sync rework has run against a live party.** It compiles and both suites pass, and that is all: the party clock, the tick, the barriers and the wait-for-everyone policy have never had two machines on them. The matrix is cold start; pause and resume ten times, measuring the spread; seek ten times; a real host rebuffer; a real guest rebuffer with the toggle on and off; host migration; the socket killed mid-film; and a `debug-v0.5.0-beta.36` client against a `.35` one, which must degrade to the old five-second behaviour rather than break. Carried forward from `debug-v0.5.0-beta.35` and still open independently of the rework: every host transport action must bump `sequence`, offline Leave/End must permit a new party immediately, and the corrected next-episode transition and the desktop HTML button still need a device/install pass. Mobile is still not wired to the Z backend and has none of this; manual iOS verification remains outstanding. From the UI rebuild: the **in-player Watch Together panel has never been seen** - reaching it needs playback, and its CSS and JS are desktop resources that a Compose reload does not pick up, so it wants a deliberate restart. Nor has any **multi-member lobby state**: a one-person party cannot produce a green `ready` tile, a red `failed` tile with its error text, the alternate-source chip, or the dimming of a disconnected member, and a still cannot judge the resolving ring's animation. All of those fall out of the two-desktop matrix. |
| Next work | Run the two-desktop Watch Together matrix against `debug-v0.5.0-beta.36` with the playback HUD on, and read `errMs` off the screen rather than diffing two logs; it is also the only run that can show the lobby's multi-member tiles and the in-player panel. Nothing in the rework has been measured between two machines. Port to mobile only after that passes and after mobile reaches `ZSupabaseProvider`/`ZSessionBridge`; stable `0.5.0-beta+126` remains untouched. |
| Debug channel | desktop `debug-v0.5.0-beta.36` carries the sync rework, published 2026-09-02 from `claude/watch-together-sync-7ceki1`; `debug-v0.5.0-beta.35` from `f0aef43f` is the build the current sync report came from. Mobile `debug-v0.5.0-beta.25` was published 2026-08-30. Stale pre-sync desktop `debug-v0.4.14-beta.18` and mobile `debug-v0.4.14-beta.25` are superseded. |

> **The history moved.** Everything before 2026-08-24 is in [`Docs/STATUS-ARCHIVE.md`](Docs/STATUS-ARCHIVE.md) -
> 48 sections, kept whole and in order. This file is the live handoff only: the
> state table above, the work since the last release, and what is still open below.

## Phase 2 Playback: in progress (2026-09-04)

Branch `claude/phase-2-playback`, cut from the Phase 1 sync branch - **not** from trunk, which is
362 commits behind. Full handoff: `../HANDOFF-phase-2-playback.md`.

**Stages 0-3 done, both repos. Stage 4 (P7), 5 (review + watched run) and 6 (docs) open.**

### The finding that matters most

**The `0.1.22-alpha` sync silently disabled desktop's whole playback recovery path.** The App.kt
dissolution recorded above moved `MainAppContent`'s `onFatalPlaybackError`/`onPlaybackStarted`
handler nowhere: `PlayerDestination` stopped passing them, while `PlayerScreen` still declared
both. Nothing was deleted, everything compiled, and the deletion check the sync brief mandates
could not see it - a lambda simply stopped being passed.

Three things were dead in production until this phase:

- `PlaybackStartupWatchdog` arms only when `onFatalPlaybackError != null`, so **it never ran**;
- the post-playback-started failover chain never advanced;
- `consumeFailoverRetry()` always answered false, so every return from the player read as a back
  press.

`nuvio-z` kept its copy, which is exactly why the loading loop was reported on desktop only.
**Worth a rule for the next sync: a lambda that stops being passed is invisible to both the
conflict list and the deletion sweep.** Grep the callers of anything the dissolution moved.

### What landed

- **One loading surface** from chosen source to first frame, rendered by both the route overlay
  and the player's opening overlay from one state object. Three loading surfaces and four
  indeterminate motions became one.
- **Every duration-derived position bounded** through pure `PlaybackPosition`. The watchdog's
  baseline ignored the fraction-only resume path, so a dead source could be declared Started -
  bugs 1 and 2 shared that root.
- **`AddonStreamGroup.error` stops being discarded**, in the list and as the failure reason.
- **Content-identity gate**, auto modes only, a partition rather than a filter.
- **All 13 ways into the source list named and logged**, with `hasSilentUncover` making a
  reasonless uncover a failing test.

### Verified, and not

Suites green: pure 388 desktop / 337 mobile, Android host 1,339, zero failures.
`:composeApp:desktopTest` **not yet run this phase**.

⚠ **Nothing here has been exercised against real playback.** Hot reload cannot reach a first
frame on this machine - the native bridge fails to attach (`java.desktop does not "opens
java.awt"`), so the player route opens to an empty surface that looks exactly like a hang. That
is a second, separate reason for the debug-MSI rule already recorded below. The watched run is
the exit gate and it has not happened.

## Mobile is synced to vanilla 0.4.13 (2026-09-04)

Phase 1 of `ROADMAP.md`, on `nuvio-z` branch `claude/upstream-sync-0.4.13`, merged in `bacb3a23`.
90 commits of vanilla, a 27-file conflict surface, 11 files actually in conflict.

The work was not the conflicts. Upstream dissolved `App.kt` into a 98-line shell plus 13 new
files, and our copy carried 1,639 lines of Z changes across 44 commits, so there was no "keep
ours": the new files merged in cleanly as additions and their declarations collided with the
monolith. Z's hunks were routed into `MainAppContent.kt`, `StreamDestination.kt` and the six
destination files, then read by hand.

**Five pieces of Z code went missing with no conflict marker.** Four were found by the compiler.
The fifth was not: `AddonSubtitleStartupPolicy.kt` and its 40-test suite were deleted outright
together with their last caller, so everything compiled while the Fast-startup subtitle setting
sat in the UI wired to nothing. The check that catches this class of loss is
`git diff --cached --diff-filter=D --name-only`, and it belongs in every sync.

Pure suites 285 green - the pre-merge baseline exactly - and the Android host suite 1,286 green.
`scripts/run-pure-suites.sh` had to be repaired first: its serialization compiler plugin was
pinned to Kotlin 2.3.0 against a 2.4.10 compiler, and there is no `java` on PATH on this machine,
so it had been exiting 0 while running nothing at all. CI `33861273289` passes, and the iOS build
`33861273328` compiles the Kotlin framework and the Xcode app in 38 minutes - the merge brought
upstream's `ios-test-build.yml` and `scripts/build-ios-ipa.sh` with it, so the IPA pipeline is
inherited rather than owed.

Full analysis, and the brief for the desktop sync, in `Docs/UPSTREAM-SYNC-0.4.13.md`.

## The social tab, the party lobby and the in-player panel were rebuilt (2026-09-03)

Landed on `NuvioZDesktop` branch `claude/watch-together-sync-7ceki1` in `12542f2f`, `4d63cbfa` and
`99b115ef`. **Desktop only.** Two of the three surfaces were verified in the running app; the third
was not - see the table above.

All three had been written without anyone being able to look at them, and it showed. Every element
was the same `tonalElevation` grey box, so an error message, the profile header and a privacy
toggle all carried equal weight; and the states people actually read - is this person ready, did my
friend request send - were the least legible things on screen.

**One vocabulary for readiness.** `WatchPartyPresentation.kt` names the tone
(ready/working/failed/offline), the label and the stage rail once. The lobby and the player were
each spelling this out for themselves and neither carried severity: the player sent
`readyState.name` with the underscores swapped - the codebase's words rather than the viewer's - and
disagreed with the lobby beside it. `PlayerPartyMember` now carries `statusTone` across the native
bridge, so the controls layer can colour a pill instead of printing a grey caption.

**Semantic colour is fixed, not themed.** Ready and live are a fixed green. `colorScheme.primary`
follows the user's theme picker, and under Crimson a red "ready" sits beside a pink
`colorScheme.error` "no source found" and the pair says nothing. The accent still carries emphasis -
the stage rail, the invite tile - where no state is meant.

`avatarUrl` and `avatarColorHex` were on `SocialProfileSummary`, `PartyParticipantProfile` and
`PlayerPartyMember` from the day the feature shipped and were drawn by nothing, so every avatar on
every social surface was a monogram. They render now.

**What looking at it actually caught.** Ten defects, none of which a test would have found:

- The lobby's blurred backdrop was invisible. The scrim ran `0.86 -> 1.0` alpha over the art, which
  is opaque; the screen read as flat black and the art might as well not have been fetched.
- `Modifier.fillMaxSize().widthIn(max = ...)` silently defeats the cap - `fillMaxSize` forces the
  node to the parent's width - so the lobby stretched across a 2880px window. The same line was
  written twice, in `SocialScreen.kt` and then again in `WatchPartyLobbyScreen.kt`.
- `NO SOURCE YET` ellipsised to `NO SOURCE ...` in a 150dp tile, so the longest and most alarming
  states were exactly the ones truncated away.
- The lobby was never centred: `widthIn` with no centring parent pinned it to the left edge of a
  wide monitor.
- Activity cropped a 2:3 poster into a 76x48 letterbox, mangling the art on every card, on the
  social tab and the home rows alike.
- The social header's tint had two hard edges and read as a mis-drawn panel rather than a header.
- Empty states stretched to about 1250px for two short lines, so an empty tab looked broken rather
  than empty.
- The recent-activity row's trailing avatar sat alone at the far right, separated from the name it
  belongs to by the entire empty middle, repeating what the subtitle already said.
- A dark `avatarColorHex` - zokaper's is near-black green - left the monogram floating with no
  visible circle.
- `social_no_activity` already says "Add friends to see what they watch", and the detail line under
  it said the same thing again.

**What the verification run established.** The social tab was driven against real friend activity.
`social_get_state` returns `activity` correctly, and the empty feed seen first was accurate, because
`zokaper` has published **zero** `social_activity_events` rows. That is not a fault:
`SocialWatchedActivity` bridges *explicit local mark-watched mutations* only and is not a backfill,
so an account whose history predates the social wiring has nothing to publish. `seraph`'s four rows
prove the path works end to end. Worth knowing before reading an empty feed as a bug.

The lobby was driven across three live stage transitions - the rail filling one, two and three
segments; the headline moving through "Waiting in the lobby", "Waiting for the host to pick a
source" and "Everyone is finding their source - 1 to go"; the pill through `NO SOURCE YET`,
`FINDING SOURCE` and `PICKING A SOURCE`; and the primary action flipping to "Resolve source" once a
fingerprint existed. Both test parties were ended afterwards.

**Two housekeeping notes from that run.** There are **20 open `watch_parties` rows** with
`ended_at is null`, from test sessions on 2026-09-01 and 02 that were never ended; they accumulate.
And `DesktopDownloadQueueE2ETest > a source that trickles and drops forever fails instead of
retrying forever` failed once in a full sweep and then passed 20/20 in isolation - it is a
wall-clock simulation of a starving source, and the failing run shared the machine with a running
Compose app and Gradle daemons. Run `desktopTest` on a quiet machine before believing it.

## Watch Together sync: the timing plane moved off the database (2026-09-02)

Landed on `NuvioZDesktop` branch `claude/watch-together-sync-7ceki1`. **Desktop only, and
unverified against a live party** - see the table above.

The "few seconds apart, pause is slow, unpause jumps" report is one design fault with three
faces, and none of them was a tuning problem:

- **The anchor was biased.** Party state carried `(position_ms, state_updated_at)`, where the
  position was a host sample lifted from a 500ms Compose polling loop and the timestamp was the
  *server's* `now()` at commit. Those are different instants, so every guest computed a position
  behind the host by the host's sample age plus its uplink - a constant, re-applied on every
  anchor, invisible from either side because both machines computed the same wrong number.
  `WatchPartyDriftDeadbandMs = 750` then declared that in sync, which is why no amount of
  correction tuning ever moved it.
- **A pause took two server hops** - PostgREST, a Postgres write, a trigger calling
  `realtime.send`, then Realtime - for which the recorded best case was about 225ms.
- **Every transition jumped by construction.** A guest was handed `position + delay` on resume
  and jumped forward by exactly the delay it had spent waiting; on pause it played on and was
  seeked backwards.

Postgres keeps everything it was already good at - membership, readiness, the host's identity,
content, the late-joiner snapshot, every authorization decision. What moved is the timing plane,
onto the private party channel that was already open and already RLS-gated. **No backend
migration.**

- The position now travels with **the instant it was read**. `PlayerEngineController` gained
  `samplePositionMs()`, answered on desktop straight off the mpv handle, and the host broadcasts
  that pair twice a second. This is the single change that removes the standing offset.
- The party clock is the **host's**, estimated NTP-style over the same socket the positions
  arrive on: min-RTT selection across a sliding window, slew-limited once locked, re-locked on a
  step no drift could produce. It replaces three PostgREST round trips taken once at party start
  and never again.
- Play and seek are **barriers**: a position and the party instant to be playing it at, executed
  by every client including the host through one code path. Pause deliberately carries no lead
  and aligns while paused, where closing a gap costs one frame rather than a visible jump.
- Bands retuned for an unbiased anchor - deadband 750ms to 200ms, seek 4s to 1.5s, with a seek
  needing the gap seen twice. The old bands are kept intact as `partyFallbackDriftCorrection` for
  the database path, which is still biased; exactly one of the two paths runs at a time.
- The host holds the party for a guest stalled past 1.5s and starts everyone together again,
  behind a lobby toggle. It is host-side and this session only: there is no party settings
  surface to persist it into yet.
- A guest without control can no longer move its own player. It always could - the press looked
  like it worked and silently desynced them - and it now says why instead.

**Three faults were found by executing rather than reading.** `minByOrNull` keeps the *first*
minimum, and on a steady link every round trip measures the same, so the opening exchange of a
party won every comparison for the life of the window and the clock estimate could never move. A
barrier scheduled against an offset of zero - which means "no estimate", not "no error" - would
be either far in the future or long past. And three helpers were top-level extensions the player
file never imported: eight files passed the `kotlinc` parser check cleanly while none of them
compiled, which is exactly the gap `AGENTS.md` names about that check. Only CI could see it.

The decision layer is deliberately import-free, for the reason `core/media/ReleaseTags.kt` is:
Gradle cannot configure in the agent sandbox, and two clients disagreeing about a clock is not
something a single-machine build can find. `scripts/run-pure-suites.sh` gained a **group 6** that
compiles the shipped sync sources - no stubs - and runs 42 tests against them.

A Watch Together row was added to the playback HUD (`errMs`, `offsetMs`, `rttMs`, `tickAgeMs`),
so the two-client matrix can be read off the screen instead of by lining up two log files
afterwards. Every previous round of this work was measured the second way.

**Verified:** all six pure-suite groups pass (131, 64, 49, 17, 29, 42); desktop CI
`33627311248` passes `:composeApp:desktopTest` in full and builds the Windows MSI.
**Not verified:** anything requiring two machines. Nothing here has met a live party.

## Desktop Watch Together recovery and control audit (2026-09-02)

The remaining desktop fixes landed on `codex/next-episode-debug-hotfix` in commits `d6f2e440`,
`2383ac75`, and `f0aef43f`, continuing commits `d9fb00a5` and `4b287aa1`:

- A guest labels a transient host stall as **Host is buffering** and remains held while the connected
  host continues to report buffering. The earlier 12-second auto-resume was removed after a live
  two-instance run proved it let a guest diverge while the host was genuinely stalled.
- Periodic and status-transition heartbeats are serialized. The same run caught `playing` and
  `buffering` requests starting four milliseconds apart and completing in the opposite order: the
  stale `playing` write undid a buffering broadcast after 26 ms, so the guest did not stay paused
  until the next five-second heartbeat. An older sampled request can no longer finish after a newer
  one and overwrite it.
- Every inspected desktop user seek path now submits the party command: native-fallback double tap,
  the native scrub bar, the Compose scrub bar, horizontal swipe, and skip-intro/outro. Play/pause,
  seek-by, and playback speed were already covered. The on-device invariant is one `sequence` bump
  for every permitted host action.
- Leave and End clear the held party, poll and realtime channel before issuing a bounded background
  RPC. This also covers `end()` failing before its RPC block because session refresh was unavailable;
  local teardown no longer depends on any network operation returning.
- Expected-position arithmetic now uses `Double`, preserving millisecond precision for long content
  rather than promoting the whole sum to 24-bit `Float` precision.

**Verified:** `:composeApp:compileKotlinDesktop` passes; the full `:composeApp:desktopTest` run passes
1,318/1,318 with no failures or skips. Desktop debug workflow `33615211655` built, verified and
published the Windows x64 MSI and unsigned macOS arm64 DMG as `debug-v0.5.0-beta.35`. Device
verification remains required: confirm a real host rebuffer pauses the guest promptly and keeps it
held until the host actually plays, exercise every host transport path while watching `sequence`,
and press Leave/End with the network down before creating a new party.

## Watch Together sync fixes carried ahead of the mobile port (2026-09-02)

**The tested client was desktop, not this one.** The two-device run that produced the five second
sync report was Windows host and macOS guest on the debug channel; mobile is still not wired to the
Z backend - `SupabaseConfig.URL` here is the official `api.nuvio.tv`, which hosts none of the party
RPCs. The diagnosis, the fixes and the verification for that run are in `NuvioZDesktop/STATUS.md`.

What landed here is the shared half of that work, applied early so the mobile port does not inherit
faults already understood. It compiles and the host suite passes, but **none of it has run against a
live party**, and it cannot until mobile reaches the Z backend.

- The drift policy: a nudge proportional to the gap and capped at +-10%, replacing a fixed 1.03x
  that recovered 300ms over its ten second hold and so escalated every drift that mattered to a
  seek; the band widened to 4s; the blocking `delay(10_000L)` removed so a snapshot arriving mid
  correction is no longer skipped; and a corrective seek that leads by the resume cost, because
  seeking to where the party is now lands where the party was.
- The `buffering` branch holds position instead of realigning. A host publishes `buffering` from its
  own `isLoading` and `expectedPartyPositionMs` freezes for any non-playing status, so the 500ms
  test passed almost every time and every host stutter cost every guest a seek.
- An `isLoading` guard and a duration clamp on the correction path, matching desktop.
- A bounded `subscribe`, the clock offset measured from a polling floor rather than behind the
  subscription, coalesced refreshes, broadcast payload decode ordered over
  `(sequence, state_updated_at)`, readiness keyed on whether a duration is known rather than on its
  value, and the `WatchParty` / `WatchPartyPlayer` log tags.

Mobile still lacks `ZSupabaseProvider` and `ZSessionBridge`, so the port itself is unstarted.

**Verified:** `:composeApp:testAndroidHostTest` passes, 1,246 tests, including new coverage of the
drift bands, the proportional nudge and its cap, and the seek lead applying only when behind.

## The social backend is Z-owned, and why it had to be (2026-09-01)

The social schema was written to extend `public.profiles`, with thirteen foreign keys into it. That
table lives in the **official Nuvio** project at `api.nuvio.tv`, which NuvioMedia operates and we
have no administrative relationship to. Nuvio Z is a mod of Nuvio, not a product we run. As written,
the feature was only deployable by NuvioMedia, and the local-only fixture that stood in for their
table during tests is precisely what kept that hidden - the suite passed against a stand-in for
infrastructure we cannot deploy to.

Nuvio Z now has its own Supabase project, `pzbpghmmordvzcfbayoh` (eu-central-1, Postgres 17), holding
**only** the social and Watch Together surface. Accounts, profiles and all base user data stay on the
official backend, so a Z install remains cross-compatible with vanilla Nuvio. `AGENTS.md` carries the
full rules under **The Two Backends**; the short version is that nothing may ever deploy to theirs.

`public.z_identities` replaces `public.profiles` as the anchor. Because every table is now ours, a
fresh database applies all migrations unaided and the fixture is deleted - `supabase db reset &&
supabase test db` works directly.

### One identity, two backends

Users still sign in once, to official Nuvio. Supabase third-party auth trusts only five named
providers, so the Z project cannot simply be told to trust that issuer; the `z-session` Edge Function
bridges instead. It works without any cooperation from NuvioMedia because their project publishes an
asymmetric ES256 JWKS, so a user's token can be verified with the public key alone.

The function verifies the presented token, then confirms the claimed profile belongs to its subject
by querying the official REST API **with the caller's own token** - their RLS answers it, so a
profile the caller does not own returns nothing. That step is load-bearing: profile UUIDs are visible
to friends through the feed, so without it any user could claim an identity they had merely seen.
`owns_profile()` then requires the token and the registry to agree, and reads the active profile from
the token rather than from a caller-supplied argument.

Two things were found by executing rather than reviewing, both of which would have failed in the
field:

- The exchange originally minted its own HS256 token signed with the project JWT secret. The Z
  project signs asymmetrically, so no shared secret exists and the token would have been rejected.
  Supabase now issues the session (`generateLink` + `verifyOtp`), which also yields refresh tokens.
- The follow-up fix was first written into migration `202609010002`, which was already applied.
  `db push` tracks migrations by version rather than content, so the change would silently never have
  reached the database - passing locally, failing live. It shipped as `202609010008` instead, and
  applied migrations stay immutable.

### Desktop client wiring

`ZSupabaseProvider` is a second Supabase client alongside the official one, and `ZSessionBridge`
performs the exchange, caches the session per profile, and re-exchanges once when a Z token is
rejected. `SocialRepository` and `WatchPartyRepository` now talk exclusively to the Z client; the
official client keeps playback and sign-in. Realtime is gated on a live Z session because both social
and party topics are private channels authorized by RLS on `realtime.messages`.

Endpoints come from the ignored `local.properties` as `NUVIO_Z_SUPABASE_URL` and
`NUVIO_Z_SUPABASE_PUBLISHABLE_KEY`. Blank values leave `ZSupabaseConfig.isConfigured` false and every
social surface hidden, which is the same degradation an undeployed backend produces, so a build
without them is valid rather than broken.

Desktop is wired first deliberately, to get one real exchange through before the shape is duplicated
into mobile.

## Social foundation and Watch Together implementation (2026-09-01)

A new private `Zokaper/nuvio-z-backend` repository now owns versioned social/party migrations,
RPC-only mutation boundaries, RLS/private-Realtime authorization, throttling and pgTAP coverage.
The KMP client has Realtime installed, stable profile-UUID activation, handle/friend/feed UI,
offline activity outbox, sanitized player presence, Home rows, a Social root tab, retained Downloads
routes plus a Library shortcut, and Watch Together lobby/player synchronization primitives.
Backend capabilities default off, so an undeployed or older server disables the surfaces cleanly.

The plan itself was only ever held in a Codex session; it is now checked in as
`Docs/SOCIAL-WATCH-TOGETHER-PLAN.md` and is the source of truth for this work.

Verified on 2026-09-01: `:composeApp:compileAndroidMain` and `:composeApp:testAndroidHostTest` pass
in `nuvio-z`. The desktop port landed the same day and is covered below. Backend deployment,
staging E2E and the iOS workflow remain required before either gate is enabled.

### Desktop port (2026-09-01)

`nuviozdesktop` now carries the shared `features/social` and `features/watchparty` packages, a
`DesktopStorage`-backed `SocialStorage` actual, Realtime installed on its Supabase client, the
`WatchPartyLobbyRoute`, Home social rows, the details and player Watch Together entries, and the
watched-activity publish/remove hooks. Three divergences from mobile are deliberate and recorded in
`Docs/PATCH-SURFACE.md`: Social is added *beside* the desktop Downloads sidebar entry rather than
replacing it, the Library Downloads shortcut is not ported, and the Watch Together entry is inserted
into both of desktop's mutually exclusive detail layouts. Folding Downloads into Library is a
separate pass the maintainer has deferred.

### The backend SQL had never been executed until 2026-09-01

Codex wrote the migrations but no database ever ran them. Installing the Supabase CLI and standing
up a local stack changed that, and `scripts/test-db.sh` now resets a database, applies all seven
migrations and runs the pgTAP suite in one command: **54 assertions across 3 files, all passing.**

`public.profiles` belongs to the main Nuvio project, not to the backend repository, so a fresh
database cannot apply migration 0001 unaided. `supabase/fixtures/` holds a local-only stand-in that
the script stages as a temporary first migration and always removes again, so it can never reach a
real project through `supabase db push`.

Executing the SQL immediately found a **blocking bug**. `party_change_broadcast_trigger` picked the
party id with a single CASE expression referencing both `new.id` and `new.party_id`. PL/pgSQL
compiles that assignment as one SQL expression and resolves every field in it against the real row
type regardless of branch, so inserting into `watch_parties` raised `record "new" has no field
"party_id"`. **Every watch party creation failed**, which also meant invites, joins and the whole
party flow were dead. `202609010007_fix_party_broadcast_trigger.sql` splits the branches so only the
field that exists on the triggering table is dereferenced.

The suite also grew beyond the original structural checks. `social_authorization.sql` drives real
`request.jwt.claims` through the RPCs to prove that profile impersonation is refused on every
mutation, that the two sharing toggles gate independently, that unfriending revokes feed access at
once, and that watched publishing is idempotent. `party_security.sql` pushes a payload containing a
stream URL, request headers, a debrid token and an addon key through `party_create` and asserts none
of it survives into the snapshot every guest receives, while the legitimate info hash still does.

### Two fixes found while verifying (2026-09-01)

`202609010006_sanitize_party_payloads.sql` adds a server-side key whitelist for
`watch_parties.content`, `.source_fingerprint` and `.quality_intent`. Those columns were free-form
`jsonb` written verbatim from client input, and `party_snapshot` fans the whole row out to every
member, so a buggy or hostile client could have published a resolved stream URL, request headers, an
addon credential or a debrid identifier to the party. The plan places that guarantee on the RPC
layer, not only on the client, and it is now enforced there, and `party_security.sql` proves it
end to end rather than only unit-testing the projection.

`generateInviteCode` drew from `kotlin.random.Random`, whose sequence is predictable once a few
outputs are observed. Party invite codes are a bearer credential, so both repositories now derive
them from `Uuid.random`, which is specified to use the platform secure generator on every target.
The 32-character alphabet keeps the five-bit mask uniform.

## Correcting the stale next-episode debug builds (2026-08-30)

The first 2026-08-30 debug builds came from `claude/release-0.5.0-beta-polish-ivcjsl`, whose merge
base predates the published bridge and the named upstream sync. The transition work was valid, but
the builds omitted the release-serial updater, About/version work, upstream merges and later Z
fixes. They are not promoted or merged wholesale.

Only the coordinated next-episode change is ported here onto `claude/upstream-doctrine-stage0`.
Manual Next episode actions now follow the active mode (Classic source list, Streamlined quality
sheet, Instant automatic pick); automatic transitions resolve without stopping the current
episode, count down with cancellation, ignore stale results and stay covered until the replacement
episode produces its first playable frame. The current failure chain, ranking preferences,
unwatched-artwork blur and desktop stream-settlement coordinator were retained during conflict
resolution. Desktop also carries the native HTML-overlay Next Episode control.

Verification and publishing: `scripts/run-pure-suites.sh` passes 290 tests in each repository;
focused `testAndroidHostTest` and `desktopTest` runs compile the real platform source sets and pass
the 16 new routing, transition and threshold tests. Mobile CI `33327792025` passes. Desktop's first clean CI run built and uploaded
the Windows MSI, then exposed a pre-existing Ubuntu job gap: the synced `compose-media-player`
module now builds a GStreamer shim during `desktopTest`, but CI installed no GStreamer development
packages. The job now installs the required core/base headers before Gradle, and replacement CI
`33328140034` passes. Debug publish runs `33328752860` and `33328752260` released mobile
`debug-v0.5.0-beta.25` from `921a62dc` and desktop `debug-v0.5.0-beta.18` from `d316a28e`.
Both tags resolve to those exact current-line commits. The behavior is not yet device-verified.

## The first named KMP upstream sync is complete (2026-08-24)

Mobile now contains upstream Nuvio `0.4.8` (`e27b9195`) via merge `33f368a5`, followed by the
compiler-led integration repairs at `2c24ffb7` and the two previously documented iOS portability
repairs at `21fd0d20`. Android run `32781826587` passed the host suite, built the debug APK and
uploaded it from the final commit. The merge keeps Z's download grouping, AIO request policy,
details actions, Continue Watching details affordance and next-episode safeguards while adding
upstream's tracking, app-icon, subtitle rendering, stream autoplay, P2P and cache-refresh work.

Desktop now contains NuvioDesktop `0.1.20-alpha` (`b32dd57b`) via merge `e649ff75`. The full local
desktop suite passed, and build-only run `32781339968` compiled the final tree, built the MSI,
verified it and uploaded it. Upstream Sentry credentials are optional in this fork: absent secrets
produce an explicit warning and skip only source-bundle upload, not compilation or packaging.

Mobile `0.4.9` and `0.4.10` both replace the monolithic app host with `MainAppContent.kt`. Merging
that split wholesale on top of Z's current host creates two application hosts, so it is deliberately
the next focused migration rather than an unsafe conflict choice inside this sync. Measured against
current upstream tips, mobile is now **266 ahead / 21 behind**, patch surface **138**, conflict
surface **7**; desktop is **193 ahead / 162 behind**, patch surface **144**, conflict surface **44**.

## The numbering bridge is published (2026-08-24)

Stable `0.5.0-beta+126` is live in both KMP repositories. It is the one-time bridge that ranks above
`0.4.14-beta` for old updaters while carrying the serial-aware updater needed for the later
`<vanilla>-z1` name. Mobile run `32777297537` published four signed ABI APKs from `6778a89f`;
desktop run `32777297995` published the verified Windows MSI and checksum file from `ee193661`.
Both tags resolve to those exact commits and both repositories return the bridge from
`/releases/latest`.




## KMP About names the vanilla base (2026-08-24)

Settings → About in both KMP repos now derives the vanilla base from the Z version name: a build
named `0.6.0-z2` shows `Based on Nuvio 0.6.0`, and a debug build `0.6.0-z2.3` names the same base.
The bridge (`0.5.0-beta`) and malformed/pre-scheme names show no base rather than inventing one.
The rule lives in the new import-free `core/build/NuvioZVersion.kt` instead of in updater or
platform code, with three tests per repo. Both `scripts/run-pure-suites.sh` runs pass: **287 tests,
0 failures** in each repo. Focused Gradle runs exceeded their bounded local runtime and were
stopped; Compose wiring remains a CI gate.

## Pending / Follow-up

### NEXT: make a download behave like a Netflix download

**This is the current priority, and it is the standard to hold the work to.** A
download in this app should be as boring and as certain as one in Netflix: you
start it, you can reorder it, pause it, resume it, close the app, lose the
network, come back tomorrow - and it either finishes or tells you plainly why it
cannot. No row that stops moving. No state only a restart can leave. Nothing that
needs the user to know what a debrid link is.

The harness in `NuvioZDesktop`
(`composeApp/src/desktopTest/.../DesktopDownloadQueueE2ETest.kt` and
`FaultyMediaServer.kt`) is where that gets proven. It now covers the local,
deterministic parts of items 1-3 below: queue controls under load and across a
repository reload, provider failures and controls during them, byte identity
across re-mint, and provider readiness immediately before transfer. The harness
was extended first and reproduced every production fault fixed in this pass.
The real-account and real connectivity-transition work in item 4 remains.

**1. The queue controls, under load - covered locally.** Every one of these
cancels a running transfer, and cancelling is what the stranding bug came out of.

- Reorder while transferring: move to top, up, down, to bottom; the promoted item
  starts at once and the preempted one keeps its `.part` file and resumes from
  where it stopped rather than restarting.
- Pause and resume, by hand, mid-transfer and mid-retry-backoff. A user pause is
  sticky - it must survive a queue nudge, a reclaim sweep and an app restart, and
  must never be undone by the recovery paths.
- Cancel and delete mid-transfer, including the last item and the only running
  one; files and `.part` files actually go.
- Reorder, pause and resume *while a fault is in flight* - during the re-mint
  round trip, during a backoff, in the window where a cancelled transfer is
  reporting its last word. That window is exactly where the fixed bug lived, and
  the other three controls reach it the same way the reclaim sweep did.
- Close and reopen: a queue that was mid-transfer comes back in the same order,
  from the same bytes, with user pauses still paused. `loadFromDiskLocked` has
  never been exercised against a queue in a real intermediate state.

**2. Provider failures - covered locally except a real connectivity observer
transition.** `FaultyMediaServer` and the re-mint stand-in now fail on demand:

- a link that expires *mid-transfer* rather than before it starts, at 20% and
  again at 90%;
- re-minting that fails once, then succeeds; that fails every time (the download
  must end `Failed` with a message a human can act on, not retry forever);
- a re-minted link that points at a *different or truncated* file - `If-Range`
  and the overrun/short checks should catch it rather than silently corrupting
  the `.part` file;
- the provider timing out or hanging rather than answering - re-mint runs off the
  lock while holding a slot, and nothing bounds it today;
- 429 and 5xx from the provider, and the whole account failing (every call 401)
  while a season batch is in flight;
- the network dropping entirely and coming back, which on desktop only
  `NetworkStatusRepository` reports.

**3. Cached-on-the-debrid, checked immediately before transfer - implemented and
covered through the provider seam.** This was the weakest link behind "download
queued" placeholders reaching the disk.

Today readiness is whatever the *addon* claimed at selection time
(`SourceFacts.isDebridReady` from `aio.debridCached` / `clientResolve.isCached`),
consulted once in `PresetSourceSelector` and only when `preferCachedSources` is
on. Nothing ever asks the provider directly, and nothing re-checks between
planning a season and reaching episode 9 an hour later. The placeholder check
(`isImplausiblySmallForMedia`) is the only real defence and it is *post-hoc* - it
downloads the wrong file first, then retries on a 1-to-10-minute backoff.

The queue now bypasses the resolver's fifteen-minute success cache and asks the
provider again **before every debrid transfer starts**. Not-cached sources wait
without touching the media URL, provider uncertainty retries with a visible
reason, dead accounts fail plainly, and a placeholder that arrives after a
successful check is still rejected. Cached, not cached, cached-then-evicted,
provider unsure, and post-check placeholder outcomes all have harness cases.

**4. Prove it against a real account - still pending.** The local server cannot imitate provider
quirks, which is where every fault so far has come from. Run the same queue
against TorBox with `NUVIO_DOWNLOAD_TEST_URLS`, and run a real season batch left
going long enough to cross the fifteen-minute link window - that is the only
thing that exercises re-minting for real, and it has still never been done.

Whatever this turns up: fix it in `nuvio-z` and mirror to `NuvioZDesktop`, keep
the harness green in CI on both, and record here what was covered and what was
found. A fault reproduced in the harness is worth more than a fix argued for in a
commit message.

### Preset/discovery work: code complete, release not cut

All five planned pieces have landed. `4ba89f7`/`59fa2ecb` carried the first
three; `55e8ccb` (nuvio-z) and `d74779f2` (NuvioZDesktop), both on
`claude/status-md-continuation-tkc41p`, carry the last two. What is done:

- Per-preset `sizePreference`: `Balanced`/`Quality` take the largest source that
  still fits the cap, `Saver` keeps taking the smallest. This reversed the old
  behaviour, which sorted size ascending and so picked the *smallest* under the
  cap.
- Per-preset `preferCachedSources` (default on). `SourceFacts.isDebridReady` is
  now its own tie-break below every quality key, so cached never costs a
  resolution tier, and an uncached debrid winner is sent to review instead of
  started.
- `PresetDownloadDialog` no longer awaits preparation or blocks dismissal.
- A Preparing section in `DownloadsScreen.kt`, above review, driven by batches
  with any entry still `DISCOVERING`/`RESOLVING`: artwork, title, a
  "Finding sources · 4 of 13" count, a progress bar and per-episode state. A
  batch is held *out* of the review section while it is still preparing, so the
  user is not asked to review a list that is still growing.
- `DownloadsLiveStatusPlatform.onBatchesChanged(batches)` with all four actuals
  (android and ios in both repositories, desktop in `NuvioZDesktop`), and an
  ongoing low-priority Android notification while any batch is preparing. It is
  called from every batch mutation as well as from `publishLocked`: preparation
  moves through `saveBatch`/`updateBatchEntry`, which never touch the item list,
  so hanging it off item changes alone would show nothing for the whole
  discovery pass.
- The unreachable in-dialog review branch is gone from `PresetDownloadDialog`,
  along with the `batch`/`error`/`approveUnknown` state and the `onQueued` and
  `onChooseManually` parameters behind it.

Remaining:

1. **Smoke-test preparation on-device.** Start a season batch and confirm the
   Preparing section fills in episode by episode, that the ongoing notification
   appears and clears, and that the batch moves to review or straight to the
   queue when discovery finishes.
2. **Check the desktop in-app update path.** `0.1.20-alpha` is installed on a
   Windows machine and launches with a responsive main window and no matching
   Application event-log crash. The actual `0.1.19-alpha` to `0.1.20-alpha`
   in-app update path has not been exercised.

### Latest release: CI verified, runtime testing pending

Two changes shipped in `0.3.8` / `0.1.21-alpha`. The merged release branches
passed Android host tests/debug assembly in run `30944119268` and desktop tests/
Windows MSI assembly in run `30944124462`. Publish runs `30944744977` and
`30944920882` then built and published the signed APKs and verified MSI. They
have not been runtime-smoke-tested. On 2026-08-04 the release was explicitly
approved without an Android device; device verification remains a post-release
follow-up.

The former `claude/status-md-continuation-tkc41p` branches are merged. The code
below is released from `main` / `Dev`.

#### (a) The two missing preset controls

`4ba89f7`/`59fa2ecb` added `preferCachedSources` and `sizePreference` to
`DownloadPreset` and wired them into `PresetSourceSelector`, but **never added
editor UI**, so they were stuck at their built-in defaults and the user could not
reach them. Added to `PresetSettingsCard` in `DownloadsSettingsScreen.kt`:

- a row that toggles `sizePreference` between `LARGEST_UNDER_CAP` and `SMALLEST`;
- a `Prefer cached sources` switch for `preferCachedSources`.

Four new strings in both `strings.xml` files:
`download_preset_size_preference`, `download_preset_size_largest`,
`download_preset_size_smallest`, `download_preset_prefer_cached`.

Both fields are already `@Serializable` on `DownloadPreset` and go through
`DownloadsRepository.updatePreset`, so persistence needed no change.

#### (b) Series page and Downloads page disagreeing (reported bug)

**Symptom.** Delete everything from the Downloads tab, then open the series page:
episodes still show download states - some "downloading", some "downloaded".

**Cause.** `buildTitleDownloadState` (`DownloadPresence.kt`) layers batch entries
underneath persisted items, items winning. The old `publishLocked` only synced an
entry when a matching item still existed (`?: return@map entry`), so deleting a
download left its batch entry frozen at `DOWNLOADING`/`COMPLETED`/`QUEUED`
forever. With the item gone the detail screen fell through to that stale entry.
The Downloads tab looked correct because it renders items, not entries.

**Fix as written.** A new pure `reconcileBatches(batches, items)` in
`DownloadBatches.kt`, called from both `publishLocked` and `loadFromDiskLocked`:

- an entry with a matching item follows that item's status, as before;
- an entry in an *item-backed* state whose item is gone becomes `CANCELLED`,
  which `toPresence()` already maps to `DownloadPresence.None`;
- a batch whose entries are now all `CANCELLED` is dropped entirely;
- `isItemBacked` covers `QUEUED`, `DOWNLOADING`, `PAUSED`, `COMPLETED` and
  **deliberately excludes `FAILED`**, because discovery failures and queueing
  failures land there with no item ever created, and those entries must stay in
  review so the user can still pick a source by hand. The trade-off: deleting a
  *failed* download leaves the episode reading as failed until the batch is
  dismissed. Left as-is on purpose; revisit only with a way to tell the two
  failures apart.

Calling it from `loadFromDiskLocked` is what heals **installs that are already
broken**, including the reporter's device - it reconciles on the next launch
rather than waiting for the next queue change. That path also had to widen its
persist condition to `normalized != stored.items || reconciledBatches !=
stored.batches`.

`DownloadBatchReconcileTest` (8 tests) covers the delete cases, the `FAILED`
carve-out, idempotence, and the empty-batch case. It ran successfully in both
CI suites above.

#### Next steps, in order

1. **Smoke-test the bug fix when a device is available**, because this is a
   persistence fix and no test touches real storage: queue a season, let some
   episodes finish, delete everything from the Downloads tab, reopen the series
   page and confirm every episode reads as not downloaded; then force-stop,
   relaunch, and confirm it still does.
2. **Exercise the desktop updater** from the installed `0.1.20-alpha` to
   `0.1.21-alpha`; merely launching `0.1.20-alpha` did not verify replacement.


- No Gradle task can configure in this sandbox: `dl.google.com` is denied by
  the egress policy, so the Android Gradle Plugin never resolves. CI is the only
  compiler available here, which makes each fix a full release-run round trip.
  Run `.\gradlew.bat :composeApp:testAndroidHostTest` locally to get the host
  suite, including the new `DownloadPresenceTest`, actually executed.
- The download transfer/queue rework **compiles** - CI built and published
  `0.3.6` from it - but its behaviour is still unverified. Only the two new
  pure-logic files have executing tests (see Verification); the repository, the
  three platform downloaders and the screen have never been run. Run
  `.\gradlew.bat :composeApp:testAndroidHostTest` locally to execute the host
  suite, which CI's assemble-only release job never runs.
- Smoke-test the reworked transfers on-device with a deliberately small file:
  pause/resume mid-transfer, resume after the source URL has expired (must not
  report a completed download at the partial size), process death mid-transfer,
  background/foreground on iOS, and a season batch to confirm E01 starts first and
  that "Download next" preempts.
- The unwatched-season download work has **not** been compiled or tested in this
  environment either: the sandbox blocks `dl.google.com`, so the Android Gradle
  Plugin cannot be resolved and no Gradle task can configure. Run
  `.\gradlew.bat :composeApp:testAndroidHostTest` and an `assembleFullDebug`
  locally before trusting it.
- Smoke-test the unwatched season download on-device: open a partly watched
  season, use the season download menu, and confirm only the current episode
  onwards is queued.
- Smoke-test the downloads redesign on-device: confirm the Downloads tab appears
  in the classic, adaptive and tablet nav bars; queue one small episode and check
  that the episode card ring, the tab's “Downloading now” row, and pause/resume
  stay in sync; confirm the “Downloaded” section appears on the entry once the
  transfer completes and disappears after deleting.
- `onBatchesChanged` is a no-op on iOS and desktop. The iOS bridge publishes one
  live item to Swift and a second payload needs matching Swift work; desktop has
  no notification surface at all. Both show preparation in the Downloads tab.
- A batch cannot be cancelled while it is preparing, on any platform. See the
  Work Log entry for why the obvious button would lie.
- The iOS Downloads tab currently falls back to the `arrow.down.circle.fill` SF
  Symbol. Add a `NuvioTabDownloads` xcasset to match the other tab icons.
- Existing profiles get the new meta-screen “Downloaded” section appended last in
  their saved section order, because `normalizePreferences` sorts unknown keys to
  the end. New profiles get it right after Actions.
- The local workspace directory is still named `stremio-z`; renaming it is
  deferred.
- Run the full host suite again after the next substantial code change.
- Test a real transfer end-to-end, including pause/resume, process death,
  network constraints, and cap-crossing approval, using a deliberately small
  file.
- Review lifecycle/cleanup for prepared batches dismissed from the review
  dialog so cancelled all-ready batches do not remain as hidden persisted
  records.
- Trakt functionality requires local client credentials and has not been
  reconfigured for this personal build.
- iOS parity gaps in the preset download feature, all in platform seams:
  `freeStorageBytes()` returns `-1` so low-space warnings and
  storage-triggered review never fire; `allowMeteredNetwork` is ignored
  because the iOS session hardcodes cellular access; downloads pause on
  app background because iOS uses a foreground `NSURLSession`.
- `DownloadsStorage.ios.kt` no longer profile-scopes its payload key,
  unlike every other iOS storage and unlike the desktop fork. Decide
  whether that de-scoping was intended.
- Desktop CI cannot be verified from a sandbox that blocks `dl.google.com`;
  the Android Gradle Plugin will not resolve there.
- `0.3.6` (versionCode 105) is released from `main` and is the first build to
  carry the download transfer/queue rework. `assembleFullRelease` succeeded, so
  the merged redesign and rework compile together; nothing in the rework has
  been exercised on a device yet.
- Queue reordering has a known rough edge: the needs-attention section is
  filtered out of the queue list, so a Move up/down that would swap with an
  attention item looks like it did nothing. "Download next" is unaffected.
- `Zokaper/nuvio-z` is public, which the unauthenticated updater requires.
  `0.3.7` (versionCode 106) is the current release; `0.3.6`, `0.3.5`, `0.3.4`
  and `0.3.3` precede it. All carry signed APKs for all four ABIs.
- CI release signing is stable: `0.3.3`, `0.3.4` and `0.3.5` all carry signer
  certificate SHA-256
  `2325A3399F9BBF5ECE1391EBE6B5A0E0F016058520FB1597B1CF30CF6184787C`.
  A locally built APK signed with a different keystore cannot be updated over
  by these releases, and Android reports only "App not installed". The installed
  build's version identifies which key it carries, because `0.3.3` and later
  exist only as CI output.
- The earlier "App not installed" in-app update failure is **resolved**: the
  in-app update from `0.3.5` to `0.3.6` succeeded on the Samsung device. It was
  the signing-key mismatch rather than Auto Blocker - once the installed build
  came from CI, later CI-signed releases update over it cleanly. A locally built
  APK still cannot be updated over by a CI release, so a local build has to be
  uninstalled first.
- `NuvioZDesktop` desktop releases are now Windows-only. Every macOS job failed
  at "Configure desktop runtime" because the repository holds none of the Apple
  signing and notarisation secrets it requires, so the target choice was
  narrowed to `windows`; the macOS job is still in the workflow behind a guard
  that can no longer match. Restoring macOS means adding the secrets and
  putting the options back.
- Compiling the desktop mirror for the first time found that the redesign added
  a `downloads` parameter to the `publishNativeTabTitles` expect and updated the
  Android and iOS actuals but not the desktop one. Fixed in `NuvioZDesktop`.
  A Windows build of the pre-redesign commit compiles, which is what identified
  the redesign mirror rather than the transfer rework as the source.
- The desktop Windows job now runs `compileKotlinDesktop` as its own step
  without `--stacktrace`, because packaging with it buried the compiler's `e:`
  lines under roughly 250 lines of Gradle internals.
- `NuvioZDesktop` compiles and produces a verified MSI in CI. `0.1.20-alpha` is
  the current release and `0.1.19-alpha` (2026-08-03) precedes it, each carrying
  one Windows x64 MSI and a `SHA256SUMS.txt`. `0.1.20-alpha` is installed and
  launches on Windows; the in-app replacement flow is still untested.
