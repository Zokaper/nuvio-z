//! idevice 0.1.68's default rustls verifier accepts every certificate AND signature (sni.rs).
//! This experiment instead pins the exact paired device certificate and verifies proof of its key.
//! Apple's device certificates are not DNS/web certificates, so WebPKI hostname checks do not apply.
use idevice::{IdeviceError, lockdown::LockdownClient, pairing_file::PairingFile};
use rustls::{
    DigitallySignedStruct, SignatureScheme,
    client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier},
    pki_types::{CertificateDer, PrivateKeyDer, ServerName, UnixTime, pem::PemObject},
};
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

#[derive(Debug)]
struct PinnedDevice {
    certificate: CertificateDer<'static>,
    algorithms: rustls::crypto::WebPkiSupportedAlgorithms,
}
impl ServerCertVerifier for PinnedDevice {
    fn verify_server_cert(
        &self,
        certificate: &CertificateDer<'_>,
        _: &[CertificateDer<'_>],
        _: &ServerName<'_>,
        _: &[u8],
        _: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        if certificate.as_ref() != self.certificate.as_ref() {
            return Err(rustls::Error::InvalidCertificate(
                rustls::CertificateError::ApplicationVerificationFailure,
            ));
        }
        Ok(ServerCertVerified::assertion())
    }
    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        signature: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(message, cert, signature, &self.algorithms)
    }
    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        signature: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(message, cert, signature, &self.algorithms)
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.algorithms.supported_schemes()
    }
}

pub(crate) fn config(record: &PairingFile) -> Result<rustls::ClientConfig, IdeviceError> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let verifier = PinnedDevice {
        certificate: record.device_certificate.clone(),
        algorithms: rustls::crypto::ring::default_provider().signature_verification_algorithms,
    };
    let key = PrivateKeyDer::from_pem_slice(&record.host_private_key)?;
    Ok(rustls::ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(verifier))
        .with_client_auth_cert(vec![record.host_certificate.clone()], key)?)
}

pub async fn start(
    lockdown: LockdownClient,
    record: &PairingFile,
) -> Result<LockdownClient, IdeviceError> {
    let mut socket = lockdown
        .idevice
        .get_socket()
        .ok_or(IdeviceError::NoEstablishedConnection)?;
    let mut request = plist::Dictionary::new();
    request.insert("Label".into(), "nuvio-android-pairing".into());
    request.insert("Request".into(), "StartSession".into());
    request.insert("HostID".into(), record.host_id.clone().into());
    request.insert("SystemBUID".into(), record.system_buid.clone().into());
    let mut bytes = Vec::new();
    plist::Value::Dictionary(request).to_writer_xml(&mut bytes)?;
    socket.write_u32(bytes.len() as u32).await?;
    socket.write_all(&bytes).await?;
    socket.flush().await?;
    let length = socket.read_u32().await?;
    if length > 1024 * 1024 {
        return Err(IdeviceError::UnexpectedResponse(
            "Oversized StartSession response".into(),
        ));
    }
    let mut response = vec![0; length as usize];
    socket.read_exact(&mut response).await?;
    let response = plist::Value::from_reader(std::io::Cursor::new(response))?
        .into_dictionary()
        .ok_or_else(|| IdeviceError::UnexpectedResponse("Invalid StartSession response".into()))?;
    if let Some(error) = response.get("Error") {
        return Err(match error.as_string() {
            Some("InvalidHostID") => IdeviceError::InvalidHostID,
            Some("PasswordProtected") => IdeviceError::PasswordProtected,
            _ => IdeviceError::UnexpectedResponse("StartSession rejected; details withheld".into()),
        });
    }
    if response
        .get("EnableSessionSSL")
        .and_then(plist::Value::as_boolean)
        != Some(true)
    {
        return Err(IdeviceError::UnexpectedResponse(
            "StartSession did not require TLS".into(),
        ));
    }
    let session = response
        .get("SessionID")
        .and_then(plist::Value::as_string)
        .ok_or_else(|| IdeviceError::UnexpectedResponse("StartSession missing SessionID".into()))?
        .to_string();
    let connector = tokio_rustls::TlsConnector::from(Arc::new(config(record)?));
    let stream = connector
        .connect(ServerName::try_from("Device").unwrap(), socket)
        .await?;
    let mut device = idevice::Idevice::new(Box::new(stream), "nuvio-android-pairing");
    if let Some(udid) = &record.udid {
        device.set_udid(udid.clone());
    }
    Ok(LockdownClient {
        idevice: device,
        session_id: Some(session),
    })
}

