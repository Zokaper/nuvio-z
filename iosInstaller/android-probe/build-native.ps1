param(
    [string]$OutputDirectory = "$PSScriptRoot/app/build/generated/jniLibs",
    [string]$AndroidSdk = $env:ANDROID_HOME
)
$ErrorActionPreference = 'Stop'
if (-not $AndroidSdk) { $AndroidSdk = "$env:LOCALAPPDATA/Android/Sdk" }
$ndkRoot = Join-Path $AndroidSdk 'ndk/28.2.13676358'
if (-not (Test-Path -LiteralPath $ndkRoot)) { throw "Install NDK 28.2.13676358 in $AndroidSdk/ndk" }
$toolchain = Join-Path $ndkRoot 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$cargo = Join-Path $env:USERPROFILE '.cargo/bin/cargo.exe'
$rustup = Join-Path $env:USERPROFILE '.cargo/bin/rustup.exe'
$env:PATH = "$(Split-Path $cargo);$toolchain;$env:PATH"
$env:CARGO_TARGET_DIR = Join-Path $PSScriptRoot 'native/build/target'
$installedTargets = & $rustup target list --installed
foreach ($target in @('aarch64-linux-android', 'x86_64-linux-android')) {
    if ($installedTargets -notcontains $target) { throw "Run: rustup target add $target" }
    $abi = if ($target -eq 'aarch64-linux-android') { 'arm64-v8a' } else { 'x86_64' }
    $envKey = $target.Replace('-', '_')
    $upperKey = $envKey.ToUpperInvariant()
    [Environment]::SetEnvironmentVariable("CARGO_TARGET_${upperKey}_LINKER", (Join-Path $toolchain 'clang.exe'), 'Process')
    [Environment]::SetEnvironmentVariable("CARGO_TARGET_${upperKey}_RUSTFLAGS", "-Clink-arg=--target=${target}24 -Clink-arg=-Wl,-z,max-page-size=16384", 'Process')
    [Environment]::SetEnvironmentVariable("CC_$envKey", (Join-Path $toolchain 'clang.exe'), 'Process')
    [Environment]::SetEnvironmentVariable("AR_$envKey", (Join-Path $toolchain 'llvm-ar.exe'), 'Process')
    [Environment]::SetEnvironmentVariable("CFLAGS_$envKey", "--target=${target}24", 'Process')
    & $cargo build --locked --release --manifest-path "$PSScriptRoot/native/Cargo.toml" --target $target -p nuvioz-usb-probe
    if ($LASTEXITCODE -ne 0) { throw "Rust build failed for $target ($LASTEXITCODE)" }
    $destination = Join-Path $OutputDirectory $abi
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    Copy-Item -LiteralPath (Join-Path $env:CARGO_TARGET_DIR "$target/release/libnuvioz_usb_probe.so") -Destination $destination
}
