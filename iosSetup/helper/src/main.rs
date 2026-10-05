//! nuvioz-device-helper: read-only USB probes for the Nuvio Z iOS Setup assistant.
//!
//! `status` prints exactly one JSON line (protocol 1). It never pairs, installs, writes or
//! reveals anything, and it deliberately omits the device name and full UDID.
use idevice::{
    afc::{opcode::AfcFopenMode, AfcClient},
    amfi::AmfiClient,
    house_arrest::HouseArrestClient,
    installation_proxy::InstallationProxyClient,
    lockdown::LockdownClient,
    provider::IdeviceProvider,
    usbmuxd::{Connection, UsbmuxdAddr, UsbmuxdConnection},
    IdeviceService,
};
use serde_json::{json, Value};
use std::time::Duration;

mod pairing;

const PROTOCOL: u32 = 1;
const IDEVICE_VERSION: &str = "0.1.68";
const PAIRING_FILE: &str = "ALTPairingFile.mobiledevicepairing";

async fn bounded<T>(fut: impl std::future::Future<Output = Result<T, String>>) -> Result<T, String> {
    match tokio::time::timeout(Duration::from_secs(15), fut).await {
        Ok(r) => r,
        Err(_) => Err("timeout".into()),
    }
}

fn err(e: impl std::fmt::Debug) -> String {
    format!("{e:?}")
}

/// Which of our apps a sideloaded bundle id belongs to. SideStore appends the signing team id.
fn classify(bundle: &str) -> Option<&'static str> {
    let lower = bundle.to_lowercase();
    if lower.starts_with("com.sidestore.sidestore") {
        Some("sidestore")
    } else if lower.starts_with("com.jkcoxson.localdevvpn") {
        Some("localDevVpn")
    } else if lower.starts_with("com.nuvio.app.z.debug") {
        Some("nuvioDebug")
    } else if lower == "com.nuvio.app.z" || lower.starts_with("com.nuvio.app.z.") {
        Some("nuvioStable")
    } else {
        None
    }
}

async fn status() -> Value {
    let mut out = json!({
        "protocol": PROTOCOL,
        "helper": env!("CARGO_PKG_VERSION"),
        "idevice": IDEVICE_VERSION,
        "usbmuxd": { "reachable": false },
        "device": null,
        "errors": {},
    });
    let mut errors = serde_json::Map::new();

    let mut mux = match UsbmuxdConnection::default().await {
        Ok(m) => m,
        Err(e) => {
            errors.insert("usbmuxd".into(), json!(err(e)));
            out["errors"] = Value::Object(errors);
            return out;
        }
    };
    out["usbmuxd"]["reachable"] = json!(true);
    let devices = mux.get_devices().await.unwrap_or_default();
    out["usbmuxd"]["usbDevices"] = json!(devices.iter().filter(|d| matches!(d.connection_type, Connection::Usb)).count());
    let Some(device) = devices.iter().find(|d| matches!(d.connection_type, Connection::Usb)) else {
        out["errors"] = Value::Object(errors);
        return out;
    };
    let provider = device.to_provider(UsbmuxdAddr::default(), "nuvioz-setup");

    let mut dev = json!({ "udidPrefix": device.udid.chars().take(8).collect::<String>() });

    // Trust: a pairing record for this host that lockdownd accepts.
    let trust = bounded(async {
        let pairing = provider.get_pairing_file().await.map_err(err)?;
        let mut lockdown = LockdownClient::connect(&provider).await.map_err(err)?;
        let version = lockdown.get_value(Some("ProductVersion"), None).await.ok()
            .and_then(|v| v.as_string().map(str::to_string));
        lockdown.start_session(&pairing).await.map_err(err)?;
        Ok(version)
    }).await;
    match trust {
        Ok(version) => {
            dev["trust"] = json!("valid");
            dev["iosVersion"] = json!(version);
        }
        Err(e) => {
            dev["trust"] = json!("invalid");
            errors.insert("trust".into(), json!(e));
        }
    }

    if dev["trust"] == "valid" {
        match bounded(async {
            let mut amfi = AmfiClient::connect(&provider).await.map_err(err)?;
            amfi.get_developer_mode_status().await.map_err(err)
        }).await {
            Ok(on) => dev["developerMode"] = json!(on),
            Err(e) => { dev["developerMode"] = Value::Null; errors.insert("developerMode".into(), json!(e)); }
        }

        let mut apps = json!({ "sidestore": null, "localDevVpn": null, "nuvioStable": null, "nuvioDebug": null });
        let mut sidestore_id: Option<String> = None;
        match bounded(async {
            let mut proxy = InstallationProxyClient::connect(&provider).await.map_err(err)?;
            proxy.get_apps(Some("User"), None).await.map_err(err)
        }).await {
            Ok(list) => {
                for (bundle, info) in &list {
                    if let Some(kind) = classify(bundle) {
                        let version = info.as_dictionary()
                            .and_then(|d| d.get("CFBundleShortVersionString"))
                            .and_then(|v| v.as_string()).unwrap_or("");
                        apps[kind] = json!({ "bundleId": bundle, "version": version });
                        if kind == "sidestore" { sidestore_id = Some(bundle.clone()); }
                    }
                }
            }
            Err(e) => { apps = Value::Null; errors.insert("apps".into(), json!(e)); }
        }
        dev["apps"] = apps;

        // SideStore keeps its pairing file in Documents; presence only, never its contents.
        dev["pairingFile"] = match sidestore_id {
            None => json!("sidestoreMissing"),
            Some(id) => match bounded(async {
                let ha = HouseArrestClient::connect(&provider).await.map_err(err)?;
                let mut afc: AfcClient = ha.vend_documents(id).await.map_err(err)?;
                afc.list_dir("/Documents").await.map_err(err)
            }).await {
                Ok(entries) => json!(if entries.iter().any(|e| e == PAIRING_FILE) { "present" } else { "absent" }),
                Err(e) => { errors.insert("pairingFile".into(), json!(e)); json!("unknown") }
            },
        };
    }

    out["device"] = dev;
    out["errors"] = Value::Object(errors);
    out
}

