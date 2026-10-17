//! Signing in to Apple and getting a signer for this account, through isideload.
//!
//! The password is passed straight to isideload, which sends it (as an SRP proof, not in the clear) to
//! Apple through the anisette server. Nothing here stores or logs it, and the installer installs no
//! tracing subscriber, so isideload's own logging goes nowhere.
use isideload::{
    anisette::remote_v3::RemoteV3AnisetteProvider,
    auth::apple_account::AppleAccount,
    dev::{certificates::DevelopmentCertificate, developer_session::DeveloperSession},
    sideload::{builder::MaxCertsBehavior, sideloader::Sideloader, SideloaderBuilder},
    util::{
        callbacks::MaxCertsCallbackBox,
        fs_storage::FsStorage,
    },
};
use serde::Serialize;
use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

pub use isideload::auth::apple_account::{TwoFactorCallbackParams as TwoFactorPrompt, TwoFactorCallbackResponse as TwoFactorReply};
pub use isideload::util::callbacks::TwoFactorCallback;
pub use rootcause::Report;

use crate::paths;

/// The name Apple lists our signing certificate under in the account. Also how we recognise certificates
/// we may revoke ourselves (see `only_ours`).
pub const MACHINE_NAME: &str = "Nuvio Z Installer";

/// iloader 2.3.9's anisette servers, in its order. They come and go (mac-ani answered 502 for a while on
/// 2026-10-17), so sign-in moves down the list when one is not answering.
pub const ANISETTE_SERVERS: &[&str] = &[
    "https://mac-ani.jkcoxson.com",
    "https://ani.jkcoxson.com",
    "https://ani3.stikstore.app",
    "https://ani2.stikstore.app",
];

pub type Signer = Sideloader<MaxCertsCallbackBox>;

#[derive(Debug, Clone, Copy, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub enum SignInErrorKind {
    /// Apple -80045, "This action cannot be completed at this time": Apple refusing sideloading tools.
    Refused,
    WrongPassword,
    Locked,
    WrongCode,
    /// The anisette server or Apple answered 503.
    ServerUnavailable,
    /// A free account allows only a few development certificates and they are all in use by other tools.
    CertificateLimit,
    Network,
    Cancelled,
    Other,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SignInError {
    pub kind: SignInErrorKind,
    /// Apple's own error code, when there is one.
    pub code: Option<i64>,
    /// The innermost cause, one line, for the screen.
    pub summary: String,
    /// The technical text, for Help's diagnostics. Never contains the password.
    pub detail: String,
}

/// isideload's reports put each cause on a line starting with "●"; the last one is where it really failed.
fn innermost(text: &str) -> String {
    text.lines().filter_map(|l| l.trim().strip_prefix('●')).map(str::trim).last()
        .unwrap_or_else(|| text.lines().map(str::trim).find(|l| !l.is_empty()).unwrap_or(""))
        .to_string()
}

/// Apple's code from isideload's "Auth error <code>: <message>" or "Developer error <code>: <message>".
fn apple_code(text: &str) -> Option<i64> {
    for marker in ["Auth error ", "Developer error "] {
        if let Some(rest) = text.split(marker).nth(1) {
            let digits: String = rest.chars().take_while(|c| *c == '-' || c.is_ascii_digit()).collect();
            if let Ok(code) = digits.parse() {
                return Some(code);
            }
        }
    }
    None
}

