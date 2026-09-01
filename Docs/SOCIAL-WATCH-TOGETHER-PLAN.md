<!-- Recovered 2026-09-01 from the Codex plan-mode session that produced this work.
     Source of truth for the Social + Watch Together implementation. Do not re-plan from scratch. -->

# Nuvio Z Social Features and Watch Together Plan

## Summary

Build two sequential, feature-gated releases across `nuvio-z`, `nuviozdesktop`, and a new private `nuvio-z-backend` repository:

1. **Social foundation:** profile handles, mutual friends, friend requests, Watching Now, permanent recent activity, Home rows, and a Social tab.
2. **Watch Together:** private sessions for up to eight signed-in profiles, source matching with safe independent resolution, readiness lobby, synchronized playback, invites, and host transfer.

Letterboxd and NuvioZWeb are explicitly excluded. Trakt/Simkl history will not create social activity in this release.

## Implementation Changes

### 1. Backend source of truth and security

- Create a private `nuvio-z-backend` repository containing versioned Supabase migrations, RPC definitions, RLS policies, pgTAP tests, and deployment documentation. No client secrets belong in it.
- Use the stable `profiles.id` UUID as social identity. Never use the reusable `profileIndex`.
- Add:
  - `social_profiles`: profile UUID, unique handle, two sharing toggles, timestamps.
  - `friend_requests`: sender, receiver, pending/accepted/declined state, timestamps.
  - `friendships`: normalized mutual profile pair and creation time.
  - `watch_presence`: one row per profile/device playback session with safe media metadata, state, position, duration, speed, and heartbeat.
  - `social_activity_events`: atomic watched events with stable origin key, safe media metadata, timestamp, and server-assigned consecutive-run UUID.
  - `watch_parties`, `watch_party_members`, `watch_party_invites`, `watch_party_state`, and idempotent party commands.
- Deny direct client table mutation. Expose authenticated RPCs that verify `auth.uid()` owns the supplied profile UUID.
- Friends may read presence/activity only when the corresponding sharing toggle is enabled. Removing a friendship revokes access immediately.
- Expose no email, stream URL, request header, addon credential, debrid identity, or provider token through social RPCs.
- Add duplicate suppression and server-side throttling for handle search, requests, invites, and party joins.
- Account/profile deletion cascades through all social and party data.

### 2. Social profiles, friends, and feeds

- Opening Social for the first time prompts the active non-anonymous profile to choose a handle without blocking the rest of the app.
- Handles are 3–24 lowercase ASCII characters using letters, numbers, and underscores; comparison is case-insensitive. Suggest a handle from the profile name with a random suffix when needed.
- Support:
  - Handle search.
  - Shareable friend-request deep links.
  - Send, cancel, accept, decline, and remove friend.
  - Mutual friendship only; no followers or blocking in this release.
- Both sharing controls default on for accepted friends:
  - Share Watching Now.
  - Share Recently Watched.
- Social tab layout:
  - Inbox banner and unread badge for requests and party invites.
  - Watching Now section.
  - Permanent paginated activity feed.
  - Friends/request management.
- Selecting a friend filters the activity feed to that profile; there is no dedicated friend-profile screen yet.
- Notifications are in-app only. Persist unread state and update it through Realtime while the app is running; do not add OS push infrastructure.
- Hide empty social rows on Home. Social itself shows setup, add-friends, offline, and no-activity states.
- Cache the last successful feed locally for read-only offline display. Friendship mutations require connectivity.

### 3. Watching Now and recent activity

- Add Supabase Kotlin Realtime to both KMP dependency sets and install it in each repository’s Supabase client.
- Use authenticated private channels:
  - `social:<viewer-profile-uuid>` for feed/request invalidations.
  - `party:<party-uuid>` for party state.
