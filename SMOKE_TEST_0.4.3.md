# 0.4.3-beta smoke + stress test plan

Everything shipped since `0.3.10` has been verified by unit tests only, except for the two
regressions found on a phone in `0.4.0-beta`. This is the list of things **no unit test reaches**.

Ordered by *risk × likelihood of being hit by a normal user*. If you only have an hour, do
sections A and B.

## Before you start

Two builds, both from the current HEAD (`ee718912` / `1a824871`):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :composeApp:testAndroidHostTest --console=plain --max-workers=4   # expect 653 / 94 classes
.\gradlew.bat :androidApp:assembleFullRelease --console=plain --max-workers=4
```

and in `NuvioZDesktop`: `:composeApp:desktopTest` (expect 859 / 124), then the packaged build.

**Install over an existing older build — do not wipe data.** Half of what's below only fails on
a migrated install: What's New, the advanced-settings default, the first-launch selector, and the
sync-wipe fix all behave differently on a clean install.

⚠ **The migration tests require the release-signed build installed over the existing one.** If the
local keystore isn't in place and you fall back to `assembleFullDebug`, that installs as
`com.nuvio.app.z.debug` — a *separate app with empty storage*. What's New-shows-once, the
advanced-settings default and selector-seen would then all pass for the wrong reason.

Check what's already on the phone first (`adb shell dumpsys package com.nuvio.app.z | findstr
versionName`). If it's already `0.4.2-beta`, section A1's "appears once" cannot fire — see A1.

Have ready: a debrid account with cached *and* uncached content, a plugin-heavy profile, a second
signed-in device, and mobile data you're willing to spend.

---

## A. Playback modes — the core of the release

### A1. First-launch selector (highest risk — it broke twice)

Split by install, because `playback_mode_selector_seen` is already true on a phone that ran
`0.4.2-beta`:

**On the migrated install** — the pass condition is that the selector **never appears at all**.
- [ ] Launch the upgraded build → straight to the app, no selector.
- [ ] The migration-safe version of the `0.4.0-beta` reappearance bug is a **sync pull**, not a
      relaunch — it's step B4. Don't skip it thinking this covered it.

**On cleared data or a fresh profile** (do this second, or on a spare device):
- [ ] Selector appears **once**. Read the three cards — each should have a tagline plus
      **Streaming** and **Downloading** blocks. No "Not ready yet" anywhere.
- [ ] Press Continue on Classic → nothing about playback changes.
- [ ] Force-stop, relaunch → **selector does not reappear**.
- [ ] Switch profile → selector logic and chosen mode are per-profile.

### A2. Instant, happy path (Wi-Fi)
- [ ] Play an episode: you see the **progress overlay**, never the source list scrolling by.
- [ ] Watch the step labels actually change: Finding sources → Choosing → Resolving link →
      Starting playback. A label that sticks for 20s+ is a finding, not a pass.
- [ ] It plays a **cached** debrid source. If you ever land on a 2-minute
      `MEDIA_NOT_CACHED_YET` slate, that's the `0.4.2-beta` bug back.

### A3. Instant, failure chain
- [ ] Kill the chosen source mid-flight (pull the link, or pick content with a dead top result):
      overlay reads **"Attempt 2 of 3"**, still no source list.
- [ ] Exhaust all three → falls back to the Classic source list **with a reason shown**.
- [ ] Never "Attempt 5 of 3" — it's clamped, confirm it.

### A3b. Instant on a plugin-only profile (no debrid)
The uncached-debrid fail-safe is deliberately scoped to debrid-backed candidates; applying it
globally would empty the candidate set. `aNonDebridSourceWithNoCacheStateStillPlays` guards that
in unit tests, but plugin metadata ingestion was rebuilt in Phase 2 and never run for real.
- [ ] Profile with plugin scrapers and **no debrid configured**, Instant mode → does it pick and
      play at all, or hang on "Finding sources"?
- [ ] Same for a plain direct-HTTP addon source.

### A4. Instant, back-button (the regression this pass introduced and fixed)
- [ ] Start Instant playback, then **press back out of the player**.
      You must land on something usable — *not* an opaque full-bleed "Starting playback" overlay.
- [ ] Repeat after force-stopping the app mid-playback (`rememberSaveable` survives process
      death, which is how this got nasty).

### A5. Instant on metered / mobile data
- [ ] Switch to mobile data, play: the **metered sheet appears instead of the overlay**.
- [ ] Answer "capped" → plays at ≤720p (or your `playback_metered_cap_height`).
- [ ] Play a second thing in the same session → **no second prompt** (once per network per session).
- [ ] Background the app for a while, come back, play → prompt may return; that's a new session.

### A6. Streamlined
- [ ] Quality sheet → pick a tier → overlay → player.
- [ ] "Best available" tier works.
- [ ] An **uncached debrid** result is offered as an explicit choice, not silently auto-played.
- [ ] "Choose source manually" from the sheet still reaches the full Classic list.
- [ ] Sticky pin: pick a release manually, accept the pin prompt, then play the **next 3 episodes**
      of the same season — each should be one tap on the same release group.
- [ ] Then play an episode where that release group doesn't exist → falls through cleanly, no hang.

### A7. Escape hatches (every one of these must uncover the source list)
- [ ] Long-press an episode on mobile → "Play manually" is present in **all three modes**.
- [ ] Right-click on desktop → same.
- [ ] P2P consent prompt appears **on top of nothing** — the overlay must be gone.
- [ ] Sticky-pin prompt, metered sheet, uncached sheet — same rule.

### A8. Auto source-swap (Instant only, off by default)
- [ ] Turn it on in Settings. Throttle your connection mid-playback (router QoS, or walk away
      from the Wi-Fi AP) and hold it there for ~15+ seconds of genuine buffer starvation.
- [ ] It downshifts **once**, same release group, position preserved.
- [ ] It does **not** swap again in the same episode; next episode re-arms.
- [ ] Seek around right after starting → no swap in the first 15s (settle grace).
- [ ] On an HLS/DASH manifest → never swaps (they adapt internally).

---

## B. Settings sync — the data-loss class of bug

This is the one that silently ate data in `0.4.0-beta`, and the fix touched **19 actuals** across
six stores. Nothing here fails loudly; you have to go look.

- [ ] Device 1: set a non-default value in **every** one of these — playback mode + quality tiers +
      metered cap, MDBList, TMDB, stream badges, Trakt comments, theme, and **debrid API keys**.
- [ ] Sign in on device 2, pull. Then pull again on device 1.
- [ ] Check all seven categories survived on **both** devices. Debrid API keys are the worst case —
      those were reachable for deletion whenever a provider was added.
- [ ] **B4.** Specifically: does the first-launch selector reappear after a pull? It must not.
      This is the migration-reachable form of the `0.4.0-beta` bug — see A1.
- [ ] Trakt comments: turn comments **off**, sync, pull → stays off (the desktop actual was
      silently resetting this).
- [ ] Desktop ↔ mobile in both directions, not just mobile ↔ mobile.

**Stress:** toggle settings on both devices and pull repeatedly / rapidly. Last-write-wins is
expected; silent deletion of an untouched category is not.

---

## C. Advanced settings toggle

- [ ] **On the migrated install**: if you'd previously touched any advanced setting (decoder
      priority, reuse-last-link, tunneled playback, DV7→HEVC), those rows stay **visible** by
      default. If they vanished, `hasTunedAnAdvancedSetting` is misjudging and it reads as data loss.
- [ ] Toggle off → the tagged rows disappear (torrent auto-pick, auto-downshift, reuse-last-link
      + its cache duration, decoder priority, DV7→HEVC, tunneled playback, the Advanced page row).
- [ ] With it **off**, use settings search for a hidden row by name → it's found, and revealing it
      works on the landing page.
- [ ] Navigate back to Settings root → the reveal clears.

---

## D. What's New

- [ ] Install over an older build → What's New shows **once**, automatically.
- [ ] It lists **previous versions** too, not just the current one.
- [ ] Markdown renders: no literal `## Fixes` headings, no literal `- ` bullets.
- [ ] Force-stop, relaunch → **does not reappear**.
- [ ] Settings → About → What's New opens on demand, **with the network off** (current version's
      notes are curated offline; only the fetched history should degrade to "needs a connection").
