# Upstream sync to vanilla 0.4.13 — working notes

Phase 1 of `ROADMAP.md`. Written 2026-09-04, mid-merge, so the analysis survives the chat that
produced it. **A merge is in progress on `claude/upstream-sync-0.4.13`** — see "Where this
stands" before doing anything else in this repo.

## Corrections to the docs, found by measuring

`Docs/UPSTREAM.md` is wrong in four places. Fix these in the same commits as the merge.

| Doc says | Actually |
| --- | --- |
| `NuvioZDesktop` forks upstream branch `desktopweb` | Upstream repo is **`NuvioMedia/NuvioDesktop`**, branch **`Dev`**. There is no `desktopweb` branch anywhere. |
| desktop fork base `1704f6c9` (2026-08-02) | That commit is upstream tag `0.1.16-alpha`, but the real merge base is **`b32dd57b` = `0.1.20-alpha`**. |
| `NuvioZWeb` is 0 behind | **137 behind** tag `1.0.5`. Vanilla web went 0.3.44 → 1.0.x. |
| pure suites are 284 per KMP repo | mobile **285**, desktop **336**. |

Also: `merge.ours.driver` and `rerere.enabled` were unset in the `nuvio-z` and `nuviozweb`
clones, so `.gitattributes`' `merge=ours` was silently doing nothing — exactly the failure the
doctrine's "machine setup" section warns about. Now set in all three.

## Drift at the target tags

| Repo | Target tag | Ahead | Behind | Patch surface | Conflict surface |
| --- | --- | --- | --- | --- | --- |
| `nuvio-z` | `0.4.13` | 319 | 90 | 153 | 27 |
| `nuviozdesktop` | `0.1.22-alpha` | 257 | 357 | 164 | 65 |
| `nuviozweb` | `1.0.5` | 24 | 137 | 20 | 18 |

Baselines before the merge, all green: mobile pure **285**, desktop pure **336**, web **184**.

> `scripts/run-pure-suites.sh` needs POSIX paths on Windows. A Windows-style work dir
> (`C:/...`) breaks its `export PATH="$WORK/kotlinc/bin:$PATH"` — the drive colon splits the
> PATH entry — and the script still exits 0, reporting success. Pass `/c/...` paths.
> It also pins `KOTLIN_VERSION="2.3.0"`, and this merge brings **Kotlin 2.4.10** and
> **Compose Multiplatform 1.12.0**. Bump the pin as part of this phase.

## The merge

11 of the 27 conflict-surface files actually conflicted. Ten are resolved:

| File | Resolved | Why |
| --- | --- | --- |
| `HomeScreen.kt` | upstream | The branch ordering looked like a Z decision but `023c2607` is **upstream's own**, pre-fork. Upstream moved its loading branch above `!hasActiveAddons` and replaced the inline condition with `shouldShowInitialHomeLoading(...)`, which also accounts for addon manifests still loading — it stops a "no addons" card flashing during startup. Nothing of Z's was lost. |
| `PlayerSettingsStorage.kt` (+ `.android`, `.ios`) | ours | Delete-vs-modify. `streamReuseLastLink*` is **byte-identical between base `0.4.8` and `0.4.13`** — upstream never touched it. Z withdrew the feature and put `addonSubtitleStartupMode` in the same place. Taking ours keeps the withdrawal and loses nothing. |
| `PlayerSettingsRepository.kt` | ours | Same feature, all six hunks. |
| `PlaybackSettingsPage.kt` | ours | Pure Z additions; checked for duplicate imports and duplicate `addonSubtitleStartupModeLabel` / `AddonSubtitleStartupModeDialog` definitions — none. |
| `PlayerScreenRuntimeEffects.kt` | ours | Upstream dropped the `collectAsStateWithLifecycle` import; it is still used at line 69 of our file. |
| `.github/workflows/android-release.yml` | union | Both sides' "not a release bump" exclusion lists, merged. |
| version xcconfigs | `merge=ours` | Held correctly once the driver was configured. |

`iosApp`'s two entry points are **not** a Z/upstream disagreement. `onAppReady`,
`nativeProfileSwitcherController` and `bypassAppGate = initialTab != Home` are all upstream's
own code, present at the fork base. Z's entire change to `MainViewController.kt` is **one
line** — a 7th `String` in `onTabTitles` for the downloads tab. Adopt upstream's new
`AppGateController` / `AppGateViewController` wholesale and re-add the 7th tab title.

## The real work: App.kt has been dissolved

Upstream split `App.kt` into a 98-line shell plus 13 new files.

| | mobile | desktop |
| --- | --- | --- |
| `App.kt` at fork base | 3,998 | 4,533 |
| `App.kt` in our HEAD | 5,436 | 6,306 |
| `App.kt` upstream now | **98** | **108** |
| Z changes to port | **+1,639 / −201**, 44 commits, 125 hunks | **+1,973 / −200** |