- Authorize private Broadcast/Presence through RLS on `realtime.messages`; clients always perform an RPC refresh after initial subscribe and reconnect. This follows the supported [Supabase private-channel authorization](https://supabase.com/docs/guides/realtime/authorization) and [Kotlin channel Flow](https://supabase.com/docs/reference/kotlin/subscribe) model.
- Realtime social messages carry only invalidation identifiers; clients obtain authorized content through RPCs.

**Watching Now**

- Publish only while the player screen is open and playing or paused.
- Heartbeat every 20 seconds, immediately after start, pause, resume, seek, episode/source transition, and foreground recovery.
- Clear presence on player exit, stop, profile switch, or sign-out.
- Treat a record as stale after 90 seconds so crashes cannot leave permanent presence.
- Paused playback remains visible until player exit or heartbeat expiry.
- Aggregate multiple devices to the newest non-stale session per profile.
- Display friend, title, season/episode, playing/paused state, progress bar, and rounded percentage.
- Tapping opens content details with the relevant Watch Together action.
- Never publish `lastSourceUrl`, addon name, stream title, or credentials.

**Recently Watched**

- Publish only Nuvio-originated events when:
  - Playback crosses the existing 90% watched threshold.
  - The user manually marks an item watched.
- Persist a local per-profile social outbox so offline publish/remove operations retry through the normal foreground sync path.
- Unmarking or deleting the originating watched item removes its social event.
- Do not publish initial history imports, Supabase reconciliation, Trakt, or Simkl projections.
- Retain events permanently until explicitly removed or the profile/account is deleted.
- Assign an episode to the previous run only when that profile’s immediately preceding activity is the same show. Another title breaks the run; returning later creates a new card.
- A run card shows the show, latest watched episode, total episodes in that run, friend, and latest timestamp. Movies remain single-event cards.
- Use stable `(last_event_time, run_id)` cursor pagination with 30 cards per Social page.
- Home shows at most 18 active Watching Now items and 18 recent run cards.

### 4. KMP UI and repository integration

- Add shared `features/social` and `features/watchparty` packages containing models, repositories, state reducers, transport adapters, UI sections, and fake adapters for tests.
- Keep new UI in Z-owned files. Touch divergent upstream files only at seams:
  - Insert social rows after Continue Watching and before catalog rows.
  - Replace the Downloads root tab with Social.
  - Wire the new tab into Compose phone/tablet navigation and native iOS navigation.
- Root order becomes: **Home, Search, Library, Social, Profile**.
- Preserve Downloads access through:
  - A prominent Downloads shortcut in Library.
  - The existing Downloads page under Profile/Settings.
  - Existing download deep links/routes.
- Add Home rows in this order:
  1. Continue Watching.
  2. Watching Now.
  3. Friends Recently Watched.
  4. Existing collection/catalog rows.
- Implement shared logic in `nuvio-z`, merge shared history into desktop, and manually port divergent `App`, Home, Supabase, dependency, and native-navigation seams.
- Update `Docs/Z-FEATURES.md`, `Docs/PATCH-SURFACE.md`, and `STATUS.md` before release.

### 5. Watch Together

**Session creation and membership**

- Entry points:
  - “Watch Together” on movie/show/episode details before source resolution.
  - “Start Watch Together” inside an active player.
  - Persistent invites in Social.
- Only signed-in, non-anonymous profiles may create or join.
- Support up to eight active participants including the host.
- Admit accepted friends through in-app invites or any signed-in profile possessing the unguessable party deep link/manual code.
- Converting solo playback pauses the host and opens the readiness lobby.
- Creating from details enters the same lobby before playback starts.
- Lobby displays participant connection, resolution, buffering, readiness, and failure state.
- The host may start once anyone is ready and may force-start without unready participants. Unready participants continue resolving and join at the current shared position later.

**Source selection**

- Never send the host’s resolved playback URL or headers.
- Publish a sanitized `SourceFingerprint` containing:
  - Addon manifest ID.
  - Info hash/file index when available.
  - Normalized release fingerprint.
  - Resolution/quality intent.
  - Language and relevant media attributes.
- Each client:
  1. Queries the same addon when installed.
  2. Matches info hash/file index first, then normalized release fingerprint.
  3. Resolves its own URL using its own credentials.
  4. Falls back automatically to its normal source-ranking policy constrained by the host’s quality intent.
- Reject a candidate as timing-incompatible when its duration differs from the host by more than the larger of 90 seconds or 2%; try the next candidate.
- Audio track, subtitle selection, subtitle delay, and volume remain local.

**Authority and commands**

- Party control mode is host-configurable:
  - `HOST_ONLY`: only the host commits play, pause, seek, and shared speed; guests may send non-binding requests.
  - `COLLABORATIVE`: any ready participant may commit playback commands.
- Content changes, next-episode selection, control-mode changes, participant removal, and ending the party remain host-only.
- Every command carries a client-generated idempotency UUID. The server validates authority, increments a monotonic sequence, updates authoritative state, and broadcasts the committed result.
- Authoritative state contains content generation, position, playing/paused/buffering state, shared speed, sequence, and server timestamp.
- Host speed is shared across all participants.
- Host buffering pauses the authoritative timeline for everyone. Guest buffering affects only that guest; it catches up afterward.
- Switching content or advancing to the next episode increments the generation and returns everyone to a new readiness lobby.

**Synchronization**

- Send discrete commands immediately and an authoritative host snapshot every five seconds.
- Estimate server clock offset using periodic timestamp RPCs and the lowest-RTT sample.
- Calculate expected position from authoritative position, server timestamp, playing state, and shared speed.
- Correction policy:
  - Drift ≤750 ms: no correction.
  - Drift from 751–2500 ms: temporarily apply shared speed ±3%, for at most ten seconds, then restore shared speed.
  - Drift >2500 ms: hard seek to the authoritative position.
  - On pause, align when drift exceeds 500 ms and then pause.
- During party-channel loss, continue local playback with a disconnected badge. On reconnect, refresh authoritative state and apply the correction policy.
- Profile switch or sign-out leaves the party.
- When the host disconnects, reserve host ownership for 15 seconds. Then transfer it to the longest-connected currently present participant, using profile UUID as the deterministic tie-breaker.
- If the original host returns after transfer, they return as an ordinary participant.
- Ending the party removes synchronization and party controls but leaves every stream playing locally.
- Each participant continues to record their own watch progress and watched completion normally.

## Public Interfaces

Introduce shared client contracts:

- `SocialRepository`
  - Setup/update handle and privacy.
  - Search profiles.
  - Manage requests and friendships.
  - Load/paginate social state.
  - Publish/clear presence.
  - Enqueue/remove watched activity.
  - Subscribe to private invalidation channels.
- `SocialProfileSummary`, `FriendRequest`, `WatchingNowItem`, `RecentActivityRun`, `SocialInboxItem`, and `SocialUiState`.
- `WatchPartyRepository`
  - Create/join/leave/end.
  - Invite profiles.
  - Resolve readiness.
  - Submit commands.
  - Change content/control mode.
  - Observe state and presence.
- `WatchPartyState`, `WatchPartyParticipant`, `WatchPartyControlMode`, `WatchPartyCommand`, `PartyContent`, `SourceFingerprint`, `SourceResolutionState`, and `PartyConnectionState`.

Expose backend RPC groups for:

- Social profile setup/search/privacy.
- Friend request lifecycle and friendship removal.
- Authorized Home/Social feed reads and cursor pagination.
- Presence publish/clear.
- Idempotent activity publish/remove.
- Party create/join/invite/leave/end.
- Readiness, heartbeat, content changes, commands, state refresh, and clock synchronization.

All RPC results return stable profile UUIDs and server timestamps; clients never infer ownership from `profileIndex`.

## Test and Acceptance Plan

### Backend

- pgTAP coverage for owner, friend, removed friend, stranger, anonymous user, and cross-account profile access.
- Verify direct table access is denied and every RPC rejects profile impersonation.
- Test handle uniqueness, request races, duplicate acceptance, removal, rate limits, and cascading deletion.
- Test presence heartbeat, pause visibility, multiple devices, privacy toggles, clearing, and 90-second expiry.
- Test watched idempotency, deletion, permanent cursor pagination, and consecutive show-run grouping.
- Test party capacity, invite authorization, expired/ended links, command permissions, idempotency, sequence ordering, host transfer, and next-content generation.

### Shared KMP logic

- Fake-repository tests for social onboarding, offline cache, unread inbox, feed invalidation/refetch, privacy changes, and outbox retry.
- Projection tests for progress percentage, stale presence, multi-device selection, run grouping, deletion, and stable pagination.
- Pure tests for source fingerprint normalization/matching, independent fallback, duration compatibility, command reduction, host transfer, clock-offset selection, and drift correction thresholds.
- Navigation tests confirming Social replaces Downloads while every previous Downloads entry route remains reachable.

### UI and integration

- Render tests for Home rows, Social setup/empty/loading/error/feed states, inbox badge, party lobby, readiness errors, disconnection, and host transfer.
- Two-profile staging tests for request acceptance/removal and immediate privacy revocation.
- Multi-client party tests covering Android↔desktop, Android↔iOS, pause/seek/speed, buffering, fallback sources, reconnect, host loss, force start, and episode transition.
- Confirm no source URL, headers, addon credential, or debrid identifier appears in database rows, Realtime payloads, or diagnostic logs.
- Run common tests, Android build/tests, manual iOS workflow after Realtime/player changes, and the Windows desktop build-only workflow.

### Acceptance criteria

- An accepted friend’s live playback appears within five seconds when Realtime is connected and disappears within 90 seconds after an unclean disconnect.
- Privacy toggles and friendship removal prevent subsequent authorized feed reads immediately.
- Offline watched actions publish exactly once after reconnect; undo removes them.
- Home and Social render correctly with zero, one, and many friends.
- Eight clients can join a party without exposing a shared URL.
- Ready clients start together; steady-state drift remains under 750 ms under normal conditions and converges after reconnect.
- Host loss transfers control after the 15-second grace period.
- Ending a party leaves all participants in functional solo playback.

## Assumptions and Rollout

- Deploy backend migrations and private Realtime policies before enabling either client feature.
- Gate Social and Watch Together independently through a backend capabilities RPC; missing RPCs mean disabled, not an app error.
- Release Social first. Enable Watch Together only after cross-platform staging validation.
- Existing signed-in profiles remain usable without handles until Social is opened.
- Sharing defaults on for accepted friends, with separate Watching Now and Recently Watched toggles.
- There is no block list, OS push notification, friend profile page, rating/review activity, Trakt/Simkl social ingestion, Letterboxd integration, or NuvioZWeb implementation in this plan.
