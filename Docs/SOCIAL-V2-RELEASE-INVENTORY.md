# Social V2: release inventory and artwork choices

Snapshot: 2026-10-21. A working inventory for the maintainer's release copy and image selection.
Descriptions below are factual scope, not final release-note wording.

## Release readiness (2026-10-21)

Social V2 is implemented on its mobile/desktop branches and is not yet a stable release.
The mobile language/What's New port and Advanced Setup Language pages are complete.
Backend Social V2, push and recommendations were deployed in the earlier approved work;
this upstream sync performs no backend deployment.

| Area | Current evidence / remaining work |
| --- | --- |
| Upstream integration | Mobile 0.6.2-beta and desktop 0.1.31-alpha merged; zero behind those named tags. Preserve Z Watch Together; upstream Party UI stays gated. |
| Mobile automation/builds | Android 3,828 host checks and all 1,090 pure checks pass; Android/iOS CI and debug 108 publication pass. |
| Desktop automation/builds | Final full Linux CI 39609270820 passes: 3,871 passed, zero failures, three existing headless GUI skips (3,874 total); native Linux checks, Windows MSI and changelog jobs pass. All 1,080 pure checks and local full suite also pass. Windows/Mac packages and Mac launch check pass; debug 117 is published. |
| Product acceptance | Maintainer confirms no additional device testing on 2026-10-21. Retest language migration/picks, upgrade review, Social/Inbox/recommendations, Android push, desktop tray/startup and cross-device Watch Together. iOS Home/lock/return acceptance remains open. |
| Release content | Maintainer's final notes and chosen artwork are pending. Bundle approved images and the real global Social V2 event with review_settings, then validate both changelogs. |
| Stable publication | Final version/serial bump, release checks and explicit stable publication remain pending. Current debug versions retain the old marketing base; the release must name the newly synced vanilla bases. |

Generic Inbox dismissal/manual Mark all read and Start party/Recommend from a friend's profile
remain optional scope decisions from early designs. They are not implemented; recommendations
from title details are implemented. iOS system notifications, TV/web Social V2 and Reaction Mode
remain outside this release.

## Baselines and platform labels

- Mobile `claude/social-v2` (post-sync 0.6.2-beta), compared with released `0.5.4-z3+129`.
- Desktop `claude/social-v2-design` (post-sync 0.1.31-alpha), compared with released `0.1.26-alpha-z2+133`.
- Both stable tags were checked against GitHub's latest stable release during this audit.
- All platforms means Android, iOS and the desktop app; TV/web is outside this release's current scope.
- Windows tray/startup features are labelled Windows; macOS acceptance is parked in the plan.
- Language/settings/What's New and Advanced Setup Language are implemented on all three platforms.
- Other implemented items still need final hardware checks. This inventory is not a release-readiness claim.
- The inherited upstream delta is inventoried below; final acceptance and notes remain separate.

## Inherited with the upstream sync

These are vanilla capabilities arriving with mobile **0.6.2-beta** and desktop
**0.1.31-alpha**, rather than new Z features. Full decisions and verification:
[upstream sync record](UPSTREAM-SYNC-2026-10-20.md).

| Change | Platforms / scope |
| --- | --- |
| Jellyfin and Emby media-server libraries, matching, playback/resume and settings | Android / iOS / desktop |
| MDBList tracking authentication and related settings | Android / iOS / desktop |
| Subtitle AutoSync | Supported Android playback engines; unavailable on iOS/desktop |
| Native-memory/buffer and VOD-cache controls | Android Exo playback |
| Picture in Picture, audio-session and system-control improvements | iOS; Z serial mpv ownership preserved |
| Stream information/engine controls, source metadata, YouTube resolution and next-episode prefetch | Inherited where supported by the platform engine |
| New Nuvio engine and native packaging replacing TorrServer; player/window refinements and download completion badge | Desktop |
| Localization, formatting and UI fixes in the named upstream tags | Inherited alongside Z layout/language seams |

Z Watch Together remains the active party system; upstream's duplicate Party UI is
gated pending the optional maintainer choice. Desktop shuffle remains disabled under
the standing mobile-only decision. Final release copy should name the new vanilla bases.

## Feature inventory

### F01. Rebuilt Social activity and navigation - all platforms

- Phones have Activity and Friends views; wide layouts retain an adaptive feed and friends rail.
- Identity, handle, friend count, Inbox and Social settings lead the header.
- Watching Now uses landscape artwork, avatars, live/paused/party state, episode information and progress.
- Cards keep their order through heartbeat refreshes; new sessions can appear first.
- Recently watched becomes per-friend cards with centered stacks of landscape stills and Today / Yesterday /
  This week / Last week / Earlier buckets. The hover fan-out was removed.
