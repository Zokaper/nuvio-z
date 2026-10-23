//! Direct Android install proof, using the desktop installer's exact isideload/idevice pins.
//! No credentials, certificate material, phone identity or raw SDK errors enter diagnostics.
use crate::pairing::{Failure, Store};
use idevice::{IdeviceService, provider::IdeviceProvider};
use isideload::{
    anisette::remote_v3::RemoteV3AnisetteProvider,
    auth::apple_account::{AppleAccount, TwoFactorCallbackParams, TwoFactorCallbackResponse},
    dev::{
        certificates::DevelopmentCertificate, developer_session::DeveloperSession,
        devices::DevicesApi,
    },
    sideload::{SideloaderBuilder, builder::MaxCertsBehavior},
    util::{callbacks::MaxCertsCallbackBox, storage::SideloadingStorage},
};
use jni::{
    JNIEnv, JavaVM,
    objects::{GlobalRef, JByteArray, JCharArray, JObject, JString, JValue},
};
use rootcause::prelude::*;
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::{
    path::PathBuf,
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::Duration,
};
use tokio::sync::oneshot;
use zeroize::{Zeroize, Zeroizing};

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn sdk_errors_never_echo_credentials_or_private_material() {
        for text in [
            "request failed for account private@example.invalid password example-secret",
            "unknown error: private@example.invalid example-secret private-key-marker",
        ] {
            let (_, safe) = safe_failure("signing", text);
            assert!(!safe.contains("private@example.invalid"));
            assert!(!safe.contains("example-secret"));
            assert!(!safe.contains("private-key-marker"));
        }
    }
}
const IPA_URL: &str = "https://github.com/Zokaper/nuvio-z/releases/download/debug-v0.5.4-z3.112/Nuvio-Z-iOS-0.5.4-z3-112-debug-unsigned.ipa";
const IPA_SHA: &str = "46c8f82b0dacd326cde2a0115779669a0f7258c8892cf7f826597a5fff7f7991";
const BASE_BUNDLE: &str = "com.nuvio.app.z.debug";
const SERVERS: &[&str] = &[
    "https://new.ani.sidestore.io",
    "https://mac-ani.jkcoxson.com",
    "https://ani3.stikstore.app",
    "https://ani.jkcoxson.com",
    "https://ani2.stikstore.app",
];

#[derive(Clone)]
pub struct SigningStorage {
    vm: Arc<JavaVM>,
    object: GlobalRef,
}
impl SigningStorage {
    fn call<T>(
        &self,
        f: impl FnOnce(&mut JNIEnv, &JObject) -> Result<T, jni::errors::Error>,
    ) -> Result<T, rootcause::Report> {
        let mut env = self
            .vm
            .attach_current_thread()
            .map_err(|_| report!("Android storage thread unavailable"))?;
        let result = f(&mut env, self.object.as_obj());
        if result.is_err() {
            let _ = env.exception_clear();
        }
        result.map_err(|_| {
            report!("Encrypted Android signing storage unavailable; no secret details logged")
        })
    }
}
impl SideloadingStorage for SigningStorage {
    fn store_data(&self, key: &str, value: &[u8]) -> Result<(), rootcause::Report> {
        self.call(|env, obj| {
            let key = env.new_string(key)?;
            let bytes = env.byte_array_from_slice(value)?;
            env.call_method(
                obj,
                "putSecret",
                "(Ljava/lang/String;[B)V",
                &[JValue::Object(&key), JValue::Object(&bytes)],
            )?;
            Ok(())
        })
    }
    fn retrieve_data(&self, key: &str) -> Result<Option<Vec<u8>>, rootcause::Report> {
        self.call(|env, obj| {
            let key = env.new_string(key)?;
            let bytes = env
                .call_method(
                    obj,
                    "getSecret",
                    "(Ljava/lang/String;)[B",
                    &[JValue::Object(&key)],
                )?
                .l()?;
            if bytes.is_null() {
                Ok(None)
            } else {
                env.convert_byte_array(JByteArray::from(bytes)).map(Some)
            }
        })
    }
    fn store(&self, key: &str, value: &str) -> Result<(), rootcause::Report> {
        self.store_data(key, value.as_bytes())
    }
    fn retrieve(&self, key: &str) -> Result<Option<String>, rootcause::Report> {
        Ok(self
            .retrieve_data(key)?
            .map(|v| String::from_utf8_lossy(&v).into_owned()))
    }
    fn delete(&self, key: &str) -> Result<(), rootcause::Report> {
        self.call(|env, obj| {
            let key = env.new_string(key)?;
            env.call_method(
                obj,
                "deleteSecret",
                "(Ljava/lang/String;)V",
                &[JValue::Object(&key)],
            )?;
            Ok(())
        })
    }
}

