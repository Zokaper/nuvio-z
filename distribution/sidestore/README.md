# Nuvio Z — iOS Sideloading (SideStore Distribution)

This directory contains the official SideStore distribution metadata and the advanced fallback scripts for installing **Nuvio Z** on iOS devices without requiring TestFlight or a paid Apple Developer account.

The intended end-user path is the portable **Nuvio Z iOS Setup** desktop wizard in `iosSetup/`.
Download the Windows or macOS ZIP from the dedicated **Build iOS Setup GUI** workflow, extract it,
and open the bundled application. It separates every human-controlled SideStore step, persists
non-sensitive progress, and never treats iloader closing as proof that SideStore or pairing was set up.

---

## Architecture Overview

Nuvio Z uses the official, current **SideStore** and **iloader** workflow:

```
[GitHub Releases]
      │
      ├─► Nuvio-Z-iOS-<version>-unsigned.ipa
      ├─► source.json (AltStore/SideStore Source)
      ├─► setup-windows.ps1
      └─► setup-macos.sh
            │
      [One-Time PC Setup]
            │
            ├─► iloader (official GUI tool)
            │      └─► Signs & installs SideStore to iPhone via USB
            │
      [On-Device iPhone Operation]
            │
            ├─► LocalDevVPN (on-device loopback VPN)
            ├─► SideStore (manages app certificates wirelessly)
            └─► Nuvio Z (installed from the Nuvio Z source)
```

### Why SideStore?

Traditional sideloading tools require reconnecting your iPhone to a PC via USB every 7 days to refresh app certificates. **SideStore runs directly on your iPhone** and refreshes apps wirelessly over your local Wi-Fi network using an on-device loopback VPN (**LocalDevVPN**). 

Normally, the computer is only needed for initial setup. If SideStore's pairing file later expires (e.g. after iOS updates, device resets, or Apple pairing lifecycle expirations), you may need to reconnect to a computer and use iloader to replace it.

---

## Prerequisites

| Requirement | Details |
|---|---|
| **iOS Device** | iPhone, iPad, or iPod touch running **iOS 15.0 or higher** (iOS 16+ requires Developer Mode). |
| **Passcode** | A passcode must be configured on your iOS device. |
| **Computer** | Windows 10/11 (64-bit) or macOS (Intel or Apple Silicon) for the initial setup. |
| **USB Cable** | Lightning or USB-C cable to connect your iPhone to your computer for setup. |
| **Apple Account** | A standard, free Apple Account (Apple ID). |
| **Wi-Fi Network** | **Active Wi-Fi connection required on your iPhone** (cellular alone is not sufficient for SideStore loopback). Both computer and iPhone should be on Wi-Fi during setup. |

---

## Quick Start

### Portable GUI (recommended)

- **Windows:** extract `Nuvio-Z-iOS-Setup-Windows-x64.zip`, then double-click
  `Nuvio Z iOS Setup.exe` inside the extracted folder.
- **macOS:** extract `Nuvio-Z-iOS-Setup-macOS.zip`, then open `Nuvio Z iOS Setup.app`.
  If an unsigned acceptance build is blocked, use macOS **System Settings → Privacy & Security →
  Open Anyway**. The setup utility does not remove quarantine attributes or bypass Gatekeeper.

The application includes its own Java runtime. It does not install itself, add a Start Menu entry,
or require administrator rights merely to launch. Windows may request elevation later only when
installing genuine Apple device prerequisites.

### Advanced fallback and diagnostics

The scripts below remain available for maintainers, troubleshooting, and environments where the
GUI cannot run. They are no longer the normal onboarding path.

### Windows (PowerShell)

Open PowerShell (no admin elevation required) and run:

```powershell
# Run from repository root
powershell -ExecutionPolicy Bypass -File distribution\sidestore\setup-windows.ps1
```

Or run diagnostics only:

```powershell
powershell -ExecutionPolicy Bypass -File distribution\sidestore\setup-windows.ps1 -DiagnosticOnly
```

### macOS (Terminal)

Open Terminal and run:

```bash
# Run from repository root
chmod +x distribution/sidestore/setup-macos.sh
./distribution/sidestore/setup-macos.sh
```

Or run diagnostics only:

```bash
./distribution/sidestore/setup-macos.sh --diagnostic-only
```

---

## Step-by-Step Installation Walkthrough

Both bootstrap scripts guide you through 7 structured steps:

### Step 1: Computer Diagnostic
- Verifies 64-bit operating system architecture.
- Tests internet connectivity to GitHub and Apple services.

### Step 2: Apple Device Drivers
- **Windows**: Checks for `Apple Mobile Device Service` and official Apple USB drivers (installed via iTunes or Apple Devices app). If missing, the script offers to download the official 64-bit Apple installer or install via winget.
- **macOS**: Native `MobileDevice` and `usbmuxd` support are verified.

