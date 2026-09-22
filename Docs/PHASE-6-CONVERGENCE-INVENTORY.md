# Phase 6 Stage A — shared-code inventory and convergence classification

**Measured 2026-09-17.** Mobile `codex/watch-together-architecture` @ `fd34e331` against
`desktop/Dev` @ `61ee8976` (`nuviozdesktop`, `0.1.23-alpha-z6`). Merge base `00e0b236`.

Stage A is a measurement gate: no product code changes. This file is the written classification the
gate asks for. Plan: `PLAN-phase-6-social-watch-together-mobile.md` § "Stage A".

---

## 0. Headline: the convergence is a merge, not a port

The plan budgeted Stage A around reconciling ~204 differing shared files by hand, treating repo
topology as "High, and the real cost". **Measurement does not support that cost.**

`nuvio-z` has **35 commits** that `desktop/Dev` does not contain. **Thirty-four are documentation.**
Exactly one touches `composeApp/src`:

| mobile commit | fate |
| --- | --- |
| `9bc6c741` `fix(playback): preserve mixed HDR and Dolby Vision labels` | already on desktop as `276489e5`, merged by `783b7aa8` |

The same holds for every other mobile branch carrying code: `claude/release-0.5.0-beta-polish`'s
"smooth next episode transitions" is desktop's `b55b0f62`; `codex/whats-new` is unmerged side work
outside Phase 6.

**So mobile's shared source sets contain nothing desktop lacks.** Every one of the 203 desktop-only
and 286 differing files is desktop moving ahead of a static mobile, not two-way divergence. There is
nothing to reconcile, because there are no competing mobile edits to reconcile *against*.

A trial merge confirms it end to end:

| trial `git merge --no-ff desktop/Dev` | result |
| --- | --- |
| conflicts in `composeApp/src` | **0** |
| conflicts total | **2**, both documentation (`Docs/Z-FEATURES.md`, `STATUS.md`) |
| `commonMain` + `commonTest` + `androidMain` + `iosMain` after merge vs `desktop/Dev` | **byte-identical, 0 files differing** |

That last row is the important one. The merge does not produce a hybrid needing review; it produces
**exactly desktop's reference implementation**, which is what §"Controlled convergence rules" asks
for. Mobile's stale watchparty trio is overwritten by the merge as ordinary content — "delete, do
not reconcile" happens for free and needs no separate deletion commit.

⚠ **This conclusion is load-bearing for `commonMain` and `commonTest` only.** Desktop compiles
those. It does **not** compile `androidMain` — there is no `androidTarget` in its build at all — so
its 66 `androidMain`/`iosMain` files are unbuilt, and one of them has already rotted in a way that
breaks the mobile build. See §3.5 before treating "byte-identical to desktop" as "correct".

### Consequence for the plan

Stage A's "classify 204 differing files individually" and Stage D's "merge desktop's 35 watchparty
files" are the **same single operation**, and it is mechanical. The risk register's "Repo topology —
High, and the real cost" should be **downgraded**. The real cost of Phase 6 is Stages E–I: the
player contract, phone UI, lifecycle and physical verification. See §4.

---

## 1. Method

`scripts/shared-code-drift.sh` reported **498** differing shared files. Re-measured independently
with content normalization (`\r\n` → `\n`, trailing whitespace stripped) across `commonMain`,
`commonTest`, `androidMain`, `iosMain`:

| class | files |
| --- | ---: |
| identical | 841 |
| **differs (real content)** | **286** |
| **desktop-only** | **203** |
| mobile-only | 6 |
| whitespace-only (phantom drift) | 3 |

The three phantom files — `CardDepthEffect.kt`, `BingeGroupCacheRepository.kt`,
`StreamAutoPlayPolicy.kt` — differ by a single blank line at EOF and need no action.

