# Nuvio Z release model

This is the authoritative release-policy document for `nuvio-z` and `NuvioZDesktop`. It records
the compatibility decision made at the start of Phase 7. Operational commands and secret setup are
expanded here as the release workflows are hardened.

## Release families

Nuvio Z has two release families. They share policy and product intent, but not literal version
numbers or ordering counters.

| Family | Platforms | Product version | Monotonic order/build |
| --- | --- | --- | --- |
| Desktop | Windows and macOS | `DesktopVersion.properties:VERSION_NAME` | `DesktopReleaseSerial.properties:RELEASE_SERIAL`; Windows MSI ProductVersion remains `2.0.<serial>` |
| Mobile | Android and iOS | `Version.xcconfig:MARKETING_VERSION` | `Version.xcconfig:CURRENT_PROJECT_VERSION`; Android uses it as `versionCode`, iOS as `CFBundleVersion` |

Desktop is already live. Its `0.1.23-alpha-z6` / serial 131 lineage, stable MSI upgrade UUID,
`2.0.<serial>` ProductVersion mapping, updater repository and installed package identity are release
contracts. Do not renumber them to match mobile.

Android and iOS launch as one mobile product. They use the same marketing version and coordinated
build number. Android must not be published as a stable GitHub release independently of the matching
iOS/TestFlight build. Store rules may later require a platform build number to advance on only one
platform; if that happens, preserve the shared marketing version and record the per-platform build
exception rather than changing desktop.

## Channels and tags

Each repository has two isolated channels:

- Stable: a non-draft, non-prerelease GitHub release with tag `<version>+<release-order>`. Stable
  clients reject every prerelease and every `debug-v*` tag.
- Debug: a GitHub prerelease tagged `debug-v<version>.<debug-build>`. Debug clients accept only
  prereleases with the `debug-v` prefix. Debug counters are repository-local and never order stable
  releases.

Desktop's `<release-order>` is its release serial. Mobile retains its release serial in stable tags
for compatibility with the already-shipped serial-aware updater; Android/iOS installation ordering
uses the mobile build number. Neither serial is required to equal the other family's.

A draft belongs to no update channel. A build-only or dry-run invocation creates workflow artifacts
only: it never creates a tag, GitHub release, updater-visible feed entry, or TestFlight upload.

## Promotion invariants

- Desktop stable promotion comes from `Dev`; mobile stable promotion comes from `main`.
- The version bump is the last application-changing commit before promotion.
- A stable tag/version is single-use. Never replace a published asset under an existing tag.
- Stable promotion requires the complete platform set for that family. Desktop publication cannot
  silently omit an expected MSI or DMG. Mobile publication cannot publish Android without the
  coordinated iOS/TestFlight build.
- Build-only and dry-run must build and verify real artifacts. They differ only in how much release
  state validation they perform; neither publishes.
- Signing files, private keys, provisioning profiles, passwords and API keys live only in local
  ignored storage or CI secrets. They are never committed or printed.
- Roll forward after a bad public release. Do not reuse a tag, lower a build/order number, change
  the stable MSI upgrade UUID, or attempt an in-place desktop downgrade.

## Phase 7 boundary

Phase 7 covers desktop release hardening, Android release readiness, the iOS/TestFlight seam,
updater/channel policy, signing-secret contracts, release guards, artifact-producing non-publishing
paths and the release runbook. Tizen `.wgt`, webOS `.ipk`, TV distribution, broad rebranding and
unrelated backend work are outside this phase.

## Desktop procedure

The workflow is `NuvioZDesktop/.github/workflows/desktop-release.yml` (`Build Desktop Release`).
Its release unit is exactly one Windows x64 MSI plus arm64 and x86_64 macOS DMGs. Linux may be
built with `target=all`, but it cannot substitute for any member of that three-installer unit.

1. On `Dev`, set `VERSION_NAME` in `composeApp/Configuration/DesktopVersion.properties` and
   increment `RELEASE_SERIAL` in `DesktopReleaseSerial.properties`. Never lower or reuse the
   serial. Do not change the stable upgrade UUID or the `2.0.<serial>` MSI mapping.
2. Make the version bump the final tracked release commit. The workflow has a narrow allowlist for
   emergency release-automation corrections; ordinary code or documentation after the bump needs
   a fresh bump commit.
