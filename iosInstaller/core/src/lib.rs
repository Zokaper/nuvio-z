//! Everything the Nuvio Z iOS Installer does, without a UI.
//!
//! Kept free of Tauri so it can be unit-tested on its own and, later, built for iOS (the Phase 2
//! spike in `PLAN-ios-setup-one-sitting.md`).
pub mod account;
pub mod anisette;
pub mod device;
pub mod ondevice;
pub mod pairing;
pub mod paths;
pub mod sidestore;

/// Call once at start-up, before any network or device call.
pub fn init() {
    // Both idevice and isideload use rustls; it refuses to pick a crypto provider on its own.
    let _ = rustls::crypto::ring::default_provider().install_default();
    // Without this, network errors from isideload carry no detail.
    let _ = isideload::init();
}
