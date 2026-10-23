# Android → iPhone USB probe (Phase 1)

An opt-in **debug-only** Android application, `com.nuvio.app.z.usbprobe`, built from the Nuvio Z
repository. It has no release variant, no Internet permission, no production-app dependencies,
and no Apple authentication, signing, pairing or IPA installation behavior. It installs beside
stable and debug Nuvio Z without changing their data. The launcher is **Nuvio Z USB Probe**.

The native code reuses idevice **0.1.68** and a narrowly adapted rusbmux **0.3.0** with nusb
**0.2.8**. See [VENDOR.md](VENDOR.md) for the exact adaptation and upstream provenance.

**Current test build: phase1-0.2 (versionCode 2, native 0.2.0).** The original 0.1 harness incorrectly
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

```powershell
cargo test --locked --manifest-path iosInstaller/android-probe/native/Cargo.toml -p nuvioz-usb-probe --lib
.\gradlew.bat '-Pnuvio.androidIosUsbProbe=true' '-Pnuvio.android.distribution=full' :androidUsbProbe:testDebugUnitTest :androidUsbProbe:connectedDebugAndroidTest :androidUsbProbe:lintDebug --max-workers=4 --console=plain
```

The host tests run the real idevice GetValue client against a framed fake Lockdown peer and
check the four-query allowlist, a pairing refusal, timeout and diagnostic reporting. Android
instrumentation verifies JNI loading, the structured negative-FD error, debug/no-network isolation
and Activity startup. Emulator results do **not** verify physical USB or ARM64 runtime behavior.

## Test with the Galaxy S25+ and an iPhone

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

This probe does not generate a Trust prompt. If iOS refuses unpaired GetValue requests, it reports
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

Proceed to Phase 2 only after reviewing the actual device report. Phase 1 does not establish
fresh trust pairing, TLS sessions, remote pairing, large transfers, authentication or installation.

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
