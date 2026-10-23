# Phase 2 pairing design and entry gate

Status: Phase 2 implemented for experimental APK 0.4, 2026-10-23; real fresh Trust/encrypted save/
pinned TLS, saved-record reconnect after Android process restart and normal iPhone reboot all pass.
After reboot, unplug/replug at the Galaxy/Android end is required on this device pair; iPhone-end
replug did not restore USB. Automatic return remains unresolved. Developer Mode was already on.
The required Phase 1 repeat/reconnect/denial/active-unplug gate passes on the actual Galaxy/iPhone
pair. One active-unplug cycle is independently visible in adb logs, with 195 ms cleanup and a
passing ordinary retry. All 14 synthetic instrumentation checks also pass on the real Galaxy.
APK 0.3 remains the validated transport build; it never pairs. APK 0.4 adds explicit Trust,
encrypted private persistence, authenticated TLS and an expected-reboot/reconnect workflow.
No Apple login, signing, IPA installation, remote pairing or self-refresh file placement is enabled.

## Implemented boundaries in 0.4

- `PairingStore.java`: Keystore AES-256-GCM, random nonce per write, record-name/version AAD,
  hashed device filenames under `noBackupFilesDir`, atomic file replacement with explicit fsync,
  read-back verification, stable HostID/SystemBUID before Trust, and persisted expected reboot.
  Corruption/key loss is surfaced; existing records are never silently replaced. Unknown envelope
  fields are retained when saving the Lockdown portion so remote pairing can be added later.
- `pairing.rs`: actual iPhone identity stays private; only the explicit Pair button can request
  fresh Trust. A saved-record failure does not automatically pair again. One prepared idevice Pair
  request retries pending Trust for up to 90 s; accepted records are saved before starting TLS.
  Reconnect uses the record bound to the real iPhone, independent of the Android USB bus address.
- `paired_tls.rs`: see the important pinned-dependency finding below. Exact paired device
  certificate pinning plus real TLS 1.2/1.3 signature verification; host client certificate/key
  configured for mutual TLS. Missing/false `EnableSessionSSL` is rejected. No plaintext fallback.
  The small StartSession framing adapter is local; no production or vendored idevice is modified.
- Developer Mode is queried through read-only AMFI action 3, opened from the pinned TLS Lockdown
  session; service TLS is also pinned when requested. No reveal/enable/accept/reboot command is sent.
  Preparing reboot persists the expected phone; an authenticated saved-record reconnect with
  Developer Mode on clears that marker. This verifies the connection after the user's expected
  reboot; it does not independently prove that a physical reboot occurred. Device QA must do that.
- Private record bytes cross JNI only to/from the store. Diagnostics have booleans and allowlisted
  device info. All third-party Rust tracing and panic details are suppressed because dependency
  logs include packet payloads. The Java store bridge clears exceptions and returns fixed codes.
- Explicit limits: 180 s total pairing operation, existing detach/cancel handling throughout.
  A process death or failed disk save after the iPhone accepts Pair can leave trust accepted
  without a completed local record. The app reports storage failure and does not proceed; that
  transition needs physical failure-injection/repair work before production. It never claims
  self-refresh readiness from Lockdown alone, and never resets iPhone trust automatically.

## Existing handoff contract (reviewed at bc9de16d2)

- `../core/src/pairing.rs::build` serializes idevice 0.1.68's complete Lockdown `PairingFile`, sets
  its actual device UDID, and merges an `RpPairingFile` dictionary on iOS 17.4+. It enables Wi-Fi
  debugging for the later on-device connection. Private keys and escrow data are secrets.
- `place_for_nuvio` writes the merged bytes as **`NuvioZPairing.plist`** into the installed app's
  Documents folder. It reads back every byte and retains the previous file. SideStore's three
  filenames are a separate compatibility path; Nuvio's own refresh does not require SideStore.
- `../core/src/ondevice.rs` parses those same bytes as both `PairingFile` and `RpPairingFile`.
  It uses LocalDevVPN's address for Lockdown/TLS or remote pairing/TLS-PSK/RSD. Keep both identities.
- `../core/src/nuvio.rs` hands over the pairing file and, separately, the signing key after install.
  `../core/src/selfsign.rs` uses the file to renew profiles or install an update on the iPhone.
  Android pairing must not alter any of these production paths or mix their signing credentials
  into the Android pairing store.

Authenticated USB Lockdown alone will establish Phase 2 transport acceptance. It does **not** prove
that the full self-refresh handoff works. Before any later installer phase, generate/reuse the
remote-pairing identity where required, enable Wi-Fi debugging explicitly, build the same merged
plist, and test it with the existing parsers and on-device probe. These actions are not in 0.3.

