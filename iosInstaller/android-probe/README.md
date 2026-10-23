# Android → iPhone installer experiment

An opt-in **debug-only** Android application, `com.nuvio.app.z.usbprobe`, built from the Nuvio Z
repository. It installs beside stable/debug Android Nuvio Z. The launcher is **Nuvio Z USB Probe**.
The maintainer now authorizes real Apple sign-in/signing/install proof after the validated
transport/pairing work. Version 0.5 adds those operations with Internet permission and the same
isideload 0.4.6 pin as desktop; no release variant or production application dependency is added.

**Actual install passed on 2026-10-24:** maintainer confirms Nuvio Z Debug installed on the iPhone.
Galaxy logs independently show successful Apple sign-in/2FA, signing, USB install and all pairing,
remote-pairing and signing-key handoff flags, `selfRefreshReady=true`, clean connection release.
A later on-device self-refresh/update cycle and app launch were not independently verified.
[Current results and next-session handoff](HANDOFF-2026-10-24.md). Next work is desktop-like UI and
shared installer architecture; keep the validated package/data and native path intact.

## Direct install proof (0.5.2)

The immediate goal is a real sign-and-install attempt, not more repetition of passed USB checks.
ARM64 Galaxy build only. Keep Android online and both phones unlocked; after an iPhone restart,
re-seat the **Galaxy end** of the cable if detection does not return.

1. Enter the Apple Account email/password directly in this Android test app, never in chat.
2. Connect the iPhone and tap **Sign and install Nuvio Z Debug**. Allow USB access if asked.
3. The app first downloads official **Debug 112** (~104 MB) and verifies SHA-256. Apple sign-in starts
   after that download. Complete Trust/Apple 2FA using the in-app code/Send to devices/SMS controls.
4. It then signs through isideload, installs via
   this USB connection, and places the existing pairing/signing-key handoff in Nuvio Z's Documents.
   Use the account previously used for this Debug app when updating it. Stable iOS uses a separate
   base bundle ID. The Debug copy may be updated if already installed under the same account/team.
5. If Apple has no free signing-certificate slot, the app displays an explicit choice. Revoking one
   can stop other apps using that certificate; Cancel leaves those certificates alone. No automatic
   certificate revocation or app removal is performed.
6. Open Nuvio Z Debug on the iPhone. If iOS requires developer-profile trust, complete it in Settings.
   Return the Galaxy to adb for filtered stage logs. Passwords, 2FA codes, keys and account/device
   identifiers must not enter diagnostic reports. Actual install/refresh success must be recorded
   separately from compiler/unit checks.

The input password is erased from the edit field and temporary arrays; it is not saved. Anisette
state and signing keys use encrypted Keystore/no-backup storage. SDK temporary files stay in app
cache through a VFS adapter. Updates must use `adb install -r`; do not uninstall this paired app.
The complete operation has a 20-minute cap, with progress and cancellation. An interrupted iOS
installation may finish on the iPhone; inspect actual device state before retrying.

The initial 0.5 attempt exposed missing Android HTTPS verifier initialization before Apple sign-in.
0.5.1 adds the official rustls-platform-verifier Android support AAR and initializes its JVM/context
at Activity startup. The actual 0.5.1 retry downloaded/verified the IPA but could not build the SDK's
Apple HTTPS client: its extra-root API is unsupported by Android's verifier. 0.5.2 uses a narrow
experimental isideload patch to configure standard rustls verification with public WebPKI roots
plus the same bundled Apple CA. That CA is required by Apple's endpoint; omitting it failed the
no-password lookup on the Galaxy. Certificate/hostname/signature verification stays enabled. The no-password Galaxy test now covers the real
Apple client builder and public URL-bag lookup. The actual sign/install/handoff subsequently passed; compiler/synthetic results remain separate
evidence. Keep the paired app's data when updating.

Follow-up preference: desktop and Android should share the Tauri/web-UI framework and portable Rust
installer logic, with platform adapters. That interface consolidation is deferred until after this
proof, per the maintainer; avoid turning this temporary native form into a second full installer.

The native code reuses idevice **0.1.68** and a narrowly adapted rusbmux **0.3.0** with nusb
**0.2.8**. See [VENDOR.md](VENDOR.md) for the exact adaptation and upstream provenance.

