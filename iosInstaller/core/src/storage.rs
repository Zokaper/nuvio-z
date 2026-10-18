//! Where isideload keeps its secrets: the anisette identity (`anisette_state`, what Apple knows this device by)
//! and the signing certificate's private key (`<sha256(email)>/key`).
//!
//! On a computer they live in the system keychain (Windows Credential Manager, macOS Keychain), one entry per
//! key. Files an earlier installer left in the `signing` folder are moved in the first time they are read, then
//! deleted. When the keychain refuses a value (Windows caps an entry at 2560 bytes) it stays a file, so signing
//! never breaks over where a secret lives.
//!
//! On the iPhone (Nuvio Z signing itself) they stay files in the app's own Application Support: no other app and
//! no computer can read it, and iOS can read it after the first unlock, which is when a Shortcuts automation or
//! a background refresh runs.
use std::path::PathBuf;

use isideload::util::storage::SideloadingStorage;
use rootcause::prelude::*;
use sha2::{Digest, Sha256};

use crate::paths;

#[cfg(any(target_os = "windows", target_os = "macos"))]
const SERVICE: &str = "Nuvio Z iOS Installer signing";

/// The key isideload stores the anisette identity under.
pub const ANISETTE_STATE: &str = "anisette_state";

/// The anisette server that made the saved identity (see `account::server_order`).
pub const ANISETTE_SERVER: &str = "anisette_server";

/// The last sign-in's server and whether it made a new identity, as JSON, for the reports.
pub const LAST_SIGN_IN: &str = "anisette_last_sign_in";

/// The key isideload stores an account's signing private key under: the SHA-256 of the email it signs in with.
pub fn private_key_name(email: &str) -> String {
    format!("{}/key", email_hash(email))
}

pub fn email_hash(email: &str) -> String {
    hex::encode(Sha256::digest(email.trim().to_lowercase().as_bytes()))
}

pub struct SecretStorage {
    folder: PathBuf,
}

impl SecretStorage {
    pub fn new() -> Self {
        SecretStorage { folder: paths::signing() }
    }

    fn file(&self, key: &str) -> PathBuf {
        self.folder.join(key)
    }

    fn read_file(&self, key: &str) -> Result<Option<Vec<u8>>, Report> {
        match std::fs::read(self.file(key)) {
            Ok(data) => Ok(Some(data)),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(e) => Err(report!("read {key}: {e}").into()),
        }
    }

    fn write_file(&self, key: &str, data: &[u8]) -> Result<(), Report> {
        let path = self.file(key);
        if let Some(dir) = path.parent() {
            std::fs::create_dir_all(dir).map_err(|e| report!("create {}: {e}", dir.display()))?;
        }
        std::fs::write(&path, data).map_err(|e| report!("write {key}: {e}"))?;
        Ok(())
    }

    fn remove_file(&self, key: &str) {
        let _ = std::fs::remove_file(self.file(key));
    }
}

impl Default for SecretStorage {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(any(target_os = "windows", target_os = "macos"))]
fn entry(key: &str) -> Result<keyring::Entry, Report> {
    keyring::Entry::new(SERVICE, key).map_err(|e| report!("keychain: {e}").into())
}

#[cfg(any(target_os = "windows", target_os = "macos"))]
impl SideloadingStorage for SecretStorage {
    fn store_data(&self, key: &str, value: &[u8]) -> Result<(), Report> {
        match entry(key).and_then(|e| e.set_secret(value).map_err(|e| report!("keychain: {e}").into())) {
            Ok(()) => {
                self.remove_file(key);
                Ok(())
            }
            Err(_) => self.write_file(key, value),
        }
    }

    fn retrieve_data(&self, key: &str) -> Result<Option<Vec<u8>>, Report> {
        match entry(key)?.get_secret() {
            Ok(secret) => return Ok(Some(secret)),
            Err(keyring::Error::NoEntry) => {}
            Err(e) => return Err(report!("keychain: {e}").into()),
        }
        // Left by an earlier installer (or kept because the keychain refused it): move it in when it fits.
        let Some(data) = self.read_file(key)? else { return Ok(None) };
        if entry(key)?.set_secret(&data).is_ok() {
            self.remove_file(key);
        }
        Ok(Some(data))
    }

    fn store(&self, key: &str, value: &str) -> Result<(), Report> {
        self.store_data(key, value.as_bytes())
    }

