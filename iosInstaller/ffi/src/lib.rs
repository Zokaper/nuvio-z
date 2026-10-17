//! C entry points for Nuvio Z on iOS (Phase 2). Kotlin calls these through cinterop
//! (`composeApp/src/nativeInterop/cinterop/nuviozsigner.def`); the header is `include/nuvioz_signer.h`.
//!
//! Every function that does I/O blocks the calling thread: call them off the main thread. Strings returned to
//! the caller are freed with `nzs_string_free`.
//!
//! Apple's two-factor prompt does not call back into Kotlin. While `nzs_refresh` waits for a code,
//! `nzs_pending_two_factor` returns the prompt; the app shows it and answers with `nzs_answer_two_factor`.
//! Polling keeps every call one-directional, which is the part of an FFI that is easy to get wrong.
use std::ffi::{c_char, CStr, CString};
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use nuvioz_installer_core::account::{Report, TwoFactorPrompt, TwoFactorReply};
use nuvioz_installer_core::{ondevice, pairing, selfsign};
use tokio::sync::oneshot;

/// How long a refresh waits for the person to type Apple's code.
const CODE_TIMEOUT: Duration = Duration::from_secs(5 * 60);

fn runtime() -> &'static tokio::runtime::Runtime {
    static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RUNTIME.get_or_init(|| {
        nuvioz_installer_core::init();
        tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().expect("tokio runtime")
    })
}

/// The prompt (as JSON) and where its answer goes, while a refresh is waiting for a code.
static PENDING: Mutex<Option<(String, oneshot::Sender<TwoFactorReply>)>> = Mutex::new(None);

fn arg(p: *const c_char) -> Option<String> {
    if p.is_null() {
        return None;
    }
    unsafe { CStr::from_ptr(p) }.to_str().ok().map(str::to_string)
}

fn give(value: serde_json::Value) -> *mut c_char {
    CString::new(value.to_string()).unwrap_or_default().into_raw()
}

fn read_pairing(documents_dir: &str) -> Result<Vec<u8>, String> {
    let path = PathBuf::from(documents_dir).join(pairing::NUVIO_FILE);
    std::fs::read(&path).map_err(|e| format!("{}: {e}", path.display()))
}

/// Reads `NuvioZPairing.plist` from `documents_dir`, connects to the phone's own services at `address`
/// (NULL: LocalDevVPN's 10.7.0.1) by both routes, and returns a JSON report.
#[no_mangle]
pub extern "C" fn nzs_probe(documents_dir: *const c_char, address: *const c_char) -> *mut c_char {
    let Some(dir) = arg(documents_dir) else {
        return give(serde_json::json!({ "error": "documents_dir is missing" }));
    };
    let address = arg(address).unwrap_or_else(|| ondevice::LOOPBACK.to_string());
    let bytes = match read_pairing(&dir) {
        Ok(b) => b,
        Err(e) => return give(serde_json::json!({ "pairingFile": "missing", "error": e })),
    };
    let probe = runtime().block_on(ondevice::probe(&bytes, &address));
    give(serde_json::json!({ "pairingFile": "found", "pairingBytes": bytes.len(), "probe": probe }))
}

/// The installed app's bundle ids and when its current signature expires. Does no I/O beyond reading the bundle.
#[no_mangle]
pub extern "C" fn nzs_status(app_dir: *const c_char) -> *mut c_char {
    let Some(dir) = arg(app_dir).map(PathBuf::from) else {
        return give(serde_json::json!({ "error": "app_dir is missing" }));
    };
    give(serde_json::json!({
        "bundleIds": selfsign::bundle_ids(&dir).unwrap_or_default(),
        "expires": selfsign::expiry(&dir),
    }))
}

