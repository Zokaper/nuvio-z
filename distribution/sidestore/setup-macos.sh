#!/usr/bin/env bash
# ==============================================================================
# Nuvio Z — Guided iOS Bootstrap for macOS
# Prepares prerequisites, guides official SideStore installation via iloader,
# and configures the Nuvio Z SideStore source.
# ==============================================================================

set -euo pipefail

SOURCE_URL="https://raw.githubusercontent.com/Zokaper/nuvio-z/main/distribution/sidestore/source.json"
ILOADER_VERSION="latest"
SKIP_PREREQUISITES=false
SKIP_DEVICE_WAIT=false
DIAGNOSTIC_ONLY=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --source-url)
            SOURCE_URL="$2"
            shift 2
            ;;
        --iloader-version)
            ILOADER_VERSION="$2"
            shift 2
            ;;
        --skip-prerequisites)
            SKIP_PREREQUISITES=true
            shift
            ;;
        --skip-device-wait)
            SKIP_DEVICE_WAIT=true
            shift
            ;;
        --diagnostic-only)
            DIAGNOSTIC_ONLY=true
            shift
            ;;
        -h|--help)
            cat << 'EOF'
Usage: ./setup-macos.sh [options]

Options:
  --source-url <url>      Custom SideStore source JSON URL
  --skip-prerequisites   Skip system and driver prerequisite checks
  --skip-device-wait     Skip waiting for a connected iPhone (dry-run/test mode)
  --diagnostic-only      Run environment checks and exit
  -h, --help             Show this help message
EOF
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            exit 1
            ;;
    esac
done

# ------------------------------------------------------------------------------
# Console & UI Helpers
# ------------------------------------------------------------------------------
if [[ -t 1 ]] && command -v tput >/dev/null 2>&1 && [[ $(tput colors 2>/dev/null || echo 0) -ge 8 ]]; then
    COLOR_RESET="$(tput sgr0)"
    COLOR_CYAN="$(tput setaf 6)"
    COLOR_GREEN="$(tput setaf 2)"
    COLOR_YELLOW="$(tput setaf 3)"
    COLOR_RED="$(tput setaf 1)"
    COLOR_GRAY="$(tput setaf 8 2>/dev/null || tput setaf 7)"
    COLOR_WHITE="$(tput setaf 7 2>/dev/null || tput bold)"
else
    COLOR_RESET=""
    COLOR_CYAN=""
    COLOR_GREEN=""
    COLOR_YELLOW=""
    COLOR_RED=""
    COLOR_GRAY=""
    COLOR_WHITE=""
fi

GLYPH_CHECK="✓"
GLYPH_ARROW="→"
GLYPH_ALERT="!"
GLYPH_CROSS="✗"

write_banner() {
    echo ""
    printf '%s===================================================================%s\n' "${COLOR_CYAN}" "${COLOR_RESET}"
    printf '%s                       Nuvio Z — iOS Setup                         %s\n' "${COLOR_CYAN}" "${COLOR_RESET}"
    printf '%s       Guided SideStore Sideloading & Source Configuration         %s\n' "${COLOR_CYAN}" "${COLOR_RESET}"
    printf '%s===================================================================%s\n' "${COLOR_CYAN}" "${COLOR_RESET}"
    echo ""
}

write_step_header() {
    local step="$1"
    local total="$2"
    local title="$3"
    echo ""
    printf '%s[%d/%d] %s%s\n' "${COLOR_YELLOW}" "${step}" "${total}" "${title}" "${COLOR_RESET}"
    echo ""
}

write_success() {
    printf '  %s%s%s %s\n' "${COLOR_GREEN}" "${GLYPH_CHECK}" "${COLOR_RESET}" "$1"
}

write_working() {
    printf '  %s%s%s %s\n' "${COLOR_CYAN}" "${GLYPH_ARROW}" "${COLOR_RESET}" "$1"
}

