//! Phase 2 protocol operations. Secrets are passed only to the private store, never to reports.
use idevice::{
    IdeviceError, IdeviceService, lockdown::LockdownClient, pairing_file::PairingFile,
    provider::IdeviceProvider,
};
use serde::Serialize;
use std::{collections::BTreeMap, future::Future, pin::Pin, time::Duration};

pub type Failure = (&'static str, &'static str);

pub trait Store {
    fn identity(&mut self, udid: &str) -> Result<(String, String), Failure>;
    fn load(&mut self, udid: &str) -> Result<Option<Vec<u8>>, Failure>;
    fn save(&mut self, udid: &str, bytes: &[u8]) -> Result<(), Failure>;
    fn reboot(&mut self, udid: &str, action: i32) -> Result<i32, Failure>;
}

#[derive(Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairingReport {
    pub paired_now: bool,
    pub record_saved: bool,
    pub tls_authenticated: bool,
    pub developer_mode: Option<bool>,
    pub reboot_pending: bool,
    pub reboot_reconnect_verified: bool,
    pub self_refresh_ready: bool, // Remote pairing / merged handoff is a later integration step.
}

// Raw mux routing uses a non-identifying placeholder. Authenticated services use the actual,
// privately verified device identity and the record bound to it, without enabling daemon preflight.
pub(crate) struct IdentifiedProvider<'a> {
    pub raw: &'a dyn IdeviceProvider,
    pub udid: String,
    pub record: PairingFile,
}

pub(crate) async fn saved_provider<'a>(
    raw: &'a dyn IdeviceProvider,
    store: &mut dyn Store,
) -> Result<IdentifiedProvider<'a>, Failure> {
    let mut lockdown = bounded("device_identity", LockdownClient::connect(raw)).await?;
    let value = bounded(
        "device_identity",
        lockdown.get_value(Some("UniqueDeviceID"), None),
    )
    .await?;
    let udid = value
        .as_string()
        .filter(|s| {
            (16..=64).contains(&s.len()) && s.bytes().all(|b| b.is_ascii_hexdigit() || b == b'-')
        })
        .ok_or(("device_identity", "Invalid private device identity"))?
        .to_string();
    let mut bytes = store.load(&udid)?.ok_or((
        "pairing_record_missing",
        "Pair the iPhone before installation",
    ))?;
    let record = PairingFile::from_bytes(&bytes);
    bytes.fill(0);
    let record =
        record.map_err(|_| ("pairing_record_invalid", "Cannot read saved pairing record"))?;
    if record.udid.as_deref() != Some(udid.as_str()) {
        return Err((
            "pairing_identity_mismatch",
            "Saved record belongs to another phone",
        ));
    }
    Ok(IdentifiedProvider { raw, udid, record })
}
impl std::fmt::Debug for IdentifiedProvider<'_> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("AndroidPairedProvider(redacted)")
    }
}
impl IdeviceProvider for IdentifiedProvider<'_> {
    fn label(&self) -> &str {
        "nuvio-android-pairing"
    }
    fn connect(
        &self,
        port: u16,
    ) -> Pin<Box<dyn Future<Output = Result<idevice::Idevice, IdeviceError>> + Send>> {
        let connection = self.raw.connect(port);
        let udid = self.udid.clone();
        Box::pin(async move {
            let mut device = connection.await?;
            device.set_udid(udid);
            Ok(device)
        })
    }
    fn get_pairing_file(
        &self,
    ) -> Pin<Box<dyn Future<Output = Result<PairingFile, IdeviceError>> + Send>> {
        let record = self.record.clone();
        Box::pin(async move { Ok(record) })
    }
}

fn protocol_error(stage: &'static str, error: IdeviceError) -> Failure {
    match error {
        IdeviceError::UserDeniedPairing => (
            "trust_denied",
            "Trust was declined on the iPhone. Unlock it and explicitly retry pairing.",
        ),
        IdeviceError::PasswordProtected => (
            "iphone_locked",
            "Unlock the iPhone and retry. Its saved record is retained.",
        ),
        IdeviceError::InvalidHostID => (
            "pairing_revoked",
            "The iPhone rejected the saved host identity. Record retained; explicit repair is required.",
        ),
        IdeviceError::PairingDialogResponsePending => (
            "awaiting_trust",
            "Waiting for Trust on the unlocked iPhone.",
        ),
        _ => (
            stage,
            "Device operation failed; sensitive protocol details are withheld. Saved identity/records are retained.",
        ),
    }
}

async fn bounded<T>(
    stage: &'static str,
    future: impl Future<Output = Result<T, IdeviceError>>,
) -> Result<T, Failure> {
    tokio::time::timeout(Duration::from_secs(10), future)
        .await
        .map_err(|_| {
            (
                stage,
                "Device step timed out. Unlock/reconnect and retry; pairing identity is retained.",
            )
        })?
        .map_err(|error| protocol_error(stage, error))
}

