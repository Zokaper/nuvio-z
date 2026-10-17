//! C entry points for Nuvio Z on iOS (Phase 2). Kotlin calls these through cinterop
//! (`composeApp/src/nativeInterop/cinterop/nuviozsigner.def`); the header is `include/nuvioz_signer.h`.
//!
//! Every function that does I/O blocks the calling thread: call them off the main thread. Strings returned to
//! the caller are freed with `nzs_string_free`.
//!
//! Nothing here calls back into Kotlin. While a sign-in waits for Apple's two-factor code,
//! `nzs_pending_two_factor` returns the prompt and the app answers with `nzs_answer_two_factor`; the certificate
//! chooser (`nzs_pending_certificates` / `nzs_answer_certificates`) and an update's progress
//! (`nzs_update_progress`) work the same way. Polling keeps every call one-directional, which is the part of an
//! FFI that is easy to get wrong.
use std::ffi::{c_char, CStr, CString};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use nuvioz_installer_core::account::{self, Report, SignInError, SignInErrorKind, TwoFactorPrompt, TwoFactorReply};
use nuvioz_installer_core::{ondevice, pairing, selfsign, storage};
use tokio::sync::oneshot;

/// How long a sign-in waits for the person to type Apple's code, or to pick certificates.
const ANSWER_TIMEOUT: Duration = Duration::from_secs(5 * 60);

fn runtime() -> &'static tokio::runtime::Runtime {
    static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RUNTIME.get_or_init(|| {
        nuvioz_installer_core::init();
        tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().expect("tokio runtime")
    })
}

/// The prompt (as JSON) and where its answer goes, while a sign-in is waiting for a code.
static PENDING: Mutex<Option<(String, oneshot::Sender<TwoFactorReply>)>> = Mutex::new(None);
/// The certificates to choose from (as JSON) and where the pick goes, while an update waits for one.
static CERTIFICATES: Mutex<Option<(String, oneshot::Sender<Option<Vec<String>>>)>> = Mutex::new(None);
/// The running update's stage and fraction, as JSON.
static PROGRESS: Mutex<Option<String>> = Mutex::new(None);
/// One Apple job at a time: opening the app and a Shortcuts run can both start a refresh.
static BUSY: Mutex<()> = Mutex::new(());

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

fn other(summary: impl Into<String>) -> serde_json::Value {
    let summary = summary.into();
    serde_json::json!({ "ok": false, "error": { "kind": "other", "summary": summary, "detail": summary } })
}

/// Apple's code prompt. Interactive: shown through `nzs_pending_two_factor`. Unattended (Shortcuts, background):
/// nobody can answer, so give up at once and remember that it asked, which becomes `codeNeeded`.
fn two_factor(interactive: bool, asked: Arc<AtomicBool>) -> impl Fn(TwoFactorPrompt) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<TwoFactorReply, Report>> + Send>> + Send + Sync {
    move |prompt: TwoFactorPrompt| {
        let asked = asked.clone();
        Box::pin(async move {
            asked.store(true, Ordering::SeqCst);
            if !interactive {
                return Ok(TwoFactorReply::Abort);
            }
            let (tx, rx) = oneshot::channel();
            *PENDING.lock().unwrap() = Some((serde_json::to_string(&prompt).unwrap_or_default(), tx));
            let reply = tokio::time::timeout(ANSWER_TIMEOUT, rx).await;
            PENDING.lock().unwrap().take();
            Ok(match reply {
                Ok(Ok(reply)) => reply,
                _ => TwoFactorReply::Abort,
            })
        })
    }
}

/// Free accounts hold only a few certificates; when they are all taken and none is ours, the app asks which to
/// revoke (same rule as the installer: only "Nuvio Z Installer" certificates go without asking).
fn choose_certificates(interactive: bool) -> Option<account::ChooseCertificates> {
    interactive.then(|| -> account::ChooseCertificates {
        Arc::new(|list| {
            Box::pin(async move {
                let (tx, rx) = oneshot::channel();
                *CERTIFICATES.lock().unwrap() = Some((serde_json::to_string(&list).unwrap_or_default(), tx));
                let pick = tokio::time::timeout(ANSWER_TIMEOUT, rx).await.ok().and_then(Result::ok).flatten();
                CERTIFICATES.lock().unwrap().take();
                pick
            })
        })
    })
}