### Step 3: Connect & Trust iPhone
1. Connect your iPhone to your PC using a USB cable.
2. Unlock your iPhone screen.
3. If prompted with **"Trust This Computer?"**, tap **Trust** and enter your passcode.
4. The script automatically detects the device and proceeds.

### Step 4: Install LocalDevVPN on iPhone
SideStore requires an on-device VPN to communicate with local developer services:
1. Open the **App Store** on your iPhone.
2. Search for **LocalDevVPN** (by jkcoxson) or visit:  
   [`https://apps.apple.com/app/localdevvpn/id6755608044`](https://apps.apple.com/app/localdevvpn/id6755608044)
3. Install the app, open it, and tap **Connect**.
4. When iOS prompts: *"LocalDevVPN Would Like to Add VPN Configurations"*, tap **Allow** and enter your passcode.
5. Verify the **VPN** icon appears in your status bar or Control Center.

### Step 5: Install SideStore via iloader
1. The script downloads and opens the official **iloader** application (`nab138/iloader`).
2. In the iloader window:
   - Sign in with your Apple Account.
   - Select your connected iPhone from the dropdown list.
   - Click **Install SideStore (Stable)**.
   - Enter your Two-Factor Authentication (2FA) code if prompted on your Apple device.
   - Wait 30–90 seconds for iloader to complete the installation.

### Step 6: iPhone Security Approvals & Priming
1. **Trust Developer Certificate**:
   - On your iPhone, open **Settings** → **General** → **VPN & Device Management**.
   - Under *DEVELOPER APP*, tap your Apple Account email.
   - Tap **Trust [Your Email]** (on iOS 18+, tap *Allow & Restart*), then confirm **Trust**.
2. **Enable Developer Mode** (iOS 16+):
   - Open **Settings** → **Privacy & Security**.
   - Scroll to the bottom and tap **Developer Mode**.
   - Toggle Developer Mode **ON** and tap **Restart**.
   - After restart, unlock the phone and tap **Turn On** when prompted, then enter your passcode.
3. **Prime SideStore**:
   - Ensure **LocalDevVPN** is connected.
   - Open **SideStore** and sign in with the same Apple Account.
   - In SideStore, tap **My Apps** at the bottom.
   - Tap the **7 DAYS** counter next to SideStore to perform the initial certificate refresh.
   - If prompted to revoke/refresh existing certificates, tap **Yes** or **Refresh Now**.

### Step 7: Add Nuvio Z Source & Install App
The script generates an interactive HTML page with a QR code and deep links:

- **Option A (Recommended — QR Code)**:
  1. Open your iPhone **Camera** app.
  2. Point the camera at the QR code on your computer screen.
  3. Tap the yellow banner: **"Open in SideStore"**.
  4. In SideStore, tap **Add Source**.

- **Option B (Manual URL)**:
  1. In SideStore, navigate to the **Sources** tab.
  2. Tap the **+** button in the top corner.
  3. Enter the Source URL:
     ```
     https://raw.githubusercontent.com/Zokaper/nuvio-z/main/distribution/sidestore/source.json
     ```
  4. Tap **Add**.

- **Install Nuvio Z**:
  1. In SideStore, tap the **Browse** tab.
  2. Find **Nuvio Z** in the catalog.
  3. Tap **INSTALL** (or **FREE**).
  4. Wait for SideStore to sign and install the app.
  5. The **Nuvio Z** icon will appear on your Home Screen!

---

## Keeping Nuvio Z Active (7-Day Refreshing)

Free Apple Accounts issue certificates valid for **7 days**. SideStore handles refreshes entirely on-device without needing a computer:

1. Ensure your iPhone is connected to Wi-Fi (cellular alone is not sufficient).
2. Open **LocalDevVPN** and ensure it is **Connected**.
3. Open **SideStore** → go to **My Apps**.
4. Tap **Refresh All**.

> [!TIP]
> Refresh every 5 to 6 days so your apps do not expire. If an app expires before refreshing, simply connect to Wi-Fi, enable LocalDevVPN, and tap refresh in SideStore.

---

## Future Updates

When a new version of Nuvio Z is released:
1. The GitHub Release automatically updates the SideStore source metadata.
2. SideStore will display an **UPDATE** badge next to Nuvio Z under **My Apps**.
3. With Wi-Fi and LocalDevVPN active, tap **Update** to install the latest version seamlessly with your settings and library preserved.

---

## Source URLs & Deep Links

| Link Type | URL |
|---|---|
| **Canonical Source URL (Stable)** | `https://raw.githubusercontent.com/Zokaper/nuvio-z/main/distribution/sidestore/source.json` |
| **Release Asset Source (Stable)** | `https://github.com/Zokaper/nuvio-z/releases/latest/download/source.json` |
| **SideStore Deep Link (Stable)** | `sidestore://source?url=https%3A%2F%2Fraw.githubusercontent.com%2FZokaper%2Fnuvio-z%2Fmain%2Fdistribution%2Fsidestore%2Fsource.json` |
| **AltStore Deep Link (Stable)** | `altstore://source?url=https%3A%2F%2Fraw.githubusercontent.com%2FZokaper%2Fnuvio-z%2Fmain%2Fdistribution%2Fsidestore%2Fsource.json` |
| **Developer Source URL (Debug)** | `https://raw.githubusercontent.com/Zokaper/nuvio-z/main/distribution/sidestore/source-debug.json` |
| **SideStore Deep Link (Debug)** | `sidestore://source?url=https%3A%2F%2Fraw.githubusercontent.com%2FZokaper%2Fnuvio-z%2Fmain%2Fdistribution%2Fsidestore%2Fsource-debug.json` |

---

## Developer Channel (Advanced)

For testers, contributors, and developers wanting to try preview builds before they reach stable release, Nuvio Z provides an isolated **Developer Channel**:

- **Independent Bundle Identifier**: `com.nuvio.app.z.debug` (Display name: `Nuvio Z Debug`).
- **Side-by-Side Coexistence**: Can be installed simultaneously alongside the stable `com.nuvio.app.z` release. iOS creates a completely independent application container, sandbox, database, settings, and keychain partition.
- **App Slot Accounting (Free Apple Accounts)**: Free Apple Developer accounts allow a maximum of **3 active sideloaded apps**.
  - SideStore: 1 slot
  - Nuvio Z (Stable): 1 slot
  - Nuvio Z Debug: 1 slot
  - *Installing both stable and debug releases alongside SideStore will consume all 3 available slots (3/3).*

### Launching in Developer Mode

To run the bootstrap scripts targeting the Developer Channel:

**Windows (PowerShell):**
```powershell
powershell -ExecutionPolicy Bypass -File distribution\sidestore\setup-windows.ps1 -DeveloperMode
```

**macOS (Terminal):**
```bash
./distribution/sidestore/setup-macos.sh --developer
```

The script will prompt for confirmation explaining the 3-app slot usage before switching the source to `source-debug.json`.

---

## Safety & Privacy

- **No Credential Access**: Nuvio Z setup scripts never request, prompt for, echo, intercept, or store your Apple Account email or password.
- **Official Tools Only**: All Apple authentication occurs within the official, open-source [iloader](https://github.com/nab138/iloader) tool directly communicating with Apple's servers.
- **No System Security Tampering**: The setup scripts do not disable OS protections or bypass Gatekeeper quarantines automatically. They run standard system utilities and provide guidance for user approval.

---

## Troubleshooting & FAQ

### macOS: "iloader cannot be opened because Apple cannot check it for malicious software"
- Open macOS **System Settings** → **Privacy & Security**.
- Scroll down to the **Security** section.
- Beside *"iloader was blocked from use because it is not from an identified developer"*, click **Open Anyway**.
- Enter your Mac admin password and click **Open**.

### "Pairing file is invalid" or SideStore fails to refresh after iOS update
- SideStore relies on a pairing file generated during initial setup. This file may expire after iOS updates, device resets, or Apple pairing lifecycle expirations.
- If this occurs, reconnect your iPhone to your computer via USB and run iloader to re-pair SideStore.

### iPhone not detected by computer
- Ensure you are using an Apple-certified or high-quality data cable (some cheap cables are charge-only).
- Make sure the iPhone is unlocked when plugged in.
- On Windows, ensure `Apple Mobile Device Service` is running (re-run `setup-windows.ps1 -DiagnosticOnly` to check).
- Unplug the cable, wait 5 seconds, and reconnect.

### "Untrusted Developer" prompt doesn't open the app
- Go to iPhone **Settings** → **General** → **VPN & Device Management**.
- Find your Apple Account email under **Developer App** and tap **Trust**.

### Developer Mode option not appearing in Privacy & Security (iOS 16+)
- Developer Mode only becomes visible in iOS Settings *after* an app has been installed via a developer tool like iloader.
- Once iloader installs SideStore, open Settings → Privacy & Security → scroll down to the bottom. If it is still missing, restart your iPhone.

### "Maximum number of active apps reached"
- Free Apple Developer accounts allow up to **3 active sideloaded apps** at any given time.
- SideStore counts as 1 app. Nuvio Z counts as 1 app. You have 1 remaining slot for another app.
- If you have other sideloaded apps installed (e.g. from AltStore or Sideloadly), deactivate or remove one to make room.

### SideStore fails to refresh apps ("Unable to connect to LocalDevVPN")
- Open the **LocalDevVPN** app and verify the toggle is **Connected** (the VPN badge must appear in the status bar).
- Check that your iPhone is connected to an active Wi-Fi network (cellular is not sufficient).
- Restart the SideStore app.
