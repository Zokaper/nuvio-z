# Phase 2 pairing design and entry gate

Status: design only, 2026-10-23. The maintainer confirms only the Phase 1 happy path was tested.
Phase 2 implementation starts after the [recovery checklist](README.md#phase-1-reliability-checklist-03)
passes on the actual Galaxy/iPhone pair. No Trust request, pair record, authenticated session,
Developer Mode change, Apple login, signing or installation has been added to APK 0.3.

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
6. `LockdownClient::start_session` requires `EnableSessionSSL=true` and performs the TLS handshake.
   Its returned `bool` is the **legacy protocol flag**, so `false` on modern iOS is successful,
   modern TLS, not a failed session. Require a successful authenticated GetValue after it, and use
   `stop_session` when available before dropping the connection. Do not fall back to plaintext or
   disable certificate checks to make a failure pass.
7. Set the native log filter conservatively before enabling pairing: Phase 1's old DEBUG filter
   included vendor packet payloads. APK 0.3 now uses INFO, but Phase 2 must also audit vendor error
   payload logging and idevice logs and redact at source before any secrets are sent.

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