- A single-title card opens the title; a multi-title card opens the friend's profile.
- Series activity uses the show's name/backdrop and a latest-episode / watched-count summary.

### F02. Friend profiles, privacy and request management - all platforms

- Profiles show the live session, recent viewing, a Watched together strip and approximate together duration/party count.
- Per-friend Hide my activity from them filters both live and recent activity on the server.
- Notify me when they start watching is an opt-in per-friend switch, off by default.
- Sent friend requests have Cancel; removing a friend asks for confirmation.
- Search and friend invite-code access are easier to reach in Friends.
- Start a party / Recommend buttons on the friend profile were in early designs but are not in the current
  profile composable. Sending recommendations from title details is implemented (F04).

### F03. Persistent Inbox and unread badges - all platforms

- One Inbox combines friend requests, party invites, join requests and available outcomes with stored events:
  friend accepted, friend started watching and recommendation-started notices.
- Unread / Read groups handle arrivals while the page is open; opening it records received events as read.
- The Social navigation item carries an unread badge, including the native iOS Social tab.
- Wide layouts use a side panel; phones use a page.
- Finished-party notices become quiet history and retire after a day. Ordinary stored events have retention;
  recommendations remain separately until watched or removed.
- Recommended to you leads the Inbox without duplicate recommendation cards underneath.
- Manual Mark all read and arbitrary per-item Inbox dismissal are not wired in the current UI.
  Removing recommendations is implemented.

### F04. Recommend movies and shows to friends - all platforms

- Title details -> three-dot menu -> Recommend: multi-select friend picker, search, optional 280-character note.
- Phones use a face-grid picker; desktop uses a list. A series is recommended as the show.
- Outstanding recommendations show Sent for recipients who already have that title from you.
- Several friends recommending one title produce one received card with the senders and their notes.
- The title's details page also shows who recommended it and their message.
- Recommendations remain until the receiver starts watching or removes them, rather than aging out with normal Inbox history.
- The sender gets an in-app event when the friend starts it, respecting sharing/privacy; this event is never background push.
- An optional Home recommendation shelf is part of F05.

### F05. Unified Social settings and Home shelves - all platforms

- A gear in Social and the Social row in Settings open the same settings surface.
- Identity/handle, sharing/privacy, who can join, platform alerts, Home choices and turning Social off are grouped together.
- Per-friend switches stay on the friend's profile.
- Home has a Friends shelf of landscape tiles, live first, hidden when empty; time-ago labels use readable chips.
- Show friends on Home is a per-profile preference integrated with settings sync.
- Show recommendations on Home is separate, per-profile/synced and off by default. The shelf sits below Friends
  and does not require the Friends shelf itself to be enabled.

### F06. Phone Social alerts - Android and iOS; background push on Android only

- Compact in-app banners use the shared Social card; tap opens the relevant place and swipe up dismisses.
- The player's Social card uses the same visual family, relevant actions and watching notices, replacing older/duplicate prompts.
- Per-device switches: master, friend requests, parties, friends watching and recommendations.
- Android background system notifications cover friend requests, party invites, join requests, friend accepted and
  recommendations, with artwork when available and tap routing. Do not promise inline system-notification action buttons.
- Friend-started-watching and someone-started-your-recommendation notices are in-app only.
- iOS currently has in-app banners and the Social-tab badge: no background system push, native local notifications,
  app-icon badge or guaranteed background refresh is implemented for Social.
- Android push deployment is recorded in the handoff; the latest registration/delivery fixes still need final device acceptance.

### F07. Tray, desktop pop-ups and Start with Windows - Windows desktop

- Closing can hide the window to the tray while Social remains active; close behaviour is configurable.
- Hidden mode disposes the main UI and clears image caches. Startup straight into the tray postpones player/WebView warm-up.
  Do not publish a fixed memory-usage promise.
- Steam-style artwork pop-ups use an independent corner window without taking focus.
- Three are visible at once, additional cards queue, hover holds them, and cards slide/fade in and out.
- Alert-kind and over-fullscreen settings control their behaviour.
- Outside playback, a click opens the relevant Social destination; during desktop video playback it dismisses the card
  without navigating away. These desktop cards do not have inline action buttons.
- Start with Windows opens straight to the tray for the last usable profile, never silently opening a PIN-protected profile.
  It is available to installed Windows builds.
- Relaunching an installed channel restores its existing window and hands over links instead of opening a second session.
  Stable, Debug and vanilla remain separate channels.

### F08. Watch Together joining and invitations - all platforms