    fn retrieve(&self, key: &str) -> Result<Option<String>, Report> {
        Ok(self.retrieve_data(key)?.map(|d| String::from_utf8_lossy(&d).into_owned()))
    }

    fn delete(&self, key: &str) -> Result<(), Report> {
        match entry(key)?.delete_credential() {
            Ok(()) | Err(keyring::Error::NoEntry) => {}
            Err(e) => return Err(report!("keychain: {e}").into()),
        }
        self.remove_file(key);
        Ok(())
    }
}

#[cfg(not(any(target_os = "windows", target_os = "macos")))]
impl SideloadingStorage for SecretStorage {
    fn store_data(&self, key: &str, value: &[u8]) -> Result<(), Report> {
        self.write_file(key, value)
    }

    fn retrieve_data(&self, key: &str) -> Result<Option<Vec<u8>>, Report> {
        self.read_file(key)
    }

    fn store(&self, key: &str, value: &str) -> Result<(), Report> {
        self.write_file(key, value.as_bytes())
    }

    fn retrieve(&self, key: &str) -> Result<Option<String>, Report> {
        Ok(self.read_file(key)?.map(|d| String::from_utf8_lossy(&d).into_owned()))
    }

    fn delete(&self, key: &str) -> Result<(), Report> {
        self.remove_file(key);
        Ok(())
    }
}

/// "Reset sign-in data": forget the anisette identity, so Apple sees a fresh device on the next sign-in (the fix
/// for -22410 and most "try again" refusals). The signing key stays: dropping it would make the next install ask
/// Apple for another certificate and revoke the one the installed apps are signed with.
pub fn reset_sign_in() -> Result<(), String> {
    let store = SecretStorage::new();
    let _ = store.delete(ANISETTE_SERVER);
    store.delete(ANISETTE_STATE).map_err(|e| format!("{e}"))
}

/// The account's signing private key (PKCS#8 DER), if this device has made one.
pub fn private_key(email: &str) -> Option<Vec<u8>> {
    SecretStorage::new().retrieve_data(&private_key_name(email)).ok().flatten()
}

/// Whether any account's signing key is stored as a file here: on the iPhone, whether the installer's key arrived
/// (it is imported before an account is saved, so the email is not known yet).
pub fn any_private_key_file() -> bool {
    std::fs::read_dir(paths::signing()).map(|dirs| dirs.filter_map(Result::ok).any(|d| d.path().join("key").is_file())).unwrap_or(false)
}

/// Whether this device has an anisette identity yet (for diagnostics; never its contents).
pub fn has_anisette_state() -> bool {
    SecretStorage::new().retrieve_data(ANISETTE_STATE).ok().flatten().is_some_and(|d| !d.is_empty())
}

/// Stores a private key handed over by the computer that installed the app, under the hash it was made for.
pub fn put_private_key(email_hash: &str, key: &[u8]) -> Result<(), String> {
    if email_hash.len() != 64 || !email_hash.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err("signing key: not an email hash".into());
    }
    SecretStorage::new().store_data(&format!("{email_hash}/key"), key).map_err(|e| format!("{e}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn key_names_match_isideload() {
        // isideload hashes the email exactly as given; sign-in hands it over trimmed and lowercased.
        assert_eq!(email_hash(" You@Example.com "), email_hash("you@example.com"));
        assert_eq!(private_key_name("you@example.com"), format!("{}/key", hex::encode(Sha256::digest(b"you@example.com"))));
        assert!(put_private_key("not-a-hash", b"x").is_err());
    }

    /// A file an earlier installer left is moved into the keychain on first read, and delete clears both.
    /// Uses a key of its own in the real keychain and removes it again.
    #[cfg(any(target_os = "windows", target_os = "macos"))]
    #[test]
    fn moves_a_left_over_file_into_the_keychain() {
        let folder = std::env::temp_dir().join(format!("nuvioz-storage-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&folder);
        let store = SecretStorage { folder: folder.clone() };
        let key = format!("selftest-{}", std::process::id());
        store.write_file(&key, b"left over").unwrap();
        assert_eq!(store.retrieve_data(&key).unwrap().as_deref(), Some(&b"left over"[..]));
        assert!(!folder.join(&key).exists(), "the file is gone once it is in the keychain");
        assert_eq!(store.retrieve_data(&key).unwrap().as_deref(), Some(&b"left over"[..]));
        store.delete(&key).unwrap();
        assert_eq!(store.retrieve_data(&key).unwrap(), None);
        let _ = std::fs::remove_dir_all(&folder);
    }
}