3. Rehearse the exact installers without publishing:

   ```bash
   gh workflow run desktop-release.yml --repo Zokaper/NuvioZDesktop --ref Dev \
     -f mode=dry-run -f target=windows-macos -f macos_signing=unsigned
   ```

   Use `macos_signing=notarized` to rehearse the production credentials. `dry-run` rejects an
   existing tag/release, generates notes, builds all three installers, verifies their exact names,
   and retains checksums. It creates no tag or release.
4. Inspect the three workflow artifacts and `SHA256SUMS-Windows-macOS.txt`. For a recoverable
   GitHub draft, use `mode=draft`; a draft is invisible to both updater channels.
5. Publish after the matching rehearsal is green. Once Apple credentials exist, use the notarized
   path:

   ```bash
   gh workflow run desktop-release.yml --repo Zokaper/NuvioZDesktop --ref Dev \
     -f mode=publish -f target=windows-macos -f macos_signing=notarized
   ```

   Until those credentials exist, the live unsigned-mac compatibility path remains available but
   requires an explicit acknowledgement so it cannot be selected accidentally:

   ```bash
   gh workflow run desktop-release.yml --repo Zokaper/NuvioZDesktop --ref Dev \
     -f mode=publish -f target=windows-macos -f macos_signing=unsigned \
     -f acknowledge_unsigned_macos=true
   ```

`build-only` is the branch-safe development path. It builds real artifacts but deliberately skips
the branch, duplicate-tag, final-bump and release-notes promotion checks:

```bash
gh workflow run desktop-release.yml --repo Zokaper/NuvioZDesktop --ref <branch> \
  -f mode=build-only -f target=windows-macos -f macos_signing=unsigned
```

On Windows, use a JDK that contains `jpackage` (the Android Studio JBR does not):

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
.\gradlew.bat :composeApp:packageMsi "-Pnuvio.desktop.debugChannel=true" `
  "-Pcompose.desktop.packaging.checkJdkVendor=false" --no-configuration-cache
.\gradlew.bat :composeApp:packageReleaseMsi `
  "-Pcompose.desktop.packaging.checkJdkVendor=false" --no-configuration-cache
```

`packageMsi` is the debug-channel artifact; `packageReleaseMsi` is the stable artifact. CI renames
the stable output to `Nuvio-Z-Windows-x64-<version>.msi`. Never hand-upload an installer whose
embedded version/upgrade UUID was not checked by the workflow.

Desktop CI configuration:

- `NUVIO_DESKTOP_LOCAL_PROPERTIES_BASE64` (required runtime properties);
- `MACOS_CERTIFICATE_P12_BASE64`, `MACOS_CERTIFICATE_PASSWORD`,
  `MACOS_CERTIFICATE_SHA256`, `MACOS_SIGNING_IDENTITY`;
- `MACOS_NOTARY_APPLE_ID`, `MACOS_NOTARY_APP_PASSWORD`, `MACOS_NOTARY_TEAM_ID`,
  `MACOS_NOTARY_PROFILE`;
- optional Sentry token/DSN.

## Mobile version and procedure

`iosApp/Configuration/Version.xcconfig` is deliberately shared release metadata:

- `MARKETING_VERSION` becomes Android `versionName` and iOS `CFBundleShortVersionString`;
- `CURRENT_PROJECT_VERSION` becomes Android `versionCode` and iOS `CFBundleVersion`.

Increment the numeric build for every Android/iOS release candidate uploaded outside a developer
machine. A TestFlight retry rejected as a duplicate build needs a new build number even when the
marketing version is unchanged. Increment `RELEASE_SERIAL` in `ReleaseSerial.xcconfig` for every
stable GitHub tag; it orders in-app updates and is not Android/iOS's install build number.

The coordinator is `.github/workflows/android-release.yml` (`Build Mobile Release`):

```bash
# Any branch: signed Android when secrets exist, otherwise unsigned; unsigned iOS verification IPA.
gh workflow run android-release.yml --repo Zokaper/nuvio-z --ref <branch> -f mode=build-only

# main only: full artifact-producing rehearsal, no tag, release, or TestFlight upload.
gh workflow run android-release.yml --repo Zokaper/nuvio-z --ref main -f mode=dry-run

