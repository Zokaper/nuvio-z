# Nuvio Z and Upstream Nuvio

**Nuvio Z is a mod of Nuvio.** Not a fork we maintain, not a separate product line - a bounded,
named set of patches that rides on a stated vanilla base. Vanilla features arrive by
**inheritance**, not by re-implementation. Every Nuvio Z release names the vanilla release it is
built on.

Nothing here is destined for upstream. `upstream` stays wired so their work can come to us.

## Why this document exists

For the first three and a half weeks of Nuvio Z, none of the three repositories pulled a single
upstream commit, and mobile's `upstream` remote - declared in `AGENTS.md` since the first week - had
**never been fetched at all**. In the 24 days after the mobile fork, vanilla NuvioMobile shipped
**nine releases**:

| | |
| --- | --- |
| `0.4.0` | SIMKL as a native tracking provider, torrent cache management, entity browser badges |
| `0.4.1` | selectable app icons, Trakt watched-marker matching, skip-intro boundary clamping |
| `0.4.2` | addon subtitle loading aligned between mobile and TV |
| `0.4.3` | **provider credential synchronisation across clients**, Greek localisation completed |
| `0.4.4` | **compact watched persistence to prevent OOM**, TMDB discovery exclusion filters, binge-group fallback toggles |
| `0.4.5` | episode progress spacing, iOS subtitle size constraints, addon cache control |
| `0.4.6` | self-hosted server discovery endpoint, PIN caching, **Bulgarian, Hungarian, Arabic** |
| `0.4.7` | supporter perks, **SDH subtitle filtering** aligned with MPV |
| `0.4.8` | profile management, background loading, anime movie handling for SIMKL |

We had none of it, and no way to find out we did not. Vanilla NuvioWeb ships every one to three
days.

## The proof the model works

Measured at the 0.4.13 sync (2026-09-04), each against the target tag named below.

| | our commits | behind upstream | files upstream owns that we edit |
| --- | --- | --- | --- |
| `nuvio-z` | 319 | **90** at `0.4.13` (now merged) | **153** |
| `NuvioZDesktop` | 257 | **357** at `0.1.22-alpha` | **164** |
| `NuvioZWeb` | 24 | **137** at `1.0.5` | **20** |

`NuvioZWeb`'s zero was an artefact of never having fetched: vanilla web went 0.3.44 -> 1.0.x while
the fork sat still.

`NuvioZWeb` carries a complete port of the playback-mode system - the facts extractor, the ranking,
the mode router, the quality options, the source selector, the startup watchdog, the quality sheet,
eight synced settings keys and 70 strings - and it touches **eight** upstream files to do it,
because it was built as new modules plus minimal seams.

The mobile fork's 128-file surface is what happens without that discipline. The rules below are
mostly "do what the web port already did".

## The rules

### 1. New code goes in new files

Whole packages that are 100% ours carry **zero** conflict risk. On mobile that is already 133 of
our 252 changed files: the playback package, the setup package, What's New, the network package,
the release-tag and language vocabularies, and most of the new downloads files.

A new Z feature belongs in a Z-owned package. If it does not obviously have one, that is a sign it
is being written in the wrong place.

### 2. Touch upstream files at seams, not in bulk

One insertion point, ideally one call. If a change needs twenty lines inside an upstream function,
**the twenty lines go in a Z file and the upstream file gets one call**.

This is the rule that decides how much a sync costs. `App.kt` carries 40 of our commits precisely
because Z decisions were written inline in it; the playback package carries none.

### 3. Never reorganise upstream code for cosmetic reasons

The settings reorganisation (C2 in `Docs/Z-FEATURES.md`) is the case study. It moved rows between
upstream's own settings pages, in a 3,903-line upstream-owned file, deleted nothing, changed no
storage key, has **still never been seen on a screen**, and is permanently on the merge path.

The problem it solved was real. The shape of the solution is the one we stop choosing.

### 4. A commit that widens the patch surface says so

Name the upstream file, and say why a seam was not possible. `Docs/PATCH-SURFACE.md` is reviewed at
every sync, and a file that appears there without a reason is a refactor waiting to happen.

