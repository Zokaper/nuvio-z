//! Phase 2: Nuvio Z keeps itself signed, without SideStore.
//!
//! A refresh does what SideStore's `RefreshAppOperation` does: no re-signing and no reinstall. It downloads a
//! fresh 7-day team provisioning profile for each of the app's bundle ids (the app and its extension) and
//! installs them through the phone's `misagent` service, reached from inside the app over LocalDevVPN (see
//! `ondevice`). Seconds of work, so it fits in a background App Intent run by a Shortcuts automation.
//! Re-signing and reinstalling is only needed for an update or a new certificate (a later step).
use std::path::Path;

use idevice::{misagent::MisagentClient, IdeviceService, RsdService};
use isideload::dev::app_ids::AppIdsApi;
use serde::Serialize;

use crate::account::{self, classify, SignInError, TwoFactorCallback};
use crate::device::err;
use crate::ondevice;

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
pub async fn refresh<C: TwoFactorCallback>(
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
}