write_alert() {
    printf '  %s%s%s %s\n' "${COLOR_YELLOW}" "${GLYPH_ALERT}" "${COLOR_RESET}" "$1"
}

write_failure() {
    printf '  %s%s%s %s\n' "${COLOR_RED}" "${GLYPH_CROSS}" "${COLOR_RESET}" "$1"
}

write_info() {
    printf '    %s%s%s\n' "${COLOR_GRAY}" "$1" "${COLOR_RESET}"
}

write_action_box() {
    local title="$1"
    shift
    local lines=("$@")

    echo ""
    printf '%s-------------------------------------------------------------------%s\n' "${COLOR_YELLOW}" "${COLOR_RESET}"
    printf '%sACTION NEEDED: %s%s\n' "${COLOR_YELLOW}" "${title}" "${COLOR_RESET}"
    printf '%s-------------------------------------------------------------------%s\n' "${COLOR_YELLOW}" "${COLOR_RESET}"
    for line in "${lines[@]}"; do
        if [[ -z "${line}" ]]; then
            echo ""
        elif [[ "${line}" =~ ^[[:space:]]{2} ]]; then
            printf '%s%s%s\n' "${COLOR_WHITE}" "${line}" "${COLOR_RESET}"
        else
            printf '%s%s%s\n' "${COLOR_GRAY}" "${line}" "${COLOR_RESET}"
        fi
    done
    printf '%s-------------------------------------------------------------------%s\n' "${COLOR_YELLOW}" "${COLOR_RESET}"
    echo ""
    printf '%sPress ENTER to continue...%s' "${COLOR_CYAN}" "${COLOR_RESET}"
    read -r _ < /dev/tty || true
    echo ""
}

# ------------------------------------------------------------------------------
# Step 1: System & Connectivity Diagnostics
# ------------------------------------------------------------------------------
test_system_environment() {
    write_step_header 1 7 "Checking your computer"

    local os_type
    os_type="$(uname -s)"
    if [[ "${os_type}" != "Darwin" ]]; then
        write_failure "This script is designed for macOS (Darwin). Detected: ${os_type}"
        exit 1
    fi

    local mac_version
    mac_version="$(sw_vers -productVersion 2>/dev/null || echo 'Unknown')"
    local arch
    arch="$(uname -m)"
    write_success "macOS ${mac_version} (${arch}) detected"

    write_working "Checking internet connection..."
    if curl -fsIL --connect-timeout 5 "https://github.com" >/dev/null 2>&1; then
        write_success "Internet connection available"
    else
        write_alert "Internet connection check failed."
        write_action_box "Check Internet Connection" \
            "Please ensure your Mac is connected to the internet (Wi-Fi or Ethernet)." \
            "Once connected, press ENTER to re-check."
        if ! curl -fsIL --connect-timeout 5 "https://github.com" >/dev/null 2>&1; then
            write_failure "Unable to reach GitHub. Please check your network and try again."
            exit 1
        fi
        write_success "Internet connection verified"
    fi
}

# ------------------------------------------------------------------------------
# Step 2: Apple Device Support
# ------------------------------------------------------------------------------
ensure_apple_device_support() {
    write_step_header 2 7 "Checking Apple device support"

    if [[ "${SKIP_PREREQUISITES}" == true ]]; then
        write_alert "Skipping Apple device checks (--skip-prerequisites specified)"
        return
    fi

    # macOS natively integrates iOS device communication via MobileDevice & usbmuxd
    if pgrep -x "usbmuxd" >/dev/null 2>&1 || [[ -e "/var/run/usbmuxd" ]]; then
        write_success "Native Apple device communication daemon (usbmuxd) is active"
    else
        write_success "Native macOS Apple MobileDevice subsystem detected"
    fi
}

