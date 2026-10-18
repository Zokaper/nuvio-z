//! What the computer can see of the iPhone over USB.
//!
//! Read-only except `reveal_developer_mode`, and deliberately without the device name or full UDID: the
//! snapshot ends up in the UI and in diagnostics.
use idevice::{
    afc::AfcClient,
    amfi::AmfiClient,
    house_arrest::HouseArrestClient,
    installation_proxy::InstallationProxyClient,
    lockdown::LockdownClient,
    provider::{IdeviceProvider, UsbmuxdProvider},
    usbmuxd::{Connection, UsbmuxdAddr, UsbmuxdConnection},
    IdeviceService,
};
use serde::Serialize;
use std::time::Duration;

use crate::pairing::SIDESTORE_FILES;

const PROBE_TIMEOUT: Duration = Duration::from_secs(15);

#[derive(Debug, Default, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct DeviceSnapshot {
    /// Apple's USB service answered (Apple Mobile Device Support on Windows, usbmuxd on macOS).
    pub usbmuxd_reachable: bool,
    pub usb_devices: usize,
    pub udid_prefix: Option<String>,
    /// The phone accepted this computer's pairing record, i.e. "Trust This Computer" was tapped.
    pub trusted: bool,
    pub ios_version: Option<String>,
    pub developer_mode: Option<bool>,
    pub apps: Option<Apps>,
    pub pairing: PairingState,
    /// Whether the installed Nuvio Z (Debug first) holds its own pairing file; `None` when there is none or its
    /// Documents can't be opened.
    pub nuvio_pairing: Option<bool>,
    /// An iPhone is plugged in but Windows has no Apple USB driver for it (see `drivers::iphone_needs_driver`).
    pub iphone_needs_driver: bool,
    pub errors: Vec<String>,
}

#[derive(Debug, Default, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Apps {
    pub sidestore: Option<AppInfo>,
    pub local_dev_vpn: Option<AppInfo>,
    pub nuvio_stable: Option<AppInfo>,
    pub nuvio_debug: Option<AppInfo>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct AppInfo {
    pub bundle_id: String,
    pub version: String,
}

/// Whether SideStore's Documents holds every pairing file it reads (presence only, never contents).
#[derive(Debug, Default, Clone, Copy, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub enum PairingState {
    #[default]
    Unknown,
    SidestoreMissing,
    Absent,
    /// Some of the files: an older placement (iloader before 2.3.8, or our 2.x assistant).
    Partial,
    Present,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum AppKind {
    SideStore,
    LocalDevVpn,
    NuvioStable,
    NuvioDebug,
}

/// Which of our apps a bundle belongs to. SideStore appends the signing team id to its bundle id, and a
/// copy installed from inside SideStore can carry another prefix, so its display name counts too.
pub fn classify(bundle: &str, display_name: Option<&str>) -> Option<AppKind> {
    let lower = bundle.to_lowercase();
    if lower.starts_with("com.sidestore.sidestore") || display_name == Some("SideStore") {
        Some(AppKind::SideStore)
    } else if lower.starts_with("com.jkcoxson.localdevvpn") {
        Some(AppKind::LocalDevVpn)
    } else if lower.starts_with("com.nuvio.app.z.debug") {
        Some(AppKind::NuvioDebug)
    } else if lower == "com.nuvio.app.z" || lower.starts_with("com.nuvio.app.z.") {
        Some(AppKind::NuvioStable)
    } else {
        None
    }
}

pub fn pairing_state(documents: &[String]) -> PairingState {
    let found = SIDESTORE_FILES.iter().filter(|name| documents.iter().any(|e| e == *name)).count();
    match found {
        0 => PairingState::Absent,
        n if n == SIDESTORE_FILES.len() => PairingState::Present,
        _ => PairingState::Partial,
    }
}

pub(crate) async fn bounded<T>(what: &str, fut: impl std::future::Future<Output = Result<T, String>>) -> Result<T, String> {
    match tokio::time::timeout(PROBE_TIMEOUT, fut).await {
        Ok(r) => r.map_err(|e| format!("{what}: {e}")),
        Err(_) => Err(format!("{what}: timeout")),
    }
}

pub(crate) fn err(e: impl std::fmt::Debug) -> String {
    format!("{e:?}")
}

/// The first iPhone on USB, ready to talk to.
pub struct Connected {
    pub mux: UsbmuxdConnection,
    pub udid: String,
    pub provider: UsbmuxdProvider,
}

pub async fn connect(label: &str) -> Result<Connected, String> {
    let mut mux = UsbmuxdConnection::default().await.map_err(|e| format!("usbmuxd: {}", err(e)))?;
    let devices = mux.get_devices().await.map_err(|e| format!("usbmuxd: {}", err(e)))?;
    let device = devices.into_iter().find(|d| matches!(d.connection_type, Connection::Usb)).ok_or("no iPhone on USB")?;
    let provider = device.to_provider(UsbmuxdAddr::default(), label);
    Ok(Connected { mux, udid: device.udid, provider })
}

/// Installed user apps we care about, keyed by kind.
pub(crate) async fn installed(provider: &dyn IdeviceProvider) -> Result<Vec<(AppKind, AppInfo)>, String> {
    let mut proxy = InstallationProxyClient::connect(provider).await.map_err(err)?;
    let list = proxy.get_apps(Some("User"), None).await.map_err(err)?;
    Ok(list.iter().filter_map(|(bundle, info)| {
        let dict = info.as_dictionary();
        let field = |k: &str| dict.and_then(|d| d.get(k)).and_then(|v| v.as_string());
        let kind = classify(bundle, field("CFBundleDisplayName"))?;
        Some((kind, AppInfo { bundle_id: bundle.clone(), version: field("CFBundleShortVersionString").unwrap_or("").to_string() }))
    }).collect())
}

