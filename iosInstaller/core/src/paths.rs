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

/// A Nuvio Z IPA sitting next to the installer, which the direct install uses instead of the hosted build (so a
/// tester gets one zip with both).
pub fn local_nuvio_ipa() -> Option<PathBuf> {
    const NAMES: &[&str] = &["NuvioZ-Debug-test.ipa", "NuvioZ.ipa"];
    let dir = std::env::current_exe().ok()?.parent()?.to_path_buf();
    NAMES.iter().map(|n| dir.join(n)).find(|p| p.is_file())
}

/// The rppairing identity for one phone. Deliberately the iOS Setup 2.x location, so a phone set up with
/// the old assistant keeps the same host identity instead of collecting a second one.
pub fn rp_identity(udid: &str) -> Option<PathBuf> {
    let key: String = udid.chars().filter(|c| c.is_ascii_alphanumeric()).take(12).collect();
    Some(per_user_base()?.join("Nuvio Z iOS Setup").join("pairing").join(format!("rp-{key}.plist")))
}
