#!/usr/bin/env bash

set -euo pipefail

version_file="${VERSION_FILE:-iosApp/Configuration/Version.xcconfig}"
# The release ordering serial remains independent from the marketing version.
serial_file="${RELEASE_SERIAL_FILE:-iosApp/Configuration/ReleaseSerial.xcconfig}"
target_ref="${1:-HEAD}"

if ! git cat-file -e "${target_ref}^{commit}" 2>/dev/null; then
    echo "Unknown release target: ${target_ref}" >&2
    exit 1
fi

read_version() {
    local commit="$1"
    git show "${commit}:${version_file}" \
        | sed -nE 's/^[[:space:]]*MARKETING_VERSION[[:space:]]*=[[:space:]]*([^[:space:]#]+).*$/\1/p' \
        | head -n 1
}

read_serial() {
    local commit="$1"
    # `|| true` is load-bearing: with `set -o pipefail`, git show failing on a commit
    # that predates the serial file would fail the whole pipeline and abort the script
    # under `set -e`. A missing serial is a normal answer, not an error.
    { git show "${commit}:${serial_file}" 2>/dev/null || true; } \
        | sed -nE 's/^[[:space:]]*RELEASE_SERIAL[[:space:]]*=[[:space:]]*([0-9]+).*$/\1/p' \
        | head -n 1
}

current_version=""
current_bump=""
previous_version=""
previous_bump=""

while IFS= read -r commit; do
    version="$(read_version "$commit")"
    [[ -n "$version" ]] || continue

    if [[ -z "$current_version" ]]; then
        current_version="$version"
        current_bump="$commit"
    elif [[ -z "$previous_version" && "$version" == "$current_version" ]]; then
        # Keep walking to the oldest commit in this same-version group. Comments or
        # formatting edits to the version file must not become the release boundary.
        current_bump="$commit"
    elif [[ -z "$previous_version" ]]; then
        previous_version="$version"
        previous_bump="$commit"
    elif [[ "$version" == "$previous_version" ]]; then
        previous_bump="$commit"
    else
        break
    fi
done < <(git log "$target_ref" --format='%H' -- "$version_file")

if [[ -z "$current_bump" || -z "$previous_bump" ]]; then
    echo "Could not find two distinct version bumps in ${version_file}." >&2
    exit 1
fi

if [[ ! "$current_version" =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]]; then
    echo "Invalid release version: ${current_version}" >&2
    exit 1
fi

# The tag carries the serial, because that is what orders releases: a Nuvio Z version
# is a vanilla version plus a Z revision and can go BACKWARDS by name, which the version
# string cannot order. Both updaters already read tag_name, so it costs no extra request.
#
#     0.6.0-z1+127
#
# A release cut before the serial existed has no serial file and keeps a bare tag, which
# is exactly what the updater's fallback expects. The title stays the plain version.
release_serial="$(read_serial "${target_ref}")"
release_tag="$current_version"
if [[ -n "$release_serial" && "$release_serial" -gt 0 ]]; then
    release_tag="${current_version}+${release_serial}"
fi

printf 'version=%s\n' "$current_version"
printf 'serial=%s\n' "${release_serial:-0}"
printf 'tag=%s\n' "$release_tag"
printf 'release_commit=%s\n' "$(git rev-parse "${target_ref}^{commit}")"
printf 'current_bump=%s\n' "$current_bump"
printf 'previous_version=%s\n' "$previous_version"
printf 'previous_bump=%s\n' "$previous_bump"
