use crate::{ProbeReport, VERSION, control::Cancellation, read_device_info};
use idevice::{IdeviceService, lockdown::LockdownClient};
use jni::{
    JNIEnv,
    objects::{JClass, JObject},
    sys::{jint, jlong, jstring},
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
        std::panic::set_hook(Box::new(|_| {
            log("Native panic; sensitive details withheld")
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
    log(&format!("{stage}: {message}"));
    if let Ok(mut status) = PROGRESS.lock() {
        *status = format!("{stage}: {message}");
    }
    report.event(stage, message);
}

async fn probe(
    fd: i32,
    mode: i32,
    report: &mut ProbeReport,
    store: Option<&mut dyn crate::pairing::Store>,
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
            let result = crate::pairing::authenticate(
                &provider,
                store,
                mode == 3,
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

fn run(fd: i32, attempt: u64, mode: i32, store: Option<&mut dyn crate::pairing::Store>) -> String {
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
                    result = timeout(Duration::from_secs(if mode >= 3 { 180 } else { 45 }), probe(fd, mode, &mut report, store)) =>
                        result.map_err(|_| "deadline"),
                }
            });
            match result {
                Ok(Ok(())) => {
                    report.ok = true;
                    event(
                        &mut report,
                        "complete",
                        if mode >= 3 {
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
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if store.is_null() {
            run(fd, attempt as u64, mode, None)
        } else {
            let mut bridge = crate::android_store::AndroidStore {
                env: &mut env,
                object: store,
            };
            run(fd, attempt as u64, mode, Some(&mut bridge))
        }
    }))
    .unwrap_or_else(|_| {
        let mut report = ProbeReport::default();
        report.fail(
            "native_panic",
            "Sensitive details withheld; no signing or installation performed",
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
