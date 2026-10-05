//! Stage 0 feasibility spike. Read-only: it never pairs, installs, writes or reveals anything.
//! Prints one JSON document describing what the computer can learn about an attached iPhone.
use idevice::{
    afc::AfcClient,
    amfi::AmfiClient,
    house_arrest::HouseArrestClient,
    installation_proxy::InstallationProxyClient,
    lockdown::LockdownClient,
    provider::IdeviceProvider,
    usbmuxd::{Connection, UsbmuxdAddr, UsbmuxdConnection},
    IdeviceService,
};
use serde_json::{json, Value};

fn plist_to_json(value: &plist::Value) -> Value {
    match value {
        plist::Value::String(s) => json!(s),
        plist::Value::Boolean(b) => json!(b),
        plist::Value::Integer(i) => json!(i.as_signed()),
        _ => json!(format!("{:?}", value)),
    }
}

async fn probe(label: &str, out: &mut serde_json::Map<String, Value>, fut: impl std::future::Future<Output = Result<Value, String>>) {
    let started = std::time::Instant::now();
    let result = match tokio::time::timeout(std::time::Duration::from_secs(15), fut).await {
        Ok(Ok(v)) => json!({ "ok": true, "value": v }),
        Ok(Err(e)) => json!({ "ok": false, "error": e }),
        Err(_) => json!({ "ok": false, "error": "timeout after 15s" }),
    };
    let mut result = result;
    result["ms"] = json!(started.elapsed().as_millis() as u64);
    out.insert(label.to_string(), result);
}

#[tokio::main]
async fn main() {
    let mut report = serde_json::Map::new();
    let addr = UsbmuxdAddr::default();

    let mut mux = match UsbmuxdConnection::default().await {
        Ok(m) => m,
        Err(e) => {
            println!("{}", json!({ "usbmuxd": { "reachable": false, "error": format!("{e:?}") } }));
            return;
        }
    };
    let devices = mux.get_devices().await.unwrap_or_default();
    report.insert("usbmuxd".into(), json!({ "reachable": true, "devices": devices.iter().map(|d| json!({
        "udid_prefix": d.udid.chars().take(8).collect::<String>(),
        "usb": matches!(d.connection_type, Connection::Usb),
    })).collect::<Vec<_>>() }));

    let Some(device) = devices.iter().find(|d| matches!(d.connection_type, Connection::Usb)) else {
        report.insert("note".into(), json!("no USB iPhone attached; device probes skipped"));
        println!("{}", Value::Object(report));
        return;
    };
    let provider = device.to_provider(addr, "nuvioz-spike");

    // Lockdown without a session: what is visible before / without trust.
    probe("lockdown_unpaired_values", &mut report, async {
        let mut lockdown = LockdownClient::connect(&provider).await.map_err(|e| format!("{e:?}"))?;
        let mut v = serde_json::Map::new();
        for key in ["DeviceName", "ProductVersion", "ProductType", "PasswordProtected", "HostAttached"] {
            if let Ok(value) = lockdown.get_value(Some(key), None).await {
                v.insert(key.into(), plist_to_json(&value));
            }
        }
        Ok(Value::Object(v))
    }).await;

    probe("pair_record_exists", &mut report, async {
        provider.get_pairing_file().await.map(|_| json!(true)).map_err(|e| format!("{e:?}"))
    }).await;

    probe("lockdown_session", &mut report, async {
        let pairing = provider.get_pairing_file().await.map_err(|e| format!("{e:?}"))?;
        let mut lockdown = LockdownClient::connect(&provider).await.map_err(|e| format!("{e:?}"))?;
        lockdown.start_session(&pairing).await.map_err(|e| format!("{e:?}"))?;
        Ok(json!("session established (trust is valid)"))
    }).await;

    probe("developer_mode_status", &mut report, async {
        let mut amfi = AmfiClient::connect(&provider).await.map_err(|e| format!("{e:?}"))?;
        amfi.get_developer_mode_status().await.map(|b| json!(b)).map_err(|e| format!("{e:?}"))
    }).await;

    // installation_proxy: which of the interesting apps are present?
    let mut sidestore_ids: Vec<String> = Vec::new();
    probe("installed_apps", &mut report, async {
        let mut proxy = InstallationProxyClient::connect(&provider).await.map_err(|e| format!("{e:?}"))?;
        let apps = proxy.get_apps(Some("User"), None).await.map_err(|e| format!("{e:?}"))?;
        let mut interesting = serde_json::Map::new();
        for (bundle, info) in &apps {
            let lower = bundle.to_lowercase();
            if lower.contains("sidestore") || lower.contains("localdevvpn") || lower.starts_with("com.nuvio.app.z") {
                let version = info.as_dictionary()
                    .and_then(|d| d.get("CFBundleShortVersionString"))
                    .and_then(|v| v.as_string()).unwrap_or("?");
                interesting.insert(bundle.clone(), json!(version));
            }
        }
        Ok(json!({ "user_app_count": apps.len(), "interesting": interesting }))
    }).await;

    // Re-run the lookup to collect SideStore's real bundle id (it carries the team id suffix).
    if let Ok(mut proxy) = InstallationProxyClient::connect(&provider).await {
        if let Ok(apps) = proxy.get_apps(Some("User"), None).await {
            sidestore_ids = apps.keys().filter(|b| b.to_lowercase().contains("sidestore")).cloned().collect();
        }
    }

    // house_arrest: can we see SideStore's Documents (where the pairing file lives)?
    for id in sidestore_ids.iter().take(1) {
        let id = id.clone();
        probe("sidestore_documents", &mut report, async {
            let ha = HouseArrestClient::connect(&provider).await.map_err(|e| format!("{e:?}"))?;
            let mut afc: AfcClient = ha.vend_documents(id).await.map_err(|e| format!("{e:?}"))?;
            let entries = afc.list_dir("/Documents").await.or(afc.list_dir("/").await).map_err(|e| format!("{e:?}"))?;
            Ok(json!(entries))
        }).await;
    }

    println!("{}", serde_json::to_string_pretty(&Value::Object(report)).unwrap());
}
