# Watch Together — handoff (2026-09-02)

Pick this up cold. It states what is fixed and proven, what is still broken with the evidence, and
the two open bugs in priority order. Everything below is desktop (`NuvioZDesktop`); mobile cannot
run a party at all (see §6).

## 0. Codex continuation — 2026-09-02

The open desktop items in §3b, §4, B1, B2, B3 and B4 are now addressed on
`NuvioZDesktop` branch `codex/next-episode-debug-hotfix` in `d6f2e440`; debug counter commit
`2383ac75` published them as `debug-v0.5.0-beta.34` (workflow `33611263629`):

- Guest buffering holds have a 12-second safety deadline keyed to party generation and command
  sequence, deliberately ignoring heartbeat-only `state_updated_at` churn. A stuck guest then
  resumes until authoritative state advances. The transient banner says `Host is buffering`, not
  `Waiting for the host to start`.
- The native-fallback double tap, native scrub bar, horizontal seek gesture and skip interval paths
  now submit party seeks. The Compose scrub bar already did; play/pause, seek-by and speed were
  already covered.
- Both Leave and End clear the local party/poll/channel before attempting a bounded background RPC,
  so neither a dead network nor a failed session refresh can strand the client.
- Expected-position arithmetic uses `Double`, retaining millisecond precision on long content.

Verification: `:composeApp:compileKotlinDesktop` passes; focused `WatchPartyModelsTest` passes 14/14;
full `:composeApp:desktopTest` passes 1,318/1,318 with no failures or skips. Still device-only: prove
the host reaches `playing` with an advancing position, prove every host control increments `sequence`,
hold one guest on an unchanged `buffering` sequence for over 12 seconds, and leave/end with the
network down before immediately creating a new party.

---

## 1. Where things stand

The original report was "about five seconds of sync latency, both in commands and in stream
position". **The propagation half is fixed and measured.** From the most recent session log:

```
10:38:48.499  broadcast party=72e36397 seq=1 status=buffering ageMs=226 applied=true
10:38:53.650  broadcast party=72e36397 seq=1 status=buffering ageMs=222 applied=true
10:38:58.807  broadcast party=72e36397 seq=1 status=buffering ageMs=222 applied=true
```

`ageMs` is `now + serverClockOffsetMs - payload.server_time`, so end-to-end broadcast propagation is
now **~225ms**, against a measured RTT of 284ms. It was 0–5000ms.

The remaining work is §3 (fixed, needs on-device confirmation), §3b and §4. None of it is about
propagation.

---

## 2. What was fixed, and why (do not re-litigate these)

### The root cause of the five seconds

On desktop the play/pause button and the spacebar both arrive at
`prepareTogglePlaybackForNativeFallback` (`PlayerScreenRuntimeGestureActions.kt`), which flipped
`shouldPlay` and returned — the native controls layer performs the transport itself.
`togglePlayback` is the only function that ever called `submitPartyPlayPause`, and **nothing on
desktop calls it.** So a host pressing pause sent no command: `party.sequence` stayed put all
session, and the change reached other members only when the next 5s heartbeat happened to carry the
new status. `prepareSeekByForNativeFallback` had the identical omission.

Both now submit. **The regression test for this is `seq` climbing** — every host pause/play/seek must
bump it. If a future log shows `seq` frozen while `status` changes, this has regressed.

### The rest, shipped in `9025a047`

| Change | File | Why |
|---|---|---|
| Native-fallback toggle/seek submit commands | `PlayerScreenRuntimeGestureActions.kt` | the root cause above |
| Status reports *intent*, not momentary state | `PlayerWatchPartyEffect.kt` | a starved host reported `paused`, publishing a deliberate pause nobody made |
| Status change publishes within a round trip (250ms debounce) | `PlayerWatchPartyEffect.kt` | floor under the command path |
| `buffering` branch holds position instead of seeking | `PlayerWatchPartyEffect.kt` | `expectedPartyPositionMs` freezes for non-playing status, so the 500ms realign test passed on every host stutter |
| Proportional ±10% nudge; band to 4s; blocking 10s hold removed; seek leads by 2.5s | `WatchPartyModels.kt` | fixed 1.03× recovered 300ms per 10s against a 2,500ms band, so everything escalated to a seek |
| Broadcast payload applied directly; refreshes coalesced | `WatchPartyRepository.kt` | removed a `party_snapshot` RPC from the critical path |
| `leave()` always clears local state | `WatchPartyRepository.kt` | §4 below |

