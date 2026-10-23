//! Real idevice Pair/GetValue framing and real rustls handshakes against synthetic peers.
//! All RSA keys are generated in test memory; no private-key fixtures are stored in the repository.
use crate::pairing::{self, Failure, PairingReport, Store};
use idevice::{IdeviceError, pairing_file::PairingFile, provider::IdeviceProvider};
use rsa::{RsaPrivateKey, pkcs1::EncodeRsaPublicKey, pkcs8::EncodePrivateKey};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, pem::PemObject};
use std::{
    collections::VecDeque,
    future::Future,
    pin::Pin,
    sync::{Arc, Mutex, OnceLock},
};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, DuplexStream};

const UDID: &str = "00000000-0000000000000001";

fn device_key() -> &'static RsaPrivateKey {
    static KEY: OnceLock<RsaPrivateKey> = OnceLock::new();
    KEY.get_or_init(|| RsaPrivateKey::new(&mut rsa::rand_core::OsRng, 2048).unwrap())
}
async fn request(peer: &mut (impl AsyncRead + Unpin)) -> plist::Dictionary {
    let size = peer.read_u32().await.unwrap();
    assert!(size < 65536);
    let mut bytes = vec![0; size as usize];
    peer.read_exact(&mut bytes).await.unwrap();
    plist::Value::from_reader(std::io::Cursor::new(bytes))
        .unwrap()
        .into_dictionary()
        .unwrap()
}
async fn respond(peer: &mut (impl AsyncWrite + Unpin), entries: &[(&str, plist::Value)]) {
    let mut dictionary = plist::Dictionary::new();
    for (k, v) in entries {
        dictionary.insert((*k).into(), v.clone());
    }
    let mut bytes = Vec::new();
    plist::Value::Dictionary(dictionary)
        .to_writer_xml(&mut bytes)
        .unwrap();
    peer.write_u32(bytes.len() as u32).await.unwrap();
    peer.write_all(&bytes).await.unwrap();
}
fn operation(request: &plist::Dictionary) -> &str {
    request.get("Request").unwrap().as_string().unwrap()
}

#[derive(Debug)]
struct PeerProvider(Mutex<VecDeque<(u16, DuplexStream)>>);
impl PeerProvider {
    fn new(ports: &[u16]) -> (Self, Vec<DuplexStream>) {
        let mut clients = VecDeque::new();
        let mut servers = Vec::new();
        for port in ports {
            let (c, s) = tokio::io::duplex(65536);
            clients.push_back((*port, c));
            servers.push(s);
        }
        (Self(Mutex::new(clients)), servers)
    }
}
impl IdeviceProvider for PeerProvider {
    fn label(&self) -> &str {
        "test-redacted"
    }
    fn connect(
        &self,
        port: u16,
    ) -> Pin<Box<dyn Future<Output = Result<idevice::Idevice, IdeviceError>> + Send>> {
        let next = self.0.lock().unwrap().pop_front();
        Box::pin(async move {
            let (expected, stream) = next.ok_or(IdeviceError::NotFound)?;
            assert_eq!(port, expected);
            Ok(idevice::Idevice::new(Box::new(stream), "test-redacted"))
        })
    }
    fn get_pairing_file(
        &self,
    ) -> Pin<Box<dyn Future<Output = Result<PairingFile, IdeviceError>> + Send>> {
        Box::pin(async { Err(IdeviceError::NotFound) })
    }
}

#[derive(Default)]
struct MemoryStore {
    record: Option<Vec<u8>>,
    identity_calls: usize,
    saves: usize,
    fail_save: bool,
    reboot: i32,
}
impl Store for MemoryStore {
    fn identity(&mut self, _: &str) -> Result<(String, String), Failure> {
        self.identity_calls += 1;
        Ok(("TEST-HOST".into(), "TEST-BUID".into()))
    }
    fn load(&mut self, udid: &str) -> Result<Option<Vec<u8>>, Failure> {
        assert_eq!(udid, UDID);
        Ok(self.record.clone())
    }
    fn save(&mut self, _: &str, bytes: &[u8]) -> Result<(), Failure> {
        if self.fail_save {
            return Err(("pairing_store", "Synthetic save failure"));
        }
        self.saves += 1;
        self.record = Some(bytes.to_vec());
        Ok(())
    }
    fn reboot(&mut self, _: &str, action: i32) -> Result<i32, Failure> {
        if action == 1 {
            self.reboot = 1;
        } else if action == 2 {
            self.reboot = 0;
        }
        Ok(self.reboot)
    }
}

