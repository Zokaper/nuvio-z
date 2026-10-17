// No console window behind the app in release builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::sync::Arc;
use std::time::Duration;

use nuvioz_installer_core::account::{self, SignInError, SignInErrorKind, Signer, TwoFactorPrompt, TwoFactorReply};
use nuvioz_installer_core::nuvio::{self, Build};
use nuvioz_installer_core::sidestore::{self, Channel, InstallError, Stage};
use nuvioz_installer_core::{anisette, credentials, device, drivers, pairing, paths, selfsign, storage};
use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager, State};
use tokio::sync::{mpsc, Mutex};

/// How long Apple's 2FA prompt waits for the person before giving up.
const CODE_TIMEOUT: Duration = Duration::from_secs(5 * 60);

#[derive(Default)]
struct Session {
    signer: Mutex<Option<Signer>>,
    /// Replies to Apple's two-factor prompt while a sign-in is waiting for one.
    replies: std::sync::Mutex<Option<mpsc::UnboundedSender<TwoFactorReply>>>,
    /// The person's pick while an install waits for them to choose certificates to revoke.
    certificates: std::sync::Mutex<Option<tokio::sync::oneshot::Sender<Option<Vec<String>>>>>,
    /// What went wrong this session, for Help › Copy report: one line each, never a password or full email.
    errors: std::sync::Mutex<Vec<String>>,
}

impl Session {
    fn note(&self, what: &str, e: &SignInError) {
        let at = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
        let code = e.code.map(|c| format!(" {c}")).unwrap_or_default();
        self.errors.lock().unwrap().push(format!("{at} {what}: {:?}{code} {}", e.kind, e.summary));
    }
}

/// When the account is out of certificates and none is ours, ask the window which to revoke.
fn ask_which_certificates(app: AppHandle) -> account::ChooseCertificates {
    Arc::new(move |list| {
        let app = app.clone();
        Box::pin(async move {
            let (tx, rx) = tokio::sync::oneshot::channel();
            *app.state::<Session>().certificates.lock().unwrap() = Some(tx);
            let _ = app.emit("certificates", &list);
            tokio::time::timeout(CODE_TIMEOUT, rx).await.ok().and_then(Result::ok).flatten()
        })
    })
}

/// The person's answer to the `certificates` event: serials to revoke, or none to stop.
#[tauri::command]
fn answer_certificates(session: State<'_, Session>, serials: Option<Vec<String>>) -> Result<(), String> {
    let tx = session.certificates.lock().unwrap().take().ok_or("nothing is waiting for certificates")?;
    tx.send(serials).map_err(|_| "the install already ended".to_string())
}

#[tauri::command]
async fn probe_device() -> device::DeviceSnapshot {
    device::probe().await
}

#[tauri::command]
async fn reveal_developer_mode() -> Result<(), String> {
    device::reveal_developer_mode().await
}

/// Re-places the pairing files, for the SideStore this session's account installed when there is one.
#[tauri::command]
async fn place_pairing(session: State<'_, Session>) -> Result<pairing::Placed, String> {
    let team = match session.signer.lock().await.as_mut() {
        Some(signer) => signer.get_team().await.ok().map(|t| t.team_id),
        None => None,
    };
    pairing::place_for_sidestore(team.as_deref()).await
}

/// "Getting this PC ready": installs Apple Devices from the Microsoft Store and starts its iPhone service, silently.
#[tauri::command]
async fn ensure_apple_software(session: State<'_, Session>) -> Result<drivers::AppleSoftware, String> {
    let result = drivers::ensure().await;
    if let Err(e) = &result {
        session.errors.lock().unwrap().push(format!("apple software: {e}"));
    }
    result
}

/// "Plug in your iPhone" when Windows sees the phone only as a camera: Apple's USB driver from Windows Update.
#[tauri::command]
async fn install_usb_driver(session: State<'_, Session>) -> Result<(), String> {
    let result = drivers::install_usb_driver().await;
    if let Err(e) = &result {
        session.errors.lock().unwrap().push(format!("iphone driver: {e}"));
    }
    result
}

/// The fallback when the silent install can't: the Store page, where the person clicks Get.
#[tauri::command]
fn open_apple_store_page() -> Result<(), String> {
    drivers::open_store_page()
}

