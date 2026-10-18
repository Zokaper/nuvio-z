#!/usr/bin/env bash
# Exercise the real marker script against mixed release channels, without network or repository writes.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"

gh() {
    case "${1-} ${2-}" in
        'release list') printf '%s\n' 'debug-v0.5.4-z2.95' 'ios-setup-vlatest' "${MARKER_STABLE_TAG}" ;;
        'release view') printf '%s\n' '<!-- nuvio-ios:self-signing -->' ;;
        *) echo 'Unexpected gh call in marker test' >&2; return 1 ;;
    esac
}
git() {
    case "${1-}" in
        rev-parse) return 0 ;;
        diff) return 1 ;; # Signer source changed since whichever baseline was chosen.
        show) printf '%s\n' 'name = "isideload"' 'version = "0.4.6"' ;;
        *) echo 'Unexpected git call in marker test' >&2; return 1 ;;
    esac
}
export -f gh git

check() {
    local channel="$1" stable_tag="$2" expected="$3" output
    output="$(GITHUB_REPOSITORY=example/test MARKER_STABLE_TAG="${stable_tag}" bash "${root}/scripts/ios-release-markers.sh" true "${channel}" 2>&1)"
    [[ "${output}" == *'<!-- nuvio-ios:self-signing -->'* ]]
    [[ "${output}" == *'<!-- nuvio-ios:signing-changed -->'* ]]
    [[ "${output}" == *"Signer source changed since ${expected}."* ]]
}

check stable '0.5.4-z3+129' '0.5.4-z3+129'
check stable 'v0.5.4-z3+129' 'v0.5.4-z3+129'
check debug-v '0.5.4-z3+129' 'debug-v0.5.4-z2.95'
echo 'iOS release marker channel tests: 3 passed.'
