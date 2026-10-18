#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
version_file="${repository_root}/iosApp/Configuration/Version.xcconfig"
version="${1:-$(sed -nE 's/^[[:space:]]*MARKETING_VERSION[[:space:]]*=[[:space:]]*([^[:space:]#]+).*$/\1/p' "${version_file}" | head -n 1)}"
build_number="${2:-$(sed -nE 's/^[[:space:]]*CURRENT_PROJECT_VERSION[[:space:]]*=[[:space:]]*([0-9]+).*$/\1/p' "${version_file}" | head -n 1)}"
configuration="${IOS_CONFIGURATION:-Release}"
case "${configuration}" in
    Debug)
        configuration_slug="debug"
        ;;
    Release)
        configuration_slug="release"
        ;;
    *)
        echo "Unsupported iOS configuration: ${configuration}" >&2
        exit 1
        ;;
esac
derived_data="${IOS_DERIVED_DATA_PATH:-${repository_root}/build/ios-derived-full-${configuration_slug}}"
output_directory="${IOS_IPA_OUTPUT_DIR:-${repository_root}/build/ios-ipa}"
clang_module_cache="${CLANG_MODULE_CACHE_PATH:-${derived_data}/ModuleCache.noindex}"
swiftpm_module_cache="${SWIFTPM_MODULECACHE_OVERRIDE:-${derived_data}/SwiftPMModuleCache.noindex}"

if [[ ! "${version}" =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]]; then
    echo "Invalid IPA version: ${version}" >&2
    exit 1
fi
if [[ ! "${build_number}" =~ ^[1-9][0-9]*$ ]]; then
    echo "Invalid iOS build number: ${build_number}" >&2
    exit 1
fi

cd "${repository_root}"
build_environment=(
    env
    NUVIO_IOS_DISTRIBUTION=full
    CLANG_MODULE_CACHE_PATH="${clang_module_cache}"
    SWIFTPM_MODULECACHE_OVERRIDE="${swiftpm_module_cache}"
)
if [[ -n "${NUVIO_GRADLE_JVMARGS:-}" ]]; then
    build_environment+=("ORG_GRADLE_PROJECT_org.gradle.jvmargs=${NUVIO_GRADLE_JVMARGS}")
fi
if [[ -n "${NUVIO_KOTLIN_NATIVE_JVMARGS:-}" ]]; then
    build_environment+=("ORG_GRADLE_PROJECT_kotlin.native.jvmArgs=${NUVIO_KOTLIN_NATIVE_JVMARGS}")
fi
# Nuvio Z self-signing (Debug builds linked with iosInstaller's signer, see composeApp/build.gradle.kts): compile
# SelfSigning.swift's App Intent and background task, which the Info.plist keys added below go with.
self_signing=false
extra_build_settings=()
if [[ "$(echo "${NUVIO_IOS_SELF_SIGNING:-false}" | tr '[:upper:]' '[:lower:]')" == "true" ]]; then
    if [[ "${configuration}" != "Debug" ]]; then
        echo "NUVIO_IOS_SELF_SIGNING is for Debug builds only." >&2
        exit 1
    fi
    self_signing=true
    extra_build_settings+=("SWIFT_ACTIVE_COMPILATION_CONDITIONS=DEBUG NUVIO_SELF_SIGNING")
fi
"${build_environment[@]}" \
    xcodebuild \
    -project iosApp/iosApp.xcodeproj \
    -scheme iosApp \
    -configuration "${configuration}" \
    -sdk iphoneos \
    -destination 'generic/platform=iOS' \
    -derivedDataPath "${derived_data}" \
    MARKETING_VERSION="${version}" \
    CURRENT_PROJECT_VERSION="${build_number}" \
    CODE_SIGNING_ALLOWED=NO \
    CODE_SIGNING_REQUIRED=NO \
    CODE_SIGN_IDENTITY= \
    ${extra_build_settings[@]+"${extra_build_settings[@]}"} \
    build