/// Proves the pairing-file write path without touching the real file: create, read back and
/// delete a scratch file in SideStore's Documents. Run only on request (`write-probe`).
async fn write_probe() -> Value {
    const SCRATCH: &str = "/Documents/nuvioz-write-probe.tmp";
    const BODY: &[u8] = b"nuvioz write probe";
    let mut mux = match UsbmuxdConnection::default().await {
        Ok(m) => m,
        Err(e) => return json!({ "ok": false, "stage": "usbmuxd", "error": err(e) }),
    };
    let devices = mux.get_devices().await.unwrap_or_default();
    let Some(device) = devices.iter().find(|d| matches!(d.connection_type, Connection::Usb)) else {
        return json!({ "ok": false, "stage": "device", "error": "no USB device" });
    };
    let provider = device.to_provider(UsbmuxdAddr::default(), "nuvioz-write-probe");
    let sidestore = match bounded(async {
        let mut proxy = InstallationProxyClient::connect(&provider).await.map_err(err)?;
        let apps = proxy.get_apps(Some("User"), None).await.map_err(err)?;
        apps.keys().find(|b| classify(b) == Some("sidestore")).cloned().ok_or_else(|| "SideStore not installed".to_string())
    }).await {
        Ok(id) => id,
        Err(e) => return json!({ "ok": false, "stage": "sidestore", "error": e }),
    };
    let mut stages = serde_json::Map::new();
    let outcome = bounded(async {
        let ha = HouseArrestClient::connect(&provider).await.map_err(err)?;
        let mut afc: AfcClient = ha.vend_documents(sidestore).await.map_err(err)?;
        {
            let mut file = afc.open(SCRATCH, AfcFopenMode::WrOnly).await.map_err(|e| format!("open for write: {e:?}"))?;
            file.write_entire(BODY).await.map_err(|e| format!("write: {e:?}"))?;
            file.close().await.map_err(|e| format!("close after write: {e:?}"))?;
        }
        stages.insert("written".into(), json!(true));
        let read_back = {
            let mut file = afc.open(SCRATCH, AfcFopenMode::RdOnly).await.map_err(|e| format!("open for read: {e:?}"))?;
            let bytes = file.read_entire().await.map_err(|e| format!("read: {e:?}"))?;
            file.close().await.map_err(|e| format!("close after read: {e:?}"))?;
            bytes
        };
        stages.insert("readBackMatches".into(), json!(read_back == BODY));
        afc.remove(SCRATCH).await.map_err(|e| format!("remove: {e:?}"))?;
        let left = afc.list_dir("/Documents").await.map_err(err)?;
        stages.insert("removed".into(), json!(!left.iter().any(|e| e == "nuvioz-write-probe.tmp")));
        Ok(())
    }).await;
    match outcome {
        Ok(()) => json!({ "ok": true, "stages": stages }),
        Err(e) => json!({ "ok": false, "error": e, "stages": stages }),
    }
}

