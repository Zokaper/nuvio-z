//! C entry points for Nuvio Z on iOS (Phase 2 spike). Kotlin calls these through cinterop
//! (`composeApp/src/nativeInterop/cinterop/nuviozsigner.def`); the header is `include/nuvioz_signer.h`.
//!
//! Every function blocks the calling thread: call them off the main thread. Strings returned to the caller
//! are freed with `nzs_string_free`.
use std::ffi::{c_char, CStr, CString};
use std::path::PathBuf;
use std::sync::OnceLock;

use nuvioz_installer_core::{ondevice, pairing};

fn runtime() -> &'static tokio::runtime::Runtime {
    static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RUNTIME.get_or_init(|| {
        nuvioz_installer_core::init();
        tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().expect("tokio runtime")
    })
}

fn arg(p: *const c_char) -> Option<String> {
    if p.is_null() {
        return None;
    }
    unsafe { CStr::from_ptr(p) }.to_str().ok().map(str::to_string)
}

fn give(value: serde_json::Value) -> *mut c_char {
    CString::new(value.to_string()).unwrap_or_default().into_raw()
}

/// Reads `NuvioZPairing.plist` from `documents_dir`, connects to the phone's own services at `address`
/// (NULL: LocalDevVPN's 10.7.0.1) by both routes, and returns a JSON report.
#[no_mangle]
pub extern "C" fn nzs_probe(documents_dir: *const c_char, address: *const c_char) -> *mut c_char {
    let Some(dir) = arg(documents_dir) else {
        return give(serde_json::json!({ "error": "documents_dir is missing" }));
    };
    let address = arg(address).unwrap_or_else(|| ondevice::LOOPBACK.to_string());
    let path = PathBuf::from(dir).join(pairing::NUVIO_FILE);
    let bytes = match std::fs::read(&path) {
        Ok(b) => b,
        Err(e) => return give(serde_json::json!({ "pairingFile": "missing", "error": format!("{}: {e}", path.display()) })),
    };
    let probe = runtime().block_on(ondevice::probe(&bytes, &address));
    give(serde_json::json!({ "pairingFile": "found", "pairingBytes": bytes.len(), "probe": probe }))
}

/// Frees a string returned by this library.
#[no_mangle]
pub extern "C" fn nzs_string_free(s: *mut c_char) {
    if !s.is_null() {
        drop(unsafe { CString::from_raw(s) });
    }
}
