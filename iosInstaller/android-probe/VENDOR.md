# Phase 1 USB dependency patch

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

The mux handshake, framing, packet router and provider are upstream code. No new mux protocol
implementation is introduced. The harness calls `UsbDevice::new` directly, never the daemon,
network discovery, `preflight`, pair, StartSession or installer operations. Upstream library-mode
unused-code warnings are retained rather than editing unrelated code.

nusb **0.2.8** is unmodified. JNI duplicates the permission-granted UsbDeviceConnection descriptor;
nusb owns that duplicate, while Java keeps and closes its original around the complete JNI call.
The lockfile belongs exclusively to this experiment; the production installer lockfile is unchanged.

This patch is experimental and has not been proposed upstream. A real Galaxy/iPhone result is
required before choosing it as a production transport. Phase 2 must replace the placeholder
identity and provide an app-private pairing store; never enable preflight with this sentinel.