/// The latest profile the phone has installed for each of the app's bundle ids, read over LocalDevVPN.
/// `{ ok, route, profiles: [{ bundleId, expires }] }` or `{ ok: false, error }`.
#[no_mangle]
pub extern "C" fn nzs_installed_profiles(app_dir: *const c_char, documents_dir: *const c_char, address: *const c_char) -> *mut c_char {
    let (Some(app_dir), Some(documents)) = (arg(app_dir), arg(documents_dir)) else {
        return give(serde_json::json!({ "ok": false, "error": "missing argument" }));
    };
    let address = arg(address).unwrap_or_else(|| ondevice::LOOPBACK.to_string());
    let pairing = match read_pairing(&documents) {
        Ok(b) => b,
        Err(e) => return give(serde_json::json!({ "ok": false, "error": format!("no pairing file: {e}") })),
    };
    match runtime().block_on(selfsign::installed_profiles(&PathBuf::from(app_dir), &pairing, &address)) {
        Ok((route, profiles)) => give(serde_json::json!({ "ok": true, "route": route, "profiles": profiles })),
        Err(e) => give(serde_json::json!({ "ok": false, "error": e })),
    }
}

/// Renews the app at `app_dir` (its bundle path): Apple sign-in, a fresh profile per bundle id, installed on
/// this phone through LocalDevVPN at `address` (NULL: 10.7.0.1). Blocks; returns `{ ok, report }` or
/// `{ ok: false, error: { kind, code, summary, detail } }`.
#[no_mangle]
pub extern "C" fn nzs_refresh(
    app_dir: *const c_char,
    documents_dir: *const c_char,
    email: *const c_char,
    password: *const c_char,
    address: *const c_char,
) -> *mut c_char {
    let (Some(app_dir), Some(documents), Some(email), Some(password)) = (arg(app_dir), arg(documents_dir), arg(email), arg(password)) else {
        return give(serde_json::json!({ "ok": false, "error": { "kind": "other", "summary": "missing argument" } }));
    };
    let address = arg(address).unwrap_or_else(|| ondevice::LOOPBACK.to_string());
    let pairing = match read_pairing(&documents) {
        Ok(b) => b,
        Err(e) => return give(serde_json::json!({ "ok": false, "error": { "kind": "other", "summary": format!("no pairing file: {e}") } })),
    };
    let ask = |prompt: TwoFactorPrompt| async move {
        let (tx, rx) = oneshot::channel();
        *PENDING.lock().unwrap() = Some((serde_json::to_string(&prompt).unwrap_or_default(), tx));
        let reply = tokio::time::timeout(CODE_TIMEOUT, rx).await;
        PENDING.lock().unwrap().take();
        Ok::<_, Report>(match reply {
            Ok(Ok(reply)) => reply,
            _ => TwoFactorReply::Abort,
        })
    };
    let result = runtime().block_on(selfsign::refresh(&PathBuf::from(app_dir), &pairing, &address, &email, &password, ask));
    drop(password);
    match result {
        Ok(report) => give(serde_json::json!({ "ok": true, "report": report })),
        Err(e) => give(serde_json::json!({ "ok": false, "error": e })),
    }
}

/// Apple's two-factor prompt as JSON while a refresh is waiting for a code, otherwise NULL.
#[no_mangle]
pub extern "C" fn nzs_pending_two_factor() -> *mut c_char {
    match PENDING.lock().unwrap().as_ref() {
        Some((prompt, _)) => CString::new(prompt.as_str()).unwrap_or_default().into_raw(),
        None => std::ptr::null_mut(),
    }
}

/// Answers the waiting prompt: a 6-digit code, or NULL to give up. Returns false if nothing was waiting.
#[no_mangle]
pub extern "C" fn nzs_answer_two_factor(code: *const c_char) -> bool {
    let Some((_, tx)) = PENDING.lock().unwrap().take() else { return false };
    let reply = match arg(code) {
        Some(code) => TwoFactorReply::SubmitCode(code.chars().filter(char::is_ascii_digit).collect()),
        None => TwoFactorReply::Abort,
    };
    tx.send(reply).is_ok()
}

/// Frees a string returned by this library.
#[no_mangle]
pub extern "C" fn nzs_string_free(s: *mut c_char) {
    if !s.is_null() {
        drop(unsafe { CString::from_raw(s) });
    }
}
