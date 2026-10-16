//! Builds and places the pairing file SideStore reads from its Documents folder.
//!
//! Same recipe iloader uses (verified against iloader's `pairing.rs`, idevice 0.1.68): the host's
//! lockdown pair record, plus on iOS 17.4+ a remote-pairing (rppairing) identity merged into one
//! plist, plus Wi-Fi debugging switched on. The file is a secret: its contents are never printed or
//! logged, only its key names.
use idevice::{
    afc::{opcode::AfcFopenMode, AfcClient},
    house_arrest::HouseArrestClient,
    lockdown::LockdownClient,
    provider::IdeviceProvider,
    remote_pairing::{RemotePairingLockdownService, RpPairingFile},
    usbmuxd::UsbmuxdConnection,
    IdeviceService,
};
use serde_json::{json, Value};
use std::path::PathBuf;

pub const REAL_NAME: &str = "ALTPairingFile.mobiledevicepairing";
pub const SCRATCH_NAME: &str = "nuvioz-pairing-test.tmp";

fn err(e: impl std::fmt::Debug) -> String {
    format!("{e:?}")
}

fn version_at_least(version: &str, major: u32, minor: u32) -> bool {
    let mut parts = version.split('.').map(|p| p.parse::<u32>().unwrap_or(0));
    let (m, n) = (parts.next().unwrap_or(0), parts.next().unwrap_or(0));
    (m, n) >= (major, minor)
}

/// Where the rppairing identity for this phone is cached, so re-placing the file reuses the same host
/// identity instead of registering a new one on the phone every time. Per-user, never shared.
fn identity_cache(udid: &str) -> Option<PathBuf> {
    let base = std::env::var_os("LOCALAPPDATA").map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join("Library").join("Application Support")))?;
    let key: String = udid.chars().filter(|c| c.is_ascii_alphanumeric()).take(12).collect();
    Some(base.join("Nuvio Z iOS Setup").join("pairing").join(format!("rp-{key}.plist")))
}

async fn rp_identity(provider: &dyn IdeviceProvider, udid: &str) -> Result<RpPairingFile, String> {
    let cache = identity_cache(udid);
    let host = format!("nuvioz-{}", udid.chars().filter(|c| c.is_ascii_alphanumeric()).take(6).collect::<String>().to_lowercase());
    let mut file = match &cache {
        Some(path) if path.is_file() => RpPairingFile::read_from_file(path).await.unwrap_or_else(|_| RpPairingFile::generate(&host)),
        _ => RpPairingFile::generate(&host),
    };
    let service = RemotePairingLockdownService::connect(provider).await.map_err(|e| format!("remote pairing service: {e:?}"))?;
    let mut client = service.into_client(&host).map_err(|e| format!("remote pairing client: {e:?}"))?;
    client.connect(&mut file, async || "000000".to_string()).await.map_err(|e| format!("remote pairing handshake: {e:?}"))?;
    if let Some(path) = &cache {
        if let Some(dir) = path.parent() { let _ = std::fs::create_dir_all(dir); }
        let _ = file.write_to_file(path).await;
    }
    Ok(file)
}

/// Builds the merged pairing plist (XML bytes) for the first USB device. Returns (udid, ios, bytes).
pub async fn build(mux: &mut UsbmuxdConnection, provider: &dyn IdeviceProvider, udid: &str, enable_wifi: bool)
    -> Result<(String, Vec<u8>), String>
{
    let mut record = mux.get_pair_record(udid).await.map_err(|e| format!("no host pair record (is the phone trusted?): {e:?}"))?;
    record.udid = Some(udid.to_string());

    let mut lockdown = LockdownClient::connect(provider).await.map_err(err)?;
    let ios = lockdown.get_value(Some("ProductVersion"), None).await.ok()
        .and_then(|v| v.as_string().map(str::to_string)).unwrap_or_default();
    lockdown.start_session(&record).await.map_err(|e| format!("lockdown session: {e:?}"))?;
    if enable_wifi {
        lockdown.set_value("EnableWifiDebugging", true.into(), Some("com.apple.mobile.wireless_lockdown")).await
            .map_err(|e| format!("enable Wi-Fi debugging: {e:?}"))?;
    }

    let lockdown_xml = record.serialize().map_err(err)?;
    let mut merged = plist::Value::from_reader_xml(std::io::Cursor::new(&lockdown_xml)).map_err(err)?
        .into_dictionary().ok_or("lockdown record is not a dictionary")?;

    if version_at_least(&ios, 17, 4) {
        let rp = rp_identity(provider, udid).await?;
        let rp_dict = plist::Value::from_reader_xml(std::io::Cursor::new(rp.to_bytes())).map_err(err)?
            .into_dictionary().ok_or("rppairing record is not a dictionary")?;
        for (k, v) in rp_dict { merged.insert(k, v); }
    }
    let mut bytes = Vec::new();
    plist::Value::Dictionary(merged).to_writer_xml(&mut bytes).map_err(err)?;
    Ok((ios, bytes))
}

