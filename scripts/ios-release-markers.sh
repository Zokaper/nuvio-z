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
# "Signing code" is our signer (the installer's core/ and ffi/) and the upstream crates it signs and installs
# with. Until 2026-10-24 those lived here in iosInstaller/; since then signer/Cargo.toml pins a commit of
# Zokaper/nuviozinstaller, whose filtered history gives the same content the same tree hashes, so either kind of
# release compares with the other. Only those crates' Cargo.lock entries count: the lock also pins the installer's UI
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

installer_repo="https://github.com/Zokaper/nuviozinstaller.git"
installer_git=""
# The signer's source at a commit, as the tree hashes of core/ and ffi/.
signer_trees() {
    local rev
    rev=$(git show "$1:signer/Cargo.toml" 2>/dev/null | sed -n -E 's/.*nuviozinstaller", rev = "([0-9a-f]{40})".*/\1/p' | head -n 1)
    if [[ -n "${rev}" ]]; then
        if [[ -z "${installer_git}" ]]; then
            installer_git=$(mktemp -d)
            git clone --quiet --bare --filter=blob:none "${installer_repo}" "${installer_git}"
        fi
        echo "$(git --git-dir "${installer_git}" rev-parse "${rev}:core") $(git --git-dir "${installer_git}" rev-parse "${rev}:ffi")"
    else
        echo "$(git rev-parse "$1:iosInstaller/core" 2>/dev/null) $(git rev-parse "$1:iosInstaller/ffi" 2>/dev/null)"
    fi
}

lock_entries() {
    { git show "$1:signer/Cargo.lock" 2>/dev/null || git show "$1:iosInstaller/Cargo.lock" 2>/dev/null; } \
        | awk -v pattern="^name = \"(${signing_crates})\"$" '$0 ~ pattern { name = $0; getline; print name " " $0 }' \
        | sort
}

changed=false
if [[ "$(signer_trees "${previous}")" != "$(signer_trees HEAD)" ]]; then
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