async fn identify(peer: &mut DuplexStream) {
    let req = request(peer).await;
    assert_eq!(operation(&req), "GetValue");
    assert_eq!(req.get("Key").unwrap().as_string(), Some("UniqueDeviceID"));
    respond(peer, &[("Value", UDID.into())]).await;
}

async fn pair_peer(
    peer: &mut DuplexStream,
    pending: bool,
    deny: bool,
) -> Option<CertificateDer<'static>> {
    let req = request(peer).await;
    assert_eq!(req.get("Key").unwrap().as_string(), Some("DevicePublicKey"));
    let public = rsa::RsaPublicKey::from(device_key())
        .to_pkcs1_pem(rsa::pkcs8::LineEnding::LF)
        .unwrap();
    respond(peer, &[("Value", plist::Value::Data(public.into_bytes()))]).await;
    let req = request(peer).await;
    assert_eq!(req.get("Key").unwrap().as_string(), Some("WiFiAddress"));
    respond(peer, &[("Value", "00:00:00:00:00:00".into())]).await;
    let first = request(peer).await;
    assert_eq!(operation(&first), "Pair");
    if deny {
        respond(peer, &[("Error", "UserDeniedPairing".into())]).await;
        return None;
    }
    if pending {
        respond(peer, &[("Error", "PairingDialogResponsePending".into())]).await;
        let retry = request(peer).await;
        assert_eq!(
            first, retry,
            "Pending Trust must reuse the exact certificate and host identity"
        );
    }
    let record = first.get("PairRecord").unwrap().as_dictionary().unwrap();
    let certificate =
        CertificateDer::from_pem_slice(record.get("DeviceCertificate").unwrap().as_data().unwrap())
            .unwrap();
    respond(peer, &[("EscrowBag", plist::Value::Data(vec![1, 2, 3]))]).await;
    Some(certificate)
}

async fn tls_peer(
    mut peer: DuplexStream,
    certificate: CertificateDer<'static>,
    wrong_pin: Option<&PairingFile>,
    ssl: bool,
    amfi: bool,
) {
    let req = request(&mut peer).await;
    assert_eq!(operation(&req), "StartSession");
    assert_eq!(req.get("HostID").unwrap().as_string(), Some("TEST-HOST"));
    respond(
        &mut peer,
        &[
            ("EnableSessionSSL", ssl.into()),
            ("SessionID", "TEST-SESSION".into()),
        ],
    )
    .await;
    if !ssl {
        return;
    }
    let _ = rustls::crypto::ring::default_provider().install_default();
    let (certificate, key) = match wrong_pin {
        Some(record) => (
            record.host_certificate.clone(),
            PrivateKeyDer::from_pem_slice(&record.host_private_key).unwrap(),
        ),
        None => (
            certificate,
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(
                device_key().to_pkcs8_der().unwrap().as_bytes().to_vec(),
            )),
        ),
    };
    let config = rustls::ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(vec![certificate], key)
        .unwrap();
    let connection = tokio_rustls::TlsAcceptor::from(Arc::new(config))
        .accept(peer)
        .await;
    if wrong_pin.is_some() {
        assert!(
            connection.is_err(),
            "Wrong paired certificate must be rejected"
        );
        return;
    }
    let mut peer = connection.unwrap();
    for key in crate::INFO_KEYS {
        let req = request(&mut peer).await;
        assert_eq!(operation(&req), "GetValue");
        assert_eq!(req.get("Key").unwrap().as_string(), Some(key));
        respond(&mut peer, &[("Value", format!("test-{key}").into())]).await;
    }
    let req = request(&mut peer).await;
    assert_eq!(operation(&req), "StartService");
    assert_eq!(
        req.get("Service").unwrap().as_string(),
        Some("com.apple.amfi.lockdown")
    );
    if amfi {
        respond(
            &mut peer,
            &[("Port", 12345.into()), ("EnableServiceSSL", false.into())],
        )
        .await;
    } else {
        respond(&mut peer, &[("Error", "InvalidService".into())]).await;
    }
    let req = request(&mut peer).await;
    assert_eq!(operation(&req), "StopSession");
    respond(&mut peer, &[("Request", "StopSession".into())]).await;
}

