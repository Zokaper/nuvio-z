# Frozen RC physical acceptance — 2026-10-02

Use the combined **mobile Debug 75 / desktop Debug 81** candidate. Record installed version,
device/OS, result, and approximate failure time for every row. Candidate SHAs and package checksums are verified below. Automated tests do not establish
physical acceptance. Performance is deferred; do not merge optimization work during QA.


## Candidate sources and artifacts

Both build-source branches: `claude/ios-watch-together-hardening`. Published debug tags
freeze these exact commits; subsequent working-branch handoff commits change docs only.

| Candidate | Frozen build SHA | Artifact and version |
| --- | --- | --- |
| Mobile | `f172bfb6d0cc6a31f33a675de96dc81d480b3945` | [Debug 75 APK + IPA](https://github.com/Zokaper/nuvio-z/releases/tag/debug-v0.4.13-z1.75). Android `0.4.13-z1.75` / `125075`; iOS `0.4.13-z1.75` / build `75`. |
| Desktop | `0707ccc20e73d8298ddc0b271c56d8366b9c39ad` | [Debug 81 MSI + arm64 DMG](https://github.com/Zokaper/NuvioZDesktop/releases/tag/debug-v0.1.23-alpha-z6.81). Windows `Nuvio Z Debug` / `1.45.81`; desktop app `0.1.23-alpha-z6.81`. |

All four packages were downloaded and independently matched to GitHub asset size and SHA-256.
APK/IPA also match SHA256SUMS-Debug.txt. MSI identity and APK identity/version were read from
the packages; IPA CRC/plist, bundle identity, version/build and Files export settings pass.
Canonical SideStore debug feed matches IPA build 75, size 92,978,864 and SHA-256
`4eb43d6acd46179b666beb01b957cd59e2522539c504e996fcc5478563254b69`.
Stable source.json blob is unchanged. No stable publication or stable version bump.

Automated candidate validation: mobile host 3440 (six policy skips, all six separately pass),
desktop 3382 (no failures/errors/skips/duplicates), Android debug/release-R8, Swift lifecycle,
iOS device + simulator frameworks and unsigned Xcode all pass. Linux native CI still fails
before Kotlin at frame_copy_test.c:36, independently retained as a known risk. Classification:
**READY FOR DEVICE QA**, with physical acceptance still open.

## Recent acceptance that was missed

Maintainer confirmed on 2026-10-02: no additional recent physical testing and no newer logs.
Run these first, on this pair:

1. iPhone WT quiet foreground for 5+ minutes, paused and playing.
2. Windows host + iPhone guest, Pause for Away ON: Home/return and lock/unlock after 30–60s.
   Host pauses, guest catches up, and host resumes after fresh readiness/settle; record any
   12s fallback. Return during another guest's buffer hold; manually pause during a hold.
3. Repeat Home/lock with Pause for Away OFF, then network loss/restore and controls after return.
4. iOS email login: wrong then correct password, logout/login A then B, restart offline/online;
   also desktop and Android. Anonymous-to-email and browser/code paths where supported.
5. iOS active downloads + Classic source/player entry/exit ten times, including subtitle changes;
   force-close/reopen with restored downloads, lock/unlock and offline completed-file playback.
6. Current setup/profile selection, Social OFF/ON, route identity and player safe areas on both phones.

Historical device results do not accept the changed combined candidate. Lock/Away behavior,
return readiness, quiet foreground, auth and download/player lifecycle remain release acceptance
blockers until this pass succeeds. Current iOS Downloads has a real background implementation
and prior successful device evidence; no current nonfunctional feature has been established.
Keep it exposed for this QA. A reproduced current failure blocks release rather than silently
shipping it. Numeric native socket code 53 alone does not identify the initiating network cause.

## Full device matrix

For parties, use distinct active profiles for each peer, Social/WT enabled, the same episode
and lobby-matched playable sources. Keep the host and guest logs from the same test interval.

| Area | Devices and exact procedure | Required outcome |
| --- | --- | --- |
| Installation | Windows Debug MSI, Android full-debug APK, iPhone unsigned Debug IPA through SideStore. Upgrade prior Debug where possible; also test a fresh install/profile. | Correct new Debug versions; side-by-side identity; launch without hang or storage loss. |
| Email auth | On all three: wrong password, then correct password; logout/login A; logout/login B; rapid repeated attempts with slow/offline network, restore network; authenticated restart offline then online. On iPhone also complete email confirmation if needed. | Only latest successful session wins; old errors disappear; delayed failure/sign-out never logs out B; persisted account matches UI. |
| Other auth | Anonymous to email, browser/device-code login, canceled/expired code then retry, where supported. | Account/session/profile agree; unrelated old session is never adopted. |
| Setup / AppGate | Fresh install; complete setup; restart mid-setup; imported device setup; one-profile and multi-profile account; switch A to B with different profiles; return to A. | Correct setup gate, current account's profiles only, no old profile flash/selection, one selected profile, no stuck Loading. |
| Social OFF / ON | On Android and iPhone: disable Social, restart, play; enable Social, edit profile/friends, play and finish an episode; switch profiles/account; disable again. Check peers on desktop. | OFF hides/deactivates Social publication and party entry; ON uses correct identity, watching/recent activity and errors recover; no foreign profile/activity survives replacement. |
| Navigation identity | Android and iPhone: tab/detail/player/back cycles; Downloads toast from Details; WT lobby exit/cancel; profile/account replacement while navigating. | Back reaches expected destination; no duplicate/stale route, wrong profile or accidental party departure; download source choice never starts playback. |
| Player | Android and iPhone: Classic, Streamlined, Instant cold start; source failure/manual escape; play/pause/seek, subtitle/audio changes; portrait/landscape/fullscreen and notched safe areas; end episode/autoplay; reopen title while downloads are active. | Starts and progresses, controls respond, usable safe areas, no freeze; correct completion and next episode identity. |
| WT core | Windows host with Android and iPhone guests; repeat with mobile host. Join by code/invite, lobby ready/start, play/pause/seek, collaborative/host mode, next episode, leave and host transfer. Also Android to Android if available. | Correct party/member/source identity, synchronized timeline and commands, truthful connection state; no stuck lobby or invalid source transition. |
| WT quiet foreground | iPhone foreground in party for at least 5 minutes on stable Wi-Fi, including paused and playing intervals. | No spontaneous reconnect loop or dropped controls. If a banner appears, export trace with exact time before changing settings. |
| Away ON | Windows host and both guests, Pause for Away ON: iPhone Home for 30–60 seconds, return; lock for 30–60 seconds, unlock; repeat with Android. | Away arrives and host pauses, remains held, return clears Away and catches up. Host resumes after fresh peer readiness/settle; any 12-second fallback is recorded and assessed. |
| Hold overlap | Return iPhone while another guest is buffering/unready, with Pause for Away ON. Manually pause host during hold. | One hold cannot release another; no premature host resume and no automatic override of manual pause. |
| Away OFF / interruptions | Repeat Home/lock with Pause for Away OFF; background over 10 minutes; incoming call; Wi-Fi loss/restore and Wi-Fi to cellular transition. | Host continues when policy says so; guest returns to current timeline without rejoin churn or stale readiness. Genuine loss is shown and recovery restores controls. |
| Downloads modes | Android/iPhone: small known-valid file in Automatic, Assisted, Manual; episode and season; cancel during discovery; pause/resume/reorder/retry/delete; toast navigation. | Real progress or named wait, correct file/source, no unintended playback, no orphan batch/phantom state. |
| Downloads lifecycle | iPhone: lock during a small transfer, return; complete a season within the 30-item submission window, then a queue over 30 while allowing foreground handover; relaunch and force-quit/reopen mid-transfer. Android: background/process death and connectivity loss/restore. | Continued or honestly requeued/resumable work, sticky user pause, no fake live/completed task, no truncated file accepted. iOS force-quit is not a promise of uninterrupted OS execution. |
| Downloads offline / storage | On each platform: airplane mode, play completed download, next downloaded episode, delete title/season; reopen Details/restart; upgrade an existing flat-file library. Switch profile/account. | Real local playback with no online-source fallback, valid organized files, no phantom downloaded badges, device-wide library preserved across profiles/accounts, correct profile preferences and migration. |
| Download constraints | Android/iPhone Wi-Fi-only versus permitted mobile data; low-space/cap/uncached-source attention; restore allowed network and retry. Desktop metered network must not hold transfers. | Visible actionable states; no permanent stuck item or placeholder media. iOS free-space preflight is unavailable; verify honest failure/integrity rather than an early warning. |

Export iPhone Files → Nuvio Z Debug → `nuvio_diagnostics/watchparty-*.log`; desktop Debug
logs are under `%APPDATA%\Nuvio Z Debug\logs` on Windows (macOS:
`~/Library/Application Support/Nuvio Z Debug/logs`). For Away failures keep both host `away policy`
input/output and iPhone lifecycle/RPC/socket traces with matching times. Do not share raw
credentials, personal addon URLs or tokens. A banner or numeric socket code is not a root cause.

Auth/session corruption, setup/profile misrouting, startup hangs, invalid completed downloads,
nonfunctional exposed iOS Downloads, persistent reconnect, or failed Away policy block release.
Hardware-only failures remain open acceptance gates until this candidate passes; stable
publication requires a separate maintainer instruction after acceptance.