pub async fn authenticate(
    raw: &dyn IdeviceProvider,
    store: &mut dyn Store,
    allow_pair: bool,
    arm_reboot: bool,
    report: &mut PairingReport,
    mut progress: impl FnMut(&str, &str),
) -> Result<BTreeMap<String, String>, Failure> {
    progress(
        "device_identity",
        "Verifying the actual iPhone identity privately; identifiers will not be logged",
    );
    let mut lockdown = bounded("lockdown", LockdownClient::connect(raw)).await?;
    progress(
        "lockdown_connected",
        "Lockdown connected; no authenticated session started yet",
    );
    let identity = bounded(
        "device_identity",
        lockdown.get_value(Some("UniqueDeviceID"), None),
    )
    .await?;
    let udid = identity.as_string().filter(|s| (16..=64).contains(&s.len()) && s.bytes().all(|b| b.is_ascii_hexdigit() || b == b'-'))
        .ok_or(("device_identity", "The iPhone returned an invalid device identity; nothing was paired or opened from the store."))?.to_string();
    lockdown.idevice.set_udid(udid.clone());
    progress(
        "pairing_store",
        "Checking the encrypted app-private record for this iPhone",
    );
    let existing = store.load(&udid)?;
    let mut record = if let Some(mut bytes) = existing {
        let parsed = PairingFile::from_bytes(&bytes);
        bytes.fill(0);
        let record = parsed.map_err(|_| ("pairing_record_invalid", "Saved pairing record could not be parsed. It was retained; no automatic re-pairing."))?;
        if record.udid.as_deref() != Some(udid.as_str()) {
            return Err((
                "pairing_identity_mismatch",
                "Saved pairing record belongs to another device. No authenticated session started.",
            ));
        }
        report.record_saved = true;
        record
    } else {
        if !allow_pair {
            return Err((
                "pairing_record_missing",
                "No saved record for this iPhone. Use Pair iPhone once to request Trust.",
            ));
        }
        let (host_id, system_buid) = store.identity(&udid)?;
        progress(
            "awaiting_trust",
            "Unlock the iPhone, tap Trust This Computer and enter its passcode. Waiting up to 90 seconds",
        );
        // One prepared request/host identity is reused while iOS says Trust is pending.
        let mut paired = tokio::time::timeout(Duration::from_secs(90),
            lockdown.pair(host_id, system_buid, Some("Nuvio Z Android installer experiment"))).await
            .map_err(|_| ("trust_timeout", "Trust was not completed in 90 seconds. Retry explicitly; the same Android host identity is retained."))?
            .map_err(|error| protocol_error("pairing", error))?;
        paired.udid = Some(udid.clone());
        report.paired_now = true;
        let mut bytes = paired.clone().serialize().map_err(|_| {
            (
                "pairing_store",
                "Accepted record could not be serialized; signing/installation is disabled.",
            )
        })?;
        let saved = store.save(&udid, &bytes);
        bytes.fill(0);
        saved?;
        report.record_saved = true;
        progress(
            "pairing_saved",
            "Trust accepted; full Lockdown record durably saved in encrypted private storage",
        );
        paired
    };
    record.udid = Some(udid.clone());
    let paired = IdentifiedProvider {
        raw,
        udid: udid.clone(),
        record,
    };
    // Use a fresh channel after Pair, as on reconnection. Never silently pair when StartSession fails.
    drop(lockdown);
    let lockdown = bounded("lockdown", LockdownClient::connect(&paired)).await?;
    progress(
        "lockdown_tls",
        "Starting authenticated Lockdown and completing the TLS handshake",
    );
    let mut lockdown = bounded(
        "lockdown_tls",
        crate::paired_tls::start(lockdown, &paired.record),
    )
    .await?;
    report.tls_authenticated = true;
    let result = async {
        let mut info = BTreeMap::new();
        for key in crate::INFO_KEYS {
            let value = bounded("authenticated_info", lockdown.get_value(Some(key), None)).await?;
            let value = value.as_string().ok_or(("authenticated_info", "Unexpected authenticated device-info type"))?;
            info.insert(key.to_string(), value.to_string());
        }
        progress("authenticated_info", "Four allowlisted information fields read through the authenticated TLS session");
        // Read-only AMFI action 3; no reveal/enable/accept/reboot request is sent.
        report.developer_mode = bounded("developer_mode", crate::paired_tls::developer_mode(&mut lockdown, &paired, &paired.record)).await.ok();
        let pending = store.reboot(&udid, 0)?;
        if pending == 2 { return Err(("reboot_device_mismatch", "Another iPhone is awaiting reboot recovery. No pending recovery was cleared.")); }
        if arm_reboot {
            store.reboot(&udid, 1)?;
            report.reboot_pending = true;
            progress("waiting_for_reboot", "Pairing retained. Enable Developer Mode in iPhone Settings if needed, complete its reboot/confirmation, reconnect and use Saved pairing");
        } else if pending == 1 && report.developer_mode == Some(true) {
            store.reboot(&udid, 2)?;
            report.reboot_reconnect_verified = true;
            progress("reboot_reconnect_verified", "Expected iPhone authenticated with its saved record; Developer Mode is on. No new Trust request");
        } else { report.reboot_pending = pending == 1; }
        Ok(info)
    }.await;
    progress(
        "session_cleanup",
        "Stopping the authenticated session; retaining the saved pairing identity",
    );
    // Cleanup failure does not erase proof of authenticated reads; USB cancellation still drops the socket.
    let _ = tokio::time::timeout(Duration::from_secs(2), lockdown.stop_session()).await;
    result
}

