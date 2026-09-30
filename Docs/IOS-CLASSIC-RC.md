# iOS Classic playback RC blocker — 2026-09-30

## Captured freeze: proven lock cycle

Use the recent diagnostics, not the previous day's reports:
`session-20260930-114026.log` records an unrecovered main stall at 11:53:22;
`session-20260930-115500.log` records another at 11:55:31 after relaunch.
`metrickit-20260930-115500.json` is a **debug 71 watchdog crash**, not a hang payload:
termination `0x8BADF00D`, scene-update deadline 10 seconds, sample at 11:54.

The release IPA's `Nuvio Z.debug.dylib` UUID is
`CC33CEC6-2C9A-345D-91B0-2BA37DCF8234`, exactly the report's UUID. Its retained
symbol table maps the captured addresses without guessing or rebuilding:

| Thread | Offset | Symbol / wait |
| --- | --- | --- |
| Main (attributed) | `0x5dfe09c` | `NativeMutexNode.wait` |
| Main | `0x5e00d64` | `SynchronizedObject.lock` |
| Main | `0x34663bc` | `DownloadsRepository.ensureLoaded` |
| Main | `0x29b70ec` | `MainAppContent` |
| Transfer delegate (61) | `0x4e49d14` | `DownloadsLiveStatusPlatform.writePayload` (Foundation observer wait above it) |
| Transfer delegate | `0x33983a8` | `DownloadStore.notifyLiveStatusPlatform` |
| Transfer delegate | `0x3398150` | `DownloadStore.publishLocked` |
| Transfer delegate | `0x3374bc0` | `DownloadScheduler.onTransferProgress` |

`onTransferProgress` holds `DownloadStore.lock`, publishes, and synchronously posts
`NuvioDownloadsLiveStatusUpdated`. Swift registered its observer with `queue: .main`.
Foundation waits for that main observer to run. Main is composing and waiting for
the same store lock. Neither can proceed. Download restoration explains recurrence
at the source list before any mpv player exists. The interrupted lifecycle also explains
why locking the phone could leave the Live Activity's detailed foreground payload visible.

Fix: observe with `queue: nil`, then `DispatchQueue.main.async` the UI callback. The
producer returns and releases the store lock. ActivityKit's existing update serialization
and payload policy remain in place. Raw logs, downloaded media and IPAs stay outside git.

## Manual routing and wrong error

The Classic tap goes through `StreamsScreen.onStreamSelected` to
`StreamDestination.openSelectedStream`. Direct-debrid resolution, when needed, recurses
with the resolved form of that pick. The direct URL goes into `PlayerLaunch.sourceUrl`,
then `PlayerLaunchStore`, `PlayerDestination`, `PlayerScreen`, the iOS bridge and `loadfile`.
The manual launch leaves `autoPickedWithFailureChain` false; its fatal callback is null.
There is no ranked automatic picker in that initial manual handoff.

The **"No safe automatic source was found. Choose a source to continue."** wording is
`player_next_episode_choose_source`, emitted by the in-player next-episode picker.
The separate initial automatic route says **"No safe automatic source matched."**
The EOF effect previously accepted `isEnded` without the keyed lifecycle/error/seek
guard used by the threshold effect, so a failed/opening/old EOF could start next-episode
selection and put that automatic failure above a videoless manual player. A duration
of zero is not rejected by `isShortPlaceholderDuration`, so that also needs an explicit guard.

The new `hasCompletedCurrentEpisode` requires a completed load, positive duration,
current snapshot key, finished seek, and no loading/scrubbing/error before EOF advances
the episode. iOS additionally requires `FILE_LOADED` and no playback error before publishing
`isEnded`; synchronous `loadfile` refusal sets an actionable player error. The existing
manual error screen retains its source and controls instead of substituting another picker.

**Evidence limit:** the supplied freeze files contain no playback event log or selected
StreamItem. The wrong-message path and its missing guard are reproducible in regression
fixtures; the exact mpv EOF/source response in the physical Futurama attempt is not captured.
Do not claim the first source was playable or the exact original EOF timing was observed.

## Subtitle patch / mpv audit

`6ec82e819` (`4ea93e489` originally) is **not the captured freeze's cause**: the crash's
wait cycle is downloads/notification, with no mpv frame. It did expose a separate lifecycle
defect: subtitle work and snapshot reads ran on the event queue while main could set the
context to nil and call `mpv_terminate_destroy` against an in-flight call or cached pointer.
Nil-before-destroy is not serialization.

One controller now owns one `MPVSerialExecutor`. Initialization, synchronous native reads,
commands, subtitle/header transactions and destruction all run on it. Close is immediate,
rejects queued/late work, and schedules destruction behind in-flight work. The controller,
wakeup callback and Metal layer remain alive until destruction finishes; the final UIKit
reference is released on main. Late snapshot/Now Playing publication is gated. Every new
controller has an independent executor. UIKit and polled state stay on main.

No queue sync, semaphore wait or terminate-destroy runs on main. The remaining getters
assert they are off main. The download-store mutex was the proven source-loading wait;
the launch stores are main-owned maps with no blocking primitive. Subtitle work stays
off main rather than reverting the queue patch. Tests exercise simulated blocked subtitle
work, not a real remote mpv demuxer.

## Verification and minimal physical retest

Focused Kotlin tests cover an actual Classic source tap/launch, actionable manual failure,
opening/zero-duration/stale/error EOF, and valid completion in all three modes. Native Swift
tests use the production Live Activity observer and 50 ordered subtitle/exit/recreation
cycles; build-only iOS CI runs them before compiling Kotlin/Swift. Record actual results
in STATUS.md. No version bump or debug release is authorized by this investigation.

1. With an active download, Classic → Futurama S1E1 → first source. Check the chosen
   source opens; if it fails, the player says why and offers retry/another source.
2. Exit, Home → Futurama → source list immediately. Repeat enter/exit/retry ten times,
   once while a remote subtitle is loading/switching. Spinner/taps must keep responding.
3. Force-close/reopen with downloads restored and repeat. Lock during download: the
   Live Activity must change to the vague background message; unlock restores detail.
4. Briefly play one Streamlined/Instant source and one Android Classic source; verify
   subtitle switching and genuine episode completion still work normally.

The blocking freeze and wrong-message path are separate defects, joined by this user
journey. Physical confirmation is still required for RC clearance.