/// Re-places Nuvio Z's own pairing file (the "Add it again" link on the last phone step).
#[tauri::command]
async fn place_nuvio_pairing(debug: bool) -> Result<pairing::Placed, String> {
    pairing::place_for_nuvio(debug).await
}

#[tauri::command]
async fn check_anisette() -> anisette::AnisetteCheck {
    anisette::check(anisette::DEFAULT_SERVER).await
}

/// Signs in. When Apple asks for a code the window gets a `two-factor` event and answers with
/// `answer_two_factor`. The password lives only for the length of this call.
#[tauri::command]
async fn sign_in(app: AppHandle, session: State<'_, Session>, email: String, password: Option<String>, remember: bool) -> Result<String, SignInError> {
    // No password means "the one this computer remembered" (the window never sees it).
    let saved = password.is_none();
    let password = match password.or_else(|| credentials::saved_password(&email)) {
        Some(p) => p,
        None => return Err(SignInError { kind: SignInErrorKind::Other, code: None, summary: "no saved password for this account".into(), detail: String::new() }),
    };
    let app_for_certs = app.clone();
    let (tx, rx) = mpsc::unbounded_channel();
    *session.replies.lock().unwrap() = Some(tx);
    let rx = Arc::new(Mutex::new(rx));
    let ask = move |prompt: TwoFactorPrompt| {
        let (app, rx) = (app.clone(), rx.clone());
        async move {
            let _ = app.emit("two-factor", &prompt);
            let reply = tokio::time::timeout(CODE_TIMEOUT, async { rx.lock().await.recv().await }).await;
            Ok::<_, account::Report>(match reply {
                Ok(Some(reply)) => reply,
                _ => TwoFactorReply::Abort,
            })
        }
    };
    let result = account::sign_in(&email, &password, account::ANISETTE_SERVERS, ask, Some(ask_which_certificates(app_for_certs))).await;
    *session.replies.lock().unwrap() = None;
    match &result {
        Ok(_) if !saved && remember => {
            let _ = credentials::remember(&email, &password);
        }
        Ok(_) if !saved => credentials::forget(),
        Err(e) if saved && e.kind == SignInErrorKind::WrongPassword => credentials::forget(),
        _ => {}
    }
    drop(password);
    if let Err(e) = &result {
        session.note("sign in", e);
    }
    let signer = result?;
    *session.signer.lock().await = Some(signer);
    Ok(email.trim().to_lowercase())
}

/// The Apple Account this computer remembers, if any. Only the email; the password stays in the keychain.
#[tauri::command]
fn saved_account() -> Option<String> {
    credentials::saved_email()
}

#[tauri::command]
fn forget_account() {
    credentials::forget();
}

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase", tag = "kind")]
enum Answer {
    Code { code: String },
    Resend,
    SendToDevices,
    Sms { id: u32 },
    Cancel,
}

#[tauri::command]
fn answer_two_factor(session: State<'_, Session>, answer: Answer) -> Result<(), String> {
    let reply = match answer {
        Answer::Code { code } => TwoFactorReply::SubmitCode(code.chars().filter(char::is_ascii_digit).collect()),
        Answer::Resend => TwoFactorReply::ResendCode,
        Answer::SendToDevices => TwoFactorReply::SendToDevices,
        Answer::Sms { id } => TwoFactorReply::SendSms(id),
        Answer::Cancel => TwoFactorReply::Abort,
    };
    let guard = session.replies.lock().unwrap();
    guard.as_ref().ok_or("no sign-in is waiting for a code")?.send(reply).map_err(|_| "the sign-in already ended".to_string())
}

#[derive(Clone, Serialize)]
struct Progress {
    stage: Stage,
    fraction: f32,
}

#[tauri::command]
async fn install_sidestore(app: AppHandle, session: State<'_, Session>, channel: Channel) -> Result<pairing::Placed, InstallError> {
    let mut guard = session.signer.lock().await;
    let signer = guard.as_mut().ok_or_else(|| InstallError {
        stage: Stage::Sign,
        error: SignInError { kind: SignInErrorKind::Other, code: None, summary: "not signed in".into(), detail: "not signed in".into() },
    })?;
    let result = sidestore::install(signer, channel, |stage, fraction| {
        let _ = app.emit("install-progress", Progress { stage, fraction });
    })
    .await;
    if let Err(e) = &result {
        session.note(&format!("install SideStore ({:?})", e.stage), &e.error);
    }
    result
}