pub(super) async fn fixture_record() -> PairingFile {
    static RECORD: tokio::sync::OnceCell<PairingFile> = tokio::sync::OnceCell::const_new();
    RECORD
        .get_or_init(|| async {
            let (client, mut server) = tokio::io::duplex(65536);
            let task = tokio::spawn(async move {
                pair_peer(&mut server, false, false).await;
            });
            let mut client = idevice::lockdown::LockdownClient::new(idevice::Idevice::new(
                Box::new(client),
                "test",
            ));
            let mut record = client
                .pair("TEST-HOST", "TEST-BUID", Some("test"))
                .await
                .unwrap();
            record.udid = Some(UDID.into());
            task.await.unwrap();
            record
        })
        .await
        .clone()
}

#[tokio::test]
async fn fresh_trust_reuses_pending_request_saves_record_then_authenticates_tls() {
    let (provider, mut peers) = PeerProvider::new(&[62078, 62078]);
    let second = peers.pop().unwrap();
    let mut first = peers.pop().unwrap();
    let server = tokio::spawn(async move {
        identify(&mut first).await;
        let cert = pair_peer(&mut first, true, false).await.unwrap();
        drop(first);
        tls_peer(second, cert, None, true, false).await;
    });
    let mut store = MemoryStore::default();
    let mut report = PairingReport::default();
    let info = pairing::authenticate(&provider, &mut store, true, false, &mut report, |_, _| {})
        .await
        .unwrap();
    assert_eq!(info.len(), 4);
    assert!(report.paired_now && report.record_saved && report.tls_authenticated);
    assert!(!report.self_refresh_ready);
    assert_eq!(store.saves, 1);
    let record = PairingFile::from_bytes(store.record.as_ref().unwrap()).unwrap();
    assert_eq!(record.udid.as_deref(), Some(UDID));
    assert_eq!(record.escrow_bag, Some(vec![1, 2, 3]));
    server.await.unwrap();
}

#[tokio::test]
async fn trust_denial_does_not_save_or_start_a_session() {
    let (provider, mut peers) = PeerProvider::new(&[62078]);
    let mut peer = peers.pop().unwrap();
    let server = tokio::spawn(async move {
        identify(&mut peer).await;
        pair_peer(&mut peer, false, true).await;
    });
    let mut store = MemoryStore::default();
    let mut report = PairingReport::default();
    assert_eq!(
        pairing::authenticate(&provider, &mut store, true, false, &mut report, |_, _| {})
            .await
            .unwrap_err()
            .0,
        "trust_denied"
    );
    assert!(store.record.is_none());
    assert_eq!(store.saves, 0);
    assert!(!report.tls_authenticated);
    server.await.unwrap();
}

#[tokio::test]
async fn reconnect_without_a_record_never_silently_pairs() {
    let (provider, mut peers) = PeerProvider::new(&[62078]);
    let mut peer = peers.pop().unwrap();
    let server = tokio::spawn(async move {
        identify(&mut peer).await;
        assert!(peer.read_u8().await.is_err());
    });
    let mut store = MemoryStore::default();
    assert_eq!(
        pairing::authenticate(
            &provider,
            &mut store,
            false,
            false,
            &mut PairingReport::default(),
            |_, _| {}
        )
        .await
        .unwrap_err()
        .0,
        "pairing_record_missing"
    );
    assert_eq!(store.identity_calls, 0);
    server.await.unwrap();
}