Server: `202609020001_party_broadcast_state.sql`, **applied to `pzbpghmmordvzcfbayoh`**. Puts
`sequence`, `status`, `position_ms`, `playback_speed`, `state_updated_at` and `server_time` in the
payload (projected field by field — never `to_jsonb(new)`, that row carries `invite_code_hash`), and
splits the member trigger so a bare `last_seen_at` bump broadcasts nothing. That bump was 2,336 of
2,800 broadcasts (83%) in a real session.

---

## 3. FIXED, NEEDS ON-DEVICE CONFIRMATION — the host latched `buffering` forever

**Symptom as reported:** "we both loaded in, both started playing, then about two seconds later it
says waiting for host on his client again, and mine continued."

**Evidence — the host's own log, the user hosting this time.** For minutes on end, every heartbeat
republishes the same thing:

```
10:52:10.511  state viewer=d3397924 party=7dd29ae2 status=buffering seq=2 positionMs=10244 host=d3397924
              members=[d3397924:host:ready:up, f662ef78:participant:ready:up]
10:52:15.751  state ... status=buffering seq=2 positionMs=10244 ...
10:52:20.985  state ... status=buffering seq=2 positionMs=10244 ...
```

`updatedAt` advances every ~5s, so the host *is* heartbeating; `status` and `positionMs` never move.

**Mechanism — a regression introduced by the intent-aware status change.** `reportedStatus` was
computed in the composable body and read inside

```kotlin
LaunchedEffect(generationKey) { while (true) { ... status = reportedStatus ...; delay(5s) } }
```

That effect is keyed **only on the generation**, so the value is captured from the composition pass
that launched it and never updated. The loop launches the moment playback starts, when
`shouldPlay` is true and `isPlaying` has not turned true yet — which maps to `buffering`. The host
therefore latched `buffering` and republished it every five seconds for the rest of the session.

`partyPlaybackGate` turns *any* non-`playing` status into `WAITING_FOR_HOST` for every non-host:

```kotlin
if (party.status == WatchPartyStatus.playing) return allow
if (party.hostProfileId != viewerProfileId) return WAITING_FOR_HOST
```

So the guest sat on "waiting for host" indefinitely while the host played on. Both halves of the
report, one cause.

**Fixed** by deriving the status inside each heartbeat from the snapshot that pass just read, via a
top-level `partyStatusFor(snapshot, shouldPlay)` in `PlayerWatchPartyEffect.kt`. The
composable-level `reportedStatus` now serves only as the key for the immediate-publish effect.
Compiles; **not yet confirmed on device.**

⚠ **The general trap:** `LaunchedEffect(generationKey)` runs for the life of a party. Anything it
closes over from composition is frozen at launch. `playbackSnapshot` is safe because it is re-read
from the runtime each pass; a plain `val` computed in the composable body is not.

**Confirm it is fixed:** host a party, start playback, and watch the host log. `status` must reach
`playing` and `positionMs` must advance. If `status=buffering` with a frozen position repeats, this
has regressed.

## 3b. STILL OPEN — a frozen party has no recovery

Independent of §3, and seen in the earlier 10:37 session where the *guest's* host abandoned a failed
source: `party_heartbeat` only writes position and status when the caller is the host **and**
`p_position_ms` is not null (`202609010003_watch_party_rpc_and_realtime.sql:96-100`). The
repository's poll heartbeat sends no position, so once a host's *player* stops heartbeating the row
freezes at its last value and every guest obeys it indefinitely — four minutes of
`hold seq=1 status=buffering localMs=10160 expectedMs=16400` in that log.

`applied=false` on the broadcasts afterwards is **correct** — the monotonicity guard rejecting
payloads whose `(sequence, state_updated_at)` has not advanced. Symptom, not cause.

Candidate fixes, preferred first:

