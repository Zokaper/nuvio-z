//! "Remember me": the Apple Account password in the system keychain (Windows Credential Manager, macOS
//! Keychain), keyed by email, the way iloader keeps it. Only the email is ever shown to the UI; the password
//! goes from the keychain straight to sign-in.
//!
//! Apple rarely asks for a 2FA code again on a later run, because the anisette identity in `paths::signing()`
//! stays the same.
use keyring::Entry;

const SERVICE: &str = "Nuvio Z iOS Installer";

fn last_account_file() -> std::path::PathBuf {
    crate::paths::data_dir().join("last-account")
}

fn entry(email: &str) -> Result<Entry, String> {
    Entry::new(SERVICE, email).map_err(|e| format!("keychain: {e}"))
}

pub fn remember(email: &str, password: &str) -> Result<(), String> {
    let email = email.trim().to_lowercase();
    entry(&email)?.set_password(password).map_err(|e| format!("keychain: {e}"))?;
    let file = last_account_file();
    if let Some(dir) = file.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    std::fs::write(file, &email).map_err(|e| format!("remember account: {e}"))
}

/// The remembered email, if its password is still in the keychain.
pub fn saved_email() -> Option<String> {
    let email = std::fs::read_to_string(last_account_file()).ok()?.trim().to_string();
    entry(&email).ok()?.get_password().ok().map(|_| email)
}

pub fn saved_password(email: &str) -> Option<String> {
    entry(&email.trim().to_lowercase()).ok()?.get_password().ok()
}

pub fn forget() {
    if let Ok(email) = std::fs::read_to_string(last_account_file()) {
        if let Ok(e) = entry(email.trim()) {
            let _ = e.delete_credential();
        }
    }
    let _ = std::fs::remove_file(last_account_file());
}
