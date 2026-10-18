//! Stage 3: Apple's iPhone software without the iTunes installer (Windows).
//!
//! Verified on a PC reset to "never had Apple software" (2026-10-17): `winget` installs Apple Devices from the
//! Microsoft Store in about 30 seconds with no admin prompt and no window. Its iPhone service
//! (AppleMobileDeviceLauncher, which serves usbmuxd on 127.0.0.1:27015) is a package startup task, so after an
//! install nothing listens until the next sign-in; and the WindowsApps folder refuses a direct start. Starting it
//! inside its package (`Invoke-CommandInDesktopPackage`) works, still with no window. Where winget is missing or
//! refuses, the person gets the Store page and clicks Get.
//!
//! The iPhone's own USB driver is a separate piece (found 2026-10-18 with a real iPhone on that reset PC): Apple
//! Devices does not carry it, so a never-used PC shows the phone only as a camera (WPD) and usbmuxd lists nothing.
//! Windows Update offers "Apple, Inc. - USBDevice" (and Apple's network driver) for the phone's hardware ids, found in
//! about 15 s; installing them needs admin, so `install_usb_driver` costs one permission prompt (~30 s in all).
//!
//! macOS has usbmuxd built in, so everything here is Windows-only and reports `Ready` elsewhere.
use serde::Serialize;
use std::time::Duration;

/// Apple Devices in the Microsoft Store.
pub const STORE_PRODUCT_ID: &str = "9NP83LWLPZ9K";
pub const STORE_PAGE: &str = "ms-windows-store://pdp/?ProductId=9NP83LWLPZ9K";
#[cfg(windows)]
const PACKAGE: &str = "AppleInc.AppleDevices";

#[derive(Debug, Clone, Copy, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub enum AppleSoftware {
    /// usbmuxd answers: nothing to do.
    Ready,
    /// Apple Devices is installed but its iPhone service isn't running.
    NotRunning,
    /// Neither Apple Devices nor anything else answers on 27015.
    Missing,
}

async fn usbmuxd_answers() -> bool {
    matches!(
        tokio::time::timeout(Duration::from_secs(2), tokio::net::TcpStream::connect(("127.0.0.1", 27015))).await,
        Ok(Ok(_))
    )
}

#[cfg(windows)]
fn hidden(program: &str) -> std::process::Command {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    let mut command = std::process::Command::new(program);
    command.creation_flags(CREATE_NO_WINDOW);
    command
}

#[cfg(windows)]
async fn powershell(script: String) -> Result<String, String> {
    tokio::task::spawn_blocking(move || {
        let out = hidden("powershell.exe").args(["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", &script])
            .output().map_err(|e| format!("powershell: {e}"))?;
        let text = String::from_utf8_lossy(&out.stdout).trim().to_string();
        if out.status.success() {
            Ok(text)
        } else {
            Err(format!("{} {}", text, String::from_utf8_lossy(&out.stderr).trim()).trim().to_string())
        }
    }).await.map_err(|e| format!("{e}"))?
}

#[cfg(windows)]
async fn apple_devices_installed() -> bool {
    powershell(format!("(Get-AppxPackage -Name {PACKAGE}).InstallLocation")).await.is_ok_and(|s| !s.is_empty())
}

pub async fn state() -> AppleSoftware {
    if usbmuxd_answers().await {
        return AppleSoftware::Ready;
    }
    #[cfg(windows)]
    {
        if apple_devices_installed().await { AppleSoftware::NotRunning } else { AppleSoftware::Missing }
    }
    #[cfg(not(windows))]
    AppleSoftware::Ready
}