1. **Give `buffering` a deadline on the guest.** If the party has been `buffering` with an unchanged
   `sequence`/`state_updated_at` for ~10–15s, stop holding: resume at the extrapolated position, or
   surface a banner. A party's buffering state must never pause someone indefinitely. This is the
   safety net that would have contained §3 too.
2. **Expire a stale host server-side.** `party_heartbeat` already flips members to
   `connected = false` after 15s (`…0003…sql:90-91`); when the *host* goes disconnected while
   `status = buffering`, move the party to `paused`.
3. **Soften the gate.** `WAITING_FOR_HOST` for any non-`playing` status is very blunt — a host
   buffering for 300ms should not read as "waiting for host" at all.

The host-claim path does eventually fire (`host … gone past grace, claiming`), but it is gated on the
15s *connection* grace, which a host that is still connected but stuck never trips.

## 4. OPEN BUG 2 — leaving a broken party strands the client (partly fixed, unverified)

**Symptom as reported:** a friend's source failed; trying to leave "kept dragging us back to the
details screen of the show we were watching even if we clicked on another show", creating a new
lobby did nothing, and both clients had to be restarted.

**Mechanism, and what has been changed.** `WatchPartyRepository.leave()` had the RPC and the local
teardown inside one `runCatching`:

```kotlin
if (party != null && profile != null) ZSupabaseProvider.client.postgrest.rpc("party_leave", ...)
stopPolling(); stopChannel(); clockOffsetPartyId = null
_uiState.value = WatchPartyUiState(activeProfileId = profile)
```

If `party_leave` threw — an expired session, a dropped connection, exactly the conditions under
which someone abandons a broken party — the three teardown lines never ran and the party stayed held
in `_uiState`. The lobby route then read that held party and re-navigated to its content on every
attempt, and `create()` could not start a new one because one was already held.

**Changed:** the RPC is now wrapped in its own `runCatching`, so the local teardown always runs.
Telling the server is best effort; forgetting the party locally is not. Compiles;
**not verified on device.**

**Still to check — this fix may be incomplete:**

- `end()` has the same shape and is routed through `call { }` with `requireParty()`. If the
  `party_end` RPC throws, the same stranding applies to the host. Give it the same treatment.
- The re-navigation itself. Find the lobby route effect that reads a held party and sends the user
  to its content (`WatchPartyLobbyScreen.kt`, the `handoff` and `open route` log lines). It should
  ignore a party the user has explicitly left, and should not outrank an explicit navigation to a
  different title. Commits `b1f3149b` and `10b3c657` already circled this area; a third fix there
  should probably be a rethink of who owns that navigation rather than another guard.
- Reproduce deliberately: join a party, kill the network, press Leave, restore the network. The
  client must end up with no party held and must be able to create a new one.

---

## 5. How to verify anything here

**Build and test**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export ANDROID_HOME="/c/Users/Rayoa/AppData/Local/Android/Sdk"
./gradlew :composeApp:compileKotlinDesktop --console=plain --max-workers=4   # ~2 min
./gradlew :composeApp:desktopTest --console=plain --max-workers=4            # ~11-13 min
```

`WatchedItemsStoreTest > concurrent updates publish coherent item snapshots` **fails at HEAD on its
own**. It is a known flake, unrelated to this work — 1,317 tests, 1 failed is the expected result.

**Backend**

```bash
cd nuvio-z-backend && ./scripts/test-db.sh    # needs Docker; 75 assertions, all must pass
supabase db query --linked "..."              # reads against production are fine
```

`supabase db push --linked` is **refused by the auto-mode classifier** — verify locally, then ask
the user to run it. Reads and the whole local stack are allowed.

**Logs — this is the fastest route to a diagnosis**

Desktop debug log: `%APPDATA%\Nuvio Z Debug\logs\nuvio-debug-*.log`, needs
`-Dnuvio.debugTools=true` (the debug channel build sets it). Tags `WatchParty` (transport) and
`WatchPartyPlayer` (what the client decided to do).

| Line | Tells you |
|---|---|
| `broadcast … ageMs=… applied=…` | propagation latency; `applied=false` means the monotonicity guard dropped a stale payload |
| `state viewer=… seq=… status=… positionMs=… members=[…]` | the authoritative party state, logged on change |
| `drift seq=… driftMs=… action=…` | whether a guest is converging; repeated `SEEK` is a loop |
| `hold seq=… status=…` | the guest is deliberately not playing — bug 1 shows as this repeating unchanged |
| `command party=… type=…` | **a host action reached the server.** Its absence was the original bug |
| `realtime … state=connected\|disconnected` | whether the channel is up |

**Two-device run.** Test *both* directions — host→guest and guest→host run different code paths and
only one has ever been exercised. Compare the two logs side by side; a party disagreement is only
ever diagnosable that way.

**Release.** Bump `DEBUG_BUILD` in `composeApp/Configuration/DesktopDebugVersion.properties` (the
workflow refuses an existing tag), push, then
`gh workflow run desktop-debug-release.yml --ref <branch>`. Publishes Windows x64 + macOS arm64.
Latest is `debug-v0.5.0-beta.33`.

---

## 6. Traps that have already cost time

- **Watch Together is desktop-only.** `nuvio-z` is the mobile KMP client; its `SupabaseConfig.URL`
  is `https://api.nuvio.tv`, the official upstream backend, which hosts none of the `party_*` RPCs.
  Mobile has no `ZSupabaseProvider` and no `ZSessionBridge`. "The debug build" always means
  NuvioZDesktop. The shared fixes were carried into `nuvio-z` as a head start (`ae62ec18`) and are
  explicitly unrun.