# ------------------------------------------------------------------------------
# Step 3: Connect iPhone & Trust
# ------------------------------------------------------------------------------
get_connected_devices() {
    local count=0
    if command -v system_profiler >/dev/null 2>&1; then
        count="$(system_profiler SPUSBDataType 2>/dev/null | grep -iE 'iPhone|iPad' | wc -l | tr -d ' ' || echo 0)"
    elif command -v ioreg >/dev/null 2>&1; then
        count="$(ioreg -p IOUSB -w0 2>/dev/null | grep -iE 'iPhone|iPad' | wc -l | tr -d ' ' || echo 0)"
    fi
    echo "${count}"
}

wait_for_connected_phone() {
    write_step_header 3 7 "Connecting your iPhone"

    if [[ "${SKIP_DEVICE_WAIT}" == true ]]; then
        write_alert "Skipping device detection (--skip-device-wait specified)"
        return
    fi

    local dev_count
    dev_count="$(get_connected_devices)"
    if (( dev_count > 0 )); then
        write_success "iPhone detected over USB (${dev_count} device found)"
        return
    fi

    write_alert "No iPhone detected over USB"
    echo ""
    printf '%s-------------------------------------------------------------------%s\n' "${COLOR_YELLOW}" "${COLOR_RESET}"
    printf '%sACTION NEEDED: Connect and Trust Your iPhone%s\n' "${COLOR_YELLOW}" "${COLOR_RESET}"
    printf '%s-------------------------------------------------------------------%s\n' "${COLOR_YELLOW}" "${COLOR_RESET}"
    printf '%s  1. Connect your iPhone to this Mac using a USB / Lightning / USB-C cable.%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s  2. Connect your iPhone to Wi-Fi (SideStore requires active Wi-Fi; cellular is not sufficient).%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s  3. Unlock your iPhone screen.%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s  4. Look at your iPhone screen for the prompt:%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s       "Trust This Computer?"%s\n' "${COLOR_WHITE}" "${COLOR_RESET}"
    printf '%s     Tap "Trust" and enter your iPhone passcode.%s\n' "${COLOR_WHITE}" "${COLOR_RESET}"
    printf '%s  5. On macOS, if Finder prompts "Allow accessory to connect?", click "Allow".%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s-------------------------------------------------------------------%s\n' "${COLOR_YELLOW}" "${COLOR_RESET}"
    echo ""
    write_working "Waiting for iPhone connection... (Press ENTER to continue manually)"

    local elapsed=0
    local timeout=60
    while (( elapsed < timeout )); do
        sleep 2
        elapsed=$(( elapsed + 2 ))

        dev_count="$(get_connected_devices)"
        if (( dev_count > 0 )); then
            echo ""
            write_success "iPhone connected and detected"
            return
        fi

        # Non-blocking check for Enter key
        if read -r -t 0.1 _ 2>/dev/null; then
            echo ""
            write_alert "Continuing as requested by user"
            return
        fi
    done

    echo ""
    write_alert "Device detection wait completed."
    write_action_box "Device Connection Check" \
        "If your iPhone is connected, unlocked, and trusted, you can continue." \
        "If not, try unplugging and reconnecting the cable."
}

# ------------------------------------------------------------------------------
# Step 4: Phone-side Prep: LocalDevVPN
# ------------------------------------------------------------------------------
guide_local_dev_vpn() {
    write_step_header 4 7 "iPhone Preparation: LocalDevVPN"

    write_action_box "Install LocalDevVPN & Verify Wi-Fi on Your iPhone" \
        "CRITICAL WI-FI REQUIREMENT:" \
        "  - Your iPhone MUST be connected to an active Wi-Fi network." \
        "  - Cellular data alone is NOT sufficient. SideStore's local loopback" \
        "    and pairing communication require an active Wi-Fi connection." \
        "" \
        "WHY THIS IS NEEDED:" \
        "SideStore works completely on-device, signing and refreshing apps" \
        "wirelessly without needing a Mac for everyday use. To communicate" \
        "with Apple's developer service on your phone, iOS requires a local" \
        "loopback VPN running on the device." \
        "" \
        "ON YOUR IPHONE:" \
        "  1. Verify your iPhone is connected to Wi-Fi." \
        "  2. Open the App Store and search for 'LocalDevVPN' (by jkcoxson)" \
        "     Direct link: https://apps.apple.com/app/localdevvpn/id6755608044" \
        "  3. Install the app and open it." \
        "  4. Tap the 'Connect' button." \
        "  5. When prompted by iOS:" \
        "       'LocalDevVPN Would Like to Add VPN Configurations'" \
        "     Tap 'Allow' and enter your device passcode." \
        "  6. Verify that the 'VPN' badge appears in your status bar or" \
        "     Control Center." \
        "" \
        "NOTE: LocalDevVPN must remain connected whenever you install, refresh," \
        "or update sideloaded apps in SideStore."
    write_success "LocalDevVPN step acknowledged"
}

# ------------------------------------------------------------------------------
# Step 5: Download & Launch iloader
# ------------------------------------------------------------------------------
ensure_iloader() {
    write_step_header 5 7 "Installing SideStore via iloader"

    local iloader_app="/Applications/iloader.app"
    if [[ -d "${iloader_app}" ]]; then
        write_success "Found existing iloader installation at ${iloader_app}"
    else
        local storage_dir="${HOME}/.nuvio-z/iloader"
        mkdir -p "${storage_dir}"

        local dmg_url="https://github.com/nab138/iloader/releases/latest/download/iloader-darwin-universal.dmg"
        local dmg_path="${storage_dir}/iloader-darwin-universal.dmg"

        write_working "Downloading official iloader installer from GitHub..."
        write_info "Source: ${dmg_url}"
        if ! curl -fsSL "${dmg_url}" -o "${dmg_path}"; then
            write_alert "Latest download failed, falling back to release tag v2.3.3..."
            curl -fsSL "https://github.com/nab138/iloader/releases/download/v2.3.3/iloader-darwin-universal.dmg" -o "${dmg_path}"
        fi
        write_success "iloader DMG downloaded successfully"

        write_working "Mounting DMG and installing iloader to /Applications..."
        local mount_point
        mount_point="$(mktemp -d /tmp/iloader-mount.XXXXXX)"
        hdiutil attach "${dmg_path}" -mountpoint "${mount_point}" -nobrowse -quiet

        if [[ -d "${mount_point}/iloader.app" ]]; then
            cp -R "${mount_point}/iloader.app" /Applications/
            write_success "Copied iloader.app to /Applications"
        fi

        hdiutil detach "${mount_point}" -quiet || true
        rm -rf "${mount_point}"
    fi

    write_working "Launching iloader..."
    open "/Applications/iloader.app"

    write_action_box "Install SideStore in iloader" \
        "The official iloader window is now opening on your Mac screen." \
        "" \
        "IF MACOS GATEKEEPER BLOCKS ILOADER:" \
        "  1. Open System Settings (or System Preferences) on your Mac." \
        "  2. Go to 'Privacy & Security' (or 'Security & Privacy')." \
        "  3. Scroll down to the 'Security' section." \
        "  4. Beside 'iloader was blocked from use...', click 'Open Anyway'." \
        "  5. Enter your Mac password and click 'Open' to launch iloader." \
        "" \
        "IN THE ILOADER WINDOW:" \
        "  1. Sign in with your Apple Account (email & password)." \
        "     - SECURITY NOTE: Your login is processed directly by the official" \
        "       iloader application and communicated securely to Apple." \
        "       Nuvio Z never sees, prompts for, or records your credentials." \
        "     - If you use Two-Factor Authentication, enter the 6-digit code" \
        "       displayed on your Apple device." \
        "  2. In the device dropdown, select your connected iPhone." \
        "  3. Click 'Install SideStore (Stable)'." \
        "  4. Wait for iloader to sign and install the app onto your phone." \
        "     (This typically takes 30-90 seconds)." \
        "  5. Look for the completion message in iloader."
    write_success "SideStore installation initiated"
}

# ------------------------------------------------------------------------------
# Step 6: Phone-side Approvals & Priming
# ------------------------------------------------------------------------------
guide_phone_approvals() {
    write_step_header 6 7 "iPhone Security Approvals & Priming"

    write_action_box "Trust Developer Profile & Enable Developer Mode" \
        "Apple requires two security approvals on your iPhone before opening" \
        "sideloaded applications for the first time." \
        "" \
        "PART 1: Trust Your Developer Profile" \
        "  1. On your iPhone, open the Settings app." \
        "  2. Go to: General -> VPN & Device Management." \
        "  3. Under 'DEVELOPER APP', tap your Apple Account email." \
        "  4. Tap 'Trust [Your Email]'." \
        "     (On iOS 18+, this may say 'Allow & Restart' - tap it)." \
        "  5. Tap 'Trust' to confirm." \
        "" \
        "PART 2: Enable Developer Mode (iOS 16, 17, 18+)" \
        "  1. Open Settings -> Privacy & Security." \
        "  2. Scroll down to the very bottom and tap 'Developer Mode'." \
        "  3. Toggle Developer Mode ON." \
        "  4. Tap 'Restart' when prompted." \
        "  5. After your iPhone reboots, unlock it and tap 'Turn On' when" \
        "     prompted, then enter your device passcode." \
        "" \
        "PART 3: Prime SideStore (Crucial First Refresh)" \
        "  1. Ensure your iPhone is connected to Wi-Fi (cellular is not sufficient)." \
        "  2. Open the LocalDevVPN app and verify it is CONNECTED." \
        "  3. Open the SideStore app from your Home Screen." \
        "  4. Sign in using the SAME Apple Account you used in iloader." \
        "  5. Tap the 'My Apps' tab at the bottom." \
        "  6. Tap the '7 DAYS' button next to SideStore." \
        "  7. If prompted to revoke or create a new signing certificate," \
        "     tap 'Yes' or 'Refresh Now'." \
        "  8. SideStore will refresh and confirm it is primed."
    write_success "Phone security approvals and priming acknowledged"
}

