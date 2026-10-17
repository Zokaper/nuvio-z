//! Phase 2: Nuvio Z keeps itself signed, without SideStore.
//!
//! A refresh does what SideStore's `RefreshAppOperation` does: no re-signing and no reinstall. It downloads a
//! fresh 7-day team provisioning profile for each of the app's bundle ids (the app and its extension) and
//! installs them through the phone's `misagent` service, reached from inside the app over LocalDevVPN (see
//! `ondevice`). Seconds of work, so it fits in a background App Intent run by a Shortcuts automation.
//!
//! An update (`install_update`) is the other job: a newer unsigned IPA, signed with the same certificate the
//! computer made (its private key is handed over through `SIGNING_KEY_FILE`), installed over this app last.
use std::path::{Path, PathBuf};

use idevice::{misagent::MisagentClient, IdeviceService, RsdService};
use isideload::dev::app_ids::AppIdsApi;
use isideload::sideload::install;
use serde::Serialize;
use sha2::{Digest, Sha256};

use crate::account::{self, classify, ChooseCertificates, SignInError, TwoFactorCallback};
use crate::device::err;
use crate::{ondevice, storage};

/// The bundle ids of an installed app (the app, then each extension in `PlugIns`), as installed, i.e. with the
/// team id suffix the signer added.
pub fn bundle_ids(app_dir: &Path) -> Result<Vec<String>, String> {
    let id_of = |plist_path: &Path| -> Result<String, String> {
        let value = plist::Value::from_file(plist_path).map_err(|e| format!("{}: {e}", plist_path.display()))?;
        value.as_dictionary().and_then(|d| d.get("CFBundleIdentifier")).and_then(|v| v.as_string())
            .map(str::to_string).ok_or_else(|| format!("{}: no CFBundleIdentifier", plist_path.display()))
    };
    let mut ids = vec![id_of(&app_dir.join("Info.plist"))?];
    if let Ok(entries) = std::fs::read_dir(app_dir.join("PlugIns")) {
        let mut extensions: Vec<_> = entries.filter_map(Result::ok).map(|e| e.path())
            .filter(|p| p.extension().is_some_and(|x| x == "appex")).collect();
        extensions.sort();
        for ext in extensions {
            ids.push(id_of(&ext.join("Info.plist"))?);
        }
    }
    Ok(ids)
}

/// The plist inside a provisioning profile (it is CMS-signed; the XML sits in the clear between the markers).
pub fn profile_plist(bytes: &[u8]) -> Option<plist::Dictionary> {
    let start = bytes.windows(5).position(|w| w == b"<?xml")?;
    let end = bytes.windows(8).rposition(|w| w == b"</plist>")? + 8;
    plist::Value::from_reader_xml(std::io::Cursor::new(&bytes[start..end])).ok()?.into_dictionary()
}

/// When the app's current signature stops working: its embedded profile's ExpirationDate (ISO 8601).
pub fn expiry(app_dir: &Path) -> Option<String> {
    let bytes = std::fs::read(app_dir.join("embedded.mobileprovision")).ok()?;
    profile_plist(&bytes)?.get("ExpirationDate")?.as_date().map(|d| d.to_xml_format())
}

/// The bundle id a profile is for: its `application-identifier` entitlement minus the team prefix.
pub fn profile_bundle_id(profile: &plist::Dictionary) -> Option<String> {
    let app_id = profile.get("Entitlements")?.as_dictionary()?.get("application-identifier")?.as_string()?;
    app_id.split_once('.').map(|(_, bundle)| bundle.to_string())
}

/// The latest expiry among the profiles installed on the phone for each of `ids`. After a refresh this is what
/// moves; the profile embedded in the app bundle keeps its original date.
pub fn latest_expiries(installed: &[Vec<u8>], ids: &[String]) -> Vec<Renewed> {
    ids.iter().map(|id| {
        let expires = installed.iter()
            .filter_map(|bytes| profile_plist(bytes))
            .filter(|p| profile_bundle_id(p).as_deref() == Some(id.as_str()))
            .filter_map(|p| p.get("ExpirationDate").and_then(|v| v.as_date()).map(|d| d.to_xml_format()))
            .max();
        Renewed { bundle_id: id.clone(), expires }
    }).collect()
}

