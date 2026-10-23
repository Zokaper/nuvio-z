#!/usr/bin/env bash
# Builds the pinned nuvioz-signer for an Apple target and stages what Gradle links (composeApp/build.gradle.kts):
#   signer/out/<target>/libnuvioz_signer.a   and   signer/out/include/nuvioz_signer.h
#   bash signer/build.sh aarch64-apple-ios
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
target="${1:?target, e.g. aarch64-apple-ios}"
export IPHONEOS_DEPLOYMENT_TARGET="${IPHONEOS_DEPLOYMENT_TARGET:-16.1}"
rustup target add "${target}" >/dev/null
cargo build --locked --release --manifest-path "${here}/Cargo.toml" -p nuvioz-signer --target "${target}"
mkdir -p "${here}/out/${target}" "${here}/out/include"
cp "${here}/target/${target}/release/libnuvioz_signer.a" "${here}/out/${target}/"
# The header ships with the pinned source, next to its Cargo.toml.
manifest=$(cargo metadata --locked --format-version 1 --manifest-path "${here}/Cargo.toml" \
    | python3 -c 'import json,sys; print(next(p["manifest_path"] for p in json.load(sys.stdin)["packages"] if p["name"] == "nuvioz-signer"))')
cp "$(dirname "${manifest}")/include/nuvioz_signer.h" "${here}/out/include/"
ls -la "${here}/out/${target}/libnuvioz_signer.a" "${here}/out/include/nuvioz_signer.h"
