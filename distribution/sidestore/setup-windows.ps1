#Requires -Version 5.1
<#
.SYNOPSIS
    Guided Nuvio Z iOS Bootstrap for Windows.
    Prepares prerequisites, guides official SideStore installation via iloader,
    and configures the Nuvio Z SideStore source.

.DESCRIPTION
    This script provides a friendly, step-by-step terminal experience to guide
    a user through setting up SideStore and installing Nuvio Z on their iPhone.
    It verifies system requirements, checks Apple USB drivers, assists with
    connecting and trusting the device, downloads the official iloader tool,
    guides the required iOS approvals, and provides deep links and QR codes
    to add the Nuvio Z source.

.PARAMETER SourceUrl
    Custom URL for the SideStore source. Defaults to the official Nuvio Z source.

.PARAMETER SkipPrerequisites
    Skip driver and system requirement checks (advanced users only).

.PARAMETER SkipDeviceWait
    Skip waiting for a connected iPhone (dry-run/testing mode).
#>

[CmdletBinding()]
param(
    [string]$SourceUrl = "https://raw.githubusercontent.com/Zokaper/nuvio-z/main/distribution/sidestore/source.json",
    [string]$IloaderVersion = "latest",
    [switch]$SkipPrerequisites,
    [switch]$SkipAppleDriverCheck,
    [switch]$SkipDeviceWait,
    [switch]$DeveloperMode,
    [switch]$DiagnosticOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# -------------------------------------------------------------------------
# Console and Color Initialization
# -------------------------------------------------------------------------
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
} catch {}

$script:CanColor = $Host.UI.SupportsVirtualTerminal -or ($env:TERM -ne $null) -or ($PSVersionTable.PSVersion.Major -ge 7)

# Glyphs
$script:GlyphCheck   = [char]0x2713  # ✓
$script:GlyphArrow   = [char]0x2192  # →
$script:GlyphAlert   = "!"           # !
$script:GlyphCross   = [char]0x2717  # ✗

function Write-Styled {
    param(
        [string]$Text,
        [ConsoleColor]$ForegroundColor = [ConsoleColor]::White,
        [switch]$NoNewline
    )
    if ($script:CanColor) {
        if ($NoNewline) {
            Write-Host $Text -ForegroundColor $ForegroundColor -NoNewline
        } else {
            Write-Host $Text -ForegroundColor $ForegroundColor
        }
    } else {
        if ($NoNewline) {
            Write-Host $Text -NoNewline
        } else {
            Write-Host $Text
        }
    }
}

function Write-Banner {
    Write-Host ""
    Write-Styled "===================================================================" Cyan
    Write-Styled "                       Nuvio Z - iOS Setup                         " Cyan
    Write-Styled "       Guided SideStore Sideloading & Source Configuration         " Cyan
    Write-Styled "===================================================================" Cyan
    Write-Host ""
}

function Write-StepHeader {
    param([int]$Step, [int]$Total, [string]$Title)
    Write-Host ""
    Write-Styled "[$Step/$Total] $Title" Yellow
    Write-Host ""
}

function Write-Success {
    param([string]$Message)
    Write-Styled "  $($script:GlyphCheck) $Message" Green
}

function Write-Working {
    param([string]$Message)
    Write-Styled "  $($script:GlyphArrow) $Message" Cyan
}

function Write-Alert {
    param([string]$Message)
    Write-Styled "  $($script:GlyphAlert) $Message" Yellow
}

function Write-Failure {
    param([string]$Message)
    Write-Styled "  $($script:GlyphCross) $Message" Red
}

function Write-Info {
    param([string]$Message)
    Write-Styled "    $Message" Gray
}

function Write-ActionBox {
    param(
        [string]$Title,
        [string[]]$Lines,
        [string]$Footer = "Press ENTER when complete to continue..."
    )
    Write-Host ""
    Write-Styled "-------------------------------------------------------------------" DarkYellow
    Write-Styled "ACTION NEEDED: $Title" Yellow
    Write-Styled "-------------------------------------------------------------------" DarkYellow
    foreach ($line in $Lines) {
        if ($line.StartsWith("  ")) {
            Write-Styled $line White
        } elseif ($line -eq "") {
            Write-Host ""
        } else {
            Write-Styled $line Gray
        }
    }
    Write-Styled "-------------------------------------------------------------------" DarkYellow
    if ($Footer) {
        Write-Host ""
        Write-Styled "$Footer" Cyan -NoNewline
        $null = Read-Host
        Write-Host ""
    }
}

function Test-IsAdmin {
    $currentIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]$currentIdentity
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Test-InternetConnection {
    try {
        $client = [System.Net.HttpWebRequest]::Create("https://github.com")
        $client.Timeout = 5000
        $client.Method = "HEAD"
        $response = $client.GetResponse()
        $response.Close()
        return $true
    } catch {
        return $false
    }
}