**A correction worth recording**, because the project's standing advice pointed the wrong way here:
these phantoms are **not** CRLF. `AGENTS.md` rule 5 warns that both repos are CRLF and that a raw
`git diff` makes every shared file look diverged — true of *working-tree* comparisons, but
`.gitattributes` normalizes to LF in the index, so blob-to-blob comparison is unaffected. Measured
across all 495 differing files, **not one differs by line endings alone.** The raw count of 498 was
very nearly right; it was inflated by trailing whitespace, not by CRLF.

Two fixes to `shared-code-drift.sh` land in this stage: normalize trailing whitespace before
reporting (498 → **495**, now agreeing exactly with the independent measurement above), and point
`DEFAULT_OTHER_REF` at `desktop/Dev` instead of the long-dead
`desktop/claude/upstream-doctrine-stage0`, which made the script exit 2 rather than report.

---

## 2. Classification

Every desktop-only and differing file falls into one of five classes. Counts are exhaustive over the
489 (203 + 286) files.

| class | files | action |
| --- | ---: | --- |
| **A — import via merge** | 485 | taken from `desktop/Dev` verbatim; lands byte-identical |
| **B — deliberate divergence, must be restored after merge** | 4 | re-apply mobile's version; see §3.1 |
| **C — platform-specific, mobile-only, must survive** | 6 + 14 untracked-by-source-set | see §3.2 |
| **D — desktop-only trees, must not land** | 329 | excluded from the merge; see §3.3 |
| **E — stale, deleted by the merge itself** | 3 | mobile's watchparty trio; no separate action |

Class A breaks down per source set in **Appendix A**. The headline areas:

| area | new to mobile | updated |
| --- | ---: | ---: |
| `features/watchparty` (`commonMain`) | 26 | 3 |
| `features/watchparty` (`commonTest`) | 20 | 1 |
| `features/social` (`commonMain`) | 17 | 6 |
| `features/social` (`commonTest`) | 8 | 0 |
| `features/player` (`commonMain`) | 14 | 38 |
| `features/player` (`commonTest`) | 18 | 4 |

The Watch Together core arrives complete — barrier, clock, drift, sync protocol, transport, session
coordinator, session state, source descriptor V2, realizer, termination, handoff — **with its 21
test suites**, satisfying the plan's "port its relevant tests with it rather than only the
implementation".

---

## 3. The five hazards the merge creates

The merge is clean, which is precisely why it is dangerous: **git reports no conflict for any of
these.** Each is a silent revert.

### 3.1 It reverts the initially documented deliberate divergences

`AGENTS.md` and `shared-code-drift.sh` both name four files that are deliberately *not* shared.
Mobile has not edited them since the merge base, so git takes desktop's side silently:

| file | why it diverges | severity |
| --- | --- | --- |
| `features/setup/SetupHomeStill.kt` | per-target asset | **"copying it has broken the setup wizard before"** |
| `features/details/MetaDetailsScreen.kt` | divergent layout | high |

**Action:** restore mobile's version of these two files in the merge commit, and assert it in the
procedure (§5) rather than trusting a reviewer to notice them inside a 1,190-file diff.

#### Compiler-driven correction to the initial classification

The first Android compile disproved two entries that Stage A had classified as wholesale
divergences:

- `core/build/AppFeaturePolicy.kt` is a shared contract. Keeping mobile's old copy caused roughly
  40 compile errors. The common policy now converges; target capability differences remain in its
  platform actuals.
- `composeResources/values/strings.xml` is neither a desktop file nor a mobile file wholesale.
  Keeping mobile's old copy left roughly 335 shared resource references unresolved. Shared keys
  converge, while genuinely mobile-only values are preserved additively.

The same compile exposed a desktop pointer API leak in three shared call sites. Mouse Back/Forward
and wheel handling now cross `PlatformPointerBackNavigation`; Android and iOS actuals are deliberate
identity modifiers, while system/mobile Back remains owned by `PlatformBackHandler`. This is a
platform seam, not synthetic pointer behaviour on touch devices.

