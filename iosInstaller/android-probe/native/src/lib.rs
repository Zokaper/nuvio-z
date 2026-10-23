//! Isolated USB transport/pairing experiment. No Apple account, signing or installation operations.
use serde::Serialize;
use std::collections::BTreeMap;

pub const VERSION: &str = "nuvio-usb-probe/0.4.0 rusbmux/0.3.0+nuvio idevice/0.1.68 nusb/0.2.8";
#[cfg(target_os = "android")]
mod android_store;
#[cfg(any(target_os = "android", test))]
mod control;
mod paired_tls;
mod pairing;
#[cfg(test)]
mod pairing_tests;
pub const INFO_KEYS: [&str; 4] = [
    "DeviceClass",
    "ProductType",
    "ProductVersion",
    "BuildVersion",
];

/// Shared with protocol tests: only allowlisted, unpaired GetValue requests are sent.
pub async fn read_device_info(
    lockdown: &mut idevice::lockdown::LockdownClient,
    mut progress: impl FnMut(&str),
) -> Result<BTreeMap<String, String>, String> {
    let mut values = BTreeMap::new();
    for key in INFO_KEYS {
        progress(key);
        let value = tokio::time::timeout(std::time::Duration::from_secs(8), lockdown.get_value(Some(key), None)).await
            .map_err(|_| format!("{key}: timed out"))?
            .map_err(|e| format!("{key}: {e}. If iOS requires pairing, that is a Phase 2 gate; this probe will not pair."))?;
        let value = value
            .as_string()
            .ok_or_else(|| format!("{key}: unexpected value type"))?;
        values.insert(key.into(), value.into());
    }
    Ok(values)
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Event {
    pub stage: String,
    pub message: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProbeReport {
    pub schema: u32,
    pub version: &'static str,
    pub ok: bool,
    pub mux_connected: bool,
    pub lockdown_connected: bool,
    pub device_info: BTreeMap<String, String>,
    pub events: Vec<Event>,
    pub blocker: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pairing: Option<pairing::PairingReport>,
}

impl Default for ProbeReport {
    fn default() -> Self {
        Self {
            schema: 1,
            version: VERSION,
            ok: false,
            mux_connected: false,
            lockdown_connected: false,
            device_info: BTreeMap::new(),
            events: Vec::new(),
            blocker: None,
            pairing: None,
        }
    }
}

impl ProbeReport {
    pub fn event(&mut self, stage: &str, message: impl Into<String>) {
        self.events.push(Event {
            stage: stage.into(),
            message: message.into(),
        });
    }

    pub fn fail(&mut self, stage: &str, detail: impl std::fmt::Display) {
        self.blocker = Some(format!("{stage}: {detail}"));
        self.event(
            stage,
            "Stopped; see blocker. No Apple authentication, signing or installation performed.",
        );
    }
}

#[cfg(target_os = "android")]
mod android;

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt, DuplexStream};

    // A fake phone speaks Lockdown framing directly, exercising the real idevice client.
    async fn request(peer: &mut DuplexStream) -> plist::Dictionary {
        let size = peer.read_u32().await.unwrap();
        assert!(size < 16384);
        let mut bytes = vec![0; size as usize];
        peer.read_exact(&mut bytes).await.unwrap();
        plist::Value::from_reader(std::io::Cursor::new(bytes))
            .unwrap()
            .into_dictionary()
            .unwrap()
    }

    async fn respond(peer: &mut DuplexStream, key: &str, value: &str) {
        let mut dictionary = plist::Dictionary::new();
        dictionary.insert(key.into(), value.into());
        let mut bytes = Vec::new();
        plist::Value::Dictionary(dictionary)
            .to_writer_xml(&mut bytes)
            .unwrap();
        peer.write_u32(bytes.len() as u32).await.unwrap();
        peer.write_all(&bytes).await.unwrap();
    }

    #[test]
    fn report_distinguishes_transport_success_from_information_failure() {
        let mut r = ProbeReport {
            mux_connected: true,
            lockdown_connected: true,
            ..Default::default()
        };
        r.fail("device_info", "GetValue refused before pairing");
        let json = serde_json::to_value(r).unwrap();
        assert_eq!(json["muxConnected"], true);
        assert_eq!(json["lockdownConnected"], true);
        assert_eq!(json["ok"], false);
        assert!(json["blocker"].as_str().unwrap().contains("device_info"));
    }

    #[test]
    fn information_allowlist_excludes_personal_identifiers() {
        assert_eq!(
            INFO_KEYS,
            [
                "DeviceClass",
                "ProductType",
                "ProductVersion",
                "BuildVersion"
            ]
        );
        for forbidden in ["UniqueDeviceID", "DeviceName", "SerialNumber", "AppleID"] {
            assert!(!INFO_KEYS.contains(&forbidden));
        }
    }

    #[tokio::test]
    async fn wire_protocol_only_sends_four_unpaired_get_value_requests() {
        successful_read().await;
    }

    async fn successful_read() {
        let (client, server) = tokio::io::duplex(16384);
        let server = tokio::spawn(async move {
            let mut peer = server;
            for key in INFO_KEYS {
                let request = request(&mut peer).await;
                assert_eq!(
                    request.get("Request").and_then(plist::Value::as_string),
                    Some("GetValue")
                );
                assert_eq!(
                    request.get("Key").and_then(plist::Value::as_string),
                    Some(key)
                );
                assert!(!request.contains_key("PairRecord"));
                assert!(!request.contains_key("SessionID"));
                respond(&mut peer, "Value", &format!("test-{key}")).await;
            }
        });
        let mut lockdown = idevice::lockdown::LockdownClient::new(idevice::Idevice::new(
            Box::new(client),
            "probe-test",
        ));
        let values = read_device_info(&mut lockdown, |_| {}).await.unwrap();
        assert_eq!(values.len(), 4);
        assert_eq!(values["ProductType"], "test-ProductType");
        server.await.unwrap();
    }

    #[tokio::test]
    async fn refusal_is_reported_without_attempting_pairing() {
        let (client, server) = tokio::io::duplex(16384);
        let server = tokio::spawn(async move {
            let mut peer = server;
            let request = request(&mut peer).await;
            assert_eq!(
                request.get("Request").and_then(plist::Value::as_string),
                Some("GetValue")
            );
            respond(&mut peer, "Error", "PairingRequired").await;
        });
        let mut lockdown = idevice::lockdown::LockdownClient::new(idevice::Idevice::new(
            Box::new(client),
            "probe-test",
        ));
        let error = read_device_info(&mut lockdown, |_| {}).await.unwrap_err();
        assert!(error.contains("Phase 2 gate"));
        server.await.unwrap();
    }

    #[tokio::test(start_paused = true)]
    async fn silent_phone_has_a_bounded_information_query() {
        let (client, _server) = tokio::io::duplex(16384);
        let mut lockdown = idevice::lockdown::LockdownClient::new(idevice::Idevice::new(
            Box::new(client),
            "probe-test",
        ));
        assert!(
            read_device_info(&mut lockdown, |_| {})
                .await
                .unwrap_err()
                .contains("DeviceClass: timed out")
        );
    }

    #[tokio::test]
    async fn peer_unplug_during_get_value_fails_and_a_new_connection_can_succeed() {
        let (client, server) = tokio::io::duplex(16384);
        let server = tokio::spawn(async move {
            let mut peer = server;
            let first = request(&mut peer).await;
            assert_eq!(
                first.get("Key").and_then(plist::Value::as_string),
                Some("DeviceClass")
            );
            // EOF in the middle of the response frame, as a disappearing transport might do.
            peer.write_u32(100).await.unwrap();
            peer.write_all(b"<?xml").await.unwrap();
        });
        let mut lockdown = idevice::lockdown::LockdownClient::new(idevice::Idevice::new(
            Box::new(client),
            "probe-test",
        ));
        assert!(
            read_device_info(&mut lockdown, |_| {})
                .await
                .unwrap_err()
                .contains("DeviceClass:")
        );
        server.await.unwrap();
        // The real client on an independent stream must still perform all four unpaired requests.
        successful_read().await;
    }
}