- Watching Now opens an artwork-led confirmation sheet with Join / Ask to join and Details; an accidental card tap does not play.
- The sheet emphasizes the title, person and episode, with state/progress at its edges; the details affordance opens the title directly.
- Existing lobby Invite controls and the player's + Invite friends open a multi-select picker with search, watching-first order
  and available-slot limits.
- Sent invitations can be cancelled. Declined invitations show a five-minute re-invite cooldown.
- Guests back in the lobby have In lobby state and Rejoin.
- Declined/expired/stopped/failed join requests remain visible in party controls, with Ask again/dismissal where appropriate.
- Phones keep the full Watch Together status pill instead of an avatar-only control.

### F09. Rebuilt language and subtitles - all platforms implemented

- My language, fallback language and subtitle-language override, defaulting to Same as my language.
- Separate subtitle choices for titles in your language and titles in other languages: Off, Forced, Full and SDH.
- Foreign titles can use original audio or your language when a dub is available.
- Two moments of the same example scene explain Forced versus Full/SDH. Settings and wizard remain plain, without still images.
- Full prefers ordinary subtitles over SDH/CC; SDH prefers SDH/CC, with fallback between them.
- Original title language selects the group; unknown origin falls back to playing audio. Missing original audio falling back
  to your language follows the agreed subtitle-group exception.
- Player track selection and source ranking use the preferences where metadata is available.
- Existing choices are migrated and integrated with per-profile settings sync.
- Mobile port and Advanced Setup Language are implemented. Final playback/device acceptance is pending.

### F10. Updated setup review and What's New - all platforms implemented

- Initial Setup uses the new Language page. Revision 11 offers it to profiles that completed revision 10;
  earlier eligible upgrades can also owe the previous download steps.
- A centered New settings available offer follows What's New: Yes reviews changed/owed steps, No skips this settings revision,
  X returns to What's New. Existing users can opt into review.
- Review new settings is an implemented What's New entry action opening the current revision's changed steps.
  The actual Social V2 changelog entry is not yet present.
- What's New supports picture cards for features or improvements, text for smaller changes, platform tags on every entry
  and folded fixes, without category headings or a grey other-devices section.
- Actual notes, card images and the release event remain pending.

### F11. Downloads settings within the regular Settings layout - all platforms

- Downloads uses an ordinary Settings page and shared rows instead of a separate route, retaining desktop's sidebar/navigation.
- The larger downloads redesign itself already shipped; this is its settings/navigation improvement.

## Smaller improvements and folded-fix candidates

Use these under the related feature or in folded fixes; avoid repeating the same change twice.

1. Subtitle memory matches language rather than the same track number in another episode, and a remembered non-forced
   choice is not overwritten with the forced track. A separate unreproduced subtitle pick remains open; do not claim every issue solved.
2. Next-episode loading uses the active source/attempt and the show's sharp backdrop instead of stale launch facts or a small
   episode still. Party status also reads the active source attempt.
3. Lost sign-in raises a notice with Sign in; Account shows Sign In while signed out, and recovery does not use the destructive Sign Out flow.
4. Social renews its live-connection authentication while running, addressing desktop alerts that stopped until the window was restored.
5. Android push no longer forgets the registered profile while Social loads, which delayed delivery until opening the app/Social tab.
6. Social pop-up navigation clears a covering details page so the intended destination becomes visible.
7. Old bottom Social prompts are removed, avoiding duplicate invitation notices.
8. Invitation buttons display the selected-friend count instead of a literal formatting placeholder.
9. Watching Now stops reordering on heartbeat updates.
10. Activity uses show titles and true backdrops rather than episode titles/cropped poster art; existing events benefit too.
11. Finished-party invitations stop appearing actionable and age out of history.
12. Inbox arrivals while open enter the correct Unread group.
13. Starting another title can notify opted-in friends soon after a previous one; members of the same live party do not notify each other.
14. Recommendation details text is readable on the actual background; avatar initials and count glyphs are centered correctly.
15. Update detection chooses the newest matching release even when GitHub returns an older one first. Supported in-app updater
    paths include the self-signing iOS builds.
16. Desktop Downloads toast actions reveal Downloads correctly. The matching mobile fix was already in mobile stable.

## Artwork choices

Working labels only. Larger improvements can use the same cards as features.