pub struct Request {
    email: Zeroizing<String>,
    password: Zeroizing<String>,
    cache: PathBuf,
    storage: SigningStorage,
}
impl Request {
    pub fn from_java(env: &mut JNIEnv, object: JObject) -> Result<Self, Failure> {
        let email = env
            .get_field(&object, "email", "Ljava/lang/String;")
            .and_then(|v| v.l())
            .map_err(|_| ("install_input", "Account input unavailable"))?;
        let email: String = env
            .get_string(&JString::from(email))
            .map_err(|_| ("install_input", "Account input unavailable"))?
            .into();
        let cache = env
            .get_field(&object, "cacheDirectory", "Ljava/lang/String;")
            .and_then(|v| v.l())
            .map_err(|_| ("install_input", "Cache unavailable"))?;
        let cache: String = env
            .get_string(&JString::from(cache))
            .map_err(|_| ("install_input", "Cache unavailable"))?
            .into();
        let password = env
            .get_field(&object, "password", "[C")
            .and_then(|v| v.l())
            .map_err(|_| ("install_input", "Password input unavailable"))?;
        let password = JCharArray::from(password);
        let length = env
            .get_array_length(&password)
            .map_err(|_| ("install_input", "Password input unavailable"))?;
        let mut chars = vec![0u16; length as usize];
        env.get_char_array_region(&password, 0, &mut chars)
            .map_err(|_| ("install_input", "Password input unavailable"))?;
        let secret =
            String::from_utf16(&chars).map_err(|_| ("install_input", "Invalid password input"))?;
        chars.as_mut_slice().zeroize();
        let _ = env.set_char_array_region(&password, 0, &chars);
        let storage = env
            .get_field(&object, "signingStore", "Lcom/nuvio/usbprobe/PairingStore;")
            .and_then(|v| v.l())
            .map_err(|_| ("install_input", "Signing store unavailable"))?;
        let vm = env
            .get_java_vm()
            .map_err(|_| ("install_input", "Java VM unavailable"))?;
        let object = env
            .new_global_ref(storage)
            .map_err(|_| ("install_input", "Signing store unavailable"))?;
        if email.trim().is_empty() || secret.is_empty() {
            return Err((
                "install_input",
                "Enter account and password directly in the Android app",
            ));
        }
        Ok(Self {
            email: Zeroizing::new(email.trim().to_lowercase()),
            password: Zeroizing::new(secret),
            cache: cache.into(),
            storage: SigningStorage {
                vm: Arc::new(vm),
                object,
            },
        })
    }
}

