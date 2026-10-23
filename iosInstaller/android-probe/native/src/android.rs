use crate::{ProbeReport, VERSION, read_device_info};
use idevice::{IdeviceService, lockdown::LockdownClient};
use jni::{
    JNIEnv,
    objects::JClass,
    sys::{jint, jstring},
};
use rusbmux::{device::usb::UsbDevice, provider::RusbmuxProvider, usb_backend::AnyDeviceInfo};
use std::{
    io::{self, Write},
    os::fd::BorrowedFd,
    sync::{
        Mutex, OnceLock,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};
use tokio::time::timeout;

static PROGRESS: Mutex<String> = Mutex::new(String::new());
static BUSY: AtomicBool = AtomicBool::new(false);
struct ProbeGuard;
impl Drop for ProbeGuard {
    fn drop(&mut self) {
        BUSY.store(false, Ordering::Release);
    }
}
const STEP_TIMEOUT: Duration = Duration::from_secs(8);

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
        std::panic::set_hook(Box::new(|panic| log(&format!("native panic: {panic}"))));
        // Keep native diagnostics in logcat; never enable packet/payload trace logging.
        let _ = tracing_subscriber::fmt()
            .with_ansi(false)
            .without_time()
            .with_max_level(tracing::Level::DEBUG)
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

async fn probe(fd: i32, report: &mut ProbeReport) -> Result<(), (&'static str, String)> {
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
    report.mux_connected = true;
    event(report, "usb_mux", "Mux version/setup exchange succeeded");
    // Do not use watch_usb_daemon or preflight: those can pair automatically.
    let provider = RusbmuxProvider::new(device.clone(), "nuvio-usb-probe-phase1".into());
    let result = async {
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

fn run(fd: i32) -> String {
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
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build();
    match runtime {
        Ok(runtime) => {
            // Construct the timer inside the runtime, including negative-FD self-tests.
            let result = runtime
                .block_on(async { timeout(Duration::from_secs(45), probe(fd, &mut report)).await });
            match result {
                Ok(Ok(())) => {
                    report.ok = true;
                    event(
                        &mut report,
                        "complete",
                        "Device information read successfully; no pairing performed",
                    );
                }
                Ok(Err((stage, error))) => report.fail(stage, error),
                Err(_) => report.fail("deadline", "45s total probe deadline reached"),
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
pub extern "system" fn Java_com_nuvio_usbprobe_NativeUsbProbe_probe(
    env: JNIEnv,
    _: JClass,
    fd: jint,
) -> jstring {
    let result = std::panic::catch_unwind(|| run(fd)).unwrap_or_else(|panic| {
        let mut report = ProbeReport::default();
        let detail = panic
            .downcast_ref::<String>()
            .map(String::as_str)
            .or_else(|| panic.downcast_ref::<&str>().copied())
            .unwrap_or("Rust panic; see logcat");
        report.fail("native_panic", detail);
        serde_json::to_string_pretty(&report).unwrap()
    });
    string(&env, result)
}
