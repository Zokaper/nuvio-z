# iOS Setup v2: Stage 0 feasibility results (2026-10-05)

Helper: `iosSetup/helper`, pinned `idevice =0.1.68`, `ring` crypto provider. Test rig: Windows 11, Apple
Mobile Device Service (usbmuxd on 127.0.0.1:27015), iPhone 14 Pro class device on iOS 26.5, already
trusted, with SideStore, LocalDevVPN and Nuvio Z Debug installed. All calls were read-only.

| v1 manual gate | Detectable? | Probe | Result |
| --- | --- | --- | --- |
| iPhone present over USB | yes | usbmuxd `ListDevices` (USB only) | proven |
| Trust This Computer | yes | host pair record + lockdown `StartSession` | proven for the trusted case (31 ms) |
| Developer Mode on | yes | AMFI `get_developer_mode_status` | proven (`true`, 64 ms) |
| LocalDevVPN installed | yes | installation_proxy `Lookup` | proven (`com.jkcoxson.LocalDevVPN` 1.3.0) |
| SideStore installed | yes | installation_proxy; bundle id is `com.SideStore.SideStore.<TEAMID>` | proven (match by prefix) |
| Nuvio Z / Debug installed + version | yes | installation_proxy; ids are `com.nuvio.app.z[.debug].<TEAMID>` | proven for Debug; Stable uses the same call |
| Pairing file placed | yes | house_arrest `VendDocuments` + AFC listing of `/Documents` | proven: `ALTPairingFile.mobiledevicepairing` visible (presence only; contents never read) |

Whole `status` run: about 1.5 s.

## Not yet proven
- **Untrusted / locked / no-pair-record states.** The rig was already trusted, so the failure shapes
  of `start_session` (no pair record, user tapped Don't Trust, phone locked) are unobserved. The UI must
  treat any `trust: invalid` as "waiting for Trust" and show the raw reason in diagnostics.
- **Writing the pairing file** (needed to automate Pairing). Needs an AFC write into SideStore's
  Documents. Not attempted: it modifies the phone.
- **Developer profile trust.** AMFI exposes `trust_app_signer(profile uuid)`. Not called (it changes
  state). Until proven, "Trust the profile" stays a confirmation step.
- **SideStore sign-in / first refresh / source added / LocalDevVPN connected.** No probe found.
  Remain confirmation steps. `anisette-servers.json` in Documents hints at some container state, but
  nothing reliable.
- **Pairing file validity** (present is not the same as current). Compare a hash of the file with the
  host pair record in a later stage.

## Decisions
- Gate PASSED for: device, trust (positive), Developer Mode, LocalDevVPN, SideStore, Nuvio Z, pairing-file presence.
- iloader is downloaded at a pinned version with a verified SHA-256, never bundled
  (MIT source; name/logo need a link and no implied endorsement, see its LICENSE-BRANDING).
  v2.3.5 pins: Windows MSI `082f206b...b90df`; macOS DMG `1a968a7e...cb269`.
- Windows driver URL (`secure-appldnld.apple.com/.../iTunes64Setup.exe`, 202 MB) was still live on 2026-10-05.