This is Rule 2's bill coming due — *"`App.kt` carries 40 of our commits precisely because Z
decisions were written inline in it"* — and Rule 6's moment: refactor at the first sync that
conflicts. There is no "keep ours" option, because upstream's 13 new files merged in cleanly as
additions and their declarations collide with our monolith.

### Which new files are moves and which are new code

Measured by matching each new file's body against the base `App.kt` (`split_map.py`).

| Upstream file | Origin in base App.kt | Match |
| --- | --- | --- |
| `AppNavigationSupport.kt` | base[2:136] | 93% — a move |
| `AppScreenTab.kt` | base[96:124] | 93% — a move |
| `MainAppContent.kt` | base[156:3410] | 79% — a move |
| `AppShellComponents.kt` | base[3412:3724] | 61% — a move |
| `AppGate.kt`, `AppGateController.kt`, `AppGateOverlay.kt`, `MainTabsDestination.kt`, `DetailsDestinations.kt`, `SettingsDestinations.kt`, `StreamDestination.kt`, `PlayerDestination.kt`, `CatalogDestination.kt` | — | largely **new upstream code** |

So upstream did not merely move code: it **rewrote the navigation layer**, extracting the
destination composables out of `MainAppContent`'s NavHost and reworking them.

### Conflict volume of a slice-wise three-way merge

Each moved file three-way merged as (ours slice, base slice, upstream file) — `try_merge.py`.

| File | Hunks | Lines in conflict |
| --- | --- | --- |
| `AppScreenTab` | **0** | 0% |
| `AppNavigationSupport` | 1 | 25% |
| `AppShellComponents` | 4 | 31% |
| `MainAppContent` | 10 | **60%** (2,819 of 4,671) |

Three port near-mechanically. `MainAppContent` does not, because upstream pulled ~1,400 lines
out of the same region into which Z added ~1,300. Its share of Z's 125 hunks has to be triaged
by hand into `MainAppContent.kt` and the six destination files.

## Where this stands

- Branch `claude/upstream-sync-0.4.13`, cut from `main`. The port is **complete and compiling**;
  the merge is committed. No conflict markers remain anywhere in the tree.
- `rerere` recorded preimages for all 11 conflicts, so a re-run of this merge replays the ten
  resolutions automatically.
- Scratch analysis (`split_map.py`, `try_merge.py`, the sliced sources and merge outputs) is in
  the session scratchpad, not the repo. Both scripts are short enough to rewrite from this
  document if lost.

### Ported so far

Three of the four moved files are done and written into the working tree.

| File | Z content carried over |
| --- | --- |
| `AppScreenTab.kt` | The `Social` enum entry and both `NativeNavigationTab` mappings. Merged with **zero conflicts** — upstream never touched the enum. |
| `AppNavigationSupport.kt` | The `WatchPartyLobbyRoute` serializer registration and `PlaybackRouteDecisionSaver`, plus 3 imports. Its one conflict was only the `AppScreenTab` block overlapping the slice; that code now lives in `AppScreenTab.kt`. |
| `AppShellComponents.kt` | Seven deltas: `socialScrollToTopRequests` on `AppTabRequests`; `onContinueWatchingDetails`, `onJoinParty`, `onJoinInvitedParty`, `onWhatsNewClick`, `onRunSetupAgainClick` on `AppTabActions`; the `AppScreenTab.Social` branch; the Library tab's `onDownloadsClick`; and the Social pill in `TabletFloatingTopBar`. |

The shape of that last one is the shape of the whole port: **upstream replaced `AppTabHost`'s
flat parameter list with three data classes** (`AppTabState`, `AppTabRequests`,
`AppTabActions`), so each Z parameter has to be re-homed onto the right class and every call
site rewritten to `state.` / `requests.` / `actions.`.

`onOpenDownload`, `onDownloadShowClick` and `onChooseBatchEntryManually` were deliberately **not**
added to `AppTabActions`: they appear only in our old `AppTabHost` signature and are never used
in its body. They belong with the downloads destination, and are handled in the step below.

### How the MainAppContent port was actually done

The slice of Z's diff covering the moved region is 57 hunks, +1,512/−201. Each was routed to a
destination file by scoring its unchanged context lines against every candidate file, then
applied with `patch`, which refuses a hunk whose context is not genuinely present. 48 placed
themselves; 9 were placed by hand.

| Destination | Hunks |
| --- | --- |
| `MainAppContent.kt` | 25 |
| `StreamDestination.kt` | 16 |
| `MainTabsDestination.kt`, `PlayerDestination.kt` | 2 each |
| `App.kt`, `DetailsDestinations.kt`, `SettingsDestinations.kt` | 1 each |
| by hand | 9 |

