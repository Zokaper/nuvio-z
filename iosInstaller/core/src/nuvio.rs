//! Installing Nuvio Z itself: the normal path now that Nuvio Z keeps itself signed (no SideStore on the phone).
//!
//! Which IPA: one sitting next to the installer wins, so a tester gets a single zip with both; otherwise the
//! hosted config names a GitHub release asset with a pinned SHA-256, and only Zokaper/nuvio-z release downloads
//! are accepted from it. After the install Nuvio Z gets what it needs to look after itself, in its Documents:
//! the pairing file (to reach the phone over LocalDevVPN) and this computer's signing key (to sign its updates
//! with the certificate already in use instead of asking Apple for another).
use idevice::afc::opcode::AfcFopenMode;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

use crate::account::Signer;
use crate::device::{self, bounded, err, AppKind};
use crate::sidestore::{failed, sign_and_install, InstallError, Stage};
use crate::{pairing, paths, selfsign, storage};

/// Where the installer reads which Nuvio Z builds to install. Hosted, so a build changes without a new installer.
pub const CONFIG_URL: &str = "https://raw.githubusercontent.com/Zokaper/nuvio-z/main/distribution/ios-installer/nuvio.json";
/// The only place a hosted entry may point at.
const ALLOWED_DOWNLOADS: &str = "https://github.com/Zokaper/nuvio-z/releases/download/";
const CONFIG_SCHEMA: u32 = 1;

/// The Phase 2 update test: the installer also copies the test IPA into Nuvio Z's Documents under this name, so
/// "Install this IPA as an update" inside the app has something to install without a published release.
pub const UPDATE_TEST_FILE: &str = "NuvioZ-Update-test.ipa";

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub enum Build {
    Stable,
    /// Ctrl+Shift+D in the installer: experimental, for dev testing.
    Debug,
}

#[derive(Debug, Clone, Deserialize, PartialEq)]
pub struct HostedBuild {
    pub url: String,
    pub sha256: String,
    pub version: Option<String>,
}

#[derive(Debug, Clone, Deserialize, PartialEq)]
pub struct HostedConfig {
    pub schema: u32,
    pub stable: Option<HostedBuild>,
    pub debug: Option<HostedBuild>,
}

/// Parses and validates the hosted config. `None` for anything malformed, of another schema, or pointing anywhere
/// but this repository's release downloads, or without a 64-character SHA-256.
pub fn parse_config(text: &str) -> Option<HostedConfig> {
    let config: HostedConfig = serde_json::from_str(text).ok()?;
    let valid = |b: &Option<HostedBuild>| b.as_ref().is_none_or(|b| {
        b.url.starts_with(ALLOWED_DOWNLOADS) && b.url.ends_with(".ipa") && !b.url.contains("..")
            && b.sha256.len() == 64 && b.sha256.chars().all(|c| c.is_ascii_hexdigit())
    });
    (config.schema == CONFIG_SCHEMA && valid(&config.stable) && valid(&config.debug)).then_some(config)
}