#[tokio::test]
async fn corrupt_record_is_retained_and_never_replaced_by_pairing() {
    let (provider, mut peers) = PeerProvider::new(&[62078]);
    let mut peer = peers.pop().unwrap();
    let server = tokio::spawn(async move {
        identify(&mut peer).await;
        assert!(peer.read_u8().await.is_err());
    });
    let mut store = MemoryStore {
        record: Some(b"not-a-plist".to_vec()),
        ..Default::default()
    };
    assert_eq!(
        pairing::authenticate(
            &provider,
            &mut store,
            true,
            false,
            &mut PairingReport::default(),
            |_, _| {}
        )
        .await
        .unwrap_err()
        .0,
        "pairing_record_invalid"
    );
    assert_eq!(store.record.unwrap(), b"not-a-plist");
    assert_eq!(store.saves, 0);
    server.await.unwrap();
}

#[tokio::test]
async fn stored_record_reconnects_and_recovers_expected_developer_mode_reboot() {
    let record = fixture_record().await;
    let (provider, mut peers) = PeerProvider::new(&[62078, 62078, 12345]);
    let mut amfi = peers.pop().unwrap();
    let tls = peers.pop().unwrap();
    let mut first = peers.pop().unwrap();
    let cert = record.device_certificate.clone();
    let server = tokio::spawn(async move {
        identify(&mut first).await;
        drop(first);
        tls_peer(tls, cert, None, true, true).await;
    });
    let amfi_server = tokio::spawn(async move {
        let req = request(&mut amfi).await;
        assert_eq!(req.get("action").unwrap().as_unsigned_integer(), Some(3));
        respond(
            &mut amfi,
            &[("success", true.into()), ("status", true.into())],
        )
        .await;
    });
    let mut store = MemoryStore {
        record: Some(record.serialize().unwrap()),
        reboot: 1,
        ..Default::default()
    };
    let saved = store.record.clone();
    let mut report = PairingReport::default();
    pairing::authenticate(&provider, &mut store, false, false, &mut report, |_, _| {})
        .await
        .unwrap();
    assert!(!report.paired_now);
    assert!(report.tls_authenticated && report.reboot_reconnect_verified);
    assert_eq!(report.developer_mode, Some(true));
    assert_eq!(store.identity_calls, 0);
    assert_eq!(store.record, saved);
    assert_eq!(store.reboot, 0);
    server.await.unwrap();
    amfi_server.await.unwrap();
}

#[tokio::test]
async fn plaintext_session_is_rejected_without_losing_the_record() {
    let record = fixture_record().await;
    let cert = record.device_certificate.clone();
    let (provider, mut peers) = PeerProvider::new(&[62078, 62078]);
    let tls = peers.pop().unwrap();
    let mut first = peers.pop().unwrap();
    let server = tokio::spawn(async move {
        identify(&mut first).await;
        drop(first);
        tls_peer(tls, cert, None, false, false).await;
    });
    let mut store = MemoryStore {
        record: Some(record.serialize().unwrap()),
        ..Default::default()
    };
    let mut report = PairingReport::default();
    assert_eq!(
        pairing::authenticate(&provider, &mut store, false, false, &mut report, |_, _| {})
            .await
            .unwrap_err()
            .0,
        "lockdown_tls"
    );
    assert!(report.record_saved && !report.tls_authenticated);
    assert_eq!(store.saves, 0);
    server.await.unwrap();
}

#[tokio::test]
async fn a_valid_tls_certificate_from_the_wrong_pairing_is_rejected() {
    let record = fixture_record().await;
    let wrong = record.clone();
    let cert = record.device_certificate.clone();
    let (provider, mut peers) = PeerProvider::new(&[62078, 62078]);
    let tls = peers.pop().unwrap();
    let mut first = peers.pop().unwrap();
    let server = tokio::spawn(async move {
        identify(&mut first).await;
        drop(first);
        tls_peer(tls, cert, Some(&wrong), true, false).await;
    });
    let mut store = MemoryStore {
        record: Some(record.serialize().unwrap()),
        ..Default::default()
    };
    let mut report = PairingReport::default();
    assert_eq!(
        pairing::authenticate(&provider, &mut store, false, false, &mut report, |_, _| {})
            .await
            .unwrap_err()
            .0,
        "lockdown_tls"
    );
    assert!(!report.tls_authenticated);
    assert!(store.record.is_some());
    server.await.unwrap();
}