fn key_names(bytes: &[u8]) -> Vec<String> {
    plist::Value::from_reader_xml(std::io::Cursor::new(bytes)).ok()
        .and_then(|v| v.into_dictionary())
        .map(|d| { let mut k: Vec<String> = d.keys().cloned().collect(); k.sort(); k })
        .unwrap_or_default()
}

async fn open_documents(provider: &dyn IdeviceProvider, bundle_id: String) -> Result<AfcClient, String> {
    let ha = HouseArrestClient::connect(provider).await.map_err(err)?;
    ha.vend_documents(bundle_id).await.map_err(err)
}

/// Writes the file to `name` inside SideStore's Documents and reads it back. With the scratch name
/// the real pairing file is not touched; the result reports whether the key sets match it.
pub async fn place(mux: &mut UsbmuxdConnection, provider: &dyn IdeviceProvider, udid: &str, sidestore_id: String, scratch: bool) -> Value {
    let name = if scratch { SCRATCH_NAME } else { REAL_NAME };
    let (ios, bytes) = match build(mux, provider, udid, !scratch).await {
        Ok(v) => v,
        Err(e) => return json!({ "ok": false, "stage": "build", "error": e }),
    };
    let mut afc = match open_documents(provider, sidestore_id.clone()).await {
        Ok(a) => a,
        Err(e) => return json!({ "ok": false, "stage": "container", "error": e }),
    };
    let path = format!("/Documents/{name}");
    if !scratch {
        // Keep whatever pairing file was working so a bad placement is recoverable on the phone itself.
        let previous = format!("{path}.previous");
        let _ = afc.remove(previous.clone()).await;
        let _ = afc.rename(path.clone(), previous).await;
    }
    let written = async {
        let mut f = afc.open(path.clone(), AfcFopenMode::Wr).await.map_err(|e| format!("open: {e:?}"))?;
        f.write_entire(&bytes).await.map_err(|e| format!("write: {e:?}"))?;
        f.close().await.map_err(|e| format!("close: {e:?}"))
    }.await;
    if let Err(e) = written { return json!({ "ok": false, "stage": "write", "error": e }); }

    let read_back = async {
        let mut f = afc.open(path.clone(), AfcFopenMode::RdOnly).await.map_err(|e| format!("open: {e:?}"))?;
        let b = f.read_entire().await.map_err(|e| format!("read: {e:?}"))?;
        f.close().await.map_err(|e| format!("close: {e:?}"))?;
        Ok::<Vec<u8>, String>(b)
    }.await;
    let matches = read_back.as_ref().map(|b| *b == bytes).unwrap_or(false);

    let mut out = json!({
        "ok": matches, "scratch": scratch, "ios": ios,
        "bytes": bytes.len(), "readBackMatches": matches,
        "keys": key_names(&bytes),
    });
    if scratch {
        // Compare shape (key names only) with the pairing file SideStore is really using.
        let existing = async {
            let mut f = afc.open(format!("/Documents/{REAL_NAME}"), AfcFopenMode::RdOnly).await.map_err(|e| format!("{e:?}"))?;
            let b = f.read_entire().await.map_err(|e| format!("{e:?}"))?;
            f.close().await.map_err(|e| format!("{e:?}"))?;
            Ok::<Vec<u8>, String>(b)
        }.await;
        match existing {
            Ok(b) => { out["existingKeys"] = json!(key_names(&b)); out["sameKeyNames"] = json!(key_names(&b) == key_names(&bytes)); }
            Err(e) => out["existingKeys"] = json!(format!("unreadable: {e}")),
        }
        let removed = afc.remove(path).await.is_ok();
        out["scratchRemoved"] = json!(removed);
    }
    out
}

/// Puts back the pairing file that was in place before the last real placement (`.previous`).
pub async fn restore(provider: &dyn IdeviceProvider, sidestore_id: String) -> Value {
    let mut afc = match open_documents(provider, sidestore_id).await {
        Ok(a) => a,
        Err(e) => return json!({ "ok": false, "stage": "container", "error": e }),
    };
    let real = format!("/Documents/{REAL_NAME}");
    let previous = format!("{real}.previous");
    match afc.list_dir("/Documents").await {
        Ok(entries) if entries.iter().any(|e| e == &format!("{REAL_NAME}.previous")) => {}
        Ok(_) => return json!({ "ok": false, "stage": "previous", "error": "no previous pairing file to restore" }),
        Err(e) => return json!({ "ok": false, "stage": "list", "error": err(e) }),
    }
    let _ = afc.remove(real.clone()).await;
    match afc.rename(previous, real).await {
        Ok(()) => json!({ "ok": true }),
        Err(e) => json!({ "ok": false, "stage": "rename", "error": err(e) }),
    }
}