# -------------------------------------------------------------------------
# Step 1: System & Compatibility Diagnostics
# -------------------------------------------------------------------------
function Test-SystemEnvironment {
    Write-StepHeader 1 7 "Checking your computer"

    # Architecture check
    if (-not [System.Environment]::Is64BitOperatingSystem) {
        Write-Failure "32-bit Windows detected."
        Write-Info "SideStore and Apple device components require a 64-bit (x64) Windows OS."
        throw "Unsupported operating system architecture."
    }
    Write-Success "64-bit Windows detected"

    # Windows Version
    $os = Get-CimInstance Win32_OperatingSystem
    $osName = $os.Caption
    $buildNumber = [int]$os.BuildNumber
    Write-Success "$osName (Build $buildNumber) detected"

    # Windows 10 ARM64 check
    $arch = $env:PROCESSOR_ARCHITECTURE
    if ($arch -eq "ARM64" -and $buildNumber -lt 22000) {
        Write-Failure "Windows 10 on ARM64 is not supported by Apple USB drivers."
        Write-Info "Windows 11 on ARM64 is required for x64 emulation."
        throw "Unsupported Windows on ARM version."
    }

    # Internet check
    Write-Working "Checking internet connection..."
    if (Test-InternetConnection) {
        Write-Success "Internet connection available"
    } else {
        Write-Alert "Internet connection check failed."
        Write-Info "An active internet connection is required to download tools and add sources."
        Write-ActionBox "Check Internet Connection" @(
            "Please ensure your computer is connected to the internet (Wi-Fi or Ethernet).",
            "Once connected, press ENTER to re-check."
        )
        if (-not (Test-InternetConnection)) {
            Write-Failure "Unable to reach GitHub. Please check your network and try again."
            throw "No internet connection."
        }
        Write-Success "Internet connection verified"
    }
}

# -------------------------------------------------------------------------
# Step 2: Apple Device Drivers & Services
# -------------------------------------------------------------------------
function Get-AppleServiceStatus {
    $service = Get-Service -Name "Apple Mobile Device Service" -ErrorAction SilentlyContinue
    if ($null -ne $service) {
        return $service.Status
    }
    return $null
}

function Test-AppleRegistryPresence {
    $keys = @(
        "HKLM:\SOFTWARE\Apple Inc.\Apple Mobile Device Support",
        "HKLM:\SOFTWARE\WOW6432Node\Apple Inc.\Apple Mobile Device Support",
        "HKLM:\SOFTWARE\Apple Inc.\iTunes"
    )
    foreach ($key in $keys) {
        if (Test-Path $key) { return $true }
    }
    return $false
}

function Test-AppleStorePackage {
    try {
        $pkgs = @(Get-AppxPackage -ErrorAction SilentlyContinue | Where-Object {
            $_.Name -match "AppleInc\.iTunes" -or $_.Name -match "AppleInc\.AppleDevices"
        })
        return ($pkgs.Count -gt 0)
    } catch {
        return $false
    }
}

function Install-AppleDeviceDrivers {
    Write-ActionBox "Install Apple Device Support" @(
        "Windows requires Apple USB communication drivers so your computer",
        "can detect and communicate with your iPhone.",
        "",
        "Choose an installation method:",
        "  [1] Download official 64-bit Apple iTunes installer (Recommended)",
        "  [2] Install iTunes using Windows Package Manager (winget)",
        "  [3] Open Microsoft Store for Apple Devices",
        "  [4] I have already installed it / Check again",
        ""
    ) -Footer "Enter choice [1-4] and press ENTER: "

    $choice = Read-Host
    switch ($choice) {
        "2" {
            Write-Working "Installing iTunes via winget..."
            try {
                Start-Process -FilePath "winget" -ArgumentList "install --id Apple.iTunes -e --source winget --accept-package-agreements --accept-source-agreements" -Wait
            } catch {
                Write-Alert "winget failed or is not configured. Falling back to direct download."
                $choice = "1"
            }
        }
        "3" {
            Write-Working "Opening Microsoft Store for Apple Devices..."
            Start-Process "ms-windows-store://pdp/?productid=9np83lwlpz9k"
            Write-ActionBox "Complete Store Installation" @(
                "1. Click 'Get' or 'Install' in the Microsoft Store window.",
                "2. Wait for installation to complete."
            )
        }
        default {
            # Option 1: Direct Apple installer
            $installerUrl = "https://secure-appldnld.apple.com/itunes12/140-75773-20260908-6e5e0165-99cb-4b30-b541-1b615fccfc1a/iTunes64Setup.exe"
            $destPath = Join-Path $env:TEMP "iTunes64Setup.exe"

            Write-Working "Downloading official Apple iTunes installer (64-bit)..."
            Write-Info "URL: $installerUrl"
            Invoke-WebRequest -Uri $installerUrl -OutFile $destPath -UseBasicParsing

            Write-Working "Launching iTunes installer..."
            Write-Info "Please complete the Apple installation wizard on your screen."
            Start-Process -FilePath $destPath -Wait
        }
    }
}

