#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
version_file="${repository_root}/iosApp/Configuration/Version.xcconfig"
version="$(sed -nE 's/^[[:space:]]*MARKETING_VERSION[[:space:]]*=[[:space:]]*([^[:space:]#]+).*$/\1/p' "${version_file}" | head -n 1)"
build_number="$(sed -nE 's/^[[:space:]]*CURRENT_PROJECT_VERSION[[:space:]]*=[[:space:]]*([0-9]+).*$/\1/p' "${version_file}" | head -n 1)"
archive_path="${IOS_ARCHIVE_PATH:-${repository_root}/build/ios-archive/Nuvio-Z.xcarchive}"
output_directory="${IOS_IPA_OUTPUT_DIR:-${repository_root}/build/ios-testflight}"
export_options="${IOS_EXPORT_OPTIONS_PATH:-${repository_root}/build/ios-export-options.plist}"

required_values=(
    NUVIO_IOS_TEAM_ID
    APP_STORE_CONNECT_KEY_ID
    APP_STORE_CONNECT_ISSUER_ID
    APP_STORE_CONNECT_KEY_PATH
)
for value_name in "${required_values[@]}"; do
    if [[ -z "${!value_name:-}" ]]; then
        echo "Missing required TestFlight value: ${value_name}" >&2
        exit 1
    fi
done

if [[ ! -f "${APP_STORE_CONNECT_KEY_PATH}" ]]; then
    echo "App Store Connect private key was not found: ${APP_STORE_CONNECT_KEY_PATH}" >&2
    exit 1
fi
if [[ ! "${version}" =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ || ! "${build_number}" =~ ^[1-9][0-9]*$ ]]; then
    echo "Invalid mobile version/build pair: ${version} (${build_number})." >&2
    exit 1
fi

cd "${repository_root}"
rm -rf "${archive_path}" "${output_directory}"
mkdir -p "$(dirname "${archive_path}")" "${output_directory}" "$(dirname "${export_options}")"

build_environment=(env NUVIO_IOS_DISTRIBUTION=full)
if [[ -n "${NUVIO_GRADLE_JVMARGS:-}" ]]; then
    build_environment+=("ORG_GRADLE_PROJECT_org.gradle.jvmargs=${NUVIO_GRADLE_JVMARGS}")
fi
if [[ -n "${NUVIO_KOTLIN_NATIVE_JVMARGS:-}" ]]; then
    build_environment+=("ORG_GRADLE_PROJECT_kotlin.native.jvmArgs=${NUVIO_KOTLIN_NATIVE_JVMARGS}")
fi

"${build_environment[@]}" xcodebuild \
    -project iosApp/iosApp.xcodeproj \
    -scheme iosApp \
    -configuration Release \
    -sdk iphoneos \
    -destination 'generic/platform=iOS' \
    -archivePath "${archive_path}" \
    -allowProvisioningUpdates \
    -authenticationKeyPath "${APP_STORE_CONNECT_KEY_PATH}" \
    -authenticationKeyID "${APP_STORE_CONNECT_KEY_ID}" \
    -authenticationKeyIssuerID "${APP_STORE_CONNECT_ISSUER_ID}" \
    NUVIO_IOS_TEAM_ID="${NUVIO_IOS_TEAM_ID}" \
    DEVELOPMENT_TEAM="${NUVIO_IOS_TEAM_ID}" \
    CODE_SIGN_IDENTITY="Apple Distribution" \
    CODE_SIGN_STYLE=Automatic \
    archive

app_path="${archive_path}/Products/Applications/Nuvio Z.app"
if [[ ! -d "${app_path}" ]]; then
    echo "The signed archive did not contain ${app_path}." >&2
    exit 1
fi
built_version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "${app_path}/Info.plist")"
built_number="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "${app_path}/Info.plist")"
built_bundle="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "${app_path}/Info.plist")"
if [[ "${built_version}" != "${version}" || "${built_number}" != "${build_number}" ]]; then
    echo "Archived iOS version/build ${built_version} (${built_number}) does not match ${version} (${build_number})." >&2
    exit 1
fi
if [[ "${built_bundle}" != "com.nuvio.app.z" ]]; then
    echo "Archived iOS bundle ${built_bundle} is not com.nuvio.app.z." >&2
    exit 1
fi
codesign --verify --deep --strict --verbose=2 "${app_path}"

cat > "${export_options}" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>destination</key><string>export</string>
    <key>method</key><string>app-store-connect</string>
    <key>signingStyle</key><string>automatic</string>
    <key>teamID</key><string>${NUVIO_IOS_TEAM_ID}</string>
    <key>uploadSymbols</key><true/>
</dict>
</plist>
EOF

xcodebuild \
    -exportArchive \
    -archivePath "${archive_path}" \
    -exportPath "${output_directory}" \
    -exportOptionsPlist "${export_options}" \
    -allowProvisioningUpdates \
    -authenticationKeyPath "${APP_STORE_CONNECT_KEY_PATH}" \
    -authenticationKeyID "${APP_STORE_CONNECT_KEY_ID}" \
    -authenticationKeyIssuerID "${APP_STORE_CONNECT_ISSUER_ID}"

shopt -s nullglob
ipas=("${output_directory}"/*.ipa)
shopt -u nullglob
if [[ "${#ipas[@]}" -ne 1 ]]; then
    echo "Expected exactly one exported IPA, found ${#ipas[@]}." >&2
    exit 1
fi
final_ipa="${output_directory}/Nuvio-Z-iOS-${version}-${build_number}-testflight.ipa"
mv "${ipas[0]}" "${final_ipa}"
unzip -tq "${final_ipa}"
echo "Created ${final_ipa}"
