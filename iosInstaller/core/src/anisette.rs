//! The anisette server isideload signs in through.
//!
//! Apple changed what it accepts three times in the week of 2026-10-12, and each fix upstream was a
//! different server or client identity. The server therefore comes from the hosted config (Stage 2),
//! with this as the fallback.
use isideload::anisette::{remote_v3::RemoteV3AnisetteProvider, AnisetteProvider};
use serde::Serialize;

/// iloader 2.3.9's default (2026-10-16).
pub const DEFAULT_SERVER: &str = "https://mac-ani.jkcoxson.com";

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AnisetteCheck {
    pub server: String,
    pub reachable: bool,
    pub detail: String,
}

/// Asks the server for its client identity. Harmless: no Apple ID, nothing provisioned or stored.
pub async fn check(server: &str) -> AnisetteCheck {
    let result = async {
        let provider = RemoteV3AnisetteProvider::default()
            .map_err(|e| e.to_string())?
            .set_url(server);
        provider.get_client_info().await.map_err(|e| e.to_string())
    }
    .await;
    match result {
        Ok(info) => AnisetteCheck { server: server.into(), reachable: true, detail: info.client_info },
        Err(e) => AnisetteCheck { server: server.into(), reachable: false, detail: e },
    }
}
