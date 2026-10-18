#!/usr/bin/env bash
# Prints the iOS release-notes markers for a release being published from HEAD (see AppUpdater.kt):
#
#   <!-- nuvio-ios:self-signing -->     the IPA links the self-signing library (argument 1 is "true")
#   <!-- nuvio-ios:signing-changed -->  the signing code changed since the newest earlier release on the
#                                       channel that had the self-signing marker
#
# Usage: scripts/ios-release-markers.sh <self_signing true|false> <stable or tag prefix, e.g. debug-v>
# Needs GH_TOKEN (or a gh login) and the full git history (actions/checkout fetch-depth: 0).
#
# "Signing code" is our signer (iosInstaller/core, iosInstaller/ffi) and the upstream crates it signs and
# installs with. Only those crates' Cargo.lock entries count: the lock also pins the installer's UI
# dependencies, and bumping those must not make an update look urgent.
set -euo pipefail

self_signing="${1:-false}"
prefix="${2:?stable channel or tag prefix}"
marker_self='<!-- nuvio-ios:self-signing -->'
marker_changed='<!-- nuvio-ios:signing-changed -->'
signing_crates='isideload|idevice|idevice-srp|apple-codesign-quick'

[[ "${self_signing}" == "true" ]] || exit 0
echo "${marker_self}"

repo="${GITHUB_REPOSITORY:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"
previous=""
while read -r tag; do
    if [[ "${prefix}" == "stable" ]]; then
        # Our stable tags are numeric (0.5.4-z3+129); also accept legacy v-prefixed tags.
        # Debug and installer prereleases must never become the stable comparison baseline.
        [[ "${tag}" =~ ^v?[0-9] ]] || continue
    else
        [[ "${tag}" == "${prefix}"* ]] || continue
    fi
    if gh release view "${tag}" --repo "${repo}" --json body -q .body | grep -qF "${marker_self}"; then
        previous="${tag}"
        break
    fi
done < <(gh release list --repo "${repo}" --limit 50 --json tagName -q '.[].tagName')

if [[ -z "${previous}" ]]; then
    echo "No earlier ${prefix}* release with the self-signing marker; nothing to compare." >&2
    exit 0
fi
if ! git rev-parse -q --verify "refs/tags/${previous}" >/dev/null; then
    git fetch --quiet origin "refs/tags/${previous}:refs/tags/${previous}"
fi

lock_entries() {
    git show "$1:iosInstaller/Cargo.lock" 2>/dev/null \
        | awk -v pattern="^name = \"(${signing_crates})\"$" '$0 ~ pattern { name = $0; getline; print name " " $0 }' \
        | sort
}

changed=false
if ! git diff --quiet "${previous}" HEAD -- iosInstaller/core iosInstaller/ffi; then
    changed=true
    echo "Signer source changed since ${previous}." >&2
fi
if [[ "$(lock_entries "${previous}")" != "$(lock_entries HEAD)" ]]; then
    changed=true
    echo "Signing crates changed since ${previous}:" >&2
    diff <(lock_entries "${previous}") <(lock_entries HEAD) >&2 || true
fi
if [[ "${changed}" == "true" ]]; then
    echo "${marker_changed}"
fi