/// `place-pairing [--scratch]`: build SideStore's pairing file and write it into its Documents.
/// `--scratch` writes a throwaway file instead and leaves the real pairing file alone.
async fn place_pairing(scratch: bool) -> Value {
    let mut mux = match UsbmuxdConnection::default().await {
        Ok(m) => m,
        Err(e) => return json!({ "ok": false, "stage": "usbmuxd", "error": err(e) }),
    };
    let devices = mux.get_devices().await.unwrap_or_default();
    let Some(device) = devices.iter().find(|d| matches!(d.connection_type, Connection::Usb)) else {
        return json!({ "ok": false, "stage": "device", "error": "no USB device" });
    };
    let provider = device.to_provider(UsbmuxdAddr::default(), "nuvioz-place-pairing");
    let sidestore = match bounded(async {
        let mut proxy = InstallationProxyClient::connect(&provider).await.map_err(err)?;
        let apps = proxy.get_apps(Some("User"), None).await.map_err(err)?;
        apps.keys().find(|b| classify(b) == Some("sidestore")).cloned().ok_or_else(|| "SideStore not installed".to_string())
    }).await {
        Ok(id) => id,
        Err(e) => return json!({ "ok": false, "stage": "sidestore", "error": e }),
    };
    let udid = device.udid.clone();
    match tokio::time::timeout(Duration::from_secs(90), pairing::place(&mut mux, &provider, &udid, sidestore, scratch)).await {
        Ok(v) => v,
        Err(_) => json!({ "ok": false, "stage": "timeout", "error": "timed out after 90s (is the phone unlocked?)" }),
    }
}

/// `reveal-developer-mode`: makes Settings → Privacy & Security → Developer Mode appear (iOS 16+
/// hides it until asked). Only reveals the switch; the user still turns it on and confirms the restart.
async fn reveal_developer_mode() -> Value {
    let mut mux = match UsbmuxdConnection::default().await {
        Ok(m) => m,
        Err(e) => return json!({ "ok": false, "stage": "usbmuxd", "error": err(e) }),
    };
    let devices = mux.get_devices().await.unwrap_or_default();
    let Some(device) = devices.iter().find(|d| matches!(d.connection_type, Connection::Usb)) else {
        return json!({ "ok": false, "stage": "device", "error": "no USB device" });
    };
    let provider = device.to_provider(UsbmuxdAddr::default(), "nuvioz-reveal-developer-mode");
    match bounded(async {
        let mut amfi = AmfiClient::connect(&provider).await.map_err(err)?;
        amfi.reveal_developer_mode_option_in_ui().await.map_err(err)
    }).await {
        Ok(()) => json!({ "ok": true }),
        Err(e) => json!({ "ok": false, "stage": "amfi", "error": e }),
    }
}

#[tokio::main]
async fn main() {
    match std::env::args().nth(1).as_deref() {
        Some("reveal-developer-mode") => println!("{}", reveal_developer_mode().await),
        Some("place-pairing") => println!("{}", place_pairing(std::env::args().any(|a| a == "--scratch")).await),
        Some("status") | None => println!("{}", status().await),
        Some("write-probe") => println!("{}", write_probe().await),
        Some("--version") => println!("nuvioz-device-helper {} (idevice {IDEVICE_VERSION}, protocol {PROTOCOL})", env!("CARGO_PKG_VERSION")),
        Some(other) => {
            eprintln!("unknown command: {other}");
            std::process::exit(2);
        }
    }
}
