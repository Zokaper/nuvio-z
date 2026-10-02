# Frozen RC physical acceptance — 2026-10-02

Use the combined **mobile Debug 75 / desktop Debug 81** candidate. Record installed version,
device/OS, result, and approximate failure time for every row. Candidate SHAs and package
checksums will be recorded when the workflows finish. Automated tests do not establish
physical acceptance. Performance is deferred; do not merge optimization work during QA.

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
| Downloads offline / storage | On each platform: airplane mode, play completed download, next downloaded episode, delete title/season; reopen Details/restart; upgrade an existing flat-file library. Switch profile/account. | Real local playback with no online-source fallback, valid organized files, no phantom downloaded badges, correct storage isolation/migration. |
| Download constraints | Android/iPhone Wi-Fi-only versus permitted mobile data; low-space/cap/uncached-source attention; restore allowed network and retry. Desktop metered network must not hold transfers. | Visible actionable states; no permanent stuck item or placeholder media. |

Export iPhone Files → Nuvio Z Debug → `nuvio_diagnostics/watchparty-*.log`; desktop Debug
logs are in the app's Debug data/log directory. For Away failures keep both host `away policy`
input/output and iPhone lifecycle/RPC/socket traces with matching times. Do not share raw
credentials, personal addon URLs or tokens. A banner or numeric socket code is not a root cause.

Auth/session corruption, setup/profile misrouting, startup hangs, invalid completed downloads,
nonfunctional exposed iOS Downloads, persistent reconnect, or failed Away policy block release.
Hardware-only failures remain open acceptance gates until this candidate passes; stable
publication requires a separate maintainer instruction after acceptance.
