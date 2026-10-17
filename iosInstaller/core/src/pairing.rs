//! Builds and places the pairing file SideStore reads from its Documents folder.
//!
//! Same recipe as iloader (verified against iloader 2.3.9 `pairing.rs`, idevice 0.1.68): the host's
//! lockdown pair record, plus on iOS 17.4+ a remote-pairing (rppairing) identity merged into one plist,
//! plus Wi-Fi debugging switched on. Since iloader 2.3.8 SideStore gets the same bytes under three names;
//! newer SideStore builds (Nightly) read the last two and ask for a pairing file without them.
//! The file is a secret: its contents are never printed or logged.
use idevice::{
    afc::opcode::AfcFopenMode,
    lockdown::LockdownClient,
    provider::IdeviceProvider,
    remote_pairing::{RemotePairingLockdownService, RpPairingFile},
    usbmuxd::UsbmuxdConnection,
    IdeviceService,
};
use serde::Serialize;

use crate::device::{self, bounded, err, AppKind};
use crate::paths;

pub const SIDESTORE_FILES: &[&str] = &[
    "ALTPairingFile.mobiledevicepairing",
    "PairingFile_RemoteRP.plist",
    "PairingFile_Lockdown.plist",
];

pub fn version_at_least(version: &str, major: u32, minor: u32) -> bool {
    let mut parts = version.split('.').map(|p| p.parse::<u32>().unwrap_or(0));
    let (m, n) = (parts.next().unwrap_or(0), parts.next().unwrap_or(0));
    (m, n) >= (major, minor)
}

/// Reuses this computer's rppairing identity for the phone, so placing the file again does not register a
/// new host on the phone every time.
async fn rp_identity(provider: &dyn IdeviceProvider, udid: &str) -> Result<RpPairingFile, String> {
    let cache = paths::rp_identity(udid);
    let host = format!("nuvioz-{}", udid.chars().filter(|c| c.is_ascii_alphanumeric()).take(6).collect::<String>().to_lowercase());
    let mut file = match &cache {
        Some(path) if path.is_file() => RpPairingFile::read_from_file(path).await.unwrap_or_else(|_| RpPairingFile::generate(&host)),
        _ => RpPairingFile::generate(&host),
    };
    let service = RemotePairingLockdownService::connect(provider).await.map_err(|e| format!("remote pairing service: {e:?}"))?;
    let mut client = service.into_client(&host).map_err(|e| format!("remote pairing client: {e:?}"))?;
    client.connect(&mut file, async || "000000".to_string()).await.map_err(|e| format!("remote pairing handshake: {e:?}"))?;
    if let Some(path) = &cache {
        if let Some(dir) = path.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        let _ = file.write_to_file(path).await;
    }
    Ok(file)
}