### 3.2 It deletes mobile-only files that define the mobile product

These exist on mobile, were deleted on (or never existed in) desktop, and the merge deletes them:

| deleted | what it is |
| --- | --- |
| `androidMain/res/**/ic_launcher_z*.xml` (6) | **Nuvio Z's Android launcher icons** |
| `androidApp/nuvio-debug.keystore` | **the Android debug signing keystore** |
| `.github/workflows/ios-build.yml` | **the iOS CI compile Stage G depends on** |
| `.github/workflows/debug-release.yml` | the Android debug release job |
| `iosApp/Configuration/DebugVersion.xcconfig`, `ReleaseSerial.xcconfig` | iOS versioning |
| `CLAUDE.md`, `Docs/UPSTREAM.md`, `Docs/PATCH-SURFACE.md`, `Docs/VANILLA-BUGS.md` | **the canonical docs `AGENTS.md` says govern every repo** |
| `Docs/Stremio addons refer/**` (23) | addon protocol reference |
| `Docs/SOCIAL-WATCH-TOGETHER-PLAN.md`, `Docs/WATCH-TOGETHER-HANDOFF.md`, `Docs/UPSTREAM-SYNC-0.4.13.md` | mobile history |
| `PLAYBACK_MODES_PLAN.md`, `SMOKE_TEST_0.4.3*.md`, `Strings HR.xml.txt` | mobile working docs |

A merge taken at face value would strip Nuvio Z's Android identity, its signing key, its iOS CI and
its governing documentation — none of it conflicting, none of it announced.

### 3.3 It imports desktop-only trees, and mobile's LFS cannot hold them

The first trial merge **failed outright**:

```
Error downloading composeApp/src/desktopMain/native/macos/runtime/arm64/libX11.6.dylib
  [404] Object does not exist on the server
fatal: smudge filter lfs failed
```

Desktop's binaries are not on mobile's LFS remote. A full merge brings **324 `desktopMain` + 29
`desktopTest`** files and **141 LFS pointers whose objects do not exist**, permanently breaking
clone and CI checkout for the mobile repo. This is not a preference; it forecloses the full-tree
merge.

**Excluded:** `composeApp/src/desktopMain`, `desktopTest`, `windowsDesktopMain`,
`nonWindowsDesktopMain`, `desktopSentry/`, `MPVKit/`, `player-ui/`, `vendor/compose-media-player`
(196 files), desktop CI workflows, desktop-only `scripts/`, and desktop's `STATUS.md` /
`PHASE-*.md` / `STATUS-ARCHIVE.md`.

Mobile keeps its own four vestigial `desktopMain` files (`AppFeaturePolicy.desktop.kt`,
`ServerConfigurationStorage.desktop.kt`, `BackdropPaletteImage.desktop.kt`,
`AppUpdaterPlatform.desktop.kt`) untouched.

### 3.4 The player contract arrives with silent no-op defaults

Mobile's `PlayerEngineController` lacks `samplePositionMs`, `seekToExact`, `trySeekTo`,
`releaseBeforeNavigation` and `applyAudioLanguagePreferences`, exactly as the plan states. The merge
supplies desktop's interface — **but the two sync-critical methods carry defaults**:

```kotlin
fun samplePositionMs(): Long? = null
fun seekToExact(positionMs: Long) = seekTo(positionMs)
```

And desktop's `androidMain`/`iosMain` — which the merge also brings — **implement neither**. Grepped
across `PlayerEngine.android.kt` (media3 *and* `NuvioLibmpvView`), `PlayerEngine.ios.kt` and
`PlayerBridge.kt`: only `applyAudioLanguagePreferences` is overridden.

So after Stage D, **all three mobile engines compile, run, and silently return `null` position and
an inexact seek.** The party layer reads that as "no sample available" and an under-shooting seek is
re-measured as the same gap — the guest never converges. Nothing fails loudly; it presents as "sync
is broken" days later. This confirms Stage E's premise and is why its measured seek-landing check is
the highest-value test in the phase.

