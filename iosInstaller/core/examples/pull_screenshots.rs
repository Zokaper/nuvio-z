//! `cargo run -p nuvioz-installer-core --example pull_screenshots -- <out-dir> [count=20] [since-unix-seconds]`
//!
//! Copies the newest PNGs (screenshots) from the camera roll of the iPhone on USB, read-only, through AFC (the same
//! service Windows Photos uses). For reading a tester's screenshots of iOS screens without a round trip.
use idevice::{afc::AfcClient, afc::opcode::AfcFopenMode, IdeviceService};
use nuvioz_installer_core::device;

#[tokio::main]
async fn main() {
    nuvioz_installer_core::init();
    let args: Vec<String> = std::env::args().skip(1).collect();
    let out = std::path::PathBuf::from(args.first().expect("out dir"));
    let count: usize = args.get(1).and_then(|s| s.parse().ok()).unwrap_or(20);
    let since: u64 = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(0);
    std::fs::create_dir_all(&out).unwrap();

    let phone = device::connect("nuvioz-pull-screenshots").await.expect("iPhone on USB");
    let mut afc = AfcClient::connect(&phone.provider).await.expect("afc");
    let mut found = Vec::new();
    for dir in afc.list_dir("/DCIM").await.expect("list /DCIM") {
        if dir.starts_with('.') {
            continue;
        }
        let path = format!("/DCIM/{dir}");
        let Ok(files) = afc.list_dir(path.clone()).await else { continue };
        for f in files.into_iter().filter(|f| f.to_lowercase().ends_with(".png")) {
            let full = format!("{path}/{f}");
            let info = afc.get_file_info(full.clone()).await.expect("file info");
            let modified = info.modified.and_utc().timestamp() as u64;
            if modified >= since {
                found.push((modified, full, f));
            }
        }
    }
    found.sort();
    let newest: Vec<_> = found.into_iter().rev().take(count).collect();
    for (modified, full, name) in newest.iter().rev() {
        let mut file = afc.open(full.clone(), AfcFopenMode::RdOnly).await.expect("open");
        let bytes = file.read_entire().await.expect("read");
        file.close().await.ok();
        let dest = out.join(format!("{modified}-{name}"));
        std::fs::write(&dest, &bytes).unwrap();
        println!("{} {} bytes", dest.display(), bytes.len());
    }
    println!("{} screenshots", newest.len());
}