products_directory="${derived_data}/Build/Products/${configuration}-iphoneos"
shopt -s nullglob
built_apps=("${products_directory}"/*.app)
shopt -u nullglob
if [[ "${#built_apps[@]}" -ne 1 ]]; then
    echo "Expected exactly one iOS app in ${products_directory}, found ${#built_apps[@]}." >&2
    exit 1
fi
app_path="${built_apps[0]}"

built_version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "${app_path}/Info.plist")"
if [[ "${built_version}" != "${version}" ]]; then
    echo "Built iOS version ${built_version} does not match ${version}." >&2
    exit 1
fi
built_number="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "${app_path}/Info.plist")"
if [[ "${built_number}" != "${build_number}" ]]; then
    echo "Built iOS number ${built_number} does not match ${build_number}." >&2
    exit 1
fi
expected_bundle_id="com.nuvio.app.z"
expected_display_name="Nuvio Z"
if [[ "${configuration}" == "Debug" ]]; then
    expected_bundle_id="com.nuvio.app.z.debug"
    expected_display_name="Nuvio Z Debug"
fi
built_bundle_id="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "${app_path}/Info.plist")"
if [[ "${built_bundle_id}" != "${expected_bundle_id}" ]]; then
    echo "Built iOS bundle ${built_bundle_id} does not match ${expected_bundle_id}." >&2
    exit 1
fi
built_display_name="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleDisplayName' "${app_path}/Info.plist" 2>/dev/null || true)"
if [[ -n "${built_display_name}" && "${built_display_name}" != "${expected_display_name}" ]]; then
    echo "Built iOS display name ${built_display_name} does not match ${expected_display_name}." >&2
    exit 1
fi

executable="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "${app_path}/Info.plist")"
architectures="$(xcrun lipo -archs "${app_path}/${executable}")"
if [[ " ${architectures} " != *" arm64 "* ]]; then
    echo "Built iOS application does not contain arm64." >&2
    exit 1
fi
if ! launch_screen_plist="$(plutil -extract UILaunchScreen xml1 -o - "${app_path}/Info.plist" 2>/dev/null)"; then
    echo "Built iOS application does not contain UILaunchScreen." >&2
    exit 1
fi
if [[ "${launch_screen_plist}" == *"<key>UILaunchScreen</key>"* ]]; then
    echo "Built iOS application contains a nested UILaunchScreen." >&2
    exit 1
fi
if [[ -d "${app_path}/_CodeSignature" ]]; then
    echo "Built iOS application is unexpectedly signed." >&2
    exit 1
fi

widget_path="${app_path}/PlugIns/DownloadsWidgetExtension.appex"
if [[ ! -d "${widget_path}" ]]; then
    echo "Built iOS application does not contain the downloads widget." >&2
    exit 1
fi
widget_executable="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "${widget_path}/Info.plist")"
widget_architectures="$(xcrun lipo -archs "${widget_path}/${widget_executable}")"
if [[ " ${widget_architectures} " != *" arm64 "* ]]; then
    echo "Built downloads widget does not contain arm64." >&2
    exit 1
fi

mkdir -p "${output_directory}"
output_directory="$(cd "${output_directory}" && pwd -P)"
package_root="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-ios-ipa.XXXXXX")"
trap 'rm -rf "${package_root}"' EXIT
mkdir -p "${package_root}/Payload"
ditto "${app_path}" "${package_root}/Payload/Nuvio-Z.app"

# Debug: expose the app's Documents folder in Files ("On My iPhone > Nuvio Z Debug"),
# which is where the download diagnostics (`nuvio_diagnostics/`) are written.
#
# The Debug build settings ask for this with INFOPLIST_KEY_UIFileSharingEnabled, but Xcode's
# generated Info.plist does not carry that key through: `.45` and `.46` shipped with
# LSSupportsOpeningDocumentsInPlace and without UIFileSharingEnabled, and Files needs both.
# The staged app is unsigned, so setting the keys here breaks no seal.
#
# Release with self-signing: UIFileSharingEnabled alone. The installer writes the pairing file and
# the PC's signing key into Documents through house_arrest, which needs it; without
# LSSupportsOpeningDocumentsInPlace the folder stays out of the Files app, so nobody moves or
# deletes those files by accident. A Release IPA without self-signing exposes neither.
staged_plist="${package_root}/Payload/Nuvio-Z.app/Info.plist"
set_plist_true() {
    /usr/libexec/PlistBuddy -c "Delete :$1" "${staged_plist}" >/dev/null 2>&1 || true
    /usr/libexec/PlistBuddy -c "Add :$1 bool true" "${staged_plist}"
}
expect_plist() {
    local value
    value="$(/usr/libexec/PlistBuddy -c "Print :$1" "${staged_plist}" 2>/dev/null || echo absent)"
    if [[ "$2" == "true" && "${value}" != "true" ]]; then
        echo "${configuration} IPA must set $1 to true, found ${value}." >&2
        exit 1
    fi
    if [[ "$2" == "off" && "${value}" == "true" ]]; then
        echo "${configuration} IPA must not set $1." >&2
        exit 1
    fi
}
if [[ "${configuration}" == "Debug" ]]; then
    set_plist_true UIFileSharingEnabled
    set_plist_true LSSupportsOpeningDocumentsInPlace
    expect_plist UIFileSharingEnabled true
    expect_plist LSSupportsOpeningDocumentsInPlace true
elif [[ "${self_signing}" == "true" ]]; then
    set_plist_true UIFileSharingEnabled
    expect_plist UIFileSharingEnabled true
    expect_plist LSSupportsOpeningDocumentsInPlace off
else
    expect_plist UIFileSharingEnabled off
    expect_plist LSSupportsOpeningDocumentsInPlace off
fi

# Self-signing: the background refresh needs its task identifier listed and the fetch mode, or registering it
# crashes the app; and the App Intent only shows in Shortcuts if Xcode extracted its metadata.
if [[ "${self_signing}" == "true" ]]; then
    /usr/libexec/PlistBuddy -c "Delete :BGTaskSchedulerPermittedIdentifiers" "${staged_plist}" >/dev/null 2>&1 || true
    /usr/libexec/PlistBuddy -c "Add :BGTaskSchedulerPermittedIdentifiers array" "${staged_plist}"
    /usr/libexec/PlistBuddy -c "Add :BGTaskSchedulerPermittedIdentifiers:0 string com.nuvio.app.selfsigning.refresh" "${staged_plist}"
    if ! plutil -extract UIBackgroundModes xml1 -o - "${staged_plist}" | grep -q "<string>fetch</string>"; then
        /usr/libexec/PlistBuddy -c "Add :UIBackgroundModes: string fetch" "${staged_plist}"
    fi
    if [[ ! -d "${package_root}/Payload/Nuvio-Z.app/Metadata.appintents" ]]; then
        echo "Self-signing build has no Metadata.appintents: the Refresh Nuvio Z action would not appear in Shortcuts." >&2
        exit 1
    fi
    echo "Self-signing: background task keys added; App Intent metadata present."
fi

if [[ "${configuration}" == "Debug" ]]; then
    ipa_filename="${IOS_IPA_NAME:-Nuvio-Z-iOS-${version}-${build_number}-debug-unsigned.ipa}"
else
    ipa_filename="${IOS_IPA_NAME:-Nuvio-Z-iOS-${version}-${build_number}-unsigned.ipa}"
fi
ipa_path="${output_directory}/${ipa_filename}"
temporary_ipa="${package_root}/${ipa_filename}"
(
    cd "${package_root}"
    /usr/bin/zip -qry "${temporary_ipa}" Payload
)
unzip -tq "${temporary_ipa}"
mv "${temporary_ipa}" "${ipa_path}"

echo "Created ${ipa_path}"