function Ensure-AppleDeviceSupport {
    Write-StepHeader 2 7 "Checking Apple device support"

    if ($SkipPrerequisites -or $SkipAppleDriverCheck) {
        Write-Alert "Skipping Apple device checks (--SkipAppleDriverCheck specified)"
        return
    }

    $status = Get-AppleServiceStatus
    $regPresent = Test-AppleRegistryPresence
    $storePresent = Test-AppleStorePackage

    if ($status -eq "Running") {
        Write-Success "Apple Mobile Device Service is running"
        return
    }

    if ($status -eq "Stopped") {
        Write-Working "Starting Apple Mobile Device Service..."
        try {
            Start-Service "Apple Mobile Device Service"
            Write-Success "Apple Mobile Device Service started successfully"
            return
        } catch {
            Write-Alert "Could not start service directly: $_"
            Write-Info "Administrative permissions may be required."
        }
    }

    if ($regPresent -or $storePresent) {
        Write-Success "Apple software components detected on system"
        return
    }

    # Missing entirely
    Write-Alert "Apple device support components not found"
    Install-AppleDeviceDrivers

    # Verify again
    $statusAfter = Get-AppleServiceStatus
    $regAfter = Test-AppleRegistryPresence
    $storeAfter = Test-AppleStorePackage

    if ($statusAfter -eq "Running" -or $regAfter -or $storeAfter) {
        Write-Success "Apple device support verified"
    } else {
        Write-Alert "Could not automatically verify Apple drivers."
        Write-Info "If you just finished the installer, it may need a moment to register."
        Write-ActionBox "Confirm Apple Software" @(
            "Please ensure iTunes or the Apple Devices app is installed.",
            "If already installed, you can proceed safely."
        )
    }
}

# -------------------------------------------------------------------------
# Step 3: Connect iPhone & Trust
# -------------------------------------------------------------------------
function Get-DeviceDisplayName {
    param($Device)
    if ($null -eq $Device) { return "Apple Mobile Device" }
    if ($Device.PSObject.Properties["FriendlyName"] -and $Device.FriendlyName) {
        return $Device.FriendlyName
    }
    if ($Device.PSObject.Properties["Name"] -and $Device.Name) {
        return $Device.Name
    }
    return "Apple Mobile Device"
}

function Get-ConnectedAppleDevices {
    [OutputType([System.Object[]])]
    param()
    [System.Collections.Generic.List[System.Object]]$devices = [System.Collections.Generic.List[System.Object]]::new()
    try {
        $pnpDevices = Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue | Where-Object {
            $_.InstanceId -like "*VID_05AC*" -or $_.FriendlyName -match "Apple|iPhone|iPad"
        }
        if ($null -ne $pnpDevices) {
            foreach ($p in $pnpDevices) { $devices.Add($p) }
        }
    } catch {}

    if ($devices.Count -eq 0) {
        try {
            $cimDevices = Get-CimInstance Win32_PnPEntity -ErrorAction SilentlyContinue | Where-Object {
                $_.DeviceID -like "*VID_05AC*" -or $_.Name -match "Apple|iPhone|iPad"
            }
            if ($null -ne $cimDevices) {
                foreach ($c in $cimDevices) { $devices.Add($c) }
            }
        } catch {}
    }
    return $devices.ToArray()
}

function Wait-ForConnectedPhone {
    Write-StepHeader 3 7 "Connecting your iPhone"

    if ($SkipDeviceWait) {
        Write-Alert "Skipping device detection (--SkipDeviceWait specified)"
        return
    }

    $devices = @(Get-ConnectedAppleDevices)
    if ($devices.Count -gt 0) {
        $devName = Get-DeviceDisplayName $devices[0]
        Write-Success "iPhone detected ($devName)"
        return
    }

    Write-Alert "No iPhone detected over USB"
    Write-Host ""
    Write-Styled "-------------------------------------------------------------------" DarkYellow
    Write-Styled "ACTION NEEDED: Connect and Trust Your iPhone" Yellow
    Write-Styled "-------------------------------------------------------------------" DarkYellow
    Write-Styled "  1. Connect your iPhone to this computer using a USB / Lightning cable." Gray
    Write-Styled "  2. Connect your iPhone to Wi-Fi (SideStore requires active Wi-Fi; cellular is not sufficient)." Gray
    Write-Styled "  3. Unlock your iPhone screen." Gray
    Write-Styled "  4. Look at your iPhone screen for the prompt:" Gray
    Write-Styled "       'Trust This Computer?'" White
    Write-Styled "     Tap 'Trust' and enter your iPhone passcode." White
    Write-Styled "-------------------------------------------------------------------" DarkYellow
    Write-Host ""
    Write-Working "Waiting for iPhone connection... (Press ENTER to re-check or continue)"

    $timeoutSeconds = 60
    $elapsed = 0
    while ($elapsed -lt $timeoutSeconds) {
        Start-Sleep -Seconds 2
        $elapsed += 2

        $devices = @(Get-ConnectedAppleDevices)
        if ($devices.Count -gt 0) {
            $devName = Get-DeviceDisplayName $devices[0]
            Write-Host ""
            Write-Success "iPhone connected: $devName"
            return
        }

        # Check if user pressed Enter
        if ([Console]::KeyAvailable) {
            $key = [Console]::ReadKey($true)
            if ($key.Key -eq [ConsoleKey]::Enter) {
                Write-Host ""
                Write-Alert "Continuing as requested by user."
                return
            }
        }
    }

    Write-Host ""
    Write-Alert "Connection timed out without automatic USB detection."
    Write-ActionBox "Device Connection Check" @(
        "If your iPhone is connected, unlocked, and trusted, you can continue.",
        "If not, try unplugging and reconnecting the cable."
    )
}