pub(crate) async fn documents(provider: &dyn IdeviceProvider, bundle_id: String) -> Result<AfcClient, String> {
    let ha = HouseArrestClient::connect(provider).await.map_err(err)?;
    ha.vend_documents(bundle_id).await.map_err(err)
}

pub async fn probe() -> DeviceSnapshot {
    let mut snap = DeviceSnapshot::default();

    let mut mux = match UsbmuxdConnection::default().await {
        Ok(m) => m,
        Err(e) => {
            snap.errors.push(format!("usbmuxd: {}", err(e)));
            return snap;
        }
    };
    snap.usbmuxd_reachable = true;
    let devices = mux.get_devices().await.unwrap_or_default();
    let usb: Vec<_> = devices.iter().filter(|d| matches!(d.connection_type, Connection::Usb)).collect();
    snap.usb_devices = usb.len();
    let Some(device) = usb.first() else {
        snap.iphone_needs_driver = crate::drivers::iphone_needs_driver().await;
        return snap;
    };
    snap.udid_prefix = Some(device.udid.chars().take(8).collect());
    let provider = device.to_provider(UsbmuxdAddr::default(), "nuvioz-installer");

    let trust = bounded("trust", async {
        let pairing = provider.get_pairing_file().await.map_err(err)?;
        let mut lockdown = LockdownClient::connect(&provider).await.map_err(err)?;
        let version = lockdown.get_value(Some("ProductVersion"), None).await.ok()
            .and_then(|v| v.as_string().map(str::to_string));
        lockdown.start_session(&pairing).await.map_err(err)?;
        Ok(version)
    }).await;
    match trust {
        Ok(version) => {
            snap.trusted = true;
            snap.ios_version = version;
        }
        Err(e) => {
            snap.errors.push(e);
            return snap;
        }
    }

    match bounded("developerMode", async {
        let mut amfi = AmfiClient::connect(&provider).await.map_err(err)?;
        amfi.get_developer_mode_status().await.map_err(err)
    }).await {
        Ok(on) => snap.developer_mode = Some(on),
        Err(e) => snap.errors.push(e),
    }

    match bounded("apps", installed(&provider)).await {
        Ok(found) => {
            let mut apps = Apps::default();
            for (kind, info) in found {
                let slot = match kind {
                    AppKind::SideStore => &mut apps.sidestore,
                    AppKind::LocalDevVpn => &mut apps.local_dev_vpn,
                    AppKind::NuvioStable => &mut apps.nuvio_stable,
                    AppKind::NuvioDebug => &mut apps.nuvio_debug,
                };
                *slot = Some(info);
            }
            snap.pairing = match &apps.sidestore {
                None => PairingState::SidestoreMissing,
                Some(s) => match bounded("pairing", async {
                    documents(&provider, s.bundle_id.clone()).await?.list_dir("/Documents").await.map_err(err)
                }).await {
                    Ok(entries) => pairing_state(&entries),
                    Err(e) => {
                        snap.errors.push(e);
                        PairingState::Unknown
                    }
                },
            };
            if let Some(nuvio) = apps.nuvio_debug.as_ref().or(apps.nuvio_stable.as_ref()) {
                snap.nuvio_pairing = bounded("nuvioPairing", crate::nuvio::has_pairing(&provider, &nuvio.bundle_id)).await.ok();
            }
            snap.apps = Some(apps);
        }
        Err(e) => snap.errors.push(e),
    }
    snap
}

/// Makes Settings › Privacy & Security › Developer Mode appear (iOS 16+ hides it until a computer asks).
/// Only reveals the switch; the person still turns it on and confirms the restart.
pub async fn reveal_developer_mode() -> Result<(), String> {
    let phone = connect("nuvioz-reveal-developer-mode").await?;
    bounded("developerMode", async {
        let mut amfi = AmfiClient::connect(&phone.provider).await.map_err(err)?;
        amfi.reveal_developer_mode_option_in_ui().await.map_err(err)
    }).await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classifies_sidestore_by_prefix_or_name() {
        assert_eq!(classify("com.SideStore.SideStore.ABCDE12345", None), Some(AppKind::SideStore));
        assert_eq!(classify("com.example.nightly.ABCDE12345", Some("SideStore")), Some(AppKind::SideStore));
        assert_eq!(classify("com.example.other", Some("Other")), None);
    }

    #[test]
    fn classifies_nuvio_channels() {
        assert_eq!(classify("com.nuvio.app.z", None), Some(AppKind::NuvioStable));
        assert_eq!(classify("com.nuvio.app.z.ABCDE12345", None), Some(AppKind::NuvioStable));
        assert_eq!(classify("com.nuvio.app.z.debug.ABCDE12345", None), Some(AppKind::NuvioDebug));
        assert_eq!(classify("com.nuvio.app.zz", None), None);
    }

    #[test]
    fn pairing_state_needs_all_three_files() {
        let names = |v: &[&str]| v.iter().map(|s| s.to_string()).collect::<Vec<_>>();
        assert_eq!(pairing_state(&names(&[])), PairingState::Absent);
        assert_eq!(pairing_state(&names(&["ALTPairingFile.mobiledevicepairing"])), PairingState::Partial);
        assert_eq!(pairing_state(&names(SIDESTORE_FILES)), PairingState::Present);
    }
}