// Default IdeviceService::connect would invoke idevice's permissive TLS helper internally.
// Start the read-only AMFI service through our pinned session, then pin its TLS channel if requested.
pub async fn developer_mode(
    lockdown: &mut LockdownClient,
    provider: &dyn idevice::provider::IdeviceProvider,
    record: &PairingFile,
) -> Result<bool, IdeviceError> {
    amfi(lockdown, provider, record)
        .await?
        .get_developer_mode_status()
        .await
}

/// Makes Settings › Privacy & Security › Developer Mode appear (iOS hides it until a computer asks). Only reveals
/// the switch, as desktop's `reveal_developer_mode` does; the person still turns it on and confirms the restart.
pub async fn reveal_developer_mode(
    lockdown: &mut LockdownClient,
    provider: &dyn idevice::provider::IdeviceProvider,
    record: &PairingFile,
) -> Result<(), IdeviceError> {
    amfi(lockdown, provider, record)
        .await?
        .reveal_developer_mode_option_in_ui()
        .await
}

async fn amfi(
    lockdown: &mut LockdownClient,
    provider: &dyn idevice::provider::IdeviceProvider,
    record: &PairingFile,
) -> Result<idevice::amfi::AmfiClient, IdeviceError> {
    let (port, ssl) = lockdown.start_service("com.apple.amfi.lockdown").await?;
    let device = provider.connect(port).await?;
    let device = if ssl {
        let socket = device
            .get_socket()
            .ok_or(IdeviceError::NoEstablishedConnection)?;
        let stream = tokio_rustls::TlsConnector::from(Arc::new(config(record)?))
            .connect(ServerName::try_from("Device").unwrap(), socket)
            .await?;
        let mut device = idevice::Idevice::new(Box::new(stream), "nuvio-android-pairing");
        if let Some(udid) = &record.udid {
            device.set_udid(udid.clone());
        }
        device
    } else {
        device
    };
    Ok(idevice::amfi::AmfiClient::new(device))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Debug)]
    struct WrongSigningKey(Arc<rustls::sign::CertifiedKey>);
    impl rustls::server::ResolvesServerCert for WrongSigningKey {
        fn resolve(
            &self,
            _: rustls::server::ClientHello<'_>,
        ) -> Option<Arc<rustls::sign::CertifiedKey>> {
            Some(self.0.clone())
        }
    }

    #[tokio::test]
    async fn pinned_certificate_without_its_private_key_fails_tls12_and_tls13() {
        let record = crate::pairing_tests::fixture_record().await;
        // Present the correct pinned device certificate, but sign the handshake with the host key.
        // This catches a verifier that checks only certificate equality and skips TLS signatures.
        for protocol in [&rustls::version::TLS12, &rustls::version::TLS13] {
            let wrong_key = rustls::crypto::ring::sign::any_supported_type(
                &PrivateKeyDer::from_pem_slice(&record.host_private_key).unwrap(),
            )
            .unwrap();
            let certified =
                rustls::sign::CertifiedKey::new(vec![record.device_certificate.clone()], wrong_key);
            let server_config = rustls::ServerConfig::builder_with_protocol_versions(&[protocol])
                .with_no_client_auth()
                .with_cert_resolver(Arc::new(WrongSigningKey(Arc::new(certified))));
            let (client, server) = tokio::io::duplex(65536);
            let server = tokio::spawn(async move {
                tokio_rustls::TlsAcceptor::from(Arc::new(server_config))
                    .accept(server)
                    .await
            });
            let result = tokio_rustls::TlsConnector::from(Arc::new(config(&record).unwrap()))
                .connect(ServerName::try_from("Device").unwrap(), client)
                .await;
            assert!(
                result.is_err(),
                "A pinned certificate alone is not proof of possession"
            );
            let _ = server.await.unwrap();
        }
    }
}