/// Best effort: the codes are the ones sideloading tools report for these cases, and the text checks catch
/// the same failures when Apple or the anisette server words them differently. Anything else is `Other`,
/// shown with Apple's own message.
pub fn classify(text: &str) -> SignInError {
    let code = apple_code(text);
    let lower = text.to_lowercase();
    let kind = match code {
        Some(-80045) => SignInErrorKind::Refused,
        // -20970 "Your account information was entered incorrectly": seen from Apple on 2026-10-17 for an unknown account.
        Some(-20101) | Some(-22406) | Some(-20970) => SignInErrorKind::WrongPassword,
        Some(-20209) => SignInErrorKind::Locked,
        Some(-21669) => SignInErrorKind::WrongCode,
        Some(7460) => SignInErrorKind::CertificateLimit,
        _ if lower.contains("cannot be completed at this time") => SignInErrorKind::Refused,
        _ if ["502", "503", "504", "bad gateway", "service temporarily unavailable", "gateway timeout"].iter().any(|s| lower.contains(s)) => SignInErrorKind::ServerUnavailable,
        _ if lower.contains("maximum number of certificates") || lower.contains("already have a current") => SignInErrorKind::CertificateLimit,
        _ if lower.contains("abort") => SignInErrorKind::Cancelled,
        _ if ["dns error", "error sending request", "tcp connect error", "connection refused", "timed out"].iter().any(|s| lower.contains(s)) => SignInErrorKind::Network,
        _ => SignInErrorKind::Other,
    };
    SignInError { kind, code, summary: innermost(text), detail: text.to_string() }
}

fn storage() -> Box<FsStorage> {
    Box::new(FsStorage::new(paths::signing()))
}

/// One of the account's signing certificates, as the person is shown it when the account is full.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct CertificateChoice {
    pub serial: String,
    /// The tool that made it, as Apple records it ("SideStore", "iloader", "AltStore", ours…).
    pub made_by: Option<String>,
    pub name: Option<String>,
    pub expires: Option<String>,
    /// How many active certificates Apple allows this account, as Apple reports it.
    pub limit: Option<i64>,
}

/// Asked when the account is out of certificates and none of them is ours: returns the serials to revoke,
/// or `None` to give up. Free accounts cannot revoke on developer.apple.com, so this is the only place to.
pub type ChooseCertificates = Arc<dyn Fn(Vec<CertificateChoice>) -> Pin<Box<dyn Future<Output = Option<Vec<String>>> + Send>> + Send + Sync>;

/// Certificates this installer made; revoking these never breaks another tool's apps.
pub fn ours(certs: &[DevelopmentCertificate]) -> Vec<String> {
    certs.iter()
        .filter(|c| c.machine_name.as_deref() == Some(MACHINE_NAME))
        .filter_map(|c| c.serial_number.clone())
        .collect()
}

pub fn choices(certs: &[DevelopmentCertificate]) -> Vec<CertificateChoice> {
    certs.iter().filter_map(|c| Some(CertificateChoice {
        serial: c.serial_number.clone()?,
        made_by: c.machine_name.clone(),
        name: c.name.clone(),
        expires: c.expiration_date.as_ref().map(|d| d.to_xml_format()),
        limit: c.certificate_type.as_ref().and_then(|t| t.max_active_certs),
    })).collect()
}

/// At the certificate limit: revoke our own old certificates without asking; otherwise ask the person which
/// of the other tools' certificates to revoke (that stops the apps it signed until that tool signs them again).
fn when_full(choose: Option<ChooseCertificates>) -> MaxCertsCallbackBox {
    Box::new(move |certs: Vec<DevelopmentCertificate>| {
        let choose = choose.clone();
        Box::pin(async move {
            let mine = ours(&certs);
            if !mine.is_empty() {
                return Ok(Some(mine));
            }
            let Some(choose) = choose else { return Ok(None) };
            Ok(choose(choices(&certs)).await.filter(|picked| !picked.is_empty()))
        })
    })
}

/// Connects to the first anisette server in `servers` that answers. Nothing about the account has been
/// sent at this point (only the server's client identity is fetched), so moving to the next server never
/// becomes a second sign-in attempt at Apple.
async fn account_via(email: &str, servers: &[&str]) -> Result<AppleAccount, SignInError> {
    let mut last = None;
    for server in servers {
        let provider = RemoteV3AnisetteProvider::default()
            .map_err(|e| classify(&format!("{e}")))?
            .set_serial_number("0".to_string())
            .set_storage(storage())
            .set_url(server);
        match AppleAccount::builder(email).anisette_provider(provider).build().await {
            Ok(account) => return Ok(account),
            Err(e) => last = Some(classify(&format!("{server}: {e}"))),
        }
    }
    Err(last.unwrap_or_else(|| classify("no anisette server configured")))
}