# -------------------------------------------------------------------------
# Step 4: Phone-side Prep: LocalDevVPN
# -------------------------------------------------------------------------
function Guide-LocalDevVpn {
    Write-StepHeader 4 7 "iPhone Preparation: LocalDevVPN"

    Write-ActionBox "Install LocalDevVPN & Verify Wi-Fi on Your iPhone" @(
        "CRITICAL WI-FI REQUIREMENT:",
        "  - Your iPhone MUST be connected to an active Wi-Fi network.",
        "  - Cellular data alone is NOT sufficient. SideStore's local loopback",
        "    and pairing communication require an active Wi-Fi connection.",
        "",
        "WHY THIS IS NEEDED:",
        "SideStore works completely on-device, signing and refreshing apps",
        "wirelessly without needing a PC for everyday use. To communicate",
        "with Apple's developer service on your phone, iOS requires a local",
        "loopback VPN running on the device.",
        "",
        "ON YOUR IPHONE:",
        "  1. Verify your iPhone is connected to Wi-Fi.",
        "  2. Open the App Store and search for 'LocalDevVPN' (by jkcoxson)",
        "     Direct link: https://apps.apple.com/app/localdevvpn/id6755608044",
        "  3. Install the app and open it.",
        "  4. Tap the 'Connect' button.",
        "  5. When iOS prompts:",
        "       'LocalDevVPN Would Like to Add VPN Configurations'",
        "     Tap 'Allow' and enter your device passcode.",
        "  6. Verify that the 'VPN' badge appears in your status bar or",
        "     Control Center.",
        "",
        "NOTE: LocalDevVPN must remain connected whenever you install, refresh,",
        "or update sideloaded apps in SideStore."
    )
    Write-Success "LocalDevVPN step acknowledged"
}

# -------------------------------------------------------------------------
# Step 5: Download & Launch iloader
# -------------------------------------------------------------------------
function Find-InstalledIloader {
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA "Programs\iloader\iloader.exe"),
        (Join-Path $env:ProgramFiles "iloader\iloader.exe"),
        (Join-Path ${env:ProgramFiles(x86)} "iloader\iloader.exe"),
        (Join-Path $env:USERPROFILE ".nuvio-z\iloader\iloader.exe")
    )
    foreach ($cand in $candidates) {
        if (Test-Path $cand) { return $cand }
    }
    $cmd = Get-Command "iloader.exe" -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