### 5. Strings go in one appended block

The shared strings file is our **worst** conflict file - 47 of our commits on mobile, 44 on
desktop - and it is a file upstream churns constantly with every new locale. Z strings belong in one
contiguous block behind a marker comment at the end, never interleaved with upstream's. Interleaved,
every conflict is a manual read; appended, it is mechanical.

### 6. Retro-refactor opportunistically, not pre-emptively

The `PlayerScreenRuntime*` cluster (5 files, 53 of our commits) and the `PlayerSettings*` cluster
(4 files, 49) are where Z threads playback-mode state through upstream's player runtime inline.
They are the files a sync will actually hurt in.

**Refactor one into a proper extension point the first time a sync conflicts in it** - that is when
the cost is justified and the correct shape is visible. Rewriting all nine in advance is speculative
work against a merge that has not happened.

### 7. A vanilla bug is noted, not fixed - unless it breaks something of ours

Nuvio Z inherits vanilla's features. It inherits vanilla's bugs with them, and the default answer to
one is **write it down and wait**.

The reason is the patch surface, not purity. A vanilla bugfix is the worst possible addition to it:
it lives *inside* upstream logic, where no seam is available by definition - if a clean seam existed
the bug would be ours, not theirs - and it becomes a permanent conflict the day upstream fixes the
same bug their own way. We would then be merging our fix against their fix, in their file, forever,
for a bug neither of us still has.

**The triage:**

1. **Check whether upstream already fixed it.** This is the answer most of the time. We run behind
   vanilla by design, so a bug found today has a real chance of being fixed in commits we have not
   merged yet - free, and on the base we will actually ship. `scripts/upstream-drift.sh` says how
   far back we are looking.
2. **Fix it only if it breaks something of ours** - a Z feature, or a phase exit gate in
   `ROADMAP.md`. Then: a seam if one can be made, an entry in `Docs/PATCH-SURFACE.md` naming the
   upstream file and saying why no seam was possible, and the commit tagged **`drop-at-next-sync`**
   so it is *removed* when upstream lands their version rather than defended in a conflict.
3. **Otherwise log it in `Docs/VANILLA-BUGS.md` and inherit the fix.** One line. It costs nothing,
   and it stops the same bug being investigated three times as a suspected Z regression - which is
   the actual failure this rule prevents.

**Confirming it is vanilla is part of the report.** "Probably upstream" is not a finding. Reproduce
it on a vanilla build, or say plainly in the register that it has not been confirmed and is
therefore not yet an answer to anything.

## Versioning

**A Nuvio Z version is a vanilla version plus a Z revision.** Vanilla ships `0.6.0`, we ship
`0.6.0-z1`. Iterating on the same base gives `0.6.0-z2`, `0.6.0-z3`. The revision **resets when the
base moves**.

Settings, About reads: `Nuvio Z 0.6.0-z2 - based on Nuvio 0.6.0`.

### Release ordering is a serial, not the version string

The name can no longer be trusted to sort, because a Z version follows vanilla's number and vanilla
resets it. So the updater orders releases by an explicit **`RELEASE_SERIAL`** - a monotonic integer
with the same discipline the Android version code already has: it only ever increases.

- It is published **in the tag**, not as a new asset: `v0.6.0-z1+127`. Both updaters already read
  the tag name, so this costs no extra request.
- The updater parses the `+<serial>` suffix and compares on it, and **falls back to the existing
  string comparison when the suffix is absent**, so every pre-transition release stays orderable.
- The **Android version code is unaffected** - it is independent and already monotonic. Installing a
  lower-*named* build over a higher one is fine.
- The **debug channel keeps its own tag prefix**: `debug-v0.6.0-z1.3+128`.

### The transition needs a bridge release, once

**Old installs run the old updater.** A device sitting on `0.4.14-beta` compares by parsing the
version string and will refuse anything numerically lower - so it can never be offered `0.4.9-z1`,
no matter what we ship. The transition therefore has exactly one awkward step:

| | Version | Why |
| --- | --- | --- |
| Bridge | `0.5.0-beta` | Ranks above `0.4.14-beta` **by the old rule**, so every existing install is offered it. Carries the serial-aware updater. |
| First mod release | `<vanilla>-z1` | Free to go backwards by name; every install that took the bridge orders by serial. |