# main only: TestFlight upload must succeed before the signed Android release becomes visible.
gh workflow run android-release.yml --repo Zokaper/nuvio-z --ref main -f mode=publish
```

There is intentionally no Android-only stable publish mode and no GitHub draft mode. Publish:

1. validates the default branch, unused stable tag, clean checkout, final version bump and all
   Android/Apple secrets;
2. builds four ABI-specific, metadata-checked, signed APKs;
3. archives/signs iOS and uploads the same marketing version/build to TestFlight;
4. only after TestFlight accepts the upload, creates a non-prerelease GitHub release containing
   the APKs and checksums. The IPA is retained as a private workflow artifact and is never attached
   to GitHub;
5. updates the sideload source.

Local Android commands:

```bash
./gradlew :androidApp:assembleFullDebug
./gradlew :androidApp:assembleFullRelease -Pnuvio.android.unsignedRelease=true
```

Omit `nuvio.android.unsignedRelease` only when all four `NUVIO_RELEASE_*` properties point to the
real keystore. A partial signing configuration is an error. Debug installs use
`com.nuvio.app.z.debug`, a shared debug keystore and the derived `<release build>*1000+<debug build>`
code. Stable installs use `com.nuvio.app.z`; debug/test packages with an older ID or a different
signature do not upgrade the stable app and must be uninstalled explicitly.

On macOS, the credential-free verification command is:

```bash
./scripts/prepare-ios-dependencies.sh
./scripts/build-ios-ipa.sh
```

It validates the version, build, bundle ID, arm64 executable, widget and absence of a signature,
then produces `Nuvio-Z-iOS-<version>-<build>-unsigned.ipa`. This IPA is compile evidence only; do
not install, distribute or attach it to a release.

## TestFlight setup and manual steps

Source control fixes the release bundle IDs at `com.nuvio.app.z` and
`com.nuvio.app.z.DownloadsWidgetExtension`; debug uses the `.debug` family. The team remains empty
in source. Configure these GitHub environment/repository values when the Apple account is ready:

- variable `NUVIO_IOS_TEAM_ID`;
- secrets `NUVIO_IOS_DISTRIBUTION_CERTIFICATE_P12_BASE64` and
  `NUVIO_IOS_DISTRIBUTION_CERTIFICATE_PASSWORD`;
- secrets `APP_STORE_CONNECT_PRIVATE_KEY_BASE64`, `APP_STORE_CONNECT_KEY_ID` and
  `APP_STORE_CONNECT_ISSUER_ID` for an App Store Connect API key allowed to manage/upload apps;
- `NUVIO_LOCAL_PROPERTIES_BASE64` for compile-time runtime configuration.

Create the app record and both identifiers/capabilities in the Apple developer account before the
first publish. The workflow uses automatic App Store Connect provisioning, a temporary keychain,
an App Store distribution archive and the API key; temporary material is removed in an `always()`
step. Apple credentials are not needed for build-only/dry-run.

After upload, App Store Connect still requires a maintainer to wait for processing, answer export
compliance/content questions, add the build to the intended internal/external TestFlight group,
complete beta review where required, and confirm tester availability. These account-side steps are
Phase 8 validation, not a Phase 7 code blocker.

## Failure and recovery

- If a build-only or dry-run fails, discard its workflow artifacts, fix the source or workflow and
  rerun. Nothing is updater-visible.
- If TestFlight accepts iOS but GitHub publication fails, rerun the same workflow only if Apple
  permits the identical build upload; normally bump the mobile build (and release serial if the tag
  changes) and roll forward. Never publish the waiting Android APK manually.
- If GitHub publication succeeds but a client-visible build is bad, mark the release unavailable
  only as an emergency containment step, then ship a higher serial/build. Existing clients may have
  cached it; deleting/reusing the tag is not recovery.
- Desktop rollback is always a forward release with a greater desktop serial. Never lower the MSI
  ProductVersion, rotate the upgrade UUID, or replace assets under the old stable tag.
- Android rollback is a fixed build with a higher `versionCode`; iOS rollback is a fixed TestFlight
  build with a higher `CFBundleVersion`. Marketing versions may stay the same when store policy
  permits, but document the exception.

## Footguns

- Do not run `publish` merely to test CI. Use `build-only` or `dry-run`.
- Do not mark debug releases as stable or stable releases as GitHub prereleases. Stable clients
  reject prereleases; debug clients require both the prerelease flag and `debug-v` tag.
- Do not publish only Windows, one macOS architecture, or Android without TestFlight.
- Do not commit `.jks`, `.p12`, `.p8`, provisioning profiles, passwords or generated export files.
- Do not reuse version/build/order numbers, tags, or stale files copied from a previous build.
- Do not edit desktop identity/upgrade/version mapping as part of a cleanup. It is installed-user
  compatibility state.