/// Signs in and returns a signer for the account's (only, or first) team.
pub async fn sign_in<C: TwoFactorCallback>(email: &str, password: &str, servers: &[&str], two_factor: C, choose: Option<ChooseCertificates>) -> Result<Signer, SignInError> {
    let email = email.trim().to_lowercase();
    let mut account = account_via(&email, servers).await?;
    account.login(password, two_factor).await.map_err(|e| classify(&format!("{e}")))?;
    let session = DeveloperSession::from_account(&mut account).await.map_err(|e| classify(&format!("{e}")))?;
    Ok(SideloaderBuilder::new(session, email)
        .machine_name(MACHINE_NAME.to_string())
        .storage(storage())
        .max_certs_behavior(MaxCertsBehavior::Prompt(when_full(choose)))
        .build())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_apple_codes() {
        assert_eq!(apple_code("Auth error -80045: This action cannot be completed at this time."), Some(-80045));
        assert_eq!(apple_code("Failed to log in\nCaused by: Developer error 7460: You already have a current certificate"), Some(7460));
        assert_eq!(apple_code("no code here"), None);
    }

    #[test]
    fn classifies_the_errors_people_actually_hit() {
        assert_eq!(classify("Auth error -80045: This action cannot be completed at this time.").kind, SignInErrorKind::Refused);
        assert_eq!(classify("Auth error -20101: Your Apple Account or password was incorrect.").kind, SignInErrorKind::WrongPassword);
        assert_eq!(classify("Auth error -21669: Incorrect verification code.").kind, SignInErrorKind::WrongCode);
        assert_eq!(classify("Auth error -20970: Your account information was entered incorrectly.").kind, SignInErrorKind::WrongPassword);
        assert_eq!(classify("anisette server returned an error: HTTP status server error (503 Service Temporarily Unavailable)").kind, SignInErrorKind::ServerUnavailable);
        assert_eq!(classify("Developer error 7460: You already have a current iOS Development certificate").kind, SignInErrorKind::CertificateLimit);
        assert_eq!(classify("something new").kind, SignInErrorKind::Other);
    }

    #[test]
    fn keeps_the_code_and_detail() {
        let e = classify("Auth error -20209: This Apple Account has been locked for security reasons.");
        assert_eq!((e.kind, e.code), (SignInErrorKind::Locked, Some(-20209)));
        assert!(e.detail.contains("locked"));
    }

    fn cert(serial: &str, machine: &str) -> DevelopmentCertificate {
        serde_json::from_value(serde_json::json!({ "serialNumber": serial, "machineName": machine, "name": "iOS Development" })).unwrap()
    }

    #[test]
    fn only_our_own_certificates_are_revoked_without_asking() {
        let certs = vec![cert("A1", "SideStore"), cert("B2", MACHINE_NAME), cert("C3", "iloader")];
        assert_eq!(ours(&certs), vec!["B2".to_string()]);
        assert!(ours(&certs[..1]).is_empty());
        let shown = choices(&certs);
        assert_eq!(shown.len(), 3);
        assert_eq!(shown[0].made_by.as_deref(), Some("SideStore"));
    }

    #[test]
    fn a_failing_sign_in_server_is_busy_not_offline() {
        let report = " ● Failed to get anisette client info
 ├ apple_account.rs:164
 │
 ● HTTP status server error (502 Bad Gateway) for url (https://mac-ani.jkcoxson.com/v3/client_info)
 ╰ mod.rs:131";
        let e = classify(report);
        assert_eq!(e.kind, SignInErrorKind::ServerUnavailable);
        assert_eq!(e.summary, "HTTP status server error (502 Bad Gateway) for url (https://mac-ani.jkcoxson.com/v3/client_info)");
        assert_eq!(classify("Failed to connect to lockdown: device locked").kind, SignInErrorKind::Other);
    }
}