| Card candidate | Coverage | Render concept | Suggested treatment |
| --- | --- | --- | --- |
| Social, rebuilt | F01; glimpse of F02/F05 | Real dashboard or phone Activity view, with live artwork and recent stacks | Main card |
| Recommendations | F04/F05 | A received tile with several senders/notes; optional friend-picker inset | Main card |
| Inbox and alerts | F03/F06 | Unread/Read Inbox with an invite and recommendation; compact banner overlay | Main card; Android-only system push explicit in copy |
| Language and subtitles | F09/F10 | Approved own-language/foreign-language scene comparison; Review new settings below | Main card after mobile port |
| Pop-ups and tray | F07 | Corner pop-up with tray/clock context; optional Start with Windows detail | Main card, Windows-specific copy |
| Friend profiles/privacy | F02 | Live profile, together line and Hide/Notify controls | Optional card or text under Social |
| Joining and inviting | F08 | Artwork-led join sheet or friend picker; In lobby/Rejoin as a secondary example | Optional card |
| Home shelves | F05 | Friends and recommendation shelves at real Home tile widths | Optional card or text |
| Settings review | F10 | Centered offer over real app context | Usually pair with Language |
| Downloads settings | F11 | Settings page retaining desktop sidebar | Usually text |

A compact starting point is the five Main cards. Promote optional cards according to the maintainer's preferred emphasis.

## Available render sources

Desktop harnesses already cover these surfaces; use current production components rather than superseded candidates:

- SocialRenderHarness: tab, Friends, profile, Inbox, Home recommendations, popup cards and dock states (`build/social-render/`).
- SocialRecommendHarness: recommendation shelf/details components and picker candidates (`build/social-recommend/`).
- SocialJoinSheetHarness: join sheet and phone player-card candidates (`build/social-join-sheet/`).
- PartyInviteListHarness.renderShippedPicker: actual picker (`build/party-invite/`).
- SocialSettingsTrayHarness: settings, pop-ups and close prompt (`build/social-settings-tray/`).
- LanguageSettingsRenderHarness: actual language/offer/What's New, timestamped folders under `build/language-settings-render/`.

At release time approved artwork belongs in `composeResources/files/whatsnew/`, with the maintainer's notes and
review_settings action on the language entry. Existing preview copy and images are fixtures, not final release assets.

## Remaining before this becomes a ship list

- Mobile language/settings/What's New and Advanced Setup Language are ported and locally verified before upstream integration; see STATUS for post-sync verification.
- Complete installed-build acceptance for the new language choices and latest alerts/startup/party changes.
  iOS Social has been exercised, but iOS Watch Together acceptance remains incomplete.
- Linux native build/player creation and synthetic-handle isolation are corrected; finish the current Linux navigation/render CI confirmation. Local focused checks do not replace CI.
- Named upstream tags are merged and inventoried; finish the compiler/CI checks recorded in STATUS and release gates.
- Maintainer writes notes/chooses artwork; add the real release event, cards and review_settings action.
- Decide whether early-plan controls still wanted should be finished or dropped: generic Inbox dismissal/manual Mark all read,
  and Start a party/Recommend directly from a friend's profile.

## Outside the current release / already shipped

- Reaction Mode; blocking/reporting; suggested friends/contact import; audience lists; per-title incognito.
- Generic Watched by your friends on details and turning friend codes into party-join codes were deferred.
  Recommended to you by on details is implemented separately (F04).
- iOS system/local notifications, app-icon badge and guaranteed background refresh for Social.
- macOS tray/startup acceptance and TV/web Social V2 port.
- Installer 3, iPhone self-renewal/self-updating, earlier Home-performance pass, main downloads redesign and Advanced Setup
  as a whole already shipped. Only the listed changes are new here.
- No new locale-completeness claim: resource extraction does not mean new translations were delivered.

## Supporting engineering and evidence

- Social strings moved to an appended resource block; obsolete V1 cards and superseded placements were removed.
- Per-profile alert ledgers/push registration, account cleanup for new stores, and a separate Home-settings sync payload.
- Backend supports stored Inbox, per-friend filtering, invite cancel/cooldown and recommendation dedupe/retention/start events.
  Existing handoffs record those migrations/push services as deployed; no deployment was performed in this audit.
- Language migration preserves compatible old fields and adds subtitle-pick diagnostics.
- First-launch/upgrade/review routing, native navigation badges, notification targets and compile/render/test support.
- Evidence: canonical STATUS, Social rows S11-S17 in Z-FEATURES, workspace Social V2 and language plans, branch commit ranges,
  and spot-checks of SocialScreen, SocialRecommendations, SocialPopups, SocialPush.android, SocialSettingsPage, desktop tray,
  wizard steps/body and AppGate. Later decisions and current code override earlier proposals.
- Stable releases: https://github.com/Zokaper/nuvio-z/releases/tag/0.5.4-z3%2B129 and
  https://github.com/Zokaper/NuvioZDesktop/releases/tag/0.1.26-alpha-z2%2B133.
