<div align="center">

  <img src="https://github.com/tapframe/NuvioTV/blob/main/assets/brand/app_logo_wordmark.png" alt="Nuvio" width="300" />
  <br />
  <br />

  [![Contributors][contributors-shield]][contributors-url]
  [![Forks][forks-shield]][forks-url]
  [![Stargazers][stars-shield]][stars-url]
  [![Issues][issues-shield]][issues-url]
  [![License][license-shield]][license-url]

  <p>
    A mod of <a href="https://github.com/NuvioMedia/NuvioMobile">Nuvio</a> for Android and iOS,
    built with Kotlin Multiplatform and Compose Multiplatform.
    <br />
    Playback modes • Instant • Stremio addon ecosystem • Cross-platform
  </p>

</div>

## About

**Nuvio Z is a mod of [Nuvio](https://github.com/NuvioMedia/NuvioMobile), not a separate product.**
It is a bounded set of patches riding on a stated vanilla base, and it inherits everything vanilla
ships. Every Z release names the vanilla release it was built on — *Nuvio Z 0.6.0-z1, based on
Nuvio 0.6.0*.

Nuvio itself is the Kotlin Multiplatform rewrite of the original React Native app: a shared Compose
UI for Android and iOS with a playback-focused experience, collection tools, watch progress flows,
downloads, and Stremio addon ecosystem integration.

What the Z mod adds on top is listed in [`Docs/Z-FEATURES.md`](./Docs/Z-FEATURES.md). The doctrine
that governs the mod — how it tracks upstream, how it is versioned, and what it may patch — is in
[`Docs/UPSTREAM.md`](./Docs/UPSTREAM.md).

The mobile app is built from a single shared codebase in [composeApp](./composeApp), with native
platform entry points for Android and iOS.

## Installation

### Android

Download the latest Android build from [GitHub Releases](https://github.com/Zokaper/nuvio-z/releases/latest).

### iOS

- [TestFlight](https://testflight.apple.com/join/u4y7MHK9)

## Development

```bash
git clone https://github.com/Zokaper/nuvio-z.git
cd nuvio-z
./scripts/run-mobile.sh android
# or
./scripts/run-mobile.sh ios
```

### Project Structure

- `composeApp/` contains the shared Kotlin Multiplatform and Compose Multiplatform app code.
- `composeApp/src/commonMain/` contains shared UI, features, repositories, and platform-agnostic logic.
- `composeApp/src/androidMain/` contains Android-specific integrations.
- `composeApp/src/iosMain/` contains iOS-specific integrations.
- `iosApp/` contains the native Xcode project and iOS entry point.

Useful commands:

```bash
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:compileKotlinIosSimulatorArm64
./scripts/build-distribution.sh
```

Versioning is driven from `iosApp/Configuration/Version.xcconfig`, which is used as the shared source of truth for both iOS and Android builds.

## Upstream & License

Nuvio Z is a modification of [Nuvio](https://github.com/NuvioMedia/NuvioMobile) by NuvioMedia, and
would not exist without it. Upstream authors hold the copyright in the code Nuvio Z inherits.

Both Nuvio and Nuvio Z are licensed under the **GNU General Public License v3.0** — see
[LICENSE](./LICENSE). Nuvio Z is distributed under the same terms, with source available.

Nuvio Z is not affiliated with or endorsed by NuvioMedia. Please do not report Nuvio Z bugs to
upstream unless you can also reproduce them in vanilla Nuvio.

## Legal & DMCA

Nuvio functions solely as a client-side interface for browsing metadata and playing media provided by user-installed extensions and/or user-provided sources. It is intended for content the user owns or is otherwise authorized to access.

Nuvio is not affiliated with any third-party extensions, catalogs, sources, or content providers. It does not host, store, or distribute any media content.

For comprehensive legal information, including our full disclaimer, third-party extension policy, and DMCA/Copyright information, please visit our [Legal & Disclaimer Page](https://nuvioapp.space/legal).

## Built With

- Kotlin Multiplatform
- Compose Multiplatform
- Kotlin
- AndroidX Media3
- AVFoundation and native iOS integrations

## Star History

<a href="https://www.star-history.com/#Zokaper/nuvio-z&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=Zokaper/nuvio-z&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=Zokaper/nuvio-z&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=Zokaper/nuvio-z&type=date&legend=top-left" />
 </picture>
</a>

<!-- MARKDOWN LINKS & IMAGES -->
[contributors-shield]: https://img.shields.io/github/contributors/Zokaper/nuvio-z.svg?style=for-the-badge
[contributors-url]: https://github.com/Zokaper/nuvio-z/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/Zokaper/nuvio-z.svg?style=for-the-badge
[forks-url]: https://github.com/Zokaper/nuvio-z/network/members
[stars-shield]: https://img.shields.io/github/stars/Zokaper/nuvio-z.svg?style=for-the-badge
[stars-url]: https://github.com/Zokaper/nuvio-z/stargazers
[issues-shield]: https://img.shields.io/github/issues/Zokaper/nuvio-z.svg?style=for-the-badge
[issues-url]: https://github.com/Zokaper/nuvio-z/issues
[license-shield]: https://img.shields.io/github/license/Zokaper/nuvio-z.svg?style=for-the-badge
[license-url]: https://github.com/Zokaper/nuvio-z/blob/main/LICENSE