//! Where the installer keeps its state on this computer. Per user, never shared.
use std::path::PathBuf;

fn per_user_base() -> Option<PathBuf> {
    std::env::var_os("LOCALAPPDATA").map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join("Library").join("Application Support")))
}

/// `%LOCALAPPDATA%\Nuvio Z iOS Installer` on Windows, `~/Library/Application Support/Nuvio Z iOS Installer` on macOS.
pub fn data_dir() -> PathBuf {
    per_user_base().unwrap_or_else(std::env::temp_dir).join("Nuvio Z iOS Installer")
}

/// isideload's anisette state and the signing certificate it creates. Secrets.
pub fn signing() -> PathBuf {
    data_dir().join("signing")
}

pub fn downloads() -> PathBuf {
    data_dir().join("downloads")
}

/// Where the Phase 2 test path looks for a Nuvio Z Debug IPA to install instead of SideStore: next to the
/// installer first (so a tester gets one zip with both), then the downloads folder.
pub fn nuvio_debug_test_ipa() -> PathBuf {
    const NAME: &str = "NuvioZ-Debug-test.ipa";
    std::env::current_exe().ok()
        .and_then(|exe| exe.parent().map(|dir| dir.join(NAME)))
        .filter(|p| p.is_file())
        .unwrap_or_else(|| downloads().join(NAME))
}

/// The rppairing identity for one phone. Deliberately the iOS Setup 2.x location, so a phone set up with
/// the old assistant keeps the same host identity instead of collecting a second one.
pub fn rp_identity(udid: &str) -> Option<PathBuf> {
    let key: String = udid.chars().filter(|c| c.is_ascii_alphanumeric()).take(12).collect();
    Some(per_user_base()?.join("Nuvio Z iOS Setup").join("pairing").join(format!("rp-{key}.plist")))
}
