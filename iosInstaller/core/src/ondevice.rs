//! Talking to the phone from an app running on that same phone (Phase 2: Nuvio Z signs and installs itself).
//!
//! LocalDevVPN gives the phone a loopback endpoint at 10.7.0.1. With the pairing file the installer placed, an
//! app connects there exactly as a computer would over USB, by one of two routes, the same two SideStore's
//! minimuxer uses:
//! - **lockdown** (port 62078) with the lockdown pair record — the route that has worked since iOS 17;
//! - **remote pairing** (port 49152): verify with the rppairing identity, open the TLS-PSK tunnel and reach
//!   services through RSD — iOS 17.4 and later.
//!
//! The spike tries both and reports each, so one run on a phone says which route that iOS accepts.
use idevice::{
    installation_proxy::InstallationProxyClient,
    pairing_file::PairingFile,
    provider::TcpProvider,
    remote_pairing::{connect_tls_psk_tunnel_native, RemotePairingClient, RpPairingFile, RpPairingSocket},
    rsd::RsdHandshake,
    tcp::handle::AdapterHandle,
    IdeviceService, RsdService,
};
use serde::Serialize;
use std::net::{IpAddr, SocketAddr};
use std::time::Duration;

use crate::device::err;

/// LocalDevVPN's default device address (configurable in LocalDevVPN; SideStore lets users change it too).
pub const LOOPBACK: &str = "10.7.0.1";
const RP_PORT: u16 = 49152;
const STEP_TIMEOUT: Duration = Duration::from_secs(20);

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RouteResult {
    pub route: &'static str,
    pub ok: bool,
    pub apps: Option<usize>,
    pub error: Option<String>,
    pub millis: u128,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OnDeviceProbe {
    pub address: String,
    pub routes: Vec<RouteResult>,
    /// User apps the first working route saw: (bundle id, name, version).
    pub apps: Vec<(String, String, String)>,
}

async fn timed<T>(what: &str, fut: impl std::future::Future<Output = Result<T, String>>) -> Result<T, String> {
    match tokio::time::timeout(STEP_TIMEOUT, fut).await {
        Ok(r) => r.map_err(|e| format!("{what}: {e}")),
        Err(_) => Err(format!("{what}: timed out (is LocalDevVPN connected?)")),
    }
}

fn summarize(list: &plist::Dictionary) -> Vec<(String, String, String)> {
    let mut apps: Vec<_> = list.iter().map(|(bundle, info)| {
        let d = info.as_dictionary();
        let field = |k: &str| d.and_then(|d| d.get(k)).and_then(|v| v.as_string()).unwrap_or("").to_string();
        (bundle.clone(), field("CFBundleDisplayName"), field("CFBundleShortVersionString"))
    }).collect();
    apps.sort();
    apps
}

/// A provider for the lockdown route: lockdown-based services over TCP to the phone's own address.
pub fn lockdown_provider(pairing: &[u8], address: &str) -> Result<TcpProvider, String> {
    let addr: IpAddr = address.parse().map_err(err)?;
    let pairing_file = PairingFile::from_bytes(pairing).map_err(|e| format!("lockdown pair record: {}", err(e)))?;
    Ok(TcpProvider { addr, scope_id: None, pairing_file, label: "nuvioz-ondevice".into() })
}

/// The remote-pairing route (iOS 17.4+): verify, open the TLS-PSK tunnel, and return what RSD services are
/// reached through. Built as idevice 0.1.68's `tunnel_create_rppairing` builds it.
pub async fn tunnel(pairing: &[u8], address: &str) -> Result<(AdapterHandle, RsdHandshake), String> {
    let addr: IpAddr = address.parse().map_err(err)?;
    let mut identity = RpPairingFile::from_bytes(pairing).map_err(|e| format!("rppairing identity: {}", err(e)))?;
    let connect_addr = SocketAddr::new(addr, RP_PORT);
    let stream = timed("connect", async { tokio::net::TcpStream::connect(connect_addr).await.map_err(err) }).await?;
    let mut rpc = RemotePairingClient::new(RpPairingSocket::new(stream), "Nuvio Z");
    timed("pair verify", async { rpc.connect(&mut identity, async || "000000".to_string()).await.map_err(err) }).await?;

    let tunnel_port = timed("tunnel listener", async { rpc.create_tcp_listener().await.map_err(err) }).await?;
    let tunnel_stream = timed("tunnel connect", async { tokio::net::TcpStream::connect(SocketAddr::new(addr, tunnel_port)).await.map_err(err) }).await?;
    let tunnel = timed("tunnel TLS", async { connect_tls_psk_tunnel_native(tunnel_stream, rpc.encryption_key()).await.map_err(err) }).await?;
    let client_ip: IpAddr = tunnel.info.client_address.parse().map_err(err)?;
    let server_ip: IpAddr = tunnel.info.server_address.parse().map_err(err)?;
    let mtu = tunnel.info.mtu as usize;
    let rsd_port = tunnel.info.server_rsd_port;

    let mut adapter = idevice::tcp::adapter::Adapter::new(Box::new(tunnel.into_inner()), client_ip, server_ip);
    adapter.set_mss(mtu.saturating_sub(60));
    let mut adapter = adapter.to_async_handle();
    let rsd_stream = timed("RSD", async { adapter.connect(rsd_port).await.map_err(err) }).await?;
    let handshake = timed("RSD handshake", async { RsdHandshake::new(rsd_stream).await.map_err(err) }).await?;
    Ok((adapter, handshake))
}

async fn via_lockdown(addr: IpAddr, pairing: &[u8]) -> Result<plist::Dictionary, String> {
    let provider = lockdown_provider(pairing, &addr.to_string())?;
    let mut proxy = timed("installation_proxy", async { InstallationProxyClient::connect(&provider).await.map_err(err) }).await?;
    let apps = timed("list apps", async { proxy.get_apps(Some("User"), None).await.map_err(err) }).await?;
    Ok(apps.into_iter().collect())
}

/// The remote-pairing tunnel, as idevice 0.1.68's `tunnel_create_rppairing` builds it.
async fn via_remote_pairing(addr: IpAddr, pairing: &[u8]) -> Result<plist::Dictionary, String> {
    let (mut adapter, mut handshake) = tunnel(pairing, &addr.to_string()).await?;
    let mut proxy = timed("installation_proxy", async { InstallationProxyClient::connect_rsd(&mut adapter, &mut handshake).await.map_err(err) }).await?;
    let apps = timed("list apps", async { proxy.get_apps(Some("User"), None).await.map_err(err) }).await?;
    Ok(apps.into_iter().collect())
}

/// Tries both routes to the phone's own services and lists user apps through whichever works.
pub async fn probe(pairing: &[u8], address: &str) -> OnDeviceProbe {
    let mut out = OnDeviceProbe { address: address.to_string(), routes: Vec::new(), apps: Vec::new() };
    let addr: IpAddr = match address.parse() {
        Ok(a) => a,
        Err(e) => {
            out.routes.push(RouteResult { route: "address", ok: false, apps: None, error: Some(err(e)), millis: 0 });
            return out;
        }
    };
    for route in ["lockdown", "remotePairing"] {
        let started = std::time::Instant::now();
        let result = match route {
            "lockdown" => via_lockdown(addr, pairing).await,
            _ => via_remote_pairing(addr, pairing).await,
        };
        let millis = started.elapsed().as_millis();
        match result {
            Ok(list) => {
                if out.apps.is_empty() {
                    out.apps = summarize(&list);
                }
                out.routes.push(RouteResult { route, ok: true, apps: Some(list.len()), error: None, millis });
            }
            Err(e) => out.routes.push(RouteResult { route, ok: false, apps: None, error: Some(e), millis }),
        }
    }
    out
}