/// One installed app the installer screens care about. Same shape as desktop's `device::AppInfo`.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppInfo {
    pub bundle_id: String,
    pub version: String,
}

#[derive(Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Apps {
    pub sidestore: Option<AppInfo>,
    pub local_dev_vpn: Option<AppInfo>,
    pub nuvio_stable: Option<AppInfo>,
    pub nuvio_debug: Option<AppInfo>,
}

/// What the shared installer's steps wait on (desktop's `DeviceSnapshot` fields of the same names).
#[derive(Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PhoneStatus {
    /// The saved record opened an authenticated session: Trust was tapped for this phone.
    pub trusted: bool,
    pub ios_version: Option<String>,
    pub developer_mode: Option<bool>,
    pub apps: Option<Apps>,
    /// Whether the installed Nuvio Z (Debug first) holds its own connection file.
    pub nuvio_pairing: Option<bool>,
}

async fn longer<T>(
    stage: &'static str,
    future: impl Future<Output = Result<T, IdeviceError>>,
) -> Result<T, Failure> {
    tokio::time::timeout(Duration::from_secs(20), future)
        .await
        .map_err(|_| (stage, "Device step timed out"))?
        .map_err(|error| protocol_error(stage, error))
}

/// Reads the phone through its saved record only. A phone without one is reported untrusted; this never pairs.
pub async fn status(
    raw: &dyn IdeviceProvider,
    store: &mut dyn Store,
    report: &mut PairingReport,
    progress: impl FnMut(&str, &str),
) -> Result<PhoneStatus, Failure> {
    let mut status = PhoneStatus::default();
    match authenticate(raw, store, false, false, report, progress).await {
        Ok(info) => {
            status.trusted = true;
            status.ios_version = info.get("ProductVersion").cloned();
            status.developer_mode = report.developer_mode;
        }
        Err(("pairing_record_missing", _)) => return Ok(status),
        Err(failure) => return Err(failure),
    }
    let paired = saved_provider(raw, store).await?;
    let list = longer("apps", async {
        let mut proxy = idevice::installation_proxy::InstallationProxyClient::connect(&paired).await?;
        proxy.get_apps(Some("User"), None).await
    })
    .await?;
    let mut apps = Apps::default();
    for (bundle, info) in &list {
        let field = |k: &str| info.as_dictionary().and_then(|d| d.get(k)).and_then(|v| v.as_string());
        let lower = bundle.to_lowercase();
        let slot = if lower.starts_with("com.sidestore.sidestore") || field("CFBundleDisplayName") == Some("SideStore") {
            &mut apps.sidestore
        } else if lower.starts_with("com.jkcoxson.localdevvpn") {
            &mut apps.local_dev_vpn
        } else if lower.starts_with("com.nuvio.app.z.debug") {
            &mut apps.nuvio_debug
        } else if lower == "com.nuvio.app.z" || lower.starts_with("com.nuvio.app.z.") {
            &mut apps.nuvio_stable
        } else {
            continue;
        };
        *slot = Some(AppInfo { bundle_id: bundle.clone(), version: field("CFBundleShortVersionString").unwrap_or("").into() });
    }
    if let Some(nuvio) = apps.nuvio_debug.as_ref().or(apps.nuvio_stable.as_ref()) {
        let bundle = nuvio.bundle_id.clone();
        status.nuvio_pairing = longer("nuvio_pairing", async {
            let ha = idevice::house_arrest::HouseArrestClient::connect(&paired).await?;
            let mut afc = ha.vend_documents(bundle).await?;
            Ok::<_, IdeviceError>(afc.list_dir("/Documents").await?.iter().any(|e| e == "NuvioZPairing.plist"))
        })
        .await
        .ok();
    }
    status.apps = Some(apps);
    Ok(status)
}

/// "It's not in the list": asks the phone to show Developer Mode in Settings, through the saved record.
pub async fn reveal_developer_mode(raw: &dyn IdeviceProvider, store: &mut dyn Store) -> Result<(), Failure> {
    let paired = saved_provider(raw, store).await?;
    let lockdown = bounded("lockdown", LockdownClient::connect(&paired)).await?;
    let mut lockdown = bounded("lockdown_tls", crate::paired_tls::start(lockdown, &paired.record)).await?;
    let result = bounded("developer_mode", crate::paired_tls::reveal_developer_mode(&mut lockdown, &paired, &paired.record)).await;
    let _ = tokio::time::timeout(Duration::from_secs(2), lockdown.stop_session()).await;
    result
}
