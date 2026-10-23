# Experimental dependency patches

`native/vendor/rusbmux` contains the `src/`, original Cargo manifest and both licenses from
the published **rusbmux 0.3.0** crate:

- Source: https://static.crates.io/crates/rusbmux/rusbmux-0.3.0.crate
- Archive SHA-256: `18fe4b044738195d9763405d024f5bfd86be0e2383ffebb5dcf875c05d29fb0b`
- Upstream: https://github.com/abdullah-albanna/rusbmux
- License: MIT OR Apache-2.0; both license texts retained.

Only these upstream files are adapted:

1. `Cargo.toml`: idevice 0.1.65 -> **0.1.68**, matching the existing Nuvio installer pin.
2. `src/usb_backend/mod.rs`: Android-only `AndroidOpened(nusb::Device)` input. `open()` clones
   this already-authorized handle instead of enumerating or reopening a device path. The
   placeholder routing identity is deliberately NOT an iPhone UDID. One probe/device at a time;
   no pairing, registration or persistence may use this placeholder.
3. `src/usb_backend/nusb.rs`: Android has no nusb enumeration/hotplug APIs. Discovery returns
   an explicit unsupported error; Android UsbManager owns it instead. The endpoint path prefers
   the current USB configuration before considering another one, avoiding unnecessary changes.
4. `src/handler/mod.rs`: define an inaccessible Android sentinel for the unused daemon pairing
   path so the library compiles. This is NOT Android pairing support.
5. `src/device/usb.rs`: add an optional progress callback around handle adoption, endpoint
   selection/configuration/claim and mux version/setup. The existing constructor delegates with
   a no-op callback. Protocol operations and framing are unchanged. This lets a granted Android
   permission followed by a claim failure be reported as `usb_claim`, independently of `usb_mux`.

The mux handshake, framing, packet router and raw provider are upstream code. No new mux protocol
implementation is introduced. The harness constructs `UsbDevice` directly, never the daemon,
network discovery or `preflight`. Phase 1 modes never pair or start a session. APK 0.4's explicit
Phase 2 modes call idevice Pair and a local certificate-pinned TLS adapter; no installer operations
are linked. Upstream library-mode
unused-code warnings are retained rather than editing unrelated code.

nusb **0.2.8** is unmodified. JNI duplicates the permission-granted UsbDeviceConnection descriptor;
nusb owns that duplicate, while Java keeps and closes its original around the complete JNI call.
The lockfile belongs exclusively to this experiment; the production installer lockfile is unchanged.

This patch is experimental and has not been proposed upstream. A real Galaxy/iPhone result is
required before choosing it as a production transport. Phase 1 now has physical transport/recovery
evidence on the Galaxy/iPhone pair. In 0.4, the local `IdentifiedProvider` assigns the actual privately
read device identity to authenticated idevice connections; the raw mux routing placeholder remains
anonymous and is never a pairing store key. `PairingStore` provides encrypted private persistence.
Never enable preflight with the sentinel. No additional vendored files change for Phase 2.

**idevice 0.1.68 TLS finding:** its `sni.rs` bypasses both server certificate and TLS signature checks.
The experiment's `native/src/paired_tls.rs` therefore owns only bounded StartSession framing and
TLS wrapping through public socket APIs, with exact paired-device certificate pinning and real
rustls TLS 1.2/1.3 signature verification. Remaining Pair/GetValue/StartService/StopSession behavior
uses idevice. No idevice crate or production installer files are patched. Details and acceptance
limits: [PHASE2-DESIGN.md](PHASE2-DESIGN.md).


## isideload 0.4.6: Android HTTPS client (0.5.2)

`native/vendor/isideload` retains `src/`, `Cargo.toml`, `Cargo.toml.orig` and README from the
published **isideload 0.4.6** crate, the same version used by the desktop installer.

- Source: https://static.crates.io/crates/isideload/isideload-0.4.6.crate
- Archive SHA-256: `473bde4da71c72bcf5f1cf77acfffdf4b879b65ef989c19f8cb4c3e9ea758a76`
- Upstream: https://github.com/nab138/isideload
- Published crate's VCS revision: `8ca39b6203263e539408986e546c42e7c1cc3699` (`isideload/`).
- MIT license. The crate archive omits LICENSE; it is retained here from the same revision's
  [repository LICENSE](https://github.com/nab138/isideload/blob/8ca39b6203263e539408986e546c42e7c1cc3699/LICENSE).

Two files are adapted:

1. **`src/auth/grandslam.rs`**: on Android only, configure rustls with the public WebPKI root set
   plus the same bundled Apple root. reqwest 0.13/rustls-platform-verifier 0.7.1 cannot add roots
   on Android; omitting the Apple root failed the public GrandSlam lookup with `UnknownIssuer` on
   the real Galaxy. This adapter uses rustls' standard certificate-chain, hostname and signature
   verification, not a custom permissive verifier. Other native targets retain upstream behavior.
2. **`Cargo.toml`**: Android-only rustls 0.23 (ring/std/TLS 1.2) and webpki-roots 1.0.11 dependencies.

The experiment always supplies `debug=false`. No signing, authentication, installation or
certificate-revocation protocol is changed. The production installer still resolves its original
registry dependency and lockfile. This copy is excluded from the experiment workspace members and
used solely as a path dependency.

The separate `android_network.rs` initializes rustls-platform-verifier through its official JNI
0.22 API. Its matching Java support is `org.rustls:rustls-platform-verifier:0.2.0` from the
[official Maven archive](https://github.com/rustls/rustls-platform-verifier/tree/maven-archive/android-release-support/maven).
No trust-all network fallback is used. The no-password instrumentation test builds the SDK's Apple
client and fetches its public URL bag after verified HTTPS/anisette client-info requests.