### 3.5 Desktop's `androidMain` is never compiled by anything, and has already rotted

This is the sharpest finding in Stage A, and it qualifies §0.

`nuviozdesktop/composeApp/build.gradle.kts` declares `jvm("desktop")` and two iOS targets. It
declares **no `androidTarget` at all**. Desktop's 39 `androidMain` files are therefore **dead source
that no compiler in that repo has ever read**. (Its `iosMain` is nominally built, but iOS is SKIPPED
on Windows, so in practice only CI touches it.)

That is not theoretical. `Platform.kt` in `commonMain` declares:

```kotlin
expect fun platformDisplayMaxHeight(): Int?
```

| ref | `Platform.android.kt` provides the actual | `Platform.ios.kt` |
| --- | --- | --- |
| merge base `00e0b236` | **yes** | yes |
| mobile `HEAD` | **yes** | yes |
| `desktop/Dev` | **no** | **no** |

Desktop deleted both actuals in `c9e47509` *"feat: add NVIDIA RTX Video Super Resolution (VSR)
support"* — a desktop-only feature that the plan already lists under "leave desktop-only". The
deletion was collateral, and **nothing caught it because there is no Android target to break.**

Consequence: the merge takes desktop's `commonMain` (which *declares* the expect and calls it from
`PlaybackSelectionContextFactory` and `PlayerEpisodeQualityChooser`) together with desktop's
`Platform.android.kt` and `Platform.ios.kt` (which no longer *provide* it). **Android and iOS both
fail to compile.** Conflict-free, silent, and fatal.

So the §0 conclusion holds for `commonMain` and `commonTest`, where desktop is genuinely ahead and
genuinely compiled — but **not** for `androidMain`/`iosMain`, where desktop's copies are unbuilt
refactors that may be behind mobile's working ones. Those 66 files are the one part of the import
set that needs file-by-file judgement rather than wholesale acceptance.

**Action:** treat `androidMain`/`iosMain` as a reviewed subset, not an import. For each of the 66,
prefer mobile's version unless desktop's carries a change mobile actually needs. The compile is the
gate, and the full `expect`/`actual` symmetry check below is the standing assertion.

#### `expect`/`actual` symmetry

134 `expect` declarations across desktop's `commonMain`, 132 distinct names. Checked against every
actual in `androidMain`, `iosMain`, and the flavour sets `androidFull`, `androidPlaystore`,
`fullCommonMain`, `iosFull`, `iosAppStore` (all of which the merge also brings):

| expect | android actual | ios actual |
| --- | --- | --- |
| `AppFeaturePolicy`, `AppVersionPolicy`, `HeroTrailerPlayerSurface` | flavour sets | flavour sets |
| `PluginRepository`, `TrailerPlaybackResolver`, `LazyListScope` | flavour sets | flavour sets |
| `AppUpdaterPlatform` | flavour sets | n/a |
| `P2pStreamingEngine` | `androidMain` | flavour sets |
| `platformDisplayMaxHeight` | `androidMain` | `iosMain` |

The controlled merge repaired that gap and reconciled the rest of the platform subset deliberately.
The post-merge static check finds an Android and iOS actual for every current common expect. Android
then compiled and ran the complete host suite; iOS still requires CI on macOS and is not claimed
verified from static inspection.

---

## 4. What Stage A changes about the plan

- **Downgrade "repo topology" from High to Medium** (not Low). 0 code conflicts and 485 files landing
  byte-identical removes the *reconciliation* cost the plan budgeted for; §3.5 replaces it with a
  smaller but sharper one. The premise that mobile carries independently-evolved playback/source work
  that must be preserved is **false for `commonMain`** — and quietly **true for `androidMain`**,
  where mobile's copies are the only ones a compiler has ever checked.