/// The merged pairing plist as XML bytes, plus the iOS version it was built for.
pub async fn build(mux: &mut UsbmuxdConnection, provider: &dyn IdeviceProvider, udid: &str) -> Result<(String, Vec<u8>), String> {
    let mut record = mux.get_pair_record(udid).await.map_err(|e| format!("no host pair record (is the phone trusted?): {e:?}"))?;
    record.udid = Some(udid.to_string());

    let mut lockdown = LockdownClient::connect(provider).await.map_err(err)?;
    let ios = lockdown.get_value(Some("ProductVersion"), None).await.ok()
        .and_then(|v| v.as_string().map(str::to_string)).unwrap_or_default();
    lockdown.start_session(&record).await.map_err(|e| format!("lockdown session: {e:?}"))?;
    lockdown.set_value("EnableWifiDebugging", true.into(), Some("com.apple.mobile.wireless_lockdown")).await
        .map_err(|e| format!("enable Wi-Fi debugging: {e:?}"))?;

    let lockdown_xml = record.serialize().map_err(err)?;
    let mut merged = plist::Value::from_reader_xml(std::io::Cursor::new(&lockdown_xml)).map_err(err)?
        .into_dictionary().ok_or("lockdown record is not a dictionary")?;

    if version_at_least(&ios, 17, 4) {
        let rp = rp_identity(provider, udid).await?;
        let rp_dict = plist::Value::from_reader_xml(std::io::Cursor::new(rp.to_bytes())).map_err(err)?
            .into_dictionary().ok_or("rppairing record is not a dictionary")?;
        for (k, v) in rp_dict {
            merged.insert(k, v);
        }
    }
    let mut bytes = Vec::new();
    plist::Value::Dictionary(merged).to_writer_xml(&mut bytes).map_err(err)?;
    Ok((ios, bytes))
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Placed {
    pub ios: String,
    pub files: usize,
}

/// Which SideStore to pair when the phone has more than one (one per Apple Account that installed it):
/// the one signed by `team`, whose bundle id ends with that team id; otherwise the only one.
pub fn pick_sidestore(bundles: &[String], team: Option<&str>) -> Result<String, String> {
    if let Some(team) = team {
        if let Some(b) = bundles.iter().find(|b| b.to_lowercase().ends_with(&team.to_lowercase())) {
            return Ok(b.clone());
        }
    }
    match bundles {
        [] => Err("SideStore is not installed".into()),
        [only] => Ok(only.clone()),
        _ => Err(format!("{} copies of SideStore are installed; delete the ones you don't use", bundles.len())),
    }
}

/// Builds the pairing file and writes it under every name SideStore reads, each read back to check it.
/// The previous main file is kept as `.previous` so a bad placement can be undone.
pub async fn place_for_sidestore(team: Option<&str>) -> Result<Placed, String> {
    let mut phone = device::connect("nuvioz-place-pairing").await?;
    let copies: Vec<String> = bounded("sidestore", async {
        Ok(device::installed(&phone.provider).await?.into_iter()
            .filter(|(kind, _)| *kind == AppKind::SideStore)
            .map(|(_, info)| info.bundle_id)
            .collect())
    }).await?;
    let sidestore = pick_sidestore(&copies, team)?;
    // The remote-pairing handshake can wait on the phone, so this gets longer than a probe.
    let (ios, bytes) = tokio::time::timeout(std::time::Duration::from_secs(90), build(&mut phone.mux, &phone.provider, &phone.udid))
        .await.map_err(|_| "building the pairing file timed out (is the iPhone unlocked?)".to_string())??;

    let mut afc = device::documents(&phone.provider, sidestore).await?;
    let main = format!("/Documents/{}", SIDESTORE_FILES[0]);
    let previous = format!("{main}.previous");
    let _ = afc.remove(previous.clone()).await;
    let _ = afc.rename(main, previous).await;
    for name in SIDESTORE_FILES {
        let path = format!("/Documents/{name}");
        let mut f = afc.open(path.clone(), AfcFopenMode::Wr).await.map_err(|e| format!("open {name}: {e:?}"))?;
        f.write_entire(&bytes).await.map_err(|e| format!("write {name}: {e:?}"))?;
        f.close().await.map_err(|e| format!("close {name}: {e:?}"))?;
        let mut f = afc.open(path, AfcFopenMode::RdOnly).await.map_err(|e| format!("reopen {name}: {e:?}"))?;
        let back = f.read_entire().await.map_err(|e| format!("read back {name}: {e:?}"))?;
        f.close().await.map_err(|e| format!("close {name}: {e:?}"))?;
        if back != bytes {
            return Err(format!("{name} did not read back the same"));
        }
    }
    Ok(Placed { ios, files: SIDESTORE_FILES.len() })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rppairing_starts_at_ios_17_4() {
        assert!(!version_at_least("17.3.1", 17, 4));
        assert!(version_at_least("17.4", 17, 4));
        assert!(version_at_least("18.0", 17, 4));
        assert!(version_at_least("26.1", 17, 4));
        assert!(!version_at_least("", 17, 4));
    }

    #[test]
    fn pairs_the_copy_this_account_installed() {
        let copies = vec!["com.SideStore.SideStore.AAAA111111".to_string(), "com.SideStore.SideStore.BBBB222222".to_string()];
        assert_eq!(pick_sidestore(&copies, Some("BBBB222222")).unwrap(), copies[1]);
        assert!(pick_sidestore(&copies, None).is_err());
        assert!(pick_sidestore(&copies, Some("CCCC333333")).is_err());
        assert_eq!(pick_sidestore(&copies[..1], None).unwrap(), copies[0]);
        assert!(pick_sidestore(&[], None).is_err());
    }
}