fn failure(mut e: SignInError, asked_for_code: bool, interactive: bool) -> serde_json::Value {
    if asked_for_code && !interactive {
        e.kind = SignInErrorKind::CodeNeeded;
        e.summary = "Apple asked for a verification code; renew once from inside Nuvio Z".into();
    }
    serde_json::json!({ "ok": false, "error": e })
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
/// `{ ok: false, error: { kind, code, summary, detail } }`. Not `interactive`: never waits for a person, and a
/// code prompt ends it with kind `codeNeeded`.
#[no_mangle]
pub extern "C" fn nzs_refresh(
    app_dir: *const c_char,
    documents_dir: *const c_char,
    email: *const c_char,
    password: *const c_char,
    address: *const c_char,
    interactive: bool,
) -> *mut c_char {
    let (Some(app_dir), Some(documents), Some(email), Some(password)) = (arg(app_dir), arg(documents_dir), arg(email), arg(password)) else {
        return give(other("missing argument"));
    };
    let Ok(_busy) = BUSY.try_lock() else { return give(other("another renew or update is already running")) };
    let address = arg(address).unwrap_or_else(|| ondevice::LOOPBACK.to_string());
    let pairing = match read_pairing(&documents) {
        Ok(b) => b,
        Err(e) => return give(other(format!("no pairing file: {e}"))),
    };
    let asked = Arc::new(AtomicBool::new(false));
    let result = runtime().block_on(selfsign::refresh(&PathBuf::from(app_dir), &pairing, &address, &email, &password, two_factor(interactive, asked.clone())));
    drop(password);
    match result {
        Ok(report) => give(serde_json::json!({ "ok": true, "report": report })),
        Err(e) => give(failure(e, asked.load(Ordering::SeqCst), interactive)),
    }
}

/// Installs a newer copy of the app at `app_dir` over itself. `source` is an https URL or a path to an IPA on the
/// phone; `sha256` (may be NULL) is checked before anything is signed. iOS ends the app when the install
/// finishes, so a success may never be returned: the app learns it on its next start. Progress through
/// `nzs_update_progress`, the certificate chooser through `nzs_pending_certificates`.
#[no_mangle]
pub extern "C" fn nzs_install_update(
    app_dir: *const c_char,
    documents_dir: *const c_char,
    source: *const c_char,
    sha256: *const c_char,
    email: *const c_char,
    password: *const c_char,
    address: *const c_char,
) -> *mut c_char {
    let (Some(app_dir), Some(documents), Some(source), Some(email), Some(password)) =
        (arg(app_dir), arg(documents_dir), arg(source), arg(email), arg(password)) else {
        return give(other("missing argument"));
    };
    let Ok(_busy) = BUSY.try_lock() else { return give(other("another renew or update is already running")) };
    let sha256 = arg(sha256).filter(|s| !s.trim().is_empty());
    let address = arg(address).unwrap_or_else(|| ondevice::LOOPBACK.to_string());
    let pairing = match read_pairing(&documents) {
        Ok(b) => b,
        Err(e) => return give(other(format!("no pairing file: {e}"))),
    };
    let _ = selfsign::import_signing_key(&PathBuf::from(&documents));
    let asked = Arc::new(AtomicBool::new(false));
    let progress = |stage: &'static str, fraction: f32| {
        *PROGRESS.lock().unwrap() = Some(serde_json::json!({ "stage": stage, "fraction": fraction.clamp(0.0, 1.0) }).to_string());
    };
    let result = runtime().block_on(selfsign::install_update(
        &PathBuf::from(app_dir), &source, sha256.as_deref(), &pairing, &address, &email, &password,
        two_factor(true, asked.clone()), choose_certificates(true), progress,
    ));
    drop(password);
    PROGRESS.lock().unwrap().take();
    match result {
        Ok(report) => give(serde_json::json!({ "ok": true, "report": report })),
        Err(e) => give(failure(e, asked.load(Ordering::SeqCst), true)),
    }
}