- **Stage A and Stage D collapse into one mechanical merge for `commonMain`/`commonTest`**, gated by
  the hazards above rather than by file-by-file review. The 66 `androidMain`/`iosMain` files stay a
  reviewed subset.
- **A standing repo rule falls out of §3.5:** desktop can silently break mobile's platform source
  sets, because nothing in its CI compiles them. Either desktop stops carrying `androidMain`, or
  every desktop→mobile sync runs the mobile compile as its gate. Phase 6 does the latter; the former
  belongs to whoever next touches desktop's build.
- **The cost is where the plan already said it would be for Stages E–I**: the player contract (§3.4),
  phone UI, background lifecycle, and physical verification. Those are unaffected by this finding.
- **The instruction "preserve mobile-specific improvements where they are newer/better" has no
  subject.** There are none in the shared source sets. It survives only as §3.2's preserve list,
  which is about platform identity, not improvements.

---

## 5. Proposed convergence procedure (Stage D)

Executable, and verifiable by assertion rather than by eye:

1. Branch from `fd34e331`.
2. `git -c filter.lfs.smudge= -c filter.lfs.process= merge --no-commit --no-ff desktop/Dev`
   (LFS filters off so desktop's absent binaries cannot abort the checkout).
3. **Restore class D:** remove every excluded tree from the index (§3.3), including all 141 LFS
   pointers.
4. **Restore class C:** re-add the 6 launcher resources, the keystore, both workflows, the two
   xcconfigs and all mobile-owned docs from `HEAD` (§3.2).
5. **Restore class B:** restore the two genuine whole-file divergences (§3.1), converge the shared
   `AppFeaturePolicy` contract, and merge shared resource keys with mobile-only values additively.
5b. **Review class A's platform subset:** for the 66 `androidMain`/`iosMain` files, diff desktop's
   against mobile's and keep mobile's unless desktop's carries a needed change. At minimum restore
   the `platformDisplayMaxHeight` actuals in `Platform.android.kt` and `Platform.ios.kt` (§3.5).
6. Resolve the two documentation conflicts: keep mobile's `Docs/Z-FEATURES.md` (deleted on desktop
   because it is mobile-canonical); hand-merge `STATUS.md`.
7. **Assert before committing:**
   - `commonMain`/`commonTest` converge with `desktop/Dev`, except the reviewed pointer seam, two
     whole-file divergences, and additive mobile resource values; platform source sets match the
     explicitly reviewed actual set
   - no path under the §3.3 exclusion list is present
   - `git lfs ls-files` resolves every pointer
   - every class-C path still exists
8. Gate: `:androidApp:compileFullDebugKotlin`, `:composeApp:testAndroidHostTest`, pure suites.

Because it is a real merge, the merge base advances and subsequent desktop syncs stay cheap. The
excluded trees will present as modify/delete on the *next* sync; that is the accepted, recorded cost
of not carrying desktop's binaries, and step 3 is repeatable.

---

## 6. Open items Stage A did not settle

- The `androidMain`/`iosMain` review (§3.5, procedure step 5b) is scoped but not done. `expect`/
  `actual` symmetry is asserted; semantic rot inside a method body is not, and would not be caught
  by a compile either. **Stage F/G must treat these 66 files as unverified on hardware**, not
  inherited-good.
- The class-C preserve list is derived from this merge base. It must be re-derived, not reused, at
  the next sync.

---

## Appendix A — the import set, by area

### `commonMain` — 330 files