/// What the phone has installed for this app, read through misagent over LocalDevVPN (either route).
pub async fn installed_profiles(app_dir: &Path, pairing: &[u8], address: &str) -> Result<(&'static str, Vec<Renewed>), String> {
    let ids = bundle_ids(app_dir)?;
    let lockdown = async {
        let provider = ondevice::lockdown_provider(pairing, address)?;
        let mut agent = MisagentClient::connect(&provider).await.map_err(|e| format!("misagent: {}", err(e)))?;
        agent.copy_all().await.map_err(|e| format!("list profiles: {}", err(e)))
    }.await;
    let (route, all) = match lockdown {
        Ok(all) => ("lockdown", all),
        Err(first) => {
            let (mut adapter, mut handshake) = ondevice::tunnel(pairing, address).await.map_err(|e| format!("lockdown: {first}; remote pairing: {e}"))?;
            let mut agent = MisagentClient::connect_rsd(&mut adapter, &mut handshake).await.map_err(|e| format!("misagent: {}", err(e)))?;
            ("remotePairing", agent.copy_all().await.map_err(|e| format!("list profiles: {}", err(e)))?)
        }
    };
    Ok((route, latest_expiries(&all, &ids)))
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Renewed {
    pub bundle_id: String,
    pub expires: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RefreshReport {
    pub team: String,
    pub route: &'static str,
    pub profiles: Vec<Renewed>,
}

async fn install_via_lockdown(pairing: &[u8], address: &str, profiles: &[Vec<u8>]) -> Result<(), String> {
    let provider = ondevice::lockdown_provider(pairing, address)?;
    let mut agent = MisagentClient::connect(&provider).await.map_err(|e| format!("misagent: {}", err(e)))?;
    for p in profiles {
        agent.install(p.clone()).await.map_err(|e| format!("install profile: {}", err(e)))?;
    }
    Ok(())
}

async fn install_via_remote_pairing(pairing: &[u8], address: &str, profiles: &[Vec<u8>]) -> Result<(), String> {
    let (mut adapter, mut handshake) = ondevice::tunnel(pairing, address).await?;
    let mut agent = MisagentClient::connect_rsd(&mut adapter, &mut handshake).await.map_err(|e| format!("misagent: {}", err(e)))?;
    for p in profiles {
        agent.install(p.clone()).await.map_err(|e| format!("install profile: {}", err(e)))?;
    }
    Ok(())
}

/// Renews the app at `app_dir`: Apple sign-in, a fresh profile per bundle id, installed on this phone.
pub async fn refresh<C: TwoFactorCallback + Clone>(
    app_dir: &Path,
    pairing: &[u8],
    address: &str,
    email: &str,
    password: &str,
    two_factor: C,
) -> Result<RefreshReport, SignInError> {
    let fail = |e: String| classify(&e);
    let ids = bundle_ids(app_dir).map_err(fail)?;
    let mut signer = account::sign_in(email, password, account::ANISETTE_SERVERS, two_factor, None).await?;
    let team = signer.get_team().await.map_err(|e| classify(&format!("{e}")))?;
    let session = signer.get_dev_session();
    let registered = session.list_app_ids(&team, None).await.map_err(|e| classify(&format!("{e}")))?;

    let mut renewed = Vec::new();
    let mut blobs = Vec::new();
    for id in &ids {
        let app_id = registered.app_ids.iter().find(|a| &a.identifier == id)
            .ok_or_else(|| fail(format!("{id} is not registered to this Apple Account (installed with another account?)")))?;
        let profile = session.download_team_provisioning_profile(&team, app_id, None).await.map_err(|e| classify(&format!("{e}")))?;
        let bytes: Vec<u8> = profile.encoded_profile.into();
        let expires = profile_plist(&bytes).and_then(|d| d.get("ExpirationDate").and_then(|v| v.as_date()).map(|d| d.to_xml_format()));
        renewed.push(Renewed { bundle_id: id.clone(), expires });
        blobs.push(bytes);
    }

    let route = match install_via_lockdown(pairing, address, &blobs).await {
        Ok(()) => "lockdown",
        Err(lockdown) => match install_via_remote_pairing(pairing, address, &blobs).await {
            Ok(()) => "remotePairing",
            Err(rp) => return Err(fail(format!("installing the profiles failed. lockdown: {lockdown}; remote pairing: {rp}"))),
        },
    };
    Ok(RefreshReport { team: team.team_id, route, profiles: renewed })
}

/// The installer leaves the signing certificate's private key in the app's Documents under this name, so the app
/// signs its updates with the certificate the computer made instead of asking Apple for another (a free account
/// holds only a few, and a new one can revoke the one this app is signed with). Moved into the app's private
/// storage the first time the app starts, then deleted from Documents.
pub const SIGNING_KEY_FILE: &str = "NuvioZSigningKey.plist";

/// Builds the plist the installer places: `{ emailHash, key }` (the PKCS#8 DER key, never logged).
pub fn signing_key_plist(email: &str, key: &[u8]) -> Result<Vec<u8>, String> {
    let mut d = plist::Dictionary::new();
    d.insert("emailHash".into(), storage::email_hash(email).into());
    d.insert("key".into(), plist::Value::Data(key.to_vec()));
    let mut out = Vec::new();
    plist::Value::Dictionary(d).to_writer_xml(&mut out).map_err(err)?;
    Ok(out)
}

/// Moves a signing key the installer left in `documents` into this app's private storage. `Ok(false)`: none there.
pub fn import_signing_key(documents: &Path) -> Result<bool, String> {
    let path = documents.join(SIGNING_KEY_FILE);
    let Ok(bytes) = std::fs::read(&path) else { return Ok(false) };
    let dict = plist::Value::from_reader(std::io::Cursor::new(&bytes)).map_err(|e| format!("signing key: {e}"))?
        .into_dictionary().ok_or("signing key: not a dictionary")?;
    let hash = dict.get("emailHash").and_then(|v| v.as_string()).ok_or("signing key: no emailHash")?;
    let key = dict.get("key").and_then(|v| v.as_data()).ok_or("signing key: no key")?;
    storage::put_private_key(hash, key)?;
    std::fs::remove_file(&path).map_err(|e| format!("signing key: remove {}: {e}", path.display()))?;
    Ok(true)
}

/// `CFBundleIdentifier` of a bundle directory.
fn bundle_id_of(bundle: &Path) -> Result<String, String> {
    let path = bundle.join("Info.plist");
    plist::Value::from_file(&path).map_err(|e| format!("{}: {e}", path.display()))?
        .as_dictionary().and_then(|d| d.get("CFBundleIdentifier")).and_then(|v| v.as_string())
        .map(str::to_string).ok_or_else(|| format!("{}: no CFBundleIdentifier", path.display()))
}

fn set_bundle_id(bundle: &Path, id: &str) -> Result<(), String> {
    let path = bundle.join("Info.plist");
    let mut value = plist::Value::from_file(&path).map_err(|e| format!("{}: {e}", path.display()))?;
    value.as_dictionary_mut().ok_or("Info.plist is not a dictionary")?.insert("CFBundleIdentifier".into(), id.into());
    value.to_file_binary(&path).map_err(|e| format!("{}: {e}", path.display()))
}

/// isideload appends `.<TEAMID>` to the app's bundle id (and puts it after the app's part of each extension's id)
/// when it signs. An IPA that has been through that once (re-signing an installed copy, or one pulled off a
/// phone) would come out as `id.TEAM.TEAM`, which iOS installs as a second app. Undo it before signing.
pub fn strip_team_suffix(app: &Path, team: &str) -> Result<bool, String> {
    let suffix = format!(".{team}");
    let main = bundle_id_of(app)?;
    let Some(base) = main.strip_suffix(&suffix) else { return Ok(false) };
    set_bundle_id(app, base)?;
    if let Ok(entries) = std::fs::read_dir(app.join("PlugIns")) {
        for ext in entries.filter_map(Result::ok).map(|e| e.path()).filter(|p| p.extension().is_some_and(|x| x == "appex")) {
            let id = bundle_id_of(&ext)?;
            if let Some(rest) = id.strip_prefix(&format!("{main}.")) {
                set_bundle_id(&ext, &format!("{base}.{rest}"))?;
            }
        }
    }
    Ok(true)
}

/// Unpacks an IPA into `into` and returns its `Payload/<name>.app`.
pub fn unpack_ipa(ipa: &Path, into: &Path) -> Result<PathBuf, String> {
    let _ = std::fs::remove_dir_all(into);
    std::fs::create_dir_all(into).map_err(|e| format!("create {}: {e}", into.display()))?;
    let file = std::fs::File::open(ipa).map_err(|e| format!("{}: {e}", ipa.display()))?;
    zip::ZipArchive::new(file).map_err(|e| format!("not an IPA: {e}"))?
        .extract(into).map_err(|e| format!("unpack: {e}"))?;
    let apps: Vec<_> = std::fs::read_dir(into.join("Payload")).map_err(|_| "not an IPA: no Payload folder".to_string())?
        .filter_map(Result::ok).map(|e| e.path()).filter(|p| p.extension().is_some_and(|x| x == "app")).collect();
    match apps.as_slice() {
        [one] => Ok(one.clone()),
        _ => Err(format!("not an IPA: {} apps in Payload", apps.len())),
    }
}

/// The bundle id an IPA carries, without unpacking it (the installer reads it to know which app it installs).
pub fn ipa_bundle_id(ipa: &Path) -> Result<String, String> {
    let file = std::fs::File::open(ipa).map_err(|e| format!("{}: {e}", ipa.display()))?;
    let mut zip = zip::ZipArchive::new(file).map_err(|e| format!("not an IPA: {e}"))?;
    let name = (0..zip.len()).filter_map(|i| zip.name_for_index(i).map(str::to_string))
        .find(|n| n.starts_with("Payload/") && n.ends_with(".app/Info.plist") && n.matches('/').count() == 2)
        .ok_or("not an IPA: no app Info.plist")?;
    let mut bytes = Vec::new();
    std::io::Read::read_to_end(&mut zip.by_name(&name).map_err(err)?, &mut bytes).map_err(err)?;
    plist::Value::from_reader(std::io::Cursor::new(bytes)).map_err(err)?
        .as_dictionary().and_then(|d| d.get("CFBundleIdentifier")).and_then(|v| v.as_string())
        .map(str::to_string).ok_or_else(|| "not an IPA: no CFBundleIdentifier".into())
}

pub fn sha256_file(path: &Path) -> Result<String, String> {
    let mut file = std::fs::File::open(path).map_err(|e| format!("{}: {e}", path.display()))?;
    let mut hasher = Sha256::new();
    let mut buf = vec![0u8; 1 << 20];
    loop {
        let n = std::io::Read::read(&mut file, &mut buf).map_err(err)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hex::encode(hasher.finalize()))
}

/// Downloads `url` to `dest` without holding it in memory, and checks it against `sha256` when given.
pub async fn download(url: &str, dest: &Path, sha256: Option<&str>, progress: impl Fn(f32)) -> Result<(), String> {
    use std::io::Write;
    if !url.starts_with("https://") {
        return Err(format!("only https downloads: {url}"));
    }
    let mut response = reqwest::get(url).await.map_err(|e| format!("download: {e}"))?;
    if !response.status().is_success() {
        return Err(format!("download: HTTP {}", response.status()));
    }
    let total = response.content_length().unwrap_or(0) as f32;
    let mut out = std::fs::File::create(dest).map_err(|e| format!("{}: {e}", dest.display()))?;
    let mut hasher = Sha256::new();
    let mut done = 0f32;
    while let Some(chunk) = response.chunk().await.map_err(|e| format!("download: {e}"))? {
        out.write_all(&chunk).map_err(|e| format!("write: {e}"))?;
        hasher.update(&chunk);
        done += chunk.len() as f32;
        if total > 0.0 {
            progress(done / total);
        }
    }
    let got = hex::encode(hasher.finalize());
    if let Some(want) = sha256 {
        if !got.eq_ignore_ascii_case(want.trim()) {
            let _ = std::fs::remove_file(dest);
            return Err(format!("download: SHA-256 is {got}, expected {want}"));
        }
    }
    Ok(())
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateReport {
    pub team: String,
    pub route: &'static str,
    pub bundle_id: String,
    pub version: Option<String>,
}

/// Where an update is unpacked and signed: the app's own temporary folder on the phone.
fn work_dir() -> PathBuf {
    std::env::temp_dir().join("nuvioz-update")
}

/// Installs a newer copy of this app over itself: `source` is an https URL (checked against `sha256` when given)
/// or a local IPA path. Signs with this account's certificate, then installs through LocalDevVPN, lockdown first,
/// the remote-pairing tunnel second. iOS ends this app when it replaces it, so on success this may never return.
/// `progress` gets a stage (download, sign, install) and a fraction.
#[allow(clippy::too_many_arguments)]
pub async fn install_update<C: TwoFactorCallback + Clone>(
    app_dir: &Path,
    source: &str,
    sha256: Option<&str>,
    pairing: &[u8],
    address: &str,
    email: &str,
    password: &str,
    two_factor: C,
    choose: Option<ChooseCertificates>,
    progress: impl Fn(&'static str, f32) + Send + Sync,
) -> Result<UpdateReport, SignInError> {
    let fail = |e: String| classify(&e);
    let installed = bundle_ids(app_dir).map_err(fail)?.into_iter().next().ok_or_else(|| fail("this app has no bundle id".into()))?;
    let work = work_dir();
    let _ = std::fs::remove_dir_all(&work);
    std::fs::create_dir_all(&work).map_err(|e| fail(format!("create {}: {e}", work.display())))?;

    let ipa = if source.starts_with("https://") {
        progress("download", 0.0);
        let dest = work.join("update.ipa");
        download(source, &dest, sha256, |f| progress("download", f)).await.map_err(fail)?;
        dest
    } else {
        let path = PathBuf::from(source);
        if let Some(want) = sha256 {
            let got = sha256_file(&path).map_err(fail)?;
            if !got.eq_ignore_ascii_case(want.trim()) {
                return Err(fail(format!("{}: SHA-256 is {got}, expected {want}", path.display())));
            }
        }
        path
    };
    progress("sign", 0.0);
    let app = unpack_ipa(&ipa, &work.join("unpacked")).map_err(fail)?;
    let version = plist::Value::from_file(app.join("Info.plist")).ok()
        .and_then(|v| v.as_dictionary().and_then(|d| d.get("CFBundleShortVersionString")).and_then(|v| v.as_string()).map(str::to_string));

    let mut signer = account::sign_in(email, password, account::ANISETTE_SERVERS, two_factor, choose).await?;
    let team = signer.get_team().await.map_err(|e| classify(&format!("{e}")))?;
    strip_team_suffix(&app, &team.team_id).map_err(fail)?;
    let incoming = format!("{}.{}", bundle_id_of(&app).map_err(fail)?, team.team_id);
    if incoming != installed {
        return Err(fail(format!("this IPA would install as {incoming}, not as this app ({installed})")));
    }

    let (signed, _) = signer.sign_app(app, Some(team.clone()), false, Some(|f: f32| {
        progress("sign", f);
        std::future::ready(())
    }), None, None).await.map_err(|e| classify(&format!("{e}")))?;

    // Installed last: iOS replaces (and ends) this app when it finishes.
    progress("install", 0.0);
    let lockdown = async {
        let provider = ondevice::lockdown_provider(pairing, address)?;
        install::install_app(&provider, &signed, |p| progress("install", p as f32 / 100.0)).await.map_err(|e| format!("{e}"))
    }.await;
    let route = match lockdown {
        Ok(()) => "lockdown",
        Err(first) => {
            let (mut adapter, mut handshake) = ondevice::tunnel(pairing, address).await
                .map_err(|e| fail(format!("installing failed. lockdown: {first}; remote pairing: {e}")))?;
            install::install_app_rsd(&mut adapter, &mut handshake, &signed, |p| progress("install", p as f32 / 100.0)).await
                .map_err(|e| fail(format!("installing failed. lockdown: {first}; remote pairing: {e}")))?;
            "remotePairing"
        }
    };
    let _ = std::fs::remove_dir_all(&work);
    Ok(UpdateReport { team: team.team_id, route, bundle_id: installed, version })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_the_plist_out_of_a_signed_profile() {
        let mut bytes = b"\x30\x82junk-before".to_vec();
        bytes.extend_from_slice(br#"<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict><key>ExpirationDate</key><date>2026-10-24T12:00:00Z</date><key>Name</key><string>t</string></dict></plist>"#);
        bytes.extend_from_slice(b"\x00junk-after");
        let d = profile_plist(&bytes).expect("plist");
        assert_eq!(d.get("ExpirationDate").and_then(|v| v.as_date()).map(|d| d.to_xml_format()).as_deref(), Some("2026-10-24T12:00:00Z"));
    }

    fn profile(app_id: &str, expires: &str) -> Vec<u8> {
        format!(r#"junk<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict><key>Entitlements</key><dict><key>application-identifier</key><string>{app_id}</string></dict><key>ExpirationDate</key><date>{expires}</date></dict></plist>junk"#).into_bytes()
    }

    #[test]
    fn the_newest_installed_profile_wins_per_bundle_id() {
        let installed = vec![
            profile("TEAM123456.com.nuvio.app.z.debug.TEAM123456", "2026-10-24T12:00:00Z"),
            profile("TEAM123456.com.nuvio.app.z.debug.TEAM123456", "2026-10-31T12:00:00Z"),
            profile("TEAM123456.com.other.app", "2027-01-01T00:00:00Z"),
        ];
        let ids = vec!["com.nuvio.app.z.debug.TEAM123456".to_string(), "com.nuvio.app.z.debug.Widget.TEAM123456".to_string()];
        let found = latest_expiries(&installed, &ids);
        assert_eq!(found[0].expires.as_deref(), Some("2026-10-31T12:00:00Z"));
        assert_eq!(found[1].expires, None);
    }

    #[test]
    fn lists_the_app_then_its_extensions() {
        let dir = std::env::temp_dir().join(format!("nuvioz-bundle-ids-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("PlugIns/Widget.appex")).unwrap();
        let write = |p: std::path::PathBuf, id: &str| {
            let mut d = plist::Dictionary::new();
            d.insert("CFBundleIdentifier".into(), id.into());
            plist::Value::Dictionary(d).to_file_xml(p).unwrap();
        };
        write(dir.join("Info.plist"), "com.nuvio.app.z.debug.TEAM123456");
        write(dir.join("PlugIns/Widget.appex/Info.plist"), "com.nuvio.app.z.debug.DownloadsWidgetExtension.TEAM123456");
        assert_eq!(bundle_ids(&dir).unwrap(), vec![
            "com.nuvio.app.z.debug.TEAM123456".to_string(),
            "com.nuvio.app.z.debug.DownloadsWidgetExtension.TEAM123456".to_string(),
        ]);
        let _ = std::fs::remove_dir_all(&dir);
    }

    fn write_id(p: std::path::PathBuf, id: &str) {
        let mut d = plist::Dictionary::new();
        d.insert("CFBundleIdentifier".into(), id.into());
        plist::Value::Dictionary(d).to_file_xml(p).unwrap();
    }

    #[test]
    fn strips_a_team_suffix_isideload_added_before() {
        let dir = std::env::temp_dir().join(format!("nuvioz-strip-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("PlugIns/W.appex")).unwrap();
        write_id(dir.join("Info.plist"), "com.nuvio.app.z.debug.TEAM123456");
        write_id(dir.join("PlugIns/W.appex/Info.plist"), "com.nuvio.app.z.debug.TEAM123456.DownloadsWidgetExtension");
        assert!(strip_team_suffix(&dir, "TEAM123456").unwrap());
        assert_eq!(bundle_id_of(&dir).unwrap(), "com.nuvio.app.z.debug");
        assert_eq!(bundle_id_of(&dir.join("PlugIns/W.appex")).unwrap(), "com.nuvio.app.z.debug.DownloadsWidgetExtension");
        // A clean IPA from CI is left alone.
        assert!(!strip_team_suffix(&dir, "TEAM123456").unwrap());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_signing_key_plist_names_the_account_by_hash() {
        let docs = std::env::temp_dir().join(format!("nuvioz-key-docs-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&docs);
        std::fs::create_dir_all(&docs).unwrap();
        assert!(!import_signing_key(&docs).unwrap());
        let bytes = signing_key_plist("You@Example.com", b"not-really-a-key").unwrap();
        let parsed = plist::Value::from_reader(std::io::Cursor::new(&bytes)).unwrap().into_dictionary().unwrap();
        assert_eq!(parsed.get("emailHash").and_then(|v| v.as_string()), Some(storage::email_hash("you@example.com").as_str()));
        assert_eq!(parsed.get("key").and_then(|v| v.as_data()), Some(&b"not-really-a-key"[..]));
        let _ = std::fs::remove_dir_all(&docs);
    }

    #[test]
    fn reads_the_bundle_id_out_of_an_ipa() {
        use std::io::Write;
        let dir = std::env::temp_dir().join(format!("nuvioz-ipa-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let ipa = dir.join("t.ipa");
        let mut zip = zip::ZipWriter::new(std::fs::File::create(&ipa).unwrap());
        let mut info = Vec::new();
        let mut d = plist::Dictionary::new();
        d.insert("CFBundleIdentifier".into(), "com.nuvio.app.z.debug".into());
        plist::Value::Dictionary(d).to_writer_xml(&mut info).unwrap();
        let opts = zip::write::SimpleFileOptions::default();
        zip.start_file("Payload/Nuvio-Z.app/PlugIns/W.appex/Info.plist", opts).unwrap();
        zip.write_all(b"<plist/>").unwrap();
        zip.start_file("Payload/Nuvio-Z.app/Info.plist", opts).unwrap();
        zip.write_all(&info).unwrap();
        zip.finish().unwrap();
        assert_eq!(ipa_bundle_id(&ipa).unwrap(), "com.nuvio.app.z.debug");
        let app = unpack_ipa(&ipa, &dir.join("out")).unwrap();
        assert_eq!(bundle_id_of(&app).unwrap(), "com.nuvio.app.z.debug");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
