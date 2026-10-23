use crate::{ProbeReport, VERSION, control::Cancellation, read_device_info};
use idevice::{IdeviceService, lockdown::LockdownClient};
use jni::{
    JNIEnv,
    objects::{JClass, JObject, JString},
    sys::{jboolean, jint, jlong, jstring},
};
use rusbmux::{device::usb::UsbDevice, provider::RusbmuxProvider, usb_backend::AnyDeviceInfo};
use std::{
    io::{self, Write},
    os::fd::BorrowedFd,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};
use tokio::time::timeout;

static PROGRESS: Mutex<String> = Mutex::new(String::new());
static BUSY: AtomicBool = AtomicBool::new(false);
static CANCEL: Cancellation = Cancellation::new();
struct ProbeGuard;
impl Drop for ProbeGuard {
    fn drop(&mut self) {
        BUSY.store(false, Ordering::Release);
    }
}
const STEP_TIMEOUT: Duration = Duration::from_secs(8);

// Cancels mux loops even when the whole probe future is dropped by detach or its deadline.
struct MuxGuard(Arc<UsbDevice>);
impl Drop for MuxGuard {
    fn drop(&mut self) {
        self.0.core.canceler.cancel();
    }
}

#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(
        priority: i32,
        tag: *const std::ffi::c_char,
        text: *const std::ffi::c_char,
    ) -> i32;
}

fn log(message: &str) {
    if let Ok(text) = std::ffi::CString::new(message) {
        unsafe {
            __android_log_write(4, c"NuvioUsbProbe".as_ptr(), text.as_ptr());
        }
    }
}

