//! Installing SideStore: download it, sign it with the account's certificate, put it on the phone, then
//! give it its pairing files. The same four steps iloader 2.3.9 runs for "Install SideStore".
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

use crate::account::{classify, SignInError, Signer};
use crate::{device, pairing, paths};

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub enum Channel {
    Stable,
    Nightly,
}

impl Channel {
    /// Where iloader 2.3.9 downloads SideStore from. Moves to the hosted config, with a pinned SHA-256,
    /// in Stage 2.
    pub fn ipa_url(self) -> &'static str {
        match self {
            Channel::Stable => "https://github.com/SideStore/SideStore/releases/latest/download/SideStore.ipa",
            Channel::Nightly => "https://github.com/SideStore/SideStore/releases/download/nightly/SideStore.ipa",
        }
    }

    fn file_name(self) -> &'static str {
        match self {
            Channel::Stable => "SideStore.ipa",
            Channel::Nightly => "SideStore-Nightly.ipa",
        }
    }
}

#[derive(Debug, Clone, Copy, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub enum Stage {
    Download,
    Sign,
    Pairing,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct InstallError {
    pub stage: Stage,
    pub error: SignInError,
}

fn failed(stage: Stage, detail: impl std::fmt::Display) -> InstallError {
    InstallError { stage, error: classify(&detail.to_string()) }
}

async fn download(channel: Channel) -> Result<PathBuf, String> {
    let dir = paths::downloads();
    std::fs::create_dir_all(&dir).map_err(|e| format!("create {}: {e}", dir.display()))?;
    let response = reqwest::get(channel.ipa_url()).await.map_err(|e| format!("download: {e}"))?;
    if !response.status().is_success() {
        return Err(format!("download: HTTP {}", response.status()));
    }
    let bytes = response.bytes().await.map_err(|e| format!("download: {e}"))?;
    let dest = dir.join(channel.file_name());
    std::fs::write(&dest, &bytes).map_err(|e| format!("write {}: {e}", dest.display()))?;
    Ok(dest)
}

/// Runs the whole install. `progress` gets the stage and, while signing, a fraction from 0 to 1.
pub async fn install(signer: &mut Signer, channel: Channel, progress: impl Fn(Stage, f32) + Send + Sync) -> Result<pairing::Placed, InstallError> {
    progress(Stage::Download, 0.0);
    let ipa = download(channel).await.map_err(|e| failed(Stage::Download, e))?;

    progress(Stage::Sign, 0.0);
    let phone = device::connect("nuvioz-install").await.map_err(|e| failed(Stage::Sign, e))?;
    signer
        .install_app(&phone.provider, ipa, false, Some(|f: f32| {
            progress(Stage::Sign, f);
            std::future::ready(())
        }), None)
        .await
        .map_err(|e| failed(Stage::Sign, e))?;
    drop(phone);

    progress(Stage::Pairing, 0.0);
    pairing::place_for_sidestore().await.map_err(|e| failed(Stage::Pairing, e))
}
