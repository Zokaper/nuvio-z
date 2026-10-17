// No console window behind the app in release builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use nuvioz_installer_core::{anisette, device};

#[tauri::command]
async fn probe_device() -> device::DeviceSnapshot {
    device::probe().await
}

#[tauri::command]
async fn check_anisette() -> anisette::AnisetteCheck {
    anisette::check(anisette::DEFAULT_SERVER).await
}

fn main() {
    nuvioz_installer_core::init();
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![probe_device, check_anisette])
        .run(tauri::generate_context!())
        .expect("failed to start the Nuvio Z iOS Installer");
}