struct LogWriter;
impl Write for LogWriter {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        log(&String::from_utf8_lossy(bytes));
        Ok(bytes.len())
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

fn init() {
    static INIT: OnceLock<()> = OnceLock::new();
    INIT.get_or_init(|| {
        std::panic::set_hook(Box::new(|panic| {
            let location = panic
                .location()
                .map(|l| {
                    format!(
                        "{}:{}",
                        l.file().rsplit(['/', '\\']).next().unwrap_or("native"),
                        l.line()
                    )
                })
                .unwrap_or_default();
            log(&format!(
                "Native panic at {location}; sensitive message withheld"
            ));
        }));
        // Vendor logs can include packet payloads even at ERROR. Emit only our safe stage events.
        let _ = tracing_subscriber::fmt()
            .with_ansi(false)
            .without_time()
            .with_max_level(tracing::level_filters::LevelFilter::OFF)
            .with_writer(|| LogWriter)
            .try_init();
        let _ = rustls::crypto::ring::default_provider().install_default();
        log(VERSION);
    });
}

fn event(report: &mut ProbeReport, stage: &str, message: impl Into<String>) {
    let message = message.into();
    notice(stage, &message);
    report.event(stage, message);
}
pub(crate) fn notice(stage: &str, message: &str) {
    log(&format!("{stage}: {message}"));
    if let Ok(mut status) = PROGRESS.lock() {
        *status = format!("{stage}: {message}");
    }
}

async fn probe(
    fd: i32,
    mode: i32,
    report: &mut ProbeReport,
    store: Option<&mut dyn crate::pairing::Store>,
    install: Option<crate::installer::Request>,
) -> Result<(), (&'static str, String)> {
    if mode == 2 {
        event(
            report,
            "synthetic_wait",
            "Automated cancellation check; no USB access",
        );
        std::future::pending::<()>().await;
    }
    if fd < 0 {
        return Err((
            "file_descriptor",
            "Android did not supply an open USB descriptor".into(),
        ));
    }
    event(
        report,
        "file_descriptor",
        "Duplicating Android's granted descriptor; no USB enumeration/reopen",
    );
    // Java owns the borrowed FD for the ENTIRE JNI call. nusb owns only this duplicate.
    let owned = unsafe { BorrowedFd::borrow_raw(fd) }
        .try_clone_to_owned()
        .map_err(|e| ("file_descriptor", e.to_string()))?;
    let usb = timeout(STEP_TIMEOUT, nusb::Device::from_fd(owned))
        .await
        .map_err(|_| ("file_descriptor", "nusb open timed out".into()))?
        .map_err(|e| ("file_descriptor", e.to_string()))?;
    let desc = usb.device_descriptor();
    if desc.vendor_id() != 0x05ac {
        return Err((
            "descriptors",
            format!("Expected Apple VID 05ac, got {:04x}", desc.vendor_id()),
        ));
    }
    event(
        report,
        "descriptors",
        format!(
            "Apple USB {:04x}:{:04x}; active configuration {:?}",
            desc.vendor_id(),
            desc.product_id(),
            usb.active_configuration()
                .ok()
                .map(|c| c.configuration_value())
        ),
    );
    for config in usb.configurations() {
        for intf in config.interface_alt_settings() {
            event(
                report,
                "descriptors",
                format!(
                    "config={} interface={} alt={} class={:02x}/{:02x}/{:02x}",
                    config.configuration_value(),
                    intf.interface_number(),
                    intf.alternate_setting(),
                    intf.class(),
                    intf.subclass(),
                    intf.protocol()
                ),
            );
        }
    }
    let mut transport_stage = "usb_open";
    let device = timeout(
        STEP_TIMEOUT,
        UsbDevice::new_with_progress(AnyDeviceInfo::AndroidOpened(usb), 1, |stage| {
            transport_stage = stage;
            let message = match stage {
                "usb_open" => "Adopting the permission-granted nusb handle",
                "usb_claim" => {
                    "Selecting USB configuration and claiming the mux interface/endpoints"
                }
                _ => "Exchanging mux version/setup packets",
            };
            event(report, stage, message);
        }),
    )
    .await
    .map_err(|_| {
        (
            transport_stage,
            "USB transport step timed out; check cable, unlock iPhone and Galaxy USB host role"
                .into(),
        )
    })?
    .map_err(|e| (transport_stage, e.to_string()))?;
    let _mux_guard = MuxGuard(device.clone());
    report.mux_connected = true;
    event(report, "usb_mux", "Mux version/setup exchange succeeded");
    // Do not use watch_usb_daemon or preflight: those can pair automatically.
    let provider = RusbmuxProvider::new(device.clone(), "nuvio-usb-probe-phase1".into());
    let result = async {
        if mode >= 3 {
            let store = store.ok_or((
                "pairing_store",
                "Private Android store unavailable".to_string(),
            ))?;
            let mut pairing = crate::pairing::PairingReport::default();
            // 7: what the installer screens wait on. 8: reveal Developer Mode in Settings. Neither pairs.
            if mode == 7 {
                let status = crate::pairing::status(&provider, store, &mut pairing, |stage, message| {
                    if stage == "lockdown_connected" {
                        report.lockdown_connected = true;
                    }
                    event(report, stage, message);
                })
                .await;
                report.pairing = Some(pairing);
                report.status = Some(status.map_err(|(stage, message)| (stage, message.to_string()))?);
                return Ok(());
            }
            if mode == 8 {
                return crate::pairing::reveal_developer_mode(&provider, store)
                    .await
                    .map_err(|(stage, message)| (stage, message.to_string()));
            }
            let result = crate::pairing::authenticate(
                &provider,
                store,
                mode == 3 || mode == 6,
                mode == 5,
                &mut pairing,
                |stage, message| {
                    if stage == "lockdown_connected" {
                        report.lockdown_connected = true;
                    }
                    event(report, stage, message);
                },
            )
            .await;
            report.pairing = Some(pairing);
            report.device_info = result.map_err(|(stage, message)| (stage, message.to_string()))?;
            if mode == 6 {
                let request = install.ok_or((
                    "install_input",
                    "Enter Apple credentials directly in the app".to_string(),
                ))?;
                let mut installed = crate::installer::InstallReport::default();
                let result =
                    crate::installer::perform(&provider, store, request, &mut installed).await;
                if let Err((_, message)) = &result {
                    installed.error_kind = Some(crate::installer::error_kind(message));
                }
                if let Some(pairing) = &mut report.pairing {
                    pairing.self_refresh_ready = installed.pairing_handoff
                        && installed.remote_pairing_handoff
                        && installed.signing_key_handoff;
                }
                report.installation = Some(installed);
                result.map_err(|(stage, message)| (stage, message.to_string()))?;
            }
            return Ok(());
        }
        if mode == 1 {
            event(
                report,
                "recovery_hold",
                "Mux connected. UNPLUG NOW: holding for 10 seconds before Lockdown (test mode)",
            );
            tokio::time::sleep(Duration::from_secs(10)).await;
        }
        event(
            report,
            "lockdown",
            "Connecting to port 62078 without pairing or StartSession",
        );
        let mut lockdown = timeout(STEP_TIMEOUT, LockdownClient::connect(&provider))
            .await
            .map_err(|_| ("lockdown", "Port connection timed out".into()))?
            .map_err(|e| ("lockdown", e.to_string()))?;
        report.lockdown_connected = true;
        report.device_info = read_device_info(&mut lockdown, |key| {
            event(
                report,
                "device_info",
                format!("Reading {key} (unpaired GetValue)"),
            );
        })
        .await
        .map_err(|e| ("device_info", e))?;
        Ok(())
    }
    .await;
    event(
        report,
        "cleanup",
        "Stopping mux tasks and releasing the duplicated descriptor",
    );
    device.core.canceler.cancel();
    let _ = timeout(Duration::from_secs(2), device.shutdown()).await;
    result
}

fn run(
    fd: i32,
    attempt: u64,
    mode: i32,
    store: Option<&mut dyn crate::pairing::Store>,
    install: Option<crate::installer::Request>,
) -> String {
    init();
    let mut report = ProbeReport::default();
    if BUSY
        .compare_exchange(false, true, Ordering::Acquire, Ordering::Relaxed)
        .is_err()
    {
        report.fail("busy", "Another probe is still running");
        return serde_json::to_string_pretty(&report).unwrap();
    }
    let _busy = ProbeGuard;
    if let Ok(mut progress) = PROGRESS.lock() {
        *progress = "Starting new probe".into();
    }
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build();
    match runtime {
        Ok(runtime) => {
            // Construct the timer inside the runtime, including negative-FD self-tests.
            let result = runtime.block_on(async {
                tokio::select! {
                    biased;
                    _ = CANCEL.wait(attempt) => Err("probe_cancelled"),
                    result = timeout(Duration::from_secs(if mode == 6 { 1200 } else if mode == 7 || mode == 8 { 60 } else if mode >= 3 { 180 } else { 45 }), probe(fd, mode, &mut report, store, install)) =>
                        result.map_err(|_| "deadline"),
                }
            });
            match result {
                Ok(Ok(())) => {
                    report.ok = true;
                    event(
                        &mut report,
                        "complete",
                        if mode == 6 {
                            "Signed Nuvio Z installed with self-refresh handoff"
                        } else if mode == 7 || mode == 8 {
                            "Phone status read through the saved record"
                        } else if mode >= 3 {
                            "Authenticated information read succeeded; no Apple authentication, signing or installation"
                        } else {
                            "Device information read successfully; no pairing performed"
                        },
                    );
                }
                Ok(Err((stage, error))) => report.fail(stage, error),
                Err("probe_cancelled") => report.fail(
                    "probe_cancelled",
                    "Attempt cancelled; releasing mux tasks and descriptor",
                ),
                Err(_) => report.fail(
                    "deadline",
                    "Total operation deadline reached; retry with the retained identity",
                ),
            }
            // Drops spawned tasks, including any mux loops canceled during a timeout.
            runtime.shutdown_timeout(Duration::from_secs(2));
        }
        Err(error) => report.fail("runtime", error),
    }
    let result = serde_json::to_string_pretty(&report).unwrap();
    if let Some(blocker) = &report.blocker {
        log(blocker);
    }
    result
}

fn string(env: &JNIEnv, text: String) -> jstring {
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nuvio_usbprobe_NativeUsbProbe_version(
    env: JNIEnv,
    _: JClass,
) -> jstring {
    string(&env, VERSION.into())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nuvio_usbprobe_NativeUsbProbe_progress(
    env: JNIEnv,
    _: JClass,
) -> jstring {
    string(&env, PROGRESS.lock().map(|s| s.clone()).unwrap_or_default())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nuvio_usbprobe_NativeUsbProbe_execute<'local>(
    mut env: JNIEnv<'local>,
    _: JClass,
    fd: jint,
    attempt: jlong,
    mode: jint,
    store: JObject<'local>,
    request: JObject<'local>,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let install = if request.is_null() {
            None
        } else {
            match crate::installer::Request::from_java(&mut env, request) {
                Ok(request) => Some(request),
                Err((stage, message)) => {
                    let _ = env.exception_clear();
                    let mut report = ProbeReport::default();
                    report.fail(stage, message);
                    return serde_json::to_string_pretty(&report).unwrap();
                }
            }
        };
        if store.is_null() {
            run(fd, attempt as u64, mode, None, install)
        } else {
            let mut bridge = crate::android_store::AndroidStore {
                env: &mut env,
                object: store,
            };
            run(fd, attempt as u64, mode, Some(&mut bridge), install)
        }
    }))
    .unwrap_or_else(|_| {
        let mut report = ProbeReport::default();
        report.fail(
            "native_panic",
                if mode == 6 { "Native operation failed; sensitive details withheld. Installation may have started; inspect the iPhone before retrying" }
                else { "Native operation failed; sensitive details withheld" },
        );
        serde_json::to_string_pretty(&report).unwrap()
    });
    string(&env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nuvio_usbprobe_NativeUsbProbe_cancel(
    _: JNIEnv,
    _: JClass,
    attempt: jlong,
) {
    if attempt > 0 {
        CANCEL.cancel(attempt as u64);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nuvio_usbprobe_NativeUsbProbe_answerChallenge(
    mut env: JNIEnv,
    _: JClass,
    input: JString,
) -> jboolean {
    use zeroize::Zeroize;
    let mut text: String = match env.get_string(&input) {
        Ok(text) => text.into(),
        Err(_) => return 0,
    };
    let accepted = crate::installer::answer(&text);
    text.zeroize();
    accepted as jboolean
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nuvio_usbprobe_NativeUsbProbe_certificateChoices(
    env: JNIEnv,
    _: JClass,
) -> jstring {
    string(&env, crate::installer::certificate_choices())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nuvio_usbprobe_NativeUsbProbe_networkCheck(
    env: JNIEnv,
    _: JClass,
) -> jstring {
    init();
    let outcome = std::panic::catch_unwind(|| {
        let runtime = tokio::runtime::Runtime::new().map_err(|_| "runtime")?;
        let result = runtime.block_on(async {
            tokio::time::timeout(Duration::from_secs(30), async {
                use isideload::anisette::AnisetteProvider;
                let client = reqwest::Client::builder().timeout(Duration::from_secs(15)).build().map_err(|_| "https_init")?;
                client.get("https://raw.githubusercontent.com/Zokaper/nuvio-z/main/distribution/ios-installer/nuvio.json")
                    .send().await.map_err(|_| "https_request")?.error_for_status().map_err(|_| "https_status")?;
                let provider = isideload::anisette::remote_v3::RemoteV3AnisetteProvider::default().map_err(|_| "anisette_init")?
                    .set_url("https://new.ani.sidestore.io");
                let info = provider.get_client_info().await.map_err(|_| "anisette_request")?;
                // Exercise the exact Apple client builder and public URL lookup used by login,
                // without an account, password, anisette provisioning or an authentication call.
                isideload::auth::grandslam::GrandSlam::build_reqwest_client(false, None)
                    .map_err(|_| "apple_http_builder")?;
                isideload::auth::grandslam::GrandSlam::new(info, false, None).await
                    .map_err(|e| {
                        // Only fixed classifications leave this no-account diagnostic.
                        let detail = format!("{e:?}").to_lowercase();
                        if detail.contains("extra certs") { "apple_extra_roots" }
                        else if detail.contains("unknownissuer") || detail.contains("unknown issuer") { "apple_tls_unknown_issuer" }
                        else if detail.contains("certificate") { "apple_tls_certificate" }
                        else if detail.contains("parse url bag") { "apple_lookup_plist" }
                        else if detail.contains("missing 'urls'") { "apple_lookup_missing_urls" }
                        else if detail.contains("invalid http header") { "apple_lookup_header" }
                        else if detail.contains("failed to fetch url bag") { "apple_lookup_request" }
                        else { "apple_client_setup" }
                    })?;
                Ok::<_, &'static str>(())
            }).await.map_err(|_| "network_timeout")?
        });
        runtime.shutdown_timeout(Duration::from_secs(2)); result
    }).unwrap_or(Err("network_panic"));
    string(
        &env,
        outcome
            .map(|_| "ok")
            .unwrap_or_else(|error| error)
            .to_string(),
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nuvio_usbprobe_NativeUsbProbe_testStoreBridge<'local>(
    mut env: JNIEnv<'local>,
    _: JClass,
    object: JObject<'local>,
) -> jstring {
    use crate::pairing::Store;
    init();
    let result = (|| {
        let mut store = crate::android_store::AndroidStore {
            env: &mut env,
            object,
        };
        let udid = "00000000-0000000000000001";
        let identity = store.identity(udid)?;
        if identity.0.is_empty() || identity.1.is_empty() {
            return Err(("pairing_store", "Invalid identity"));
        }
        store.save(udid, b"synthetic-pair-record-not-a-real-credential")?;
        let mut loaded = store
            .load(udid)?
            .ok_or(("pairing_store", "Synthetic record missing"))?;
        let matches = loaded == b"synthetic-pair-record-not-a-real-credential";
        loaded.fill(0);
        if !matches {
            return Err(("pairing_store", "Synthetic record mismatch"));
        }
        store.reboot(udid, 1)?;
        store.reboot(udid, 2)?;
        Ok::<_, crate::pairing::Failure>(())
    })();
    string(
        &env,
        match result {
            Ok(()) => "ok".into(),
            Err((stage, _)) => stage.into(),
        },
    )
}