/// The running update's `{ stage, fraction }` as JSON, or NULL when none is running.
#[no_mangle]
pub extern "C" fn nzs_update_progress() -> *mut c_char {
    match PROGRESS.lock().unwrap().as_ref() {
        Some(p) => CString::new(p.as_str()).unwrap_or_default().into_raw(),
        None => std::ptr::null_mut(),
    }
}

/// Apple's two-factor prompt as JSON while a sign-in is waiting for a code, otherwise NULL.
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

/// The certificates to choose from (`[{ serial, madeBy, name, expires, limit }]`) while an update waits for the
/// person to pick which to revoke, otherwise NULL.
#[no_mangle]
pub extern "C" fn nzs_pending_certificates() -> *mut c_char {
    match CERTIFICATES.lock().unwrap().as_ref() {
        Some((list, _)) => CString::new(list.as_str()).unwrap_or_default().into_raw(),
        None => std::ptr::null_mut(),
    }
}

/// Answers the chooser: a JSON array of serials to revoke, or NULL (or an empty array) to stop. False if nothing
/// was waiting.
#[no_mangle]
pub extern "C" fn nzs_answer_certificates(serials_json: *const c_char) -> bool {
    let Some((_, tx)) = CERTIFICATES.lock().unwrap().take() else { return false };
    let pick = arg(serials_json)
        .and_then(|j| serde_json::from_str::<Vec<String>>(&j).ok())
        .filter(|v| !v.is_empty());
    tx.send(pick).is_ok()
}

/// Moves the signing key the installer left in Documents into the app's private storage.
/// `{ imported: bool }` or `{ imported: false, error }`.
#[no_mangle]
pub extern "C" fn nzs_import_signing_key(documents_dir: *const c_char) -> *mut c_char {
    let Some(documents) = arg(documents_dir) else { return give(serde_json::json!({ "imported": false, "error": "missing argument" })) };
    match selfsign::import_signing_key(&PathBuf::from(documents)) {
        Ok(imported) => give(serde_json::json!({ "imported": imported })),
        Err(e) => give(serde_json::json!({ "imported": false, "error": e })),
    }
}

/// What this phone holds for signing, for the test report (presence only, never contents):
/// `{ signingKey, anisette }`. `email` may be NULL (then signingKey is false).
#[no_mangle]
pub extern "C" fn nzs_signing_state(email: *const c_char) -> *mut c_char {
    let key = arg(email).is_some_and(|e| storage::private_key(&e).is_some());
    give(serde_json::json!({ "signingKey": key, "anisette": storage::has_anisette_state() }))
}

/// Forgets the anisette identity, so Apple sees a fresh device on the next sign-in. `{ ok }` or `{ ok, error }`.
#[no_mangle]
pub extern "C" fn nzs_reset_sign_in() -> *mut c_char {
    match storage::reset_sign_in() {
        Ok(()) => give(serde_json::json!({ "ok": true })),
        Err(e) => give(serde_json::json!({ "ok": false, "error": e })),
    }
}

/// Whether LocalDevVPN answers at `address` (NULL: 10.7.0.1). Quick; no pairing, no Apple.
#[no_mangle]
pub extern "C" fn nzs_vpn_reachable(address: *const c_char) -> bool {
    let address = arg(address).unwrap_or_else(|| ondevice::LOOPBACK.to_string());
    runtime().block_on(ondevice::reachable(&address))
}

/// This library's version.
#[no_mangle]
pub extern "C" fn nzs_version() -> *mut c_char {
    CString::new(env!("CARGO_PKG_VERSION")).unwrap_or_default().into_raw()
}

/// Frees a string returned by this library.
#[no_mangle]
pub extern "C" fn nzs_string_free(s: *mut c_char) {
    if !s.is_null() {
        drop(unsafe { CString::from_raw(s) });
    }
}
