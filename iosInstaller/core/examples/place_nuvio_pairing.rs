//! `cargo run -p nuvioz-installer-core --example place_nuvio_pairing [--debug]`: puts the pairing file into
//! Nuvio Z on the USB-connected iPhone, for the Phase 2 on-device spike.
#[tokio::main]
async fn main() {
    nuvioz_installer_core::init();
    let debug = std::env::args().any(|a| a == "--debug");
    match nuvioz_installer_core::pairing::place_for_nuvio(debug).await {
        Ok(p) => println!("placed {} file(s) for iOS {}", p.files, p.ios),
        Err(e) => println!("failed: {e}"),
    }
}