- [ ] Open it from About *before* it auto-shows → it must still auto-show after the next update.
- [ ] **Do this on desktop too** — the desktop hand-port was comparing the wrong version constant.

### D2. The updater banner (highest blast radius in the release)
`AppUpdater.kt` and `AppUpdaterBanner.kt` both changed, and the banner now renders release notes
through the new `parseReleaseNotes`. This is the path by which every future build reaches your
device — a parser that chokes on a real GitHub release body breaks shipping itself.
- [ ] Banner appears when a newer release exists on `Zokaper/nuvio-z`.
- [ ] Its notes render properly — no literal `## ` headings, no literal `- ` bullets.
- [ ] The update actually **downloads and installs**, end to end.
- [ ] A release body with unusual markdown (tables, nested lists, inline links) doesn't blank the
      banner — unrecognised syntax should fall through as a paragraph.

---

## E. Downloads following the mode

The download engine is untouched, but the entry point changed and one path silently played
instead of downloading.

- [ ] **Classic, single item**, press Download → source list opens, tapping a release
      **enqueues it — it must not start playing.** This is the exact bug that was fixed.
- [ ] Classic, whole season → preset dialog, as before.
- [ ] Streamlined → preset dialog, unchanged.
- [ ] Instant → downloads start with **no dialog**, at the tier matching the connection.
- [ ] Instant on **mobile data** → respects `allowMetered = false`, i.e. queues rather than burning
      your data.
- [ ] Degradation: a profile with **no configured presets**, and content with **no resolvable
      single video** → both fall back to the preset dialog, no dead tap.

**Stress (regression risk on the older fix set):** queue a full season over TorBox, then pause /
resume / reorder / force-stop the app mid-transfer. Watch for the old 80%-stall shape and for a
"completed" download whose bytes on disk don't match.

---

## F. Cross-platform

- [ ] Desktop build: everything in A, B, C, D, E that isn't mobile-specific. The desktop repo has
      genuinely different `AppFeaturePolicy` gating, so its settings pages were hand-ported — read
      them, don't assume.
- [ ] Desktop network detection defaults to unmetered Ethernet — confirm Instant doesn't prompt.
- [ ] **iOS is entirely unverified.** The buffer fix in `MPVPlayerBridge.swift:883` has never been
      compiled. When you get to a Mac: build first, then check the player's buffer readout is
      sane — before the fix it grew with playback position and never showed starvation.

---

## Known gaps (not bugs — don't file these)

- What's New's About row isn't in the settings search index.
- iOS Swift changes are uncompiled.
- Instant's 8-second startup budget lives on the player screen, outside the overlay's scope.