**`NuvioZWeb` needs no bridge.** It is at `0.3.37` and vanilla is at `1.0.5`, so adopting
vanilla's number moves *forward*.

### The trap to not reintroduce

The release-notes script treats **any** commit touching the release version file as a release bump.
That is what truncated `0.5.0-beta`'s notes when a debug-counter bump landed between two releases,
and why the debug counter was moved to a file of its own. **Give `RELEASE_SERIAL` its own file too**,
unless the script is taught to ignore a serial-only change.

## The sync

### Merge. Never rebase.

`STATUS.md` references our commits by SHA throughout and there are 245 of them. A rebase rewrites
every one of those references.

There is precedent for the other approach and it should not be repeated: `NuvioZDesktop` was
rebased onto upstream once, on 2026-08-02, which is why its Z commits share no SHAs with mobile's
even where the work is identical.

### The three fork points are on three different upstream branches

| Repo | Base | Upstream branch |
| --- | --- | --- |
| `nuvio-z` | `979d5680`, 2026-07-29 | `NuvioMedia/NuvioMobile`, **`cmp-rewrite`** - not `main` |
| `NuvioZDesktop` | `b32dd57b` = `0.1.20-alpha` | `NuvioMedia/NuvioDesktop`, **`Dev`** |
| `NuvioZWeb` | `0c3bafc`, 2026-08-22 | `NuvioMedia/NuvioWeb`, `main` |

Two of these were wrong until the 0.4.13 sync measured them: the desktop fork was recorded as
`1704f6c9` on a branch called `desktopweb`. That commit is upstream's `0.1.16-alpha` tag, and no
`desktopweb` branch exists in any of the upstream repositories.

Fetching the wrong branch gives a misleading ahead/behind. This is also the main argument for
unifying the two KMP repositories: otherwise every sync is performed twice, against two different
upstream branches, with conflicts hand-resolved in both.

### The procedure

1. **`scripts/upstream-drift.sh`** - know the size before starting.
2. Fetch, and merge the **tag** of a specific upstream release, not a moving branch head, so the
   base is a nameable version.
3. Resolve, in expected order of pain: the strings file (mechanical once rule 5 holds), `App.kt`
   (the hard one), the two player clusters (apply rule 6), version files (already handled by
   `merge=ours`).
4. Run everything: the pure suites (**285** on mobile, **336** on desktop), the Android host suite (**975**), the
   desktop suite, and `npm test` (**177**) on web.
5. Regenerate `Docs/PATCH-SURFACE.md`; review every file that gained a reason-free row.
6. Record the new base commit and version in `STATUS.md`, in `AGENTS.md`'s base line, and in the
   release notes.

### Cadence

- **A sync is step 1 of the release procedure.** No Nuvio Z release is cut on a stale base.
- **Floor of every two weeks**, so quiet periods do not accumulate drift.
- A **weekly drift report** runs in CI and writes ahead/behind plus the conflict list to a pinned
  issue, so the size of the next merge is never a surprise.

### Machine setup, once per clone

```
git config merge.ours.driver true
git config rerere.enabled true
```

`.gitattributes` marks the version files `merge=ours`, which removes 61 of our commits from the
merge path - but **the driver must be configured in every clone or the attribute silently does
nothing**. `rerere` means a conflict resolved once in the strings file is not resolved again a
fortnight later.

### What to test hardest after a sync

Anything upstream ships that touches **settings sync**. Nuvio Z rewrote that machinery across 19
actuals to fix a bug where a payload from an older build silently deleted every new key - which is
what wiped playback settings in `0.4.0-beta`. Upstream's provider-credential sync and its new
tracking provider both land in the same place.

## Related

`Docs/Z-FEATURES.md` - what Nuvio Z is. `Docs/PATCH-SURFACE.md` - every upstream-owned file we
modify. `nuvioweb/docs/Z-PORT-MATRIX.md` - what the TV app gets and what it deliberately does not.
`AGENTS.md` - the working rules. `STATUS.md` - the handoff.