**Validated pairing build: phase2-0.4 (versionCode 4, native 0.4.0).** Phase 1's requested physical
repeat/reconnect/denial/unplug tests now pass. Phase 2 adds explicit **Pair iPhone / Trust**, encrypted
record persistence, pinned authenticated TLS, saved-record reconnection and expected Developer Mode
reboot recovery. Fresh Trust, saved-record reconnection after Android process restart and normal
iPhone reboot now pass physically. After reboot, re-seat **the Galaxy/Android end** of the cable;
re-seating only the iPhone end did not restore USB on this pair. Automatic USB return is unresolved.

**Validated transport build: phase1-0.3.** This adds a 10-second live mux
hold for the unplug test, attempt-scoped cancellation, a process-owned operation that survives
screen recreation, and the last 12 native reports in one copyable diagnostic. Neither device
pairing nor production installer code is enabled in 0.3. [Phase 2 design and self-refresh contract](PHASE2-DESIGN.md).

## Phase 2 hardware verification (0.4)

Install over 0.3; do not clear app data between pairing and reconnect tests. The app never asks for
an Apple Account. No signing or app installation step exists. Pairing records stay encrypted on
the Galaxy; the ordinary diagnostic report contains no phone identifier or pairing secrets.

1. **Fresh Android host trust:** connect the unlocked iPhone to the Galaxy, scan, tap **Pair iPhone /
   Trust** and allow Android USB access. On the iPhone choose **Trust This Computer**, entering its
   passcode when asked. No reset of existing desktop trust is needed: this is a separate host.
   Expect PASS, `pairing.pairedNow=true`, `recordSaved=true`, `tlsAuthenticated=true` and four info
   fields. Trust can wait 90 s; the entire operation is bounded by 180 s. A TLS failure is a blocker,
   even if pairing was accepted; the accepted record is retained for inspection/retry.
2. **Saved-record reconnect:** disconnect/reconnect, tap **Saved pairing: authenticated reconnect**
   and allow Android access if asked. Expect PASS, `pairedNow=false`, `recordSaved=true`,
   `tlsAuthenticated=true`, without another iPhone Trust prompt. Android's USB dialog is separate.
3. **Process restart:** with the computer attached, the agent can force-stop/reopen only this
   test package. Reconnect to the iPhone and use **Saved pairing** again. Expect the same result;
   process-local report history can reset, but the encrypted pairing identity must survive.
4. **Developer Mode reboot:** tap **Prepare Developer Mode reboot recovery** while connected. This
   authenticates first and saves `rebootPending=true`; it does not reboot or change the iPhone.
   If Developer Mode is off, enable it in iPhone Settings -> Privacy & Security and complete its
   reboot and post-reboot confirmation. If it is already on, a normal manual iPhone restart can
   test recovery without disabling it. If detection does not return, unplug/replug **the Galaxy end**
   (iPhone-end re-seat did not work on the tested pair). After unlock/reconnection, grant Android USB access
   and tap **Saved pairing**. Expect authenticated PASS, `developerMode=true`,
   `rebootReconnectVerified=true`, `rebootPending=false`, and no new Trust prompt. If the Developer
   Mode setting is missing or status is null, report that; the app does not silently enable it.
5. **Denial/failure:** decline an initial Trust request if you want to check that path before
   accepting it. Expect `trust_denied`; an explicit retry can request Trust again. Do not revoke
   established iPhone trust globally just to test a failure. Unplug/cancel during a waiting Trust
   operation must release the connection and retain the host identity; no success is inferred.
6. Connect the Galaxy back to the computer for a filtered log capture. Device acceptance is separate
   from fake-peer TLS and synthetic encrypted-store tests; do not declare it from automated results.

The future self-refresh handoff needs a complete merged `NuvioZPairing.plist` including remote-pairing
identity on recent iOS. 0.4 preserves the complete Lockdown record but does not generate remote
pairing, enable Wi-Fi debugging, export/place a file, sign or install. `selfRefreshReady=false` is
deliberate; production handoff behavior is unchanged.