/// Starts Apple Devices' iPhone service inside its package (no window), then waits for usbmuxd.
#[cfg(windows)]
pub async fn start() -> Result<(), String> {
    powershell(format!(
        "$p = Get-AppxPackage -Name {PACKAGE}; if (-not $p) {{ throw 'Apple Devices is not installed' }}; \
         Invoke-CommandInDesktopPackage -PackageFamilyName $p.PackageFamilyName -AppId App \
         -Command (Join-Path $p.InstallLocation 'AppleMobileDeviceLauncher.exe') -PreventBreakaway"
    )).await?;
    for _ in 0..20 {
        if usbmuxd_answers().await {
            return Ok(());
        }
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
    Err("Apple Devices started but its iPhone service isn't answering".into())
}

/// Installs Apple Devices from the Microsoft Store with winget (no admin, no window), then starts its service.
#[cfg(windows)]
pub async fn install() -> Result<(), String> {
    let result = tokio::task::spawn_blocking(|| {
        hidden("winget.exe").args([
            "install", "--id", STORE_PRODUCT_ID, "--source", "msstore",
            "--accept-package-agreements", "--accept-source-agreements", "--disable-interactivity",
        ]).output()
    }).await.map_err(|e| format!("{e}"))?;
    // winget reports "already installed" and some partial failures with non-zero codes, so the package itself decides.
    let detail = match &result {
        Ok(out) => format!("winget exit {}", out.status.code().unwrap_or(-1)),
        Err(e) => format!("winget: {e}"),
    };
    if !apple_devices_installed().await {
        return Err(format!("couldn't install Apple Devices ({detail})"));
    }
    start().await
}

/// Whatever it takes to get usbmuxd answering: nothing, a start, or an install and a start.
pub async fn ensure() -> Result<AppleSoftware, String> {
    #[cfg(windows)]
    match state().await {
        AppleSoftware::Ready => {}
        AppleSoftware::NotRunning => start().await?,
        AppleSoftware::Missing => install().await?,
    }
    Ok(AppleSoftware::Ready)
}

/// An iPhone is plugged in but has no Apple USB driver: Windows sees only its camera side (Windows-only; false elsewhere).
pub async fn iphone_needs_driver() -> bool {
    #[cfg(windows)]
    {
        powershell(
            "$d = @(Get-PnpDevice -PresentOnly | Where-Object { $_.InstanceId -match 'VID_05AC' }); \
             if ($d.Count -gt 0 -and -not ($d | Where-Object { $_.FriendlyName -match 'Apple Mobile Device USB' })) { 'yes' } else { 'no' }"
                .to_string(),
        ).await.is_ok_and(|s| s == "yes")
    }
    #[cfg(not(windows))]
    false
}

/// Installs the Apple drivers Windows Update offers for the plugged-in iPhone (only Apple's, matched by the phone's
/// USB vendor id). Elevated: Windows asks for permission once.
#[cfg(windows)]
const USB_DRIVER_SCRIPT: &str = r#"
$session = New-Object -ComObject Microsoft.Update.Session
$searcher = $session.CreateUpdateSearcher()
$searcher.ServerSelection = 2
$apple = New-Object -ComObject Microsoft.Update.UpdateColl
foreach ($u in $searcher.Search("IsInstalled=0 and Type='Driver'").Updates) {
    if ($u.DriverManufacturer -match '^Apple' -and $u.DriverHardwareID -match 'vid_05ac') {
        if (-not $u.EulaAccepted) { $u.AcceptEula() }
        [void]$apple.Add($u)
    }
}
if ($apple.Count -eq 0) { exit 3 }
$downloader = $session.CreateUpdateDownloader(); $downloader.Updates = $apple; [void]$downloader.Download()
$installer = $session.CreateUpdateInstaller(); $installer.Updates = $apple
$result = $installer.Install()
if ($result.ResultCode -eq 2 -or $result.ResultCode -eq 3) { exit 0 } else { exit 4 }
"#;

#[cfg(windows)]
pub async fn install_usb_driver() -> Result<(), String> {
    let script = std::env::temp_dir().join("nuvioz-apple-usb-driver.ps1");
    std::fs::write(&script, USB_DRIVER_SCRIPT).map_err(|e| format!("{e}"))?;
    let outer = format!(
        "try {{ $p = Start-Process powershell.exe -Verb RunAs -WindowStyle Hidden -Wait -PassThru \
         -ArgumentList '-NoProfile -ExecutionPolicy Bypass -File \"{}\"'; exit $p.ExitCode }} catch {{ exit 5 }}",
        script.display()
    );
    let code = tokio::task::spawn_blocking(move || {
        hidden("powershell.exe").args(["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", &outer])
            .status().map(|s| s.code().unwrap_or(-1))
    }).await.map_err(|e| format!("{e}"))?.map_err(|e| format!("powershell: {e}"))?;
    let _ = std::fs::remove_file(&script);
    match code {
        0 => Ok(()),
        3 => Err("Windows Update has no Apple driver for this iPhone right now".into()),
        5 => Err("Windows' permission prompt was declined".into()),
        c => Err(format!("installing the iPhone driver failed ({c})")),
    }
}

#[cfg(not(windows))]
pub async fn install_usb_driver() -> Result<(), String> {
    Ok(())
}

/// The fallback: the Store page, where the person clicks Get.
pub fn open_store_page() -> Result<(), String> {
    #[cfg(windows)]
    {
        hidden("explorer.exe").arg(STORE_PAGE).spawn().map(|_| ()).map_err(|e| format!("{e}"))
    }
    #[cfg(not(windows))]
    Ok(())
}
