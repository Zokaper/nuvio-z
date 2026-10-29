# Watch Together guest EOF and connection outage

Two different clients reported these faults on desktop stable `0.1.31-alpha-z1`.
The earlier guest-source repairs in Debug 137 were not installed. There are no client logs.

## Guest opens at the end

The same client saw this both during the earlier source-matching failure and again in another
party. Swapping hosts worked around it. The historical file identity, file duration and host
position are unavailable, so the exact trigger is not established.

The code does contain an unguarded route to EOF. `WatchPartyLobbyDestination` initially supplies
an extrapolated party position. Before rendering the player, `applyWatchPartyStartPosition`
replaces that with the party snapshot's raw position. Neither is checked against the guest's
file duration before `PlayerScreenRuntimeUi` passes it to the desktop native surface. The mpv
bridge forwards it as the load command's `start` option. A position beyond a shorter local file
therefore reaches the engine before `partyPositionInThisFile` can reject it. That existing guard
protects subsequent synchronization seeks, not the native load command. The ordinary bookmark
effect also recognized only upstream's older Party implementation, rather than Watch Together.

Guests now open parked without a native offset or personal bookmark. Watch Together performs
the initial alignment after the local duration is available, using its existing rejection of
unreachable targets. The native boundary and bookmark effect apply the same guest ownership rule
to in-place source changes. Hosts retain their recorded start position; unrelated parties
do not own another playback. An unreachable cut still needs a usable source; the repair prevents
it being silently loaded at EOF. This is additional to Debug 137's source realization fixes.

## Disconnect, then the whole app stops loading

The screenshot shows a connection timeout to the Z project's PostgREST RPC endpoint, rather than
a response rejecting membership or a token. The maintainer later reported that speedtest.net
also failed to resolve on that client's network, and that Social eventually recovered without
an app repair. This supports an external reachability/DNS problem; it does not identify the
specific resolver, route or host that failed.

Trace findings:

- `ZSupabaseProvider` is separate from the official `SupabaseProvider`. `ZSessionBridge` neither
  loads nor saves a Z session to disk. Party realizations and the capabilities-loaded flag are
  also process-local. No persistent "Social disabled" flag is written after a timeout.
- Watch Together does not select/reset the official server configuration, install a global proxy,
  clear the official login, or route addon HTTP calls through the Z backend. The addon HTTP
  boundary remains independent. No cross-service state mutation explaining the reported whole-app
  failure was found. This is a source trace, not a reproduction on the affected machine.
- There are nevertheless two app-side recovery defects: a failed capability check reset Social
  to default `socialEnabled=false`, and the screen described that as a confirmed server disable.
  Recovery depended on reopening the screen or a new account/connectivity event. A temporary
  outage that ended while the network still read online could therefore leave a stale surface.

Capability failures now retain known capabilities and report a connection error on a cold start,
with Try again. A bounded-cadence recovery loop retries stale activation every 30 seconds, without
requiring an official-backend connectivity probe to succeed. An actual successful response saying
Social is disabled remains disabled. Existing profile authority/cancellation fences remain in use.

## Verification

Desktop focused validation passes: 500 tests / 52 suites, zero failures/errors/skips, Gradle exit 0 (`build/wt-eof-social-final.log`). The phone and desktop Social connection-error renders were inspected. Android focused validation also passes: 496 tests / 52 suites, zero failures/errors/skips, plus `:androidApp:compileFullDebugKotlin` (`build/wt-eof-social-mobile-final.log`, Gradle exit 0). The temporary empty build input was removed. Regression
cases exercise the actual player runtime with a host position beyond a synthetic guest duration,
and the actual Social repository with a failing MockEngine capability request followed by the
production recovery timer under virtual time. No backend/schema/configuration change, physical
two-client acceptance or stable publication is claimed.

Native engine reproduction: `python scripts/test-watchparty-native-start.py` in desktop loads a synthetic 2-second Y4M video through the shipped Windows mpv DLL and the exact bridge load options. `start=5` sets the actual `eof-reached=yes` property at position 2 s under the bridge's `keep-open=yes` setting; omitting the start offset leaves the file parked at 0 s with `eof-reached=no`. End-file load errors are rejected separately. Both assertions pass. This proves the unsafe load-time mechanism, not the historical client file identity.