function Ensure-Iloader {
    Write-StepHeader 5 7 "Installing SideStore via iloader"

    $installed = Find-InstalledIloader
    if ($null -ne $installed) {
        Write-Success "Found existing iloader installation at: $installed"
        $iloaderExe = $installed
    } else {
        $storageDir = Join-Path $env:USERPROFILE ".nuvio-z\iloader"
        if (-not (Test-Path $storageDir)) {
            New-Item -ItemType Directory -Path $storageDir -Force | Out-Null
        }

        $msiUrl = "https://github.com/nab138/iloader/releases/latest/download/iloader-windows-x64.msi"
        $msiPath = Join-Path $storageDir "iloader-windows-x64.msi"

        Write-Working "Downloading official iloader installer from GitHub..."
        Write-Info "Source: $msiUrl"
        try {
            Invoke-WebRequest -Uri $msiUrl -OutFile $msiPath -UseBasicParsing
        } catch {
            Write-Alert "Download from 'latest' failed, trying fallback release tag v2.3.3..."
            $fallbackUrl = "https://github.com/nab138/iloader/releases/download/v2.3.3/iloader-windows-x64.msi"
            Invoke-WebRequest -Uri $fallbackUrl -OutFile $msiPath -UseBasicParsing
        }
        Write-Success "iloader installer downloaded successfully"

        Write-Working "Launching iloader installer..."
        Write-Info "Please complete the setup wizard if prompted."
        $proc = Start-Process -FilePath "msiexec.exe" -ArgumentList "/i `"$msiPath`"" -Wait -PassThru

        # Locate executable post-install
        $iloaderExe = Find-InstalledIloader
        if ($null -eq $iloaderExe) {
            # Fallback: portable exe download if MSI didn't put it in standard path
            Write-Alert "iloader was not found in standard paths. Downloading standalone executable..."
            $exeUrl = "https://github.com/nab138/iloader/releases/latest/download/iloader-windows-x64.exe"
            $iloaderExe = Join-Path $storageDir "iloader.exe"
            Invoke-WebRequest -Uri $exeUrl -OutFile $iloaderExe -UseBasicParsing
        }
        Write-Success "iloader ready"
    }

    # Launch iloader
    Write-Working "Opening iloader..."
    Start-Process -FilePath $iloaderExe

    Write-ActionBox "Install SideStore in iloader" @(
        "The official iloader window is now open on your computer screen.",
        "",
        "IN THE ILOADER WINDOW:",
        "  1. Sign in with your Apple Account (email & password).",
        "     - SECURITY NOTE: Your login is processed directly by the official",
        "       iloader application and communicated securely to Apple.",
        "       Nuvio Z never sees, prompts for, or records your credentials.",
        "     - If you use Two-Factor Authentication, enter the 6-digit code",
        "       displayed on your Apple device.",
        "  2. In the device dropdown, select your connected iPhone.",
        "  3. Click 'Install SideStore (Stable)'.",
        "  4. Wait for iloader to sign and install the app onto your phone.",
        "     (This typically takes 30-90 seconds).",
        "  5. Look for the completion message in iloader."
    )
    Write-Success "SideStore installation initiated"
}

# -------------------------------------------------------------------------
# Step 6: Phone-side Approvals & Priming
# -------------------------------------------------------------------------
function Guide-PhoneApprovals {
    Write-StepHeader 6 7 "iPhone Security Approvals & Priming"

    Write-ActionBox "Trust Developer Profile & Enable Developer Mode" @(
        "Apple requires two security approvals on your iPhone before opening",
        "sideloaded applications for the first time.",
        "",
        "PART 1: Trust Your Developer Profile",
        "  1. On your iPhone, open the Settings app.",
        "  2. Go to: General -> VPN & Device Management.",
        "  3. Under 'DEVELOPER APP', tap your Apple Account email.",
        "  4. Tap 'Trust [Your Email]'.",
        "     (On iOS 18+, this may say 'Allow & Restart' - tap it).",
        "  5. Tap 'Trust' to confirm.",
        "",
        "PART 2: Enable Developer Mode (iOS 16, 17, 18+)",
        "  1. Open Settings -> Privacy & Security.",
        "  2. Scroll down to the very bottom and tap 'Developer Mode'.",
        "  3. Toggle Developer Mode ON.",
        "  4. Tap 'Restart' when prompted.",
        "  5. After your iPhone reboots, unlock it and tap 'Turn On' when",
        "     prompted, then enter your device passcode.",
        "",
        "PART 3: Prime SideStore (Crucial First Refresh)",
        "  1. Ensure your iPhone is connected to Wi-Fi (cellular is not sufficient).",
        "  2. Open the LocalDevVPN app and verify it is CONNECTED.",
        "  3. Open the SideStore app from your Home Screen.",
        "  4. Sign in using the SAME Apple Account you used in iloader.",
        "  5. Tap the 'My Apps' tab at the bottom.",
        "  6. Tap the '7 DAYS' button next to SideStore.",
        "  7. If prompted to revoke or create a new signing certificate,",
        "     tap 'Yes' or 'Refresh Now'.",
        "  8. SideStore will refresh and confirm it is primed."
    )
    Write-Success "Phone security approvals and priming acknowledged"
}

# -------------------------------------------------------------------------
# Step 7: Add Nuvio Z Source & HTML Helper
# -------------------------------------------------------------------------
function Generate-HtmlHelper {
    param(
        [string]$SourceUrl,
        [switch]$IsDeveloperMode
    )

    $encodedSourceUrl = [Uri]::EscapeDataString($SourceUrl)
    $sideStoreDeepLink = "sidestore://source?url=$encodedSourceUrl"
    $altStoreDeepLink = "altstore://source?url=$encodedSourceUrl"
    $htmlPath = Join-Path $env:TEMP "nuvio-z-sidestore-helper.html"

    $appName = if ($IsDeveloperMode) { "Nuvio Z Debug" } else { "Nuvio Z" }
    $pageTitle = if ($IsDeveloperMode) { "Nuvio Z Debug - Developer Channel" } else { "Nuvio Z - SideStore Source Setup" }
    $badgeText = if ($IsDeveloperMode) { "Nuvio Z iOS Debug (Developer Channel)" } else { "Nuvio Z iOS Sideload" }
    $accentColor = if ($IsDeveloperMode) { "#e65100" } else { "#1e88e5" }
    $accentHover = if ($IsDeveloperMode) { "#bf360c" } else { "#1565c0" }
    $warningBanner = if ($IsDeveloperMode) {
@"
  <div style="background: rgba(230, 81, 0, 0.15); border: 1px solid #e65100; border-radius: 8px; padding: 12px; margin-bottom: 20px; font-size: 13px; color: #ffb74d; text-align: left;">
    <strong>DEVELOPER PREVIEW:</strong>
    <ul style="margin: 6px 0 0 16px; padding: 0;">
      <li>Installs separately from stable Nuvio Z as <code>com.nuvio.app.z.debug</code>.</li>
      <li>Uses its own independent application container and data.</li>
      <li>Consumes an additional SideStore app slot (all 3 slots used when both installed).</li>
    </ul>
  </div>
"@
    } else { "" }

    $htmlContent = @"
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>$pageTitle</title>
<style>
  :root {
    --bg: #0d1117;
    --card: #161b22;
    --border: #30363d;
    --text: #c9d1d9;
    --heading: #f0f6fc;
    --accent: $accentColor;
    --accent-hover: $accentHover;
    --success: #2ea043;
    --code-bg: #090d13;
  }
  body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    background-color: var(--bg);
    color: var(--text);
    margin: 0;
    padding: 24px;
    display: flex;
    justify-content: center;
    align-items: center;
    min-height: 100vh;
  }
  .container {
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 16px;
    max-width: 580px;
    width: 100%;
    padding: 32px;
    box-shadow: 0 12px 32px rgba(0,0,0,0.5);
    text-align: center;
  }
  .badge {
    display: inline-block;
    background: rgba(30, 136, 229, 0.15);
    color: var(--accent);
    border: 1px solid var(--accent);
    padding: 4px 12px;
    border-radius: 20px;
    font-size: 12px;
    font-weight: 600;
    letter-spacing: 0.5px;
    margin-bottom: 12px;
  }
  h1 {
    color: var(--heading);
    font-size: 24px;
    margin: 0 0 8px 0;
  }
  p.subtitle {
    color: var(--text);
    font-size: 14px;
    margin: 0 0 24px 0;
    line-height: 1.5;
  }
  .qr-box {
    background: #ffffff;
    border-radius: 12px;
    padding: 16px;
    display: inline-block;
    margin-bottom: 24px;
    box-shadow: 0 4px 12px rgba(0,0,0,0.2);
  }
  .qr-box img {
    display: block;
    width: 220px;
    height: 220px;
  }
  .url-box {
    background: var(--code-bg);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 10px 14px;
    font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace;
    font-size: 12px;
    color: #58a6ff;
    word-break: break-all;
    margin-bottom: 24px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 8px;
  }
  .instructions {
    text-align: left;
    background: rgba(110, 118, 129, 0.1);
    border-radius: 8px;
    padding: 16px;
    font-size: 13px;
    margin-bottom: 24px;
    line-height: 1.6;
  }
  .instructions ol {
    margin: 8px 0 0 18px;
    padding: 0;
  }
  .btn-copy {
    background: #21262d;
    border: 1px solid var(--border);
    color: var(--text);
    padding: 6px 12px;
    border-radius: 6px;
    cursor: pointer;
    font-size: 12px;
    font-weight: 500;
    white-space: nowrap;
    transition: all 0.2s;
  }
  .btn-copy:hover {
    background: #30363d;
    color: var(--heading);
  }
  .btn-primary {
    display: inline-block;
    background: var(--accent);
    color: #ffffff;
    text-decoration: none;
    padding: 12px 24px;
    border-radius: 8px;
    font-weight: 600;
    font-size: 15px;
    transition: background 0.2s;
    margin: 4px;
  }
  .btn-primary:hover {
    background: var(--accent-hover);
  }
  .footer-note {
    font-size: 12px;
    color: #8b949e;
    margin-top: 20px;
    line-height: 1.4;
  }
</style>
</head>
<body>
<div class="container">
  <div class="badge">$badgeText</div>
  <h1>Add $appName to SideStore</h1>
  <p class="subtitle">Scan the QR code with your iPhone Camera to automatically add the source, or copy the URL below.</p>
$warningBanner
  <div class="qr-box">
    <img src="https://api.qrserver.com/v1/create-qr-code/?size=220x220&data=$encodedSourceUrl" alt="SideStore Source QR Code" />
  </div>

  <div class="url-box">
    <span id="source-url">$SourceUrl</span>
    <button class="btn-copy" onclick="copyUrl()">Copy URL</button>
  </div>

  <div class="instructions">
    <strong>How to install on your iPhone:</strong>
    <ol>
      <li>Make sure <strong>LocalDevVPN</strong> is connected on your iPhone.</li>
      <li>Open your iPhone <strong>Camera</strong> and point it at the QR code above.</li>
      <li>Tap the yellow prompt <strong>"Open in SideStore"</strong>.</li>
      <li>In SideStore, tap <strong>"Add Source"</strong>.</li>
      <li>Go to the <strong>Browse</strong> tab, find <strong>$appName</strong>, and tap <strong>Install</strong>!</li>
    </ol>
  </div>

  <div>
    <a href="$sideStoreDeepLink" class="btn-primary">Open in SideStore</a>
  </div>

  <p class="footer-note">
    Remember to refresh $appName in SideStore every 5-6 days with LocalDevVPN enabled to keep your 7-day developer certificate active.
  </p>
</div>

<script>
function copyUrl() {
  const url = document.getElementById('source-url').innerText;
  navigator.clipboard.writeText(url).then(() => {
    const btn = document.querySelector('.btn-copy');
    btn.innerText = 'Copied!';
    setTimeout(() => { btn.innerText = 'Copy URL'; }, 2000);
  });
}
</script>
</body>
</html>
"@

    [System.IO.File]::WriteAllText($htmlPath, $htmlContent, [System.Text.Encoding]::UTF8)
    return $htmlPath
}

function Guide-SourceAndInstall {
    param(
        [string]$SourceUrl,
        [switch]$IsDeveloperMode
    )

    $stepTitle = if ($IsDeveloperMode) {
        "Adding Nuvio Z Debug Source & Installing App"
    } else {
        "Adding Nuvio Z Source & Installing App"
    }
    Write-StepHeader 7 7 $stepTitle

    # Generate and open HTML helper
    Write-Working "Generating interactive QR code and setup helper..."
    $helperPath = Generate-HtmlHelper -SourceUrl $SourceUrl -IsDeveloperMode:$IsDeveloperMode
    Write-Success "Helper page created at: $helperPath"
    Write-Working "Opening helper page in your default browser..."
    try {
        Start-Process -FilePath $helperPath
    } catch {
        Write-Alert "Could not automatically launch browser. You can open $helperPath manually."
    }

    $encoded = [Uri]::EscapeDataString($SourceUrl)
    $deepLink = "sidestore://source?url=$encoded"
    $targetApp = if ($IsDeveloperMode) { "Nuvio Z Debug" } else { "Nuvio Z" }

    Write-Host ""
    Write-Styled "===================================================================" Cyan
    Write-Styled "                     ADD $(if ($IsDeveloperMode) { 'NUVIO Z DEBUG' } else { 'NUVIO Z' }) SOURCE                            " Cyan
    Write-Styled "===================================================================" Cyan
    Write-Host ""
    Write-Styled "A browser window has opened with a crisp QR code." White
    Write-Host ""
    Write-Styled "METHOD 1 (Recommended - Scan with iPhone):" Yellow
    Write-Styled "  1. On your iPhone, open the default Camera app." Gray
    Write-Styled "  2. Point the camera at the QR code on your computer screen." Gray
    Write-Styled "  3. Tap the yellow banner: 'Open in SideStore'." Gray
    Write-Styled "  4. In SideStore, tap 'Add Source'." Gray
    Write-Host ""
    Write-Styled "METHOD 2 (Manual entry in SideStore):" Yellow
    Write-Styled "  1. Open SideStore on your iPhone -> tap 'Sources' tab." Gray
    Write-Styled "  2. Tap the '+' icon in the top corner." Gray
    Write-Styled "  3. Enter this Source URL:" Gray
    Write-Styled "     $SourceUrl" Cyan
    Write-Styled "  4. Tap 'Add'." Gray
    Write-Host ""
    Write-Styled "-------------------------------------------------------------------" DarkGray
    Write-Styled "INSTALLING $($targetApp.ToUpper()):" Yellow
    Write-Styled "  1. Make sure LocalDevVPN is active on your iPhone." Gray
    Write-Styled "  2. In SideStore, tap the 'Browse' tab at the bottom." Gray
    Write-Styled "  3. You will see '$targetApp' in the list." Gray
    Write-Styled "  4. Tap 'FREE' or 'INSTALL' next to $targetApp." Gray
    Write-Styled "  5. Wait for SideStore to download, sign, and install." Gray
    Write-Styled "  6. $targetApp will appear on your Home Screen!" Gray
    Write-Styled "-------------------------------------------------------------------" DarkGray
    Write-Host ""

    Write-ActionBox "Install $targetApp" @(
        "Confirm that you have added the source and installed $targetApp",
        "onto your iPhone screen."
    )
}

function Show-CompletionSummary {
    Write-Host ""
    Write-Styled "===================================================================" Green
    Write-Styled "                    SETUP COMPLETE! 🎉                             " Green
    Write-Styled "===================================================================" Green
    Write-Host ""
    Write-Styled "HOW 7-DAY REFRESHING AND UPDATES WORK:" Yellow
    Write-Host ""
    Write-Styled "  1. Free Apple Accounts grant 7-day app certificates." White
    Write-Styled "  2. Normally, the computer is only needed for initial setup." Green
    Write-Styled "     SideStore refreshes everything wirelessly directly on your phone." White
    Write-Styled "     NOTE: If SideStore's pairing file later expires (e.g. after iOS" Gray
    Write-Styled "     updates, device resets, or pairing expiration), you may need" Gray
    Write-Styled "     to reconnect to a computer and use iloader to replace it." Gray
    Write-Styled "  3. Every 5 to 6 days:" White
    Write-Styled "     - Ensure your iPhone is connected to Wi-Fi (cellular is not sufficient)." Gray
    Write-Styled "     - Turn on LocalDevVPN on your iPhone." Gray
    Write-Styled "     - Open SideStore -> tap 'My Apps'." Gray
    Write-Styled "     - Tap 'Refresh All'." Gray
    Write-Styled "  4. Future Nuvio Z updates:" White
    Write-Styled "     - When a new version of Nuvio Z is released, SideStore will" Gray
    Write-Styled "       show an 'UPDATE' button automatically under 'My Apps'." Gray
    Write-Styled "     - Tap 'Update' while connected to Wi-Fi and LocalDevVPN." Gray
    Write-Host ""
    Write-Styled "===================================================================" Green
    Write-Host ""
}

# -------------------------------------------------------------------------
# Main Flow
# -------------------------------------------------------------------------
function Main {
    Write-Banner
    try {
        if ($DiagnosticOnly) {
            Write-StepHeader 1 3 "System Diagnostics"
            Test-SystemEnvironment
            Write-StepHeader 2 3 "Apple Device Software Diagnostics"
            $status = Get-AppleServiceStatus
            $reg = Test-AppleRegistryPresence
            $store = Test-AppleStorePackage
            if ($status -eq "Running") {
                Write-Success "Apple Mobile Device Service: Running"
            } elseif ($status -eq "Stopped") {
                Write-Alert "Apple Mobile Device Service: Installed but Stopped"
            } else {
                Write-Alert "Apple Mobile Device Service: Not installed"
            }
            if ($reg) {
                Write-Success "Apple registry keys detected"
            } else {
                Write-Info "Apple registry keys: None"
            }
            if ($store) {
                Write-Success "Apple Store apps (iTunes/Apple Devices): Detected"
            } else {
                Write-Info "Apple Store apps: None"
            }
            Write-StepHeader 3 3 "Connected Apple USB Devices"
            $devs = @(Get-ConnectedAppleDevices)
            if ($devs.Count -gt 0) {
                Write-Success "Found $($devs.Count) connected Apple device(s)"
                foreach ($d in $devs) {
                    $name = Get-DeviceDisplayName $d
                    Write-Info "Device: $name"
                }
            } else {
                Write-Alert "No Apple USB device currently detected"
            }
            Write-Host ""
            Write-Success "Diagnostics completed."
            Write-Host ""
            return
        }

        if ($DeveloperMode) {
            Write-Host ""
            Write-Styled "===================================================================" Yellow
            Write-Styled "              DEVELOPER / DEBUG CHANNEL REQUESTED                  " Yellow
            Write-Styled "===================================================================" Yellow
            Write-Host ""
            Write-Styled "You have enabled -DeveloperMode." White
            Write-Styled "Please review the following before continuing:" Yellow
            Write-Styled "  1. BUNDLE ID: Installs 'Nuvio Z Debug' (com.nuvio.app.z.debug)." White
            Write-Styled "  2. INDEPENDENT CONTAINER: Runs side-by-side with stable Nuvio Z" White
            Write-Styled "     with separate local databases, logs, and settings." White
            Write-Styled "  3. APPLE APP SLOTS: Free Apple Developer accounts allow a MAXIMUM" Red
            Write-Styled "     of 3 active sideloaded apps. SideStore (1) + Nuvio Z (1) +" Red
            Write-Styled "     Nuvio Z Debug (1) will use ALL 3 AVAILABLE SLOTS." Red
            Write-Styled "  4. STABILITY: Debug builds contain unreleased code, experimental" Gray
            Write-Styled "     features, and verbose logging." Gray
            Write-Host ""
            $answer = Read-Host "Are you sure you want to proceed with the Developer Channel? (y/N)"
            if ($answer -notmatch '^(y|yes)$') {
                Write-Alert "Developer mode cancelled by user. Exiting."
                return
            }
            Write-Success "Developer mode confirmed."
            if (-not $PSBoundParameters.ContainsKey('SourceUrl')) {
                $SourceUrl = "https://raw.githubusercontent.com/Zokaper/nuvio-z/main/distribution/sidestore/source-debug.json"
            }
        }

        Test-SystemEnvironment
        Ensure-AppleDeviceSupport
        Wait-ForConnectedPhone
        Guide-LocalDevVpn
        Ensure-Iloader
        Guide-PhoneApprovals
        Guide-SourceAndInstall -SourceUrl $SourceUrl -IsDeveloperMode:$DeveloperMode
        Show-CompletionSummary
    } catch {
        Write-Host ""
        Write-Failure "Setup stopped: $_"
        Write-Host ""
        exit 1
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    Main
}