> ⚠ **`patch` places by context, and context is not scope.** Three real defects came out of
> this and every one needed a human read: two hunks that both add a Social `NavItem` landed at
> the *same* rail in `MainTabsDestination`, corrupting upstream's list; `nativeTabDownloadsTitle`
> was added twice to one call instead of once there and once in the `LaunchedEffect` keys; and a
> block of 61 imports was dropped mid-file in `MainAppContent`. Treat automatic placement as a
> first pass to be reviewed, never as the answer.

Z's code moved but its **imports did not** — imports live in a file header. They were carried
per file by the rule "add an import iff the name it binds appears as a word in the body,
ignoring comments". Note this must draw on *all* of the old `App.kt`'s 332 imports, not just the
ones Z added: Z's code also leans on imports that predate the fork.

### Decisions taken during the port

- **`bypassAppGate`, `onAppReady`, `nativeProfileSwitcherController` are upstream's**, present
  at the fork base. Z never authored them, so upstream's new `AppGateController` /
  `AppGateViewController` are adopted whole. Z's entire change to `MainViewController.kt` is one
  line: a 7th `String` in `onTabTitles` for the downloads tab.
- **`DownloadsSettingsRoute` shows the settings page, not the downloads list.** Upstream's
  `DownloadsDestination` renders `DownloadsScreen`; Z renders `DownloadsSettingsScreen` and
  reaches the list through `DownloadShowRoute`. Z's routing is preserved.
- **`onOpenDownload` / `onDownloadShowClick` / `onChooseBatchEntryManually` are not
  `AppTabActions` fields.** They appear only in our old `AppTabHost` signature and are never
  used in its body, so they were wired at the downloads destinations instead —
  `DownloadShowDestination` gained `onChooseBatchEntryManually` and passes it to
  `DownloadsScreen`, which already accepts it.
- **`ContentDownloadAction`** was added beside upstream's `ContentPlayAction` typealias, because
  the details destination now needs a 12-parameter download callback threaded through it.
- **The reuse-last-link block was deleted from `StreamDestination.kt`.** Z withdrew the feature
  and `PlayerSettingsRepository` no longer exposes `streamReuseLastLinkEnabled`, so upstream's
  copy could not have compiled. This is the one place where the two resolutions had to agree.
- **`addonSubtitleStartupModeKey` was lost by the merge** in both storage actuals — the constant
  sat immediately before the reuse-last-link keys and went with them. Restored in both.

### What the merge silently dropped

Three pieces of Z code went missing with no conflict marker, because each sat immediately beside
lines upstream had changed. All three were found by the compiler, not by reading the diff, and
all three restorations are pure additions:

| Lost | Where it lives |
| --- | --- |
| `private const val addonSubtitleStartupModeKey` | both `PlayerSettingsStorage` actuals — it sat directly above the reuse-last-link keys and went with them |
| `enum class AddonSubtitleStartupMode` | `features/player/SubtitleAudioModels.kt` |
| the body of `AddonSubtitleStartupModeDialog` | `features/settings/PlaybackSettingsPage.kt` — the function was left truncated after its `options` list, which silently swallowed **every declaration below it in the file** |
| `onDetailsClick` on one `homeContinueWatchingSections` call | `features/home/HomeScreen.kt`, the loading branch |

> **The lesson for the desktop sync:** a clean `git merge` is not evidence that nothing was lost.
> Two of these produced no conflict at all. Budget for a compile-driven pass whose only job is
> finding Z code the merge quietly deleted, and check brace balance per file — a truncated
> function reads as dozens of unrelated "unresolved reference" errors elsewhere.

### Finishing the port

Hunk 47's last segment was the missing `playbackHandedOff = true` at the plain
`navigate(PlayerRoute(...))` exit of `openSelectedStream`, with the comment explaining why the
flag has to be set there. All five `= true` sites now match the pre-merge `App.kt` in statement
order and comment.

**Re-indenting.** `patch` inserted Z's lines at base's indentation into upstream's re-indented
bodies, so several hundred lines sat at the wrong column. 812 lines were shifted across four
files, and the drift ran in *both* directions:

| File | Lines | Drift |
| --- | --- | --- |
| `StreamDestination.kt` | 412 | 16 too deep (26 blocks) |
| `MainAppContent.kt` | 353 + 745 + 32 | one 400-line block 16 too *shallow*, then the whole function body 4 too deep |
| `PlayerDestination.kt` | 47 | 16 too deep |
| `DetailsDestinations.kt` | 14 | 16 too deep |