async fn fetch_config() -> Result<HostedConfig, String> {
    let response = reqwest::get(CONFIG_URL).await.map_err(|e| format!("config: {e}"))?;
    if !response.status().is_success() {
        return Err(format!("config: HTTP {}", response.status()));
    }
    parse_config(&response.text().await.map_err(|e| format!("config: {e}"))?).ok_or_else(|| "config: not valid".into())
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Located {
    pub path: PathBuf,
    /// "local" (next to the installer) or "hosted".
    pub source: &'static str,
    pub debug: bool,
}

/// The IPA to install: next to the installer if there is one, else the hosted build (downloaded and checked).
pub async fn locate(build: Build, progress: &(impl Fn(Stage, f32) + Send + Sync)) -> Result<Located, String> {
    if let Some(path) = paths::local_nuvio_ipa() {
        let id = selfsign::ipa_bundle_id(&path)?;
        return Ok(Located { debug: is_debug(&id), path, source: "local" });
    }
    let config = fetch_config().await?;
    let entry = match build {
        Build::Stable => config.stable,
        Build::Debug => config.debug,
    }.ok_or_else(|| format!("no {} build of Nuvio Z is published for direct install yet", if build == Build::Debug { "Debug" } else { "stable" }))?;
    let dir = paths::downloads();
    std::fs::create_dir_all(&dir).map_err(|e| format!("create {}: {e}", dir.display()))?;
    let path = dir.join(if build == Build::Debug { "NuvioZ-Debug.ipa" } else { "NuvioZ.ipa" });
    let cached = path.is_file() && selfsign::sha256_file(&path).is_ok_and(|h| h.eq_ignore_ascii_case(&entry.sha256));
    if !cached {
        selfsign::download(&entry.url, &path, Some(&entry.sha256), |f| progress(Stage::Download, f)).await?;
    }
    let id = selfsign::ipa_bundle_id(&path)?;
    Ok(Located { debug: is_debug(&id), path, source: "hosted" })
}

pub fn is_debug(bundle_id: &str) -> bool {
    bundle_id.to_lowercase().starts_with("com.nuvio.app.z.debug")
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Installed {
    pub placed: pairing::Placed,
    pub debug: bool,
    pub source: &'static str,
    /// The signing key went in too (it is missing when this account's key was never made on this computer).
    pub signing_key: bool,
    /// The update-test copy went in too (local test IPAs only).
    pub update_test: bool,
}

async fn put_document(phone: &device::Connected, bundle_id: &str, name: &str, bytes: &[u8]) -> Result<(), String> {
    let mut afc = device::documents(&phone.provider, bundle_id.to_string()).await?;
    let path = format!("/Documents/{name}");
    let mut f = afc.open(path, AfcFopenMode::Wr).await.map_err(|e| format!("open {name}: {}", err(e)))?;
    f.write_entire(bytes).await.map_err(|e| format!("write {name}: {}", err(e)))?;
    f.close().await.map_err(|e| format!("close {name}: {}", err(e)))
}

async fn installed_bundle(phone: &device::Connected, debug: bool) -> Result<String, String> {
    let want = if debug { AppKind::NuvioDebug } else { AppKind::NuvioStable };
    bounded("nuvio", async {
        device::installed(&phone.provider).await?.into_iter()
            .find(|(kind, _)| *kind == want)
            .map(|(_, info)| info.bundle_id)
            .ok_or_else(|| format!("Nuvio Z{} is not installed", if debug { " Debug" } else { "" }))
    }).await
}

/// Signs and installs Nuvio Z with the signed-in account, then hands it its pairing file and signing key.
pub async fn install(signer: &mut Signer, build: Build, progress: impl Fn(Stage, f32) + Send + Sync) -> Result<Installed, InstallError> {
    progress(Stage::Download, 0.0);
    let found = locate(build, &progress).await.map_err(|e| failed(Stage::Download, e))?;
    sign_and_install(signer, found.path.clone(), &progress).await?;

    progress(Stage::Pairing, 0.0);
    let placed = pairing::place_for_nuvio(found.debug).await.map_err(|e| failed(Stage::Pairing, e))?;
    let phone = device::connect("nuvioz-place-nuvio").await.map_err(|e| failed(Stage::Pairing, e))?;
    let bundle = installed_bundle(&phone, found.debug).await.map_err(|e| failed(Stage::Pairing, e))?;

    let email = signer.get_email().to_string();
    let signing_key = match storage::private_key(&email) {
        Some(key) => {
            let plist = selfsign::signing_key_plist(&email, &key).map_err(|e| failed(Stage::Pairing, e))?;
            put_document(&phone, &bundle, selfsign::SIGNING_KEY_FILE, &plist).await.map_err(|e| failed(Stage::Pairing, e))?;
            true
        }
        None => false,
    };
    let update_test = if found.source == "local" {
        progress(Stage::Pairing, 0.5);
        let bytes = std::fs::read(&found.path).map_err(|e| failed(Stage::Pairing, format!("{}: {e}", found.path.display())))?;
        put_document(&phone, &bundle, UPDATE_TEST_FILE, &bytes).await.map_err(|e| failed(Stage::Pairing, e))?;
        true
    } else {
        false
    };
    Ok(Installed { placed, debug: found.debug, source: found.source, signing_key, update_test })
}

/// Whether Nuvio Z's Documents on the phone already hold its pairing file (so a finished step can skip itself).
pub async fn has_pairing(provider: &dyn idevice::provider::IdeviceProvider, bundle_id: &str) -> Result<bool, String> {
    let mut afc = device::documents(provider, bundle_id.to_string()).await?;
    let entries = afc.list_dir("/Documents").await.map_err(err)?;
    Ok(entries.iter().any(|e| e == pairing::NUVIO_FILE))
}

#[cfg(test)]
mod tests {
    use super::*;

    const SHA: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    #[test]
    fn accepts_only_this_repositorys_release_downloads() {
        let ok = format!(r#"{{"schema":1,"debug":{{"url":"https://github.com/Zokaper/nuvio-z/releases/download/debug-100/Nuvio-Z-debug.ipa","sha256":"{SHA}"}}}}"#);
        let config = parse_config(&ok).expect("valid");
        assert!(config.stable.is_none());
        assert_eq!(config.debug.unwrap().sha256, SHA);

        let elsewhere = ok.replace("github.com/Zokaper/nuvio-z", "github.com/someone/else");
        assert!(parse_config(&elsewhere).is_none());
        let short_hash = ok.replace(SHA, "abc");
        assert!(parse_config(&short_hash).is_none());
        let schema = ok.replace(r#""schema":1"#, r#""schema":2"#);
        assert!(parse_config(&schema).is_none());
        assert!(parse_config("not json").is_none());
        assert!(parse_config(r#"{"schema":1}"#).is_some());
    }

    #[test]
    fn tells_the_debug_build_by_its_bundle_id() {
        assert!(is_debug("com.nuvio.app.z.debug"));
        assert!(!is_debug("com.nuvio.app.z"));
    }
}
