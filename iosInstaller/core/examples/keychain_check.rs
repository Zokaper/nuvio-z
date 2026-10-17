//! `cargo run -p nuvioz-installer-core --example keychain_check`: the system keychain round-trips a secret.
//! Uses its own throwaway entry, never the installer's saved account.
fn main() {
    let entry = keyring::Entry::new("Nuvio Z iOS Installer (check)", "probe").expect("entry");
    entry.set_password("round-trip").expect("set");
    assert_eq!(entry.get_password().expect("get"), "round-trip");
    entry.delete_credential().expect("delete");
    println!("keychain ok");
}