| area | new | updated |
| --- | ---: | ---: |
| `features/player` | 14 | 38 |
| `core/ui` | 13 | 18 |
| `features/settings` | 11 | 20 |
| `<composeResources>` | 9 | 20 |
| `features/watchparty` | 26 | 3 |
| `features/social` | 17 | 6 |
| `features/details` | 2 | 18 |
| `features/home` | 4 | 13 |
| `features/playback` | 3 | 12 |
| `features/setup` | 6 | 4 |
| `features/streams` | 1 | 9 |
| `features/profiles` | 0 | 7 |
| `features/downloads` | 0 | 5 |
| `features/addons` | 0 | 4 |
| `core/network` | 2 | 1 |
| `features/simkl` | 1 | 2 |
| `features/trakt` | 0 | 3 |
| `features/updater` | 0 | 3 |
| `features/watching` | 0 | 3 |
| `features/tmdb` | 1 | 1 |
| `core/sync` | 0 | 2 |
| `features/collection` | 0 | 2 |
| `features/watched` | 0 | 2 |
| `features/watchprogress` | 0 | 2 |
| `navigation` | 0 | 2 |
| `WatchPartyLobbyDestination.kt` | 1 | 0 |
| `core/build` | 1 | 0 |
| `App.kt` | 0 | 1 |
| `AppGate.kt` | 0 | 1 |
| `AppScreenTab.kt` | 0 | 1 |
| `AppShellComponents.kt` | 0 | 1 |
| `DetailsDestinations.kt` | 0 | 1 |
| `MainAppContent.kt` | 0 | 1 |
| `MainTabsDestination.kt` | 0 | 1 |
| `Platform.kt` | 0 | 1 |
| `PlayerDestination.kt` | 0 | 1 |
| `SettingsDestinations.kt` | 0 | 1 |
| `StreamDestination.kt` | 0 | 1 |
| `core/auth` | 0 | 1 |
| `core/language` | 0 | 1 |
| `core/storage` | 0 | 1 |
| `features/catalog` | 0 | 1 |
| `features/library` | 0 | 1 |
| `features/membership` | 0 | 1 |
| `features/search` | 0 | 1 |

### `commonTest` — 95 files

| area | new | updated |
| --- | ---: | ---: |
| `features/player` | 18 | 4 |
| `features/watchparty` | 20 | 1 |
| `features/playback` | 5 | 6 |
| `features/social` | 8 | 0 |
| `features/details` | 2 | 2 |
| `features/home` | 2 | 2 |
| `core/ui` | 3 | 0 |
| `features/downloads` | 1 | 2 |
| `features/streams` | 3 | 0 |
| `features/updater` | 1 | 2 |
| `features/setup` | 1 | 1 |
| `features/simkl` | 1 | 1 |
| `SocialTabAvailabilityTest.kt` | 1 | 0 |
| `core/network` | 1 | 0 |
| `features/addons` | 1 | 0 |
| `features/tmdb` | 1 | 0 |
| `navigation` | 1 | 0 |
| `core/auth` | 0 | 1 |
| `core/language` | 0 | 1 |
| `features/membership` | 0 | 1 |
| `features/watching` | 0 | 1 |

### `androidMain` — 33 files

| area | new | updated |
| --- | ---: | ---: |
| `features/player` | 1 | 7 |
| `core/ui` | 4 | 1 |
| `features/settings` | 2 | 3 |
| `<res>` | 2 | 3 |
| `features/social` | 2 | 1 |
| `features/setup` | 1 | 0 |
| `MainActivity.kt` | 0 | 1 |
| `Platform.android.kt` | 0 | 1 |
| `core/auth` | 0 | 1 |
| `core/storage` | 0 | 1 |
| `features/addons` | 0 | 1 |
| `features/home` | 0 | 1 |

### `iosMain` — 27 files

| area | new | updated |
| --- | ---: | ---: |
| `core/ui` | 4 | 2 |
| `features/settings` | 2 | 3 |
| `features/player` | 0 | 5 |
| `features/social` | 2 | 1 |
| `features/setup` | 1 | 0 |
| `Platform.ios.kt` | 0 | 1 |
| `core/auth` | 0 | 1 |
| `core/debug` | 0 | 1 |
| `core/storage` | 0 | 1 |
| `features/downloads` | 0 | 1 |
| `features/home` | 0 | 1 |
| `features/updater` | 0 | 1 |
