//! `cargo run -p nuvioz-installer-core --example probe`: what the installer sees, without the UI.
#[tokio::main]
async fn main() {
    nuvioz_installer_core::init();
    let device = nuvioz_installer_core::device::probe().await;
    println!("{}", serde_json::to_string_pretty(&device).unwrap());
    let server = nuvioz_installer_core::anisette::check(nuvioz_installer_core::anisette::DEFAULT_SERVER).await;
    println!("{}", serde_json::to_string_pretty(&server).unwrap());
}