struct CodePrompt {
    tx: oneshot::Sender<TwoFactorCallbackResponse>,
    sms: Option<u32>,
}
struct CertPrompt {
    id: u64,
    tx: oneshot::Sender<Option<Vec<String>>>,
    serials: Vec<String>,
    choices: Vec<String>,
}
static CODE: Mutex<Option<CodePrompt>> = Mutex::new(None);
static CERTS: Mutex<Option<CertPrompt>> = Mutex::new(None);
static PROMPTS: AtomicU64 = AtomicU64::new(0);
pub fn certificate_choices() -> String {
    CERTS
        .lock()
        .ok()
        .and_then(|p| {
            p.as_ref()
                .map(|p| serde_json::json!({"id":p.id,"choices":p.choices}).to_string())
        })
        .unwrap_or_default()
}
pub fn answer(value: &str) -> bool {
    if value == "abort" || value.starts_with("cert:") {
        if let Some(prompt) = CERTS.lock().unwrap().take() {
            let picked = value
                .strip_prefix("cert:")
                .and_then(|v| v.parse::<usize>().ok())
                .and_then(|i| prompt.serials.get(i).cloned());
            return prompt.tx.send(picked.map(|p| vec![p])).is_ok();
        }
    }
    let Some(prompt) = CODE.lock().unwrap().take() else {
        return false;
    };
    let reply = match value {
        "devices" => TwoFactorCallbackResponse::SendToDevices,
        "sms" => match prompt.sms {
            Some(id) => TwoFactorCallbackResponse::SendSms(id),
            None => {
                *CODE.lock().unwrap() = Some(prompt);
                return false;
            }
        },
        "resend" => TwoFactorCallbackResponse::ResendCode,
        "abort" => TwoFactorCallbackResponse::Abort,
        code if code.len() == 6 && code.bytes().all(|c| c.is_ascii_digit()) => {
            TwoFactorCallbackResponse::SubmitCode(code.into())
        }
        _ => {
            *CODE.lock().unwrap() = Some(prompt);
            return false;
        }
    };
    prompt.tx.send(reply).is_ok()
}
struct PromptGuard;
impl Drop for PromptGuard {
    fn drop(&mut self) {
        CODE.lock().unwrap().take();
        CERTS.lock().unwrap().take();
    }
}
fn status(stage: &str, text: impl AsRef<str>) {
    #[cfg(target_os = "android")]
    crate::android::notice(stage, text.as_ref());
    #[cfg(not(target_os = "android"))]
    let _ = (stage, text);
}
fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}
fn safe_failure(stage: &'static str, error: impl std::fmt::Display) -> Failure {
    let text = error.to_string().to_lowercase();
    let message = if text.contains("7460") || text.contains("certificate limit") {
        "Apple certificate limit; no certificate was revoked without an explicit choice"
    } else if text.contains("20101") || text.contains("20970") || text.contains("password") {
        "Apple rejected the account/password; enter it again directly in the app"
    } else if text.contains("21669") || text.contains("verification code") {
        "Apple rejected the verification code"
    } else if text.contains("80045") {
        "Apple refused this anisette identity; retry with the next configured server"
    } else if text.contains("applicationverificationfailed") {
        "iOS rejected application signature verification"
    } else if text.contains("locked") || text.contains("passwordprotected") {
        "Unlock the iPhone and retry"
    } else if text.contains("abort") || text.contains("cancel") {
        "Operation cancelled; no automatic certificate revocation"
    } else if text.contains("network") || text.contains("request") || text.contains("connect") {
        "Network/device request failed; check Android Internet and the unlocked USB-connected iPhone"
    } else {
        "Signing/install operation failed; sensitive SDK error details are withheld"
    };
    (stage, message)
}

#[derive(Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallReport {
    pub download_verified: bool,
    pub apple_authenticated: bool,
    pub app_installed: bool,
    pub pairing_handoff: bool,
    pub signing_key_handoff: bool,
    pub remote_pairing_handoff: bool,
}

async fn download(request: &Request) -> Result<PathBuf, Failure> {
    std::fs::create_dir_all(&request.cache)
        .map_err(|_| ("download", "Private cache unavailable"))?;
    let path = request.cache.join("NuvioZ-Debug-112.ipa");
    if let Ok(bytes) = std::fs::read(&path) {
        if hex(&Sha256::digest(&bytes)) == IPA_SHA {
            return Ok(path);
        }
    }
    status(
        "download",
        "Downloading official Nuvio Z Debug 112 (104 MB), with SHA-256 verification",
    );
    let client = reqwest::Client::builder()
        .user_agent("NuvioZ-Android-Install-Proof")
        .timeout(Duration::from_secs(600))
        .build()
        .map_err(|e| safe_failure("download", e))?;
    let mut response = client
        .get(IPA_URL)
        .send()
        .await
        .map_err(|e| safe_failure("download", e))?
        .error_for_status()
        .map_err(|e| safe_failure("download", e))?;
    let partial = path.with_extension("partial");
    let mut output = std::fs::File::create(&partial)
        .map_err(|_| ("download", "Cannot write private IPA cache"))?;
    let mut digest = Sha256::new();
    let mut total = 0u64;
    let mut last = 0u64;
    while let Some(bytes) = response
        .chunk()
        .await
        .map_err(|e| safe_failure("download", e))?
    {
        use std::io::Write;
        total += bytes.len() as u64;
        if total > 300 * 1024 * 1024 {
            return Err(("download", "IPA exceeds download limit"));
        }
        digest.update(&bytes);
        output
            .write_all(&bytes)
            .map_err(|_| ("download", "IPA cache write failed"))?;
        if total / 1_048_576 > last {
            last = total / 1_048_576;
            status("download", format!("Downloaded {last} / 100 MiB"));
        }
    }
    if hex(&digest.finalize()) != IPA_SHA {
        return Err((
            "download_checksum",
            "IPA SHA-256 mismatch; nothing was signed or installed",
        ));
    }
    output
        .sync_all()
        .map_err(|_| ("download", "IPA cache sync failed"))?;
    std::fs::rename(partial, &path).map_err(|_| ("download", "IPA cache finalization failed"))?;
    Ok(path)
}

