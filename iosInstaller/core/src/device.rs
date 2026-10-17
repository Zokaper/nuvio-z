//! What the computer can see of the iPhone over USB.
//!
//! Read-only, and deliberately without the device name or full UDID: the snapshot ends up in the UI
//! and in diagnostics.
use idevice::{
    amfi::AmfiClient,
    lockdown::LockdownClient,
    provider::IdeviceProvider,
    usbmuxd::{Connection, UsbmuxdAddr, UsbmuxdConnection},
    IdeviceService,
};
use serde::Serialize;
use std::time::Duration;

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
    pub errors: Vec<String>,
}

async fn bounded<T>(what: &str, fut: impl std::future::Future<Output = Result<T, String>>) -> Result<T, String> {
    match tokio::time::timeout(PROBE_TIMEOUT, fut).await {
        Ok(r) => r.map_err(|e| format!("{what}: {e}")),
        Err(_) => Err(format!("{what}: timeout")),
    }
}

fn err(e: impl std::fmt::Debug) -> String {
    format!("{e:?}")
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
    let Some(device) = usb.first() else { return snap };
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
    snap
}
