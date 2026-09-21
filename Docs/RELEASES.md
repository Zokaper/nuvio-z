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