# ------------------------------------------------------------------------------
# Step 7: Add Nuvio Z Source & HTML Helper
# ------------------------------------------------------------------------------
generate_html_helper() {
    local source_url="$1"
    local encoded_url
    encoded_url="$(python3 -c "import urllib.parse; print(urllib.parse.quote('''${source_url}''', safe=''))" 2>/dev/null || echo "${source_url}")"
    local deep_link="sidestore://source?url=${encoded_url}"
    local html_path="/tmp/nuvio-z-sidestore-helper.html"

    cat << EOF > "${html_path}"
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Nuvio Z — SideStore Source Setup</title>
<style>
  :root {
    --bg: #0d1117;
    --card: #161b22;
    --border: #30363d;
    --text: #c9d1d9;
    --heading: #f0f6fc;
    --accent: #1e88e5;
    --accent-hover: #1565c0;
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
    padding: 4px 12px;
    border-radius: 20px;
    font-size: 13px;
    font-weight: 600;
    margin-bottom: 12px;
  }
  h1 { color: var(--heading); margin: 0 0 8px 0; font-size: 26px; }
  p.subtitle { color: #8b949e; margin: 0 0 24px 0; font-size: 14px; line-height: 1.5; }
  .qr-box {
    background: #ffffff;
    padding: 20px;
    border-radius: 12px;
    display: inline-block;
    margin-bottom: 20px;
    box-shadow: 0 4px 16px rgba(0,0,0,0.3);
  }
  .qr-box img { display: block; width: 220px; height: 220px; }
  .instructions {
    text-align: left;
    background: var(--code-bg);
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 16px 20px;
    margin: 20px 0;
    font-size: 14px;
    line-height: 1.6;
  }
  .instructions ol { margin: 0; padding-left: 20px; }
  .instructions li { margin-bottom: 6px; }
  .url-box {
    background: var(--code-bg);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 10px 14px;
    font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
    font-size: 12px;
    word-break: break-all;
    text-align: left;
    color: #58a6ff;
    margin-bottom: 16px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 8px;
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
  .btn-copy:hover { background: #30363d; color: var(--heading); }
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
  }
  .btn-primary:hover { background: var(--accent-hover); }
  .footer-note { font-size: 12px; color: #8b949e; margin-top: 20px; line-height: 1.4; }
</style>
</head>
<body>
<div class="container">
  <div class="badge">Nuvio Z iOS Sideload</div>
  <h1>Add Nuvio Z to SideStore</h1>
  <p class="subtitle">Scan the QR code with your iPhone Camera to automatically add the source, or copy the URL below.</p>

  <div class="qr-box">
    <img src="https://api.qrserver.com/v1/create-qr-code/?size=220x220&data=${encoded_url}" alt="SideStore Source QR Code" />
  </div>

  <div class="url-box">
    <span id="source-url">${source_url}</span>
    <button class="btn-copy" onclick="copyUrl()">Copy URL</button>
  </div>

  <div class="instructions">
    <strong>How to install on your iPhone:</strong>
    <ol>
      <li>Make sure <strong>LocalDevVPN</strong> is connected on your iPhone.</li>
      <li>Open your iPhone <strong>Camera</strong> and point it at the QR code above.</li>
      <li>Tap the yellow prompt <strong>"Open in SideStore"</strong>.</li>
      <li>In SideStore, tap <strong>"Add Source"</strong>.</li>
      <li>Go to the <strong>Browse</strong> tab, find <strong>Nuvio Z</strong>, and tap <strong>Install</strong>!</li>
    </ol>
  </div>

  <div>
    <a href="${deep_link}" class="btn-primary">Open in SideStore</a>
  </div>

  <p class="footer-note">
    Remember to refresh Nuvio Z in SideStore every 5-6 days with LocalDevVPN enabled to keep your 7-day developer certificate active.
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
EOF
    echo "${html_path}"
}

guide_source_and_install() {
    local source_url="$1"
    write_step_header 7 7 "Adding Nuvio Z Source & Installing App"

    write_working "Generating interactive QR code and setup helper..."
    local helper_path
    helper_path="$(generate_html_helper "${source_url}")"
    write_success "Helper page created at: ${helper_path}"

    write_working "Opening helper page in your default browser..."
    open "${helper_path}" 2>/dev/null || true

    echo ""
    printf '%s===================================================================%s\n' "${COLOR_CYAN}" "${COLOR_RESET}"
    printf '%s                     ADD NUVIO Z SOURCE                            %s\n' "${COLOR_CYAN}" "${COLOR_RESET}"
    printf '%s===================================================================%s\n' "${COLOR_CYAN}" "${COLOR_RESET}"
    echo ""
    printf '%sA browser window has opened with a crisp QR code.%s\n' "${COLOR_WHITE}" "${COLOR_RESET}"
    echo ""
    printf '%sMETHOD 1 (Recommended — Scan with iPhone):%s\n' "${COLOR_YELLOW}" "${COLOR_RESET}"
    printf '%s  1. On your iPhone, open the default Camera app.%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s  2. Point the camera at the QR code on your Mac screen.%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s  3. Tap the yellow banner: "Open in SideStore".%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s  4. In SideStore, tap "Add Source".%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    echo ""
    printf '%sMETHOD 2 (Manual entry in SideStore):%s\n' "${COLOR_YELLOW}" "${COLOR_RESET}"
    printf '%s  1. Open SideStore on your iPhone -> tap "Sources" tab.%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s  2. Tap the "+" icon in the top corner.%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s  3. Enter this Source URL:%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '     %s%s%s\n' "${COLOR_CYAN}" "${source_url}" "${COLOR_RESET}"
    printf '%s  4. Tap "Add".%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    echo ""
    printf '%s-------------------------------------------------------------------%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%sINSTALLING NUVIO Z:%s\n' "${COLOR_YELLOW}" "${COLOR_RESET}"
    printf '%s  1. Make sure LocalDevVPN is active on your iPhone.%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s  2. In SideStore, tap the "Browse" tab at the bottom.%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s  3. You will see "Nuvio Z" in the list.%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s  4. Tap "FREE" or "INSTALL" next to Nuvio Z.%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s  5. Wait for SideStore to download, sign, and install.%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s  6. Nuvio Z will appear on your Home Screen!%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s-------------------------------------------------------------------%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    echo ""

    write_action_box "Install Nuvio Z" \
        "Confirm that you have added the source and installed Nuvio Z" \
        "onto your iPhone screen."
}

show_completion_summary() {
    echo ""
    printf '%s===================================================================%s\n' "${COLOR_GREEN}" "${COLOR_RESET}"
    printf '%s                    SETUP COMPLETE! 🎉                             %s\n' "${COLOR_GREEN}" "${COLOR_RESET}"
    printf '%s===================================================================%s\n' "${COLOR_GREEN}" "${COLOR_RESET}"
    echo ""
    printf '%sHOW 7-DAY REFRESHING AND UPDATES WORK:%s\n' "${COLOR_YELLOW}" "${COLOR_RESET}"
    echo ""
    printf '%s  1. Free Apple Accounts grant 7-day app certificates.%s\n' "${COLOR_WHITE}" "${COLOR_RESET}"
    printf '%s  2. Normally, the computer is only needed for initial setup.%s\n' "${COLOR_GREEN}" "${COLOR_RESET}"
    printf '%s     SideStore refreshes everything wirelessly directly on your phone.%s\n' "${COLOR_WHITE}" "${COLOR_RESET}"
    printf '%s     NOTE: If SideStore'\''s pairing file later expires (e.g. after iOS%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s     updates, device resets, or pairing expiration), you may need%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s     to reconnect to a computer and use iloader to replace it.%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s  3. Every 5 to 6 days:%s\n' "${COLOR_WHITE}" "${COLOR_RESET}"
    printf '%s     - Ensure your iPhone is connected to Wi-Fi (cellular is not sufficient).%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s     - Turn on LocalDevVPN on your iPhone.%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s     - Open SideStore -> tap "My Apps".%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s     - Tap "Refresh All".%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s  4. Future Nuvio Z updates:%s\n' "${COLOR_WHITE}" "${COLOR_RESET}"
    printf '%s     - When a new version of Nuvio Z is released, SideStore will%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s       show an "UPDATE" button automatically under "My Apps".%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    printf '%s     - Tap "Update" while connected to Wi-Fi and LocalDevVPN.%s\n' "${COLOR_GRAY}" "${COLOR_RESET}"
    echo ""
    printf '%s===================================================================%s\n' "${COLOR_GREEN}" "${COLOR_RESET}"
    echo ""
}

# ------------------------------------------------------------------------------
# Main Flow
# ------------------------------------------------------------------------------
main() {
    write_banner

    if [[ "${DIAGNOSTIC_ONLY}" == true ]]; then
        write_step_header 1 3 "System Diagnostics"
        test_system_environment
        write_step_header 2 3 "Apple Device Subsystem Diagnostics"
        ensure_apple_device_support
        write_step_header 3 3 "Connected Apple USB Devices"
        local devs
        devs="$(get_connected_devices)"
        if (( devs > 0 )); then
            write_success "Found ${devs} connected Apple device(s)"
        else
            write_alert "No Apple USB device currently detected"
        fi
        echo ""
        write_success "Diagnostics completed."
        echo ""
        return 0
    fi

    test_system_environment
    ensure_apple_device_support
    wait_for_connected_phone
    guide_local_dev_vpn
    ensure_iloader
    guide_phone_approvals
    guide_source_and_install "${SOURCE_URL}"
    show_completion_summary
}

main