The method generalises and is worth reusing on desktop: compute each line's expected indent from
a brace/paren depth walk that skips strings and comments, and take the *distribution* of
`actual - expected`. It is sharply bimodal — 0/1/4/8 for correct lines and their continuations,
16/17/20 for a block inserted four levels too deep — so the ported blocks identify themselves.
Shift whole runs, never single lines, then confirm the result differs from the pre-edit file by
whitespace only (`diff -w`) and that every file's brace depth still returns to zero.

### A fifth thing the merge silently dropped, found after the compile was green

`AddonSubtitleStartupPolicy.kt` and its 40-line test were **deleted outright** by the merge, and
the gate they exist for was dropped from `PlayerScreenRuntimeEffects.kt` — the effect lost
`playerSettingsUiState.addonSubtitleStartupMode` from its keys along with the `canFetch` check.
Nothing referenced the function any more, so it compiled perfectly: the Fast-startup subtitle
setting was still in the settings UI, still synced, still stored, and wired to nothing.

> **A compile-driven pass is not enough.** The four losses in the section above were all found by
> the compiler because something still referenced them. This one was found by asking a different
> question: *which files existed at HEAD and do not exist now?* — `git diff --cached
> --diff-filter=D --name-only`. Run it on the desktop sync. It takes a second and it is the only
> check that catches a Z file the merge removed together with its last caller.

### Results

- Pure suites: **285**, green — the exact pre-merge baseline (126 + 64 + 49 + 17 + 29).
- `:androidApp:compileFullDebugKotlin`: green.
- iOS framework and IPA: CI only, no macOS here.

`scripts/run-pure-suites.sh` needed two fixes before it could report anything at all, and both
are the same failure mode the doctrine warns about — **a green run that ran nothing**:

1. The serialization compiler plugin was pinned at `2.3.0` while `KOTLIN_VERSION` moved to
   `2.4.10`. A plugin built against an older compiler dies at registration with
   `NoSuchMethodError: ...ExtensionStorage.registerExtension`. That is not a compile error, so it
   scrolled past as a stack trace and the script carried on. The URL and the cached jar name now
   both interpolate `${KOTLIN_VERSION}`.
2. There is no `java` on PATH on this machine — the only JDK is Android Studio's bundled runtime
   — so every `java` line failed with "command not found" and the script still exited 0. It now
   falls back to `$JAVA_HOME/bin` and exits 1 if it cannot find a JVM.

### Remaining, in order

1. Regenerate `Docs/PATCH-SURFACE.md` — it should shrink sharply, since `App.kt` went from
   5,436 lines of ours to a 98-line upstream shell.
2. Watch CI for the iOS framework build and the Android release workflow.

## Handing the desktop sync its own chat

Open with: *"read `Docs/UPSTREAM-SYNC-0.4.13.md` in `nuvio-z`, then do the `nuviozdesktop` sync"*,
and ask the standing question first — the answer for mobile was "nothing checked since Phase 0".

What that chat inherits:

- **The same `App.kt` dissolution**, one size larger: base 4,533 → upstream 108 lines, with
  **+1,973 / −200** of Z changes to port. Verified, not assumed.
- **`upstream` is already wired** in `nuviozdesktop` to `NuvioMedia/NuvioDesktop`, branch `Dev`,
  with tags fetched. Target tag **`0.1.22-alpha`**. Real merge base is `0.1.20-alpha`.
- **357 commits behind, 65-file conflict surface** — against mobile's 90 and 27, so expect
  roughly double the work, not the same.
- `merge.ours.driver` and `rerere.enabled` were already set in that clone.
- Desktop's pure baseline is **336** and was green before any of this.
- Desktop additionally has the two native player bridges (`player_bridge.mm`,
  `player_bridge.cpp`) and the HTML controls card in its conflict surface, none of which mobile
  had. `STATUS-ARCHIVE.md` lives at the **repository root** there, not under `Docs/`.

The method that worked, in order: measure drift at a named tag → merge with `--no-commit` →
resolve the non-`App.kt` conflicts by checking provenance (`git show <base>:<file>`) before
picking a side → take upstream's `App.kt` shell → route the Z hunks by context → compile → fix
imports wholesale → chase what the merge dropped. Do not trust `patch` placement without reading
it.
3. Re-add the 7th `onTabTitles` string; adopt upstream's gate in both iOS entry points.
4. Bump `KOTLIN_VERSION` in `scripts/run-pure-suites.sh` to match the incoming toolchain.
5. Re-run all suites; regenerate `Docs/PATCH-SURFACE.md` and review reason-free rows.
6. Adopt `0.4.13-z1` with release serial 127; give `RELEASE_SERIAL` its own file so the
   release-notes script does not read a serial bump as a release bump.
7. Correct `Docs/UPSTREAM.md` per the table at the top, and `AGENTS.md`'s base line.
