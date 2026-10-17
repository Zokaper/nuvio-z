// No console window behind the app in release builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::sync::Arc;
use std::time::Duration;

use nuvioz_installer_core::account::{self, SignInError, SignInErrorKind, Signer, TwoFactorPrompt, TwoFactorReply};
use nuvioz_installer_core::sidestore::{self, Channel, InstallError, Stage};
use nuvioz_installer_core::{anisette, device, pairing};
use serde::Serialize;
use tauri::{AppHandle, Emitter, State};
use tokio::sync::{mpsc, Mutex};

/// How long Apple's 2FA prompt waits for the person before giving up.
const CODE_TIMEOUT: Duration = Duration::from_secs(5 * 60);

#[derive(Default)]
struct Session {
    signer: Mutex<Option<Signer>>,
    /// Replies to Apple's two-factor prompt while a sign-in is waiting for one.
    replies: std::sync::Mutex<Option<mpsc::UnboundedSender<TwoFactorReply>>>,
}

#[tauri::command]
async fn probe_device() -> device::DeviceSnapshot {
    device::probe().await
}

#[tauri::command]
async fn reveal_developer_mode() -> Result<(), String> {
    device::reveal_developer_mode().await
}

#[tauri::command]
async fn place_pairing() -> Result<pairing::Placed, String> {
    pairing::place_for_sidestore().await
}

#[tauri::command]
async fn check_anisette() -> anisette::AnisetteCheck {
    anisette::check(anisette::DEFAULT_SERVER).await
}

/// Signs in. When Apple asks for a code the window gets a `two-factor` event and answers with
/// `answer_two_factor`. The password lives only for the length of this call.
#[tauri::command]
async fn sign_in(app: AppHandle, session: State<'_, Session>, email: String, password: String) -> Result<String, SignInError> {
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
    let result = account::sign_in(&email, &password, anisette::DEFAULT_SERVER, ask).await;
    drop(password);
    *session.replies.lock().unwrap() = None;
    let signer = result?;
    *session.signer.lock().await = Some(signer);
    Ok(email.trim().to_lowercase())
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
        error: SignInError { kind: SignInErrorKind::Other, code: None, detail: "not signed in".into() },
    })?;
    sidestore::install(signer, channel, |stage, fraction| {
        let _ = app.emit("install-progress", Progress { stage, fraction });
    })
    .await
}

fn main() {
    nuvioz_installer_core::init();
    tauri::Builder::default()
        .manage(Session::default())
        .invoke_handler(tauri::generate_handler![
            probe_device,
            reveal_developer_mode,
            place_pairing,
            check_anisette,
            sign_in,
            answer_two_factor,
            install_sidestore
        ])
        .run(tauri::generate_context!())
        .expect("failed to start the Nuvio Z iOS Installer");
}