- **Never deploy to `api.nuvio.tv`.** Z owns `pzbpghmmordvzcfbayoh` only. See **The Two Backends** in
  `nuvio-z/AGENTS.md`.
- **Desktop files are CRLF, mobile files are LF.** A patch script that normalises will rewrite the
  whole file. Read and write with the repo's own line endings.
- **Broadcast rows in `realtime.messages` prove the server sent something, not that a client
  received it.** Only the client log settles delivery.
- **The fixture separates auth-user ids from profile ids.** `party_*` RPCs take *profile* ids
  (`11111111-…`), not the `aaaaaaaa-…` auth users that own them. This cost a pgTAP debugging round.
- Several `realtime.send` calls in one transaction share its timestamp, so
  `order by inserted_at desc, id desc limit 1` picks an arbitrary row. Assert over the topic, not
  over "the newest".

---

## 6b. Bug sweep — found by reading, not yet reproduced

Confidence is stated for each. **Confirmed** means the code plainly does this; **likely** means the
reasoning is sound but it has not been run. Nothing here is fixed.

### B1 — double-tap seek never reaches the party (confirmed, same class as the root cause)

`PlayerScreenRuntimeGestureActions.kt:248-251`:

```kotlin
    if (sendToController) {
        playerController?.seekTo(targetPositionMs)
    submitPartySeek(targetPositionMs)
    }
```

`submitPartySeek` is **inside** the `if`, and `handleDoubleTapSeek(direction, sendToController = false)`
at `:218` is the native-fallback caller — the same path the play button and spacebar take. So a host
double-tapping to skip moves only itself. The misindentation is how it hid; treat the indentation as
the tell, not the cause.

This is the third instance of one pattern: **the native controls layer performs the transport, and
the Kotlin side forgets to tell the party.** Audit every `PlayerControlsAction` branch in
`PlayerScreenRuntimeUi.kt` that returns `false`, and every `prepare*ForNativeFallback`, against the
matching non-fallback function. Two were fixed (toggle, seek-by); this is a third; there may be more
— speed changes and the scrub bar are the obvious candidates to check next.

### B2 — `end()` strands the host the way `leave()` used to (confirmed)

`WatchPartyRepository.end()` still has the pre-fix shape: `party_end` RPC and the local teardown in
one `call { }`. An RPC failure leaves the party held, with the same consequences as §4. Give it the
same treatment.

### B3 — the gate reads any non-`playing` status as "waiting for host" (confirmed)

`partyPlaybackGate` in `WatchPartyModels.kt`:

```kotlin
if (party.status == WatchPartyStatus.playing) return allow
if (party.hostProfileId != viewerProfileId) return WAITING_FOR_HOST
```

A host buffering for 300ms therefore shows every guest "waiting for host" and pauses them. It needs
either a grace period before the gate closes, or a distinction between "the host has not started"
and "the host is momentarily buffering". This is what made §3 so visible, and it will make any
future status glitch equally visible.