## Identity, persistence and protocol boundaries

1. Continue using Android's granted, live USB instance for each open. Obtain the real iPhone UDID
   privately after permission; do not use rusbmux's Phase 1 routing placeholder as a device identity
   or storage key. Keep the existing unpaired four-field report; do not add personal identifiers,
   pair-record contents, certificate/key bytes, Wi-Fi addresses or packet dumps to diagnostics.
2. Create one random SystemBUID for the Android app installation and a stable HostID for each
   pairing relationship. Persist the identity before requesting Trust so a timeout/restart does not
   manufacture a different host on every retry. A corrupt or revoked record is a surfaced repair
   state, never a reason to silently replace a working record or reset iPhone trust globally.
3. Store a versioned envelope in Android's private `noBackupFilesDir`, encrypted with an
   Android Keystore AES-GCM key. Include the complete idevice-serialized Lockdown record, optional
   remote-pairing bytes and internal device binding. Use unique nonces, authenticated metadata and
   atomic replace with a recoverable last-good record. Keep `allowBackup=false`; handle a missing
   Keystore key explicitly after reinstall/restore. Never use external storage, clipboard, logs,
   shared preferences or cloud sync for these secrets. Pairing records are not Apple credentials.
4. Use an Android-specific `IdeviceProvider` that reads this store and opens ports on the current
   raw mux device. Leave the vendored daemon/preflight and its inaccessible Android sentinel off.
   A transport reconnection recreates sockets and sessions while retaining pairing identity.
5. Reuse idevice **0.1.68** pairing and TLS, in this independent experimental Cargo workspace.
   `LockdownClient::pair` retries `PairingDialogResponsePending` with one prepared request/identity;
   wrap it in a bounded, cancellable operation and surface Trust/unlock/denial/timeout separately.
   Do not loop `pair_once` blindly: its current implementation generates new certificates each call.
   Save an accepted full record before moving on; test interrupted writes and reload.
6. **Pinned dependency finding:** `LockdownClient::start_session` requires `EnableSessionSSL=true`
   and returns a **legacy protocol flag**, not success. However idevice 0.1.68's `sni.rs` verifier
   returns success for every server certificate and every TLS signature, despite its name
   `NoServerNameVerification`. The experimental code therefore does **not** use that TLS helper.
   `paired_tls.rs` reproduces only bounded StartSession framing, pins the exact paired device
   certificate and calls rustls's real signature validators. It then returns an ordinary idevice
   Lockdown client on the secured socket for GetValue/StartService/StopSession. Wrong pin, forged
   proof of the pinned key (both TLS 1.2 and 1.3), and plaintext are regression tested. Production
   installer TLS behavior is untouched and is a separate review item; do not imply it was fixed here.
7. Set the native log filter conservatively before enabling pairing: Phase 1's old DEBUG filter
   included vendor packet payloads. APK 0.3 uses INFO; vendor ERROR events also contain payloads.
   APK 0.4 disables third-party tracing entirely and retains only fixed/safe app stage events.

Pinned source reviewed locally: idevice 0.1.68 `services/lockdown.rs` (`pair`, `pair_once`,
`start_session`, `stop_session`) and the production files above. Recheck these implementations
if changing the dependency pin; do not infer a TLS result from a boolean name.

## Recovery state machine and acceptance

Use explicit states: disconnected -> USB permission -> mux -> identify -> stored-record session
or awaiting Trust -> record saved -> TLS verified. Locked, denied, timed out, revoked, storage
failure and detached are separate terminal/retry states. A cable event cancels the old attempt;
late permission/native callbacks cannot complete a replacement attempt. The process-owned Phase 1
session and monotonic cancellation IDs are the transport foundation, not the pairing store.

Developer Mode reboot is an expected disconnection: retain the record, show waiting for reboot,
discard sockets/FDs, then enumerate the new Android USB instance, obtain its permission and verify
the same iPhone privately before retrying StartSession/TLS. Trust must not be regenerated merely
because the USB address changed. User actions on the iPhone (unlock, Settings toggle, reboot and
post-reboot confirmation) remain physical steps. Do not automatically initiate a reboot in a probe.

Automate fake-peer Trust-pending/denied/TLS-failure tests, encrypted-store round trips/corruption/
atomic-save failures, device binding, restart and stale-callback rejection. Device acceptance must
then cover fresh Trust (use a new Android host identity, without resetting all iPhone trust), Trust
denial/retry, accepted record after Android process restart, cable reconnect without a new Trust
prompt, authenticated queries, revoked trust, and Developer Mode reboot/permission recovery.
Report each device result separately from mocks/emulator results. No Apple authentication,
signing, IPA transfer or installation is authorized in this phase.