The original 0.1 harness incorrectly
used an immutable USB permission PendingIntent and treated missing result extras as denial.
Android supplies those extras through `PendingIntent.send` fill-in; immutable intents discard them.
0.2 uses a mutable explicit one-shot callback to a private receiver, and validates the selected live
USB instance and its actual permission before attempting an open. No unsafe implicit-intent flag
or exported permission receiver is used. References: [PendingIntent API](https://developer.android.com/reference/android/app/PendingIntent)
and [Android USB permission implementation](https://android.googlesource.com/platform/frameworks/base/+/6acb1cfda8d2/services/usb/java/com/android/server/usb/UsbUserPermissionManager.java).

## Build on Windows

Prerequisites: repository JDK/SDK, Android NDK **28.2.13676358**, Rust, and:

```powershell
rustup target add aarch64-linux-android x86_64-linux-android
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA/Android/Sdk"
.\gradlew.bat '-Pnuvio.androidIosUsbProbe=true' '-Pnuvio.android.distribution=full' :androidUsbProbe:assembleDebug --max-workers=4 --console=plain
```

Run from the repository root. Quoting the `-P` arguments matters in Windows PowerShell.
Gradle compiles Rust with the NDK for ARM64 (Galaxy) and x86_64 (emulator), then packages the `.so`
files through the Android generated-JNI source API. The native link uses 16 KB ELF alignment.
Output: `iosInstaller/android-probe/app/build/outputs/apk/debug/androidUsbProbe-debug.apk`.
The opt-in flag is required to even include this module. Ordinary app builds have no probe code.

## Automated verification

On this setup Gradle's connected-test runner uninstalls the target probe after running. Use it
only on a disposable/unpaired test installation. All 0.4 instrumentation ran before real pairing.
Preserve the current Galaxy's paired app: further hardware checks use the installed app and filtered
logs; updates use `adb install -r`, never uninstall/clear-data.

```powershell
cargo test --locked --manifest-path iosInstaller/android-probe/native/Cargo.toml -p nuvioz-usb-probe --lib
.\gradlew.bat '-Pnuvio.androidIosUsbProbe=true' '-Pnuvio.android.distribution=full' :androidUsbProbe:testDebugUnitTest :androidUsbProbe:connectedDebugAndroidTest :androidUsbProbe:lintDebug --max-workers=4 --console=plain
```

The host tests run the real idevice GetValue client against a framed fake Lockdown peer and
check the four-query allowlist, a pairing refusal, timeout and diagnostic reporting. Android
instrumentation verifies JNI loading, the structured negative-FD error, debug/no-network isolation
and Activity startup. Emulator results do **not** verify physical USB or ARM64 runtime behavior.

## Phase 1 transport reference: Galaxy S25+ and iPhone

These are the completed transport checks. In 0.4 the unpaired button is **USB info only (Phase 1)**;
**Pair iPhone / Trust** is the separate Phase 2 action. You do not need to repeat passed Phase 1 steps.

1. Install the APK on the Galaxy and open **Nuvio Z USB Probe**. It must show **Native loaded**.
   Tap **Native self-test**: the expected result is a `file_descriptor` blocker with both connection
   flags false. This confirms JNI execution; it is not a USB failure.
2. Connect the unlocked iPhone to the Galaxy using a data cable. iPhone 13/14 uses USB-C-to-Lightning;
   iPhone 15+ uses USB-C-to-USB-C. Keep the Galaxy as USB host/controller. If role negotiation fails,
   try a USB-C OTG adapter on the Galaxy with an appropriate data cable. No developer options,
   root, Developer Mode or pre-existing pairing file are required by this probe.
3. Tap **Scan attached devices**. An Apple device (`05ac:…`) should appear. Tap its **Probe** button
   and allow Android's USB permission dialog. The report now includes the request, callback field
   presence, current USB address/instance and `hasPermission()`. A denial must leave the app usable;
   tap Probe to retry. A changed USB instance requires selecting that new instance again.
4. Keep both phones unlocked and this screen open. Completion is bounded by a 45-second native
   deadline plus cleanup. The report separates `muxConnected`, `lockdownConnected`, `deviceInfo`,
   per-stage events and `blocker`.
5. A full pass has `ok: true`, both connection flags true, and `DeviceClass`, `ProductType`,
   `ProductVersion`, `BuildVersion`. Those are the only Lockdown fields requested. No name,
   UDID, serial number or account identifiers are collected.
6. Unplug, reconnect and probe three times. Also try unplugging during a probe and denying USB
   permission once. The UI must recover without an app restart or indefinite wait.
7. Tap **Copy diagnostic report** and return it with the exact iPhone model/iOS and Galaxy Android/
   One UI versions. Do not reset trust settings or change existing pairing records for this test.

The Phase 1 info-only mode does not generate a Trust prompt. If iOS refuses unpaired GetValue requests, it reports
the refusal and stops. That can establish mux transport while leaving the device-info acceptance
criterion incomplete; pairing is a separate Phase 2 decision.

Optional filtered computer logs (not required to operate the probe):

```powershell
adb logcat -v threadtime -s NuvioUsbProbe AndroidRuntime
```

## Interpreting blockers

| Stage/result | What it establishes / next investigation |
| --- | --- |
| `native_load` | APK ABI/JNI loading failed; not an Apple protocol result. |
| No Apple USB device | Check data cable, iPhone unlock/accessory policy and Galaxy host role. |
| `permission_denied` | Android explicitly returned false, the selected instance remains present, and `hasPermission()` is false. |
| `permission_callback_incomplete` / `permission_callback_timeout` | Missing result fields/callback; not labeled as a denial. |
| `permission_grant_lost` | Android reported a grant, but the live instance no longer has permission. |
| `usb_disconnected` / `usb_reenumerated` / `usb_identity_changed` | Selected device vanished or its identity changed; replacement devices are never automatically opened. |
| `permission_callback_missing_recovered` | Callback missing, but the same live instance has independently confirmed Android permission. |
| `android_open` / `android_permission_check` / `android_device_lookup` | Separate Java open, permission-loss or device-removal failure before JNI. |
| `file_descriptor` / `descriptors` | Android FD adoption or descriptor access failed. |
| `usb_claim` | USB configuration/interface/endpoint claim failed after permission and opening succeeded. |
| `usb_mux` | Version/setup exchange failed after the interface claim succeeded. |
| `muxConnected: true`, `lockdown` blocker | Raw mux works; port connection/protocol remains unresolved. |
| Both flags true, `device_info` refusal | Mux/Lockdown connection works; unpaired information request refused. Do not silently pair. |
| `ok: true` | Phase 1 transport/info pass on that device/cable/OS combination only. |
| `recovery_hold` | Test mode only: mux is live for 10 seconds so you can unplug before Lockdown. |
| `usb_disconnected_during_probe` / `permission_lost_during_probe` | Selected instance lost during work; cancellation requested, Java connection released before another attempt is allowed. |
| `user_cancelled` | Cancel button used; wait for the connection-released message, then retry. |

Proceed to Phase 2 only after reviewing the actual device report. Phase 1 does not establish
fresh trust pairing, TLS sessions, remote pairing, large transfers, authentication or installation.

## Current 0.4 automated verification (2026-10-23)

- **16 Rust host protocol/TLS tests pass**, using synthetic peers and generated-in-memory keys:
  Trust pending reuses one request, denial/missing/corrupt record behavior, fresh record persistence,
  authenticated saved-record reconnect, simulated expected reboot, real TLS success, plaintext/wrong
  certificate rejection, and forged signature rejection under both TLS 1.2 and 1.3.
- **24/24 Android instrumentation tests pass on the real Galaxy SM-S936B / Android 16 ARM64**,
  zero failures/skips. Ten new tests use isolated synthetic store namespaces: Keystore encryption,
  persistence across store recreation, random nonces, corruption/key loss, device-file swapping,
  interrupted atomic write, expected-reboot binding and JNI callbacks/exception handling. These tests
  do not pair with or access an iPhone. **15/15 JVM permission-state tests** also pass.
- Both native ABIs and the APK build. Lint **0 errors / 13 prototype warnings**; APK signature and
  16 KB ZIP/ELF alignment pass (16 KB runtime not tested). Build and launch verified on the Galaxy;
  versionCode 4, `phase2-0.4`. Normal builds remain excluded from this opt-in module; no release
  variant/Internet permission or production source/installer lockfile change.
- **Physical fresh pairing/TLS passes:** maintainer's 0.4 run at 23:35 has `pairedNow=true`,
  `recordSaved=true`, `tlsAuthenticated=true`, all four authenticated info fields and normal session/
  Java cleanup. Developer Mode reports true. The probe was then force-stopped/reopened without
  clearing data; saved-record reconnect and normal iPhone reboot subsequently passed. The latter
  required an Android-end cable re-seat; iPhone-end re-seat did not restore USB. Developer Mode was
  already on, so its off-to-on transition was not tested. `selfRefreshReady=false`;
  merged remote-pairing handoff, Wi-Fi debugging, signing and installation are not implemented here.

Artifact: **Nuvio-Z-USB-Pairing-phase2-0.4.apk**, **8,163,347 bytes**, SHA-256
`9d8fa9e6d707b2cd4580c03ae764f882d6fdf19b2a826360aee658f19d9aac41`.
Local evidence: `build/usb-pairing-0.4/{final-rust-tests.log,final-galaxy-verification.log,galaxy-startup.log}`,
signature/alignment logs, and the app build's connected/JVM/lint reports.

## Phase 1 reliability checklist (0.3)

Install **Nuvio-Z-USB-Probe-phase1-0.3.apk** over 0.2, then open **Nuvio Z USB Probe**.
All steps use only this separate test app. Keep both phones unlocked. Do not reset iPhone trust.
The first physical 0.3 sweep now passes repeated probes, three reconnects and real Deny/Allow retry;
active-unplug, Cancel and screen recovery remain pending (see verification below).

1. **Repeated probes:** with the cable attached, run the ordinary **Probe Apple device** five times.
   Each should finish with `ok=true`, both connection flags true and all four information fields.
   Repeated taps during work must not launch another attempt. No `busy` blocker on the next probe.
2. **Reconnects:** unplug, wait until the app sees the detach, reconnect, scan and select the current
   device. Grant access if Android asks. Repeat **three** times; each new probe must pass without
   restarting the app. A new USB address must not silently reuse the removed instance's permission.
3. **Actual denial/retry:** start a fresh cable connection and check `hasPermission=false`. Tap
   Probe, then **Deny/Cancel** in Android's USB permission dialog. Expect `permission_denied` while
   the iPhone remains listed. Tap Probe again and **Allow**; expect a full pass. If Android has
   already granted access automatically, this did not test denial: clear only the probe app's USB
   open-by-default association in Android settings, reconnect and retry. No iPhone Trust action.
   Copy the report after this step so subsequent scans do not evict the permission journal.
4. **Unplug while active:** tap **Unplug recovery test (10-second hold after mux)**. Wait until the
   status says **`recovery_hold` / UNPLUG NOW**, then unplug within ten seconds. This is a real mux
   connection, deliberately held before the information read. Expect `usb_disconnected_during_probe`,
   `ok=false` and a connection-released/ready message (normally a few seconds; never a hang).
   Reconnect, scan and run an ordinary probe; it must pass. Repeat once. If you never see the hold,
   copy the blocker instead; do not count an unplug after a normal successful probe as this test.
5. **Cancel and screen recovery:** start the hold test with the cable connected and tap **Cancel
   active probe**. Expect `user_cancelled`, connection released, and a passing ordinary retry.
   Start the hold again, press Back and reopen the app before it completes. It must still show one
   active attempt or its completed report; the next ordinary probe must pass. Reports survive screen
   recreation/reopen within the same Android process, not force-stop/process death.
6. **Return evidence:** tap **Copy diagnostic report** after the unplug/retry steps and send it
   with the denial report, test counts and Galaxy Android/One UI versions. The report includes mode
   (`androidMode`: 0 ordinary, 1 live recovery hold, 2 automated no-USB cancellation test), attempt ID,
   cancellation reason and `javaConnectionClosed`. The latter means worker cleanup finished, not
   that a descriptor was necessarily opened (negative-FD self-tests never open one).

Pass the physical reconnect, denial/retry and active-unplug rows before starting Phase 2.
Automated cancellation, fake-peer EOF and emulator permission callbacks cannot settle these rows.

## Current 0.3 verification (2026-10-23)

- **8 Rust host tests, 15 JVM tests and 14 API 34 x86_64 emulator tests pass**, zero failures/skips.
  Includes framed fake-peer EOF/retry, cancellation before/while native work runs, screen recreation,
  prevention of overlapping attempts, and 20 repeated non-USB failures with FD ownership/count checks.
  The synthetic cancellation test (`androidMode=2`) never adopts a descriptor or connects mux.
- Both native ABIs build. Lint **0 errors, 12 prototype warnings**. APK signature and ZIP/ELF
  16 KB alignment pass; 16 KB runtime is not tested. Normal builds exclude the module, which still
  has no release variant or Internet permission. Production installer sources/lockfile are unchanged.
- The first cold-emulator run timed out two existing permission callback tests at 5 seconds during
  background broadcast delivery. Test-only waits now allow 30 seconds; the final full run passes.
  The actual app's 120-second permission deadline did not change.
- **Physical Galaxy S25+ / Android 16 ARM64 -> iPhone15,2 / iOS 26.5 (23F77):** maintainer completed
  repeated ordinary probes, three cable reconnects and real denial/retry. Pulled adb logs confirm
  six consecutive ordinary passes, three reconnect passes and a passing Allow retry after explicit
  `permission_denied` with the device still present. All ten native reports have both connection
  flags true, four expected info fields, `blocker=null` and `javaConnectionClosed=true`.
  Active-unplug (`androidMode=1`), Cancel and screen recovery are not yet physically verified;
  Phase 2 remains gated on the remaining recovery checks. Source design: `PHASE2-DESIGN.md`.
  Additional physical evidence: `build/usb-reliability-0.3/device/denial-retry{.log,-reports.json,-summary.json}`.

APK: **Nuvio-Z-USB-Probe-phase1-0.3.apk**, **7,621,019 bytes**, SHA-256:
`96e89a36242eb56f1cb5a2f2bde4059f38d8348a16d5365f69760c7c3fd4547d`.
Local evidence: `build/usb-reliability-0.3/` from the repo root, with the final test logs and artifact
verification. The app build directory contains the JVM, connected-test and lint reports.


## Current 0.2 verification (2026-10-23)

- Real Galaxy S25+ / Android 16 / ARM64: **10 Android tests pass**, including actual Android
  PendingIntent delivery preserving Allow/Deny extras to the private receiver, reproduction of the
  old immutable bug, stale-request rejection, native execution and FD ownership. No USB permission
  is requested in these synthetic callback tests, and no iPhone is paired or modified.
- **15 JVM permission-state tests and 5 Rust protocol tests pass**. Both native ABIs build.
- Lint: **0 errors, 13 prototype warnings**. APK signature and ZIP/ELF 16 KB alignment verified.
- **Real Galaxy S25+ / Android 16 -> iPhone15,2 / iOS 26.5 (23F77): core Phase 1 pass.**
  At 20:17:41 the callback preserved `granted=true` and the device extra; `hasPermission()` was true.
  Java open, native FD adoption, configuration 1 -> 3, mux interface 1 claim (0x85 IN / 0x04 OUT),
  mux 2.0 exchange and Lockdown port 62078 all succeeded. All four unpaired GetValue reads returned;
  final report: `ok=true`, `muxConnected=true`, `lockdownConnected=true`, `blocker=null`.
  No pairing/StartSession was performed. A later normal physical detach was logged.
- This is one successful physical probe. Repeated reconnect/reprobe, actual denial/retry, unplug
  during a probe and physical re-enumeration remain unverified. Phase 2 has not started. Local
  evidence: `build/usb-permission-fix/phone-retest-0.2{.log,-report.json}` from the repository root.

Updated APK: `Nuvio-Z-USB-Probe-phase1-0.2.apk`, 7,599,395 bytes. SHA-256:
`245990059cd1e9badc9bbbef4e3f88fadb2b17b42327746431465b1724e4c5cb`.

## Historical initial 0.1 verification (2026-10-23)

- Windows Rust 1.99.0 / NDK 28.2.13676358: ARM64 and x86_64 libraries, debug APK and test APK build.
- Rust host tests: **5 passed**, zero failures.
- API 34 x86_64 emulator: **5 passed**, zero failures/skips; native load/negative-FD, borrowed-FD
  ownership using `/dev/null`, debug/no-network isolation and Activity startup. The emulator has
  no USB host hardware; this is not a USB success result.
- Android lint: **0 errors, 11 warnings** (prototype UI strings, target SDK matching the main app,
  backup declaration). Signature and ZIP/ELF 16 KB alignment pass; 16 KB runtime was not tested.
- Normal Gradle graph excludes the module; the opt-in graph exposes debug but no release variant.
- Native self-test button and initial screen were inspected. No physical phone was tested.

Prepared APK: `Nuvio-Z-USB-Probe-phase1.apk`, 7,566,563 bytes. SHA-256:
`84b03d7f474f0961fd1692176e8a0ef044e4a70f23f810f9fab9422b033fff0c`.