### B4 — `expectedPartyPositionMs` loses precision on long content (likely)

`WatchPartyModels.kt`: `(statePositionMs + elapsed * playbackSpeed).toLong()`. `playbackSpeed` is a
`Float`, so the whole expression promotes to `Float` — 24 bits of mantissa, exact only to
16,777,216. Beyond ~4h39m of runtime the position quantises to 2ms steps and grows from there, which
starts to interact with the 750ms dead-band and the 500ms realign test. Extended cuts, concert films
and multi-part features reach that. Compute in `Long`, or in `Double`.

### B5 — a guest pausing under `host_only` is silently overridden (confirmed)

`submitPartyPlayPause` returns early when the viewer is not allowed to command. The guest's local
pause therefore happens, propagates nowhere, and is undone by the next drift correction with no
explanation. Either disable the control in `host_only` for non-hosts, or surface "only the host can
control playback".

### B6 — `applyBroadcastState` never refreshes the roster (likely, latent)

It copies `held.members` unchanged. Correct *today* only because member-row broadcasts carry no
`sequence` and so fall through to `refresh()`. That is an implicit coupling between the client guard
and the trigger's payload shape: if the member trigger ever gains a `sequence`, the roster silently
freezes. Make the fallthrough explicit — key on `reason`, not on a missing field.

### B7 — a coalesced refresh can be dropped between channels (likely, low impact)

`refreshRequests` is an object-level `MutableSharedFlow(replay = 0)`, but `refreshJob` is cancelled
in `closeChannel()` and recreated in `openChannel()`. An emit landing in that window has no
collector and is discarded. The 5s poll covers it, so the effect is a delayed update rather than a
lost one.

### B8 — a `lobby` snapshot yanks a guest to position 0 (likely)

The guest hold branch treats `lobby` like `paused` and realigns when more than 500ms out. A fresh
lobby has `positionMs = 0`, so any snapshot that arrives with `status = lobby` while a guest has a
real position seeks them to the start. Reachable via `party_change_content`, which sets
`status = 'lobby'` and `position_ms = 0`.

### B9 — a brief network blip can trigger host transfer (likely)

`party_heartbeat` marks peers `connected = false` after 15s of silence, and outside the player the
only heartbeat is the 5s poll. Three consecutive failed polls — a short blip — is enough to mark a
host down, and `party_claim_or_transfer_host` then moves the crown after its own 15s grace. The
margin is three ticks; consider requiring more, or distinguishing "no heartbeat" from "heartbeat
failed".

### B10 — `hostStartReleased` latches for the generation (likely)

Once the host's start gate is released, `partyPlaybackGate` returns allow for the host regardless of
participant readiness. A participant who later drops back to `resolving` — which the logs show
happening — leaves the host playing on with no indication anyone has fallen out.

### B11 — duration compatibility is loose enough to admit a different cut (by design, but unhandled)

`arePartyDurationsCompatible` allows `max(90_000L, 2%)`. Two cuts of one film can pass, after which
the party drifts permanently and the guest has no way to say "my file does not match yours". Worth a
mismatch banner even if the tolerance stays.

### Mobile-only

`nuvio-z`'s host-claim effect calls `claimHostAfterGrace()` immediately rather than after
`WatchPartyHostGraceMs`, so it is rejected with `host_grace_active` on every snapshot. Desktop has
the delay. Harmless but noisy, and it will matter when mobile is wired up.

## 7. Commits

| Repo | Commit | What |
|---|---|---|
| `NuvioZDesktop` | `9025a047` | the sync fixes in §2 |
| `NuvioZDesktop` | `b7dc9370` | `DEBUG_BUILD` → 33 |
| `NuvioZDesktop` | `d9fb00a5` | the `leave()` fix in §4 — compiles, untested on device |
| `NuvioZDesktop` | `4b287aa1` | the latched-`buffering` fix in §3 — compiles, untested on device |
| `nuvio-z` | `ae62ec18` | shared half, head start, unrun |
| `nuvio-z-backend` | `a5e7b84` | migration + 4 pgTAP assertions; **applied to production** |

Branch `codex/next-episode-debug-hotfix` in both KMP repos, `master` in the backend.