/// The normal path: Nuvio Z straight from this computer (no SideStore), with its pairing file and signing key.
/// An IPA next to the installer wins over the hosted build; `build` picks stable or Debug (Ctrl+Shift+D).
#[tauri::command]
async fn install_nuvio(app: AppHandle, session: State<'_, Session>, build: Build) -> Result<nuvio::Installed, InstallError> {
    let mut guard = session.signer.lock().await;
    let signer = guard.as_mut().ok_or_else(|| InstallError {
        stage: Stage::Sign,
        error: SignInError { kind: SignInErrorKind::Other, code: None, summary: "not signed in".into(), detail: "not signed in".into() },
    })?;
    let result = nuvio::install(signer, build, |stage, fraction| {
        let _ = app.emit("install-progress", Progress { stage, fraction });
    })
    .await;
    if let Err(e) = &result {
        session.note(&format!("install Nuvio Z ({:?})", e.stage), &e.error);
    }
    result
}

/// What the installer will install when it installs Nuvio Z directly: an IPA next to it, if there is one.
#[tauri::command]
fn local_nuvio() -> Option<serde_json::Value> {
    let path = paths::local_nuvio_ipa()?;
    let id = selfsign::ipa_bundle_id(&path).ok();
    Some(serde_json::json!({
        "file": path.file_name().map(|n| n.to_string_lossy().into_owned()),
        "debug": id.as_deref().is_some_and(nuvio::is_debug),
        "bundleId": id,
    }))
}

/// Help › Reset sign-in data: a fresh anisette identity on the next sign-in (the fix for Apple's -22410).
/// The remembered account and the signing key stay.
#[tauri::command]
fn reset_sign_in(session: State<'_, Session>) -> Result<(), String> {
    storage::reset_sign_in()?;
    // Whoever was signed in signed in with the old identity.
    if let Ok(mut guard) = session.signer.try_lock() {
        *guard = None;
    }
    Ok(())
}

/// Help › Copy report: what the installer knows, for a tester to paste back. Presence only for secrets; no
/// device name, no full UDID, no email, no password.
#[tauri::command]
async fn diagnostics(session: State<'_, Session>) -> Result<serde_json::Value, String> {
    let saved = credentials::saved_email();
    let mut servers = Vec::new();
    for server in account::ANISETTE_SERVERS {
        let check = anisette::check(server).await;
        servers.push(serde_json::json!({ "server": server, "reachable": check.reachable }));
    }
    Ok(serde_json::json!({
        "installer": env!("CARGO_PKG_VERSION"),
        "os": format!("{} {}", std::env::consts::OS, std::env::consts::ARCH),
        "appleSoftware": drivers::state().await,
        "device": device::probe().await,
        "rememberedAccount": saved.is_some(),
        "anisetteIdentity": storage::has_anisette_state(),
        "signingKey": saved.as_deref().is_some_and(|e| storage::private_key(e).is_some()),
        "localIpa": local_nuvio(),
        "anisetteServers": servers,
        "errors": session.errors.lock().unwrap().clone(),
    }))
}

fn main() {
    nuvioz_installer_core::init();
    tauri::Builder::default()
        .manage(Session::default())
        .setup(|app| {
            // Proves the window receives events from Rust (the 2FA prompt once never arrived because no
            // capability allowed event.listen): NUVIOZ_INSTALLER_EVENT_SELFTEST=1 sends a fake prompt.
            if std::env::var_os("NUVIOZ_INSTALLER_EVENT_SELFTEST").is_some() {
                let handle = app.handle().clone();
                std::thread::spawn(move || {
                    std::thread::sleep(Duration::from_secs(3));
                    let _ = handle.emit("two-factor", serde_json::json!({ "selftest": true }));
                });
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            probe_device,
            reveal_developer_mode,
            place_pairing,
            place_nuvio_pairing,
            ensure_apple_software,
            install_usb_driver,
            open_apple_store_page,
            check_anisette,
            sign_in,
            saved_account,
            forget_account,
            answer_two_factor,
            answer_certificates,
            install_sidestore,
            install_nuvio,
            local_nuvio,
            reset_sign_in,
            diagnostics
        ])
        .run(tauri::generate_context!())
        .expect("failed to start the Nuvio Z iOS Installer");
}