pub async fn perform(
    raw: &dyn IdeviceProvider,
    pair_store: &mut dyn Store,
    request: Request,
    report: &mut InstallReport,
) -> Result<(), Failure> {
    let _prompts = PromptGuard;
    let ipa = download(&request).await?;
    report.download_verified = true;
    crate::installer_vfs::configure(request.cache.clone());
    let provider = crate::pairing::saved_provider(raw, pair_store).await?;
    let mut last = None;
    let pinned = request.storage.retrieve("anisette_server").ok().flatten();
    let mut order: Vec<&str> = SERVERS.to_vec();
    if let Some(pinned) = pinned.as_deref() {
        if SERVERS.contains(&pinned) {
            order.retain(|s| *s != pinned);
            order.insert(0, pinned);
        }
    }
    let mut signed_in = None;
    for server in order {
        status(
            "apple_sign_in",
            "Connecting to Apple through the existing isideload sign-in flow",
        );
        let anisette = RemoteV3AnisetteProvider::default()
            .map_err(|e| safe_failure("apple_sign_in", e))?
            .set_serial_number("0".into())
            .set_storage(Box::new(request.storage.clone()))
            .set_url(server);
        let mut account = match AppleAccount::builder(request.email.as_str())
            .anisette_provider(anisette)
            .build()
            .await
        {
            Ok(a) => a,
            Err(e) => {
                last = Some(safe_failure("apple_sign_in", e));
                continue;
            }
        };
        let callback = |params: TwoFactorCallbackParams| {
            Box::pin(async move {
                status(
                    "apple_two_factor",
                    "Enter the Apple verification code in this app, or choose Send code to devices / SMS",
                );
                let (tx, rx) = oneshot::channel();
                *CODE.lock().unwrap() = Some(CodePrompt {
                    tx,
                    sms: params.numbers.first().map(|n| n.id),
                });
                Ok::<_, rootcause::Report>(
                    tokio::time::timeout(Duration::from_secs(300), rx)
                        .await
                        .ok()
                        .and_then(Result::ok)
                        .unwrap_or(TwoFactorCallbackResponse::Abort),
                )
            })
        };
        let mut retried = false;
        loop {
            match account.login(request.password.as_str(), callback).await {
                Ok(()) => {
                    let _ = request.storage.store("anisette_server", server);
                    signed_in = Some(account);
                    break;
                }
                Err(e) => {
                    let details = e.to_string();
                    let safe = safe_failure("apple_sign_in", &e);
                    if !retried && (details.contains("-22410") || details.contains("-22421")) {
                        retried = true;
                        continue;
                    }
                    if details.contains("-80045")
                        || details.contains("502")
                        || details.contains("503")
                    {
                        last = Some(safe);
                        break;
                    }
                    return Err(safe);
                }
            }
        }
        if signed_in.is_some() {
            break;
        }
    }
    let mut account = signed_in
        .ok_or(last.unwrap_or(("apple_sign_in", "No configured sign-in server succeeded")))?;
    report.apple_authenticated = true;
    let session = DeveloperSession::from_account(&mut account)
        .await
        .map_err(|e| safe_failure("apple_developer", e))?;
    let max_certs: MaxCertsCallbackBox = Box::new(|certs: Vec<DevelopmentCertificate>| {
        Box::pin(async move {
            let mut serials = Vec::new();
            let mut choices = Vec::new();
            for cert in certs {
                if let Some(serial) = cert.serial_number {
                    serials.push(serial);
                    choices.push(format!(
                        "{} · expires {}",
                        cert.machine_name
                            .unwrap_or_else(|| "Existing signing certificate".into()),
                        cert.expiration_date
                            .map(|d| d.to_xml_format())
                            .unwrap_or_else(|| "unknown".into())
                    ));
                }
            }
            let (tx, rx) = oneshot::channel();
            let id = PROMPTS.fetch_add(1, Ordering::Relaxed) + 1;
            *CERTS.lock().unwrap() = Some(CertPrompt {
                id,
                tx,
                serials,
                choices,
            });
            status(
                "certificate_choice",
                "Apple certificate limit: choose explicitly in the app; revocation can stop other signed apps",
            );
            Ok(tokio::time::timeout(Duration::from_secs(300), rx)
                .await
                .ok()
                .and_then(Result::ok)
                .flatten())
        })
    });
    let mut signer = SideloaderBuilder::new(session, request.email.to_string())
        .machine_name("Nuvio Z Android Installer experiment".into())
        .storage(Box::new(request.storage.clone()))
        .max_certs_behavior(MaxCertsBehavior::Prompt(max_certs))
        .build();
    let team = signer
        .get_team()
        .await
        .map_err(|e| safe_failure("apple_team", e))?;
    signer
        .get_dev_session()
        .ensure_device_registered(&team, "Nuvio Z iPhone", &provider.udid, None)
        .await
        .map_err(|e| safe_failure("register_device", e))?;
    status("signing", "Signing Nuvio Z Debug with isideload");
    let (signed, _) = signer
        .sign_app(
            ipa,
            Some(team.clone()),
            false,
            Some(|f: f32| {
                status(
                    "signing",
                    format!("Signing Nuvio Z Debug: {:.0}%", f * 100.0),
                );
                std::future::ready(())
            }),
            None,
            Some(&provider.udid),
        )
        .await
        .map_err(|e| safe_failure("signing", e))?;
    status(
        "installing",
        "Transferring signed Nuvio Z Debug to the iPhone",
    );
    isideload::sideload::install::install_app(&provider, &signed, |f| {
        status(
            "installing",
            format!("Transferring/installing Nuvio Z Debug: {f}%"),
        )
    })
    .await
    .map_err(|e| safe_failure("installing", e))?;
    report.app_installed = true;
    let info = plist::Value::from_file(signed.join("Info.plist"))
        .map_err(|e| safe_failure("handoff", e))?;
    let bundle = info
        .as_dictionary()
        .and_then(|d| d.get("CFBundleIdentifier"))
        .and_then(plist::Value::as_string)
        .filter(|id| id.to_lowercase().starts_with(BASE_BUNDLE))
        .ok_or(("handoff", "Signed app is not Nuvio Z Debug"))?
        .to_string();
    status(
        "self_refresh_handoff",
        "Building the existing Nuvio Z self-refresh pairing handoff",
    );
    let mut lockdown = crate::paired_tls::start(
        idevice::lockdown::LockdownClient::connect(&provider)
            .await
            .map_err(|e| safe_failure("handoff", e))?,
        &provider.record,
    )
    .await
    .map_err(|e| safe_failure("handoff", e))?;
    lockdown
        .set_value(
            "EnableWifiDebugging",
            true.into(),
            Some("com.apple.mobile.wireless_lockdown"),
        )
        .await
        .map_err(|e| safe_failure("handoff", e))?;
    let host = "nuvioz-android";
    let rp_key = format!("rp:{}", hex(&Sha256::digest(provider.udid.as_bytes())));
    let mut rp = match request
        .storage
        .retrieve_data(&rp_key)
        .map_err(|e| safe_failure("handoff", e))?
    {
        Some(bytes) => idevice::remote_pairing::RpPairingFile::from_bytes(&bytes)
            .map_err(|e| safe_failure("handoff", e))?,
        None => idevice::remote_pairing::RpPairingFile::generate(host),
    };
    let service = idevice::remote_pairing::RemotePairingLockdownService::connect(&provider)
        .await
        .map_err(|e| safe_failure("handoff", e))?;
    let mut remote = service
        .into_client(host)
        .map_err(|e| safe_failure("handoff", e))?;
    remote
        .connect(&mut rp, async || "000000".to_string())
        .await
        .map_err(|e| safe_failure("handoff", e))?;
    request
        .storage
        .store_data(&rp_key, &rp.to_bytes())
        .map_err(|e| safe_failure("handoff", e))?;
    let mut record = plist::Value::from_reader(std::io::Cursor::new(
        provider
            .record
            .clone()
            .serialize()
            .map_err(|e| safe_failure("handoff", e))?,
    ))
    .map_err(|e| safe_failure("handoff", e))?
    .into_dictionary()
    .ok_or(("handoff", "Invalid Lockdown handoff record"))?;
    let remote = plist::Value::from_reader(std::io::Cursor::new(rp.to_bytes()))
        .map_err(|e| safe_failure("handoff", e))?
        .into_dictionary()
        .ok_or(("handoff", "Invalid remote pairing record"))?;
    for (k, v) in remote {
        record.insert(k, v);
    }
    let mut pairing = Vec::new();
    plist::Value::Dictionary(record)
        .to_writer_xml(&mut pairing)
        .map_err(|e| safe_failure("handoff", e))?;
    let ha = idevice::house_arrest::HouseArrestClient::connect(&provider)
        .await
        .map_err(|e| safe_failure("handoff", e))?;
    let mut afc = ha
        .vend_documents(bundle)
        .await
        .map_err(|e| safe_failure("handoff", e))?;
    put(&mut afc, "NuvioZPairing.plist", &pairing).await?;
    pairing.zeroize();
    report.pairing_handoff = true;
    report.remote_pairing_handoff = true;
    let hash = hex(&Sha256::digest(request.email.as_bytes()));
    let key = request
        .storage
        .retrieve_data(&format!("{hash}/key"))
        .map_err(|e| safe_failure("handoff", e))?
        .ok_or(("handoff", "Signing key missing from encrypted store"))?;
    let mut key_file = plist::Dictionary::new();
    key_file.insert("emailHash".into(), hash.into());
    key_file.insert("key".into(), plist::Value::Data(key));
    let mut bytes = Vec::new();
    plist::Value::Dictionary(key_file)
        .to_writer_xml(&mut bytes)
        .map_err(|e| safe_failure("handoff", e))?;
    put(&mut afc, "NuvioZSigningKey.plist", &bytes).await?;
    bytes.zeroize();
    report.signing_key_handoff = true;
    status(
        "installed",
        "Nuvio Z Debug installed with pairing and signing-key handoff. Open it on the iPhone; trust its developer profile if iOS asks",
    );
    Ok(())
}

async fn put(afc: &mut idevice::afc::AfcClient, name: &str, bytes: &[u8]) -> Result<(), Failure> {
    let path = format!("/Documents/{name}");
    let mut file = afc
        .open(path.clone(), idevice::afc::opcode::AfcFopenMode::Wr)
        .await
        .map_err(|e| safe_failure("handoff", e))?;
    file.write_entire(bytes)
        .await
        .map_err(|e| safe_failure("handoff", e))?;
    file.close().await.map_err(|e| safe_failure("handoff", e))?;
    let mut file = afc
        .open(path, idevice::afc::opcode::AfcFopenMode::RdOnly)
        .await
        .map_err(|e| safe_failure("handoff", e))?;
    let mut read = file
        .read_entire()
        .await
        .map_err(|e| safe_failure("handoff", e))?;
    file.close().await.map_err(|e| safe_failure("handoff", e))?;
    let same = read == bytes;
    read.zeroize();
    if !same {
        return Err((
            "handoff_verify",
            "Phone handoff file did not read back correctly",
        ));
    }
    Ok(())
}
