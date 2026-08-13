# Nuvio Z Status

Last updated: 2026-08-12

| | |
| --- | --- |
| **Active branch** | `claude/onboarding-setup-wizard-7juovt` in both repositories, cut from `claude/release-0.5.0-beta-polish-ivcjsl` (**not** from `main` / `Dev` — phase 1 is not on the default branches yet, and this edits the same `App.kt` gate). Carries **phase 2 of `0.5.0-beta`, the setup wizard**, on top of the phase-1 polish pass. **Not yet released and the version is deliberately not bumped.** |
| **Released** | `0.4.14-beta` on both. Superseded once phase 1 and phase 2 ship together as `0.5.0-beta`. |
| **Next** | **Run the `SetupWizardRenderHarness` on the Windows host first** — the wizard has still never been rendered anywhere, and that gap is what let revision 2's unreadable sheet and revision 3's uncentred chips reach a device. Then **run both device scripts** — "The 0.5.0-beta device script" for phase 1 and "The setup wizard device script" for phase 2, whose first five checks are the regressions from revisions 2 and 3. Test with `debug-v0.4.14-beta.9`. Then merge to `main` / `Dev`, bump both version files as the final commit, and dispatch the release workflows. |
| **Also unpushed** | `codex/whats-new` (local only, in `nuvio-z`): one commit, "feat: show release notes after updates". Not merged, not verified. |

This table is the first thing to update in any session, and it is kept current on
`main` as well as on the working branch - see "Keeping `main` current" in
`AGENTS.md`. If it names a branch, the newest work is on that branch, not here.

**Read `AGENTS.md` first.** It carries the two-repository mirroring rules, the
full release procedure, which secrets exist and where, and how to verify code in a
sandbox where Gradle cannot configure.

## Phase 2 of 0.5.0-beta: the setup wizard (2026-08-12, unreleased)

**Branch `claude/onboarding-setup-wizard-7juovt`, cut from the phase-1 branch in both
repositories.** Phase 1 fixed the Streamlined flow; this is the other half of a first
impression. Until now the app's entire onboarding was one full-screen question about playback
mode, asked before the user had seen anything it applied to - and every visual option sat
behind five sub-pages of `Settings → Appearance` that nobody was going to find.

⚠ **Three earlier shapes shipped and were wrong.** `debug-v0.4.14-beta.6` was preset-first;
`.7` previewed a whole fake home screen behind a translucent sheet; `.8` moved the preview to
whichever control was last touched. See "What the earlier attempts got wrong". Do not restore
any of them.

### The shape

**Two opaque regions, and nothing is ever drawn behind the text.** A full-bleed specimen band
on top, an opaque panel of controls below, a hairline between them. Readability is a property
of the layout rather than something to re-check in every theme - which is what revision 2 got
wrong, and badly: on a device the home screen read straight through the sheet, and because the
sheet's gradient was most transparent at its top edge, the worst of it was behind the heading.

**The band shows what the current step changes.** Eight steps: Welcome → playback mode → cards
→ home → details → theme → sources → done. Sources is **dropped, not shown-and-skipped**, when
the profile already has an enabled addon.

Appearance is four steps grouped by surface, down from six. Two of the six carried a single
control each - a whole screen, a whole preview and two taps to answer one toggle. Nothing now
exceeds four controls, so **no panel scrolls on a phone**, which is what put the Cards step's
first control group off-screen and cut the playback-mode step off mid-card in revision 2.

⚠ **The band is fixed per step and does not move while you are on it.** Revision 3 held the
current specimen in state and let each control move it, on the reasoning that every control
should change something visible. It does achieve that, and on a device it was still worse: the
object being studied kept getting swapped out mid-thought. The merged steps now draw everything
they cover at once - Home is the banner *and* the Continue Watching row, Details is one small
details screen - and the controls change that in place. There is no specimen state left.

The one control whose effect the band cannot show is "Group sections into tabs", which regroups
the sections *below* the episode list. It is commented as such at the call site.

**Trakt is gone** (revision 4). It offered a connection that is not functional yet, and a
first-run flow that asks for an account it cannot use is worse than one that does not ask.
`TraktAuthRepository` is untouched - the settings page still owns it.

### The specimens are purpose-built, and that was a reversal

Revisions 1 and 2 both rendered the **shipped** composables - `HomeHeroSection`,
`HomeContinueWatchingSection`, `HomeCatalogRowSection`, `DetailHero` - on the argument that a
preview built from the real thing can never drift from the app. That argument is true and it
was still the wrong trade:

- Those composables read their settings repository **internally** and apply a change the
  instant it is written. You cannot tween between two values you never hold, so every choice
  snapped. Smooth transitions were the maintainer's specific ask.
- They own their own section padding and sizing, so they cannot be framed full-bleed at a
  chosen height.
- ⚠ **They diverge between the repositories.** Desktop's `HomeContinueWatchingSection` takes a
  *required* `dataSourceKey` this repository's does not, so `SetupPreviewStage.kt` was the one
  setup file that could never be `cp`'d and had to be hand-maintained twice.

`SetupSpecimen.kt` draws from primitives, takes **every** setting as a parameter and reads no
repository. That buys the tweening, the framing, and - because it calls nothing divergent -
**every setup source file is now byte-identical in both repositories**, so `diff -q` is a real
check on them rather than a formality.

⚠ **The cost is a second implementation that can drift from the real cards, and nothing will
catch it.** The file header carries the table; the four things it mirrors are `NuvioPosterCard`
(via the `internal` `landscapePosterWidth` / `landscapePosterHeightForWidth` and a copy of the
0.675 poster aspect), `HomeContinueWatchingSection`'s three styles and its 18 dp blur,
`MetaDetailsScreen`'s three background treatments including the 0.92 scrim and the 0.42
dominant blend, and `DetailSeriesContent`'s two episode card styles. **Change one of those and
change this too.**

The apply-as-you-go mechanic is unchanged: every settings repository here is a singleton
`MutableStateFlow`, and the wizard writes each choice through the real setter the moment it is
tapped. There is no undo, which is how every settings page here already behaves.

⚠ **Band heights are hand-fitted arithmetic and will clip if content grows.** Each
`SetupSpecimen` carries a `preferredHeight` sized against its content at the *largest* settings,
capped at 45% of the window. The tight ones are commented in place: `Home` (150 dp banner + 14
+ the Poster-style Continue Watching row, inside 330) and `Details` (126 hero + 22 seam +
episodes, inside 320). **None of these sums has ever been checked against a renderer** - see
the harness note under Verification.

⚠ **The details specimen must keep matching `MetaDetailsScreen`, and it has already drifted
once.** It blurred Cinematic at 18 dp for a whole release where the real screen uses **30 dp**
under a `background @ 0.92` scrim, which overstated that mode badly and is part of why the
maintainer could not tell what the background step was showing. Three things to hold: the 30 dp
blur, the 0.92 scrim, and that **only `DominantColor` tints the hero** - `heroGradientColor` is
passed for that mode and null for the other two, and that tint reaching into the hero is the
single most visible difference between the three. Normal and Cinematic looking similar is
correct; about 8% of the artwork survives that scrim in the real app too.

### The product is "Nuvio Z", and the copy mostly still says "Nuvio"

The Android label, applicationId, launcher icons and downloads notification all say **Nuvio Z**.
**42 of the 43 product-name strings in `values/strings.xml` say "Nuvio"**, including the
canonical `app_brand_name`, and every one of those has ~20 locale variants that say the same.

Revision 4 renamed **the setup wizard's own copy only** - `setup_welcome_title`,
`setup_welcome_body`, `setup_home_subtitle`, `setup_sources_subtitle`, `setup_sources_body` -
on the maintainer's instruction, because that is what was on screen when they noticed.
`playback_mode_selector_*` is deliberately untouched: it is shared with the settings dialog.

⚠ **The rest is a known, deliberate inconsistency, not an oversight.** A full rename is 42
English strings plus ~20 locale files, and it should be its own change rather than riding along
inside a UI pass. Two things it must also cover:

- `app_logo_wordmark.png` has "Nuvio" **baked in as pixels** and is drawn on the splash screen,
  both auth screens and now the wizard's welcome step. Strings cannot fix it; the asset needs
  redrawing.
- `settings_licenses_attributions_nuvio_title` says "Nuvio Mobile", a third variant.
- iOS `PRODUCT_NAME` in `Config.xcconfig` is still `Nuvio`, so the iOS home-screen name is wrong.

### Sample artwork: two hosts, one of them unproven

`images.metahub.space` is keyed by the **show's** IMDb id, so it has no per-episode images -
which is why the episode list showed one frame repeated. `SetupSampleTitle.episodeStillUrl`
uses the sibling host `episodes.metahub.space/<imdbId>/<season>/<episode>.jpg`, keyed by
episode and equally keyless.

⚠ **That host has never returned a byte here** - the sandbox blocks metahub, as it does the
show-artwork host. Every still falls back to the show backdrop on error, which is the same
chain `DetailSeriesContent` uses (`video.thumbnail ?: meta.background ?: meta.poster`), so a
dead host degrades the specimen to precisely what the app shows for a series with no episode
artwork. **If the stills come back identical on a device, the URL shape is wrong and the
fallback is hiding it.** Device check.

The details subject is Breaking Bad (`tt0903747`), and `rowItems` must keep it **first**: the
Continue Watching specimen captions its in-progress card with a named episode of the featured
title, so a different show in slot 0 would claim one series is playing another's episode.

⚠ **The illustrative diagram on the five non-visual steps is provisional.** The maintainer
approved it with "be prepared for me to tell you to remove it". It is therefore one file,
`SetupDiagram.kt`, with one public composable and exactly one call site; it is wordless, so it
holds no string keys; and it uses no `Canvas`, no assets and no animation of its own. Removing
it is: delete the file, replace the `Diagram` branch of `SetupSpecimenBand` with a `Spacer`.

### Sample artwork is fetched, never bundled

Poster art is copyrighted, `Zokaper/nuvio-z` must stay public, and every release ships a signed
APK and an MSI. `SetupSampleTitle` holds IMDb ids plus constant public artwork URLs on
`images.metahub.space`, which needs no API key and no installed addon and is keyed by IMDb id -
one id yields poster, backdrop **and** logo, the exact triple the wide-card and blurred-art
options need to look different. **TMDB cannot do this job**: `TmdbService.currentApiKey()` is
null until the user enters a personal key, so a first launch has no TMDB access.

⚠ **Those URLs are still unverified.** The sandbox egress policy answers 403 to
`CONNECT images.metahub.space:443`. Device check 1.

⚠ **The wizard must be fully usable with no network.** The stage paints a token gradient behind
the artwork and `NuvioPosterCard` already draws a titled placeholder. Device check 2.

### Shown once per profile, by revision

`setup_wizard_completed_revision` on `PlayerSettingsStorage` (profile-scoped, synced, four
actuals), with **`SETUP_WIZARD_REVISION = 2`**.

⚠ **An integer, and both alternatives are worse.** A boolean can never re-ask when a later
release changes the flow. The app version - what `WhatsNewStorage` stores - would re-show the
whole wizard on *every* release. **Revision 2 exists precisely because revision 1 shipped**:
anyone who completed the preset-fork wizard answered a flow that no longer exists and never saw
most of these options. A *higher* stored revision does not re-show; the value syncs, so a
profile can arrive from a newer build.

`syncKeysToClear` was **not** touched. Finishing writes both `markSetupWizardCompleted` and
`markPlaybackModeSelectorSeen`, the latter so a downgrade to 0.4.x does not re-prompt.

Settings → About → **Run setup again** re-runs it dismissible, over `MainAppContent` rather
than gating it, indexed by `SettingsSearch` as `run-setup-again`.

### What the first two attempts got wrong

Worth keeping in full, because most of these are process failures rather than design ones.

**Revision 1 (`debug-v0.4.14-beta.6`)**

1. **The shape.** Presets did the choosing, so most users would never have reached the
   individual options at all, and the live preview the whole feature was built around got
   looked at once. Replaced by one topic per step.
2. **The preview was a scale model of a phone even on desktop.** Correct for the layout it was
   in; wrong once the preview became the background.
3. ⚠ **It did not compile, and the parser check said it was fine.** The first
   `debug-release.yml` run failed on five Compose errors, fixed by a second agent in `247be94`
   (nuvio-z) and `8e0f5b0` (desktop): two string keys used in `SettingsRootPage.kt` without
   the explicit imports that file requires, a `private` `HomeCatalogSettingsRepository.ensureLoaded()`,
   `maxHeight` read inside a nested layout receiver, and a desktop `AppSettingsTabContent`
   argument with no matching parameter. **Three of those four are ordinary cross-file
   resolution that a single-file parse structurally cannot see.** The rule `AGENTS.md` already
   states held: the parser check is necessary and never sufficient, and **CI is the gate**.

**Revision 2 (`debug-v0.4.14-beta.7`)** - and this one is the important entry, because it
**compiled cleanly, passed every suite, and was still bad on a screen**.

4. ⚠ **The translucent sheet was not readable.** The home screen showed through it - "Continue
   watching", episode titles, poster art, all behind the heading. This was written down as a
   known risk *in the plan*, shipped anyway on the reasoning that the alphas were tuned high
   enough, and it was the first thing the maintainer saw. The gradient made the sheet's **top**
   edge the most transparent part, which is where the heading sits, so it was worst exactly
   where it mattered most. Fixed by construction: opaque panel, no overlap, nothing behind text.
5. **Previewing a whole fake screen was the wrong idea.** Most of the band had nothing to do
   with the control being changed, and the per-step scroll anchoring that tried to correct that
   left rows half-clipped at the top. Now: only the component the step changes.
6. **Two steps carried one control each**, so the flow was longer than the content justified,
   while the steps that *did* have content overflowed and scrolled internally.
7. **Seven strings rendered a literal backslash** - `We\'ll`, `You\'ll`. Compose Multiplatform
   resources do not honour Android's `\'` escape. The other thirty apostrophes in the file are
   bare, so the convention was already there to copy and I invented a different one.

⚠ **The reusable lesson from 4 is not about alpha values.** "CI is the gate" was the lesson
from revision 1 and it is still true, but revision 2 shows it is not sufficient either:
**compiling is not looking.** A rendering pass is the cheapest thing that would have caught it,
and there was none. See the harness note below.

**Revision 3 (`debug-v0.4.14-beta.8`)** - "significantly better", and still four things.

8. ⚠ **Making the preview follow the last-touched control was a mistake.** It was introduced to
   guarantee no control changed nothing visible, which it did. On a device it read as the thing
   being studied getting swapped out mid-thought. **A preview that moves is worse than a
   preview that shows one thing less.** The merged steps now draw everything they cover at once.
9. ⚠ **Chip labels were never centred.** `SetupChoiceGroup` gave its label `weight(1f)` and no
   `textAlign`, so every chip in the wizard read hard left. It is visible in the *first*
   revision-2 screenshots and went unnamed for three rounds by three sets of eyes, mine
   included. Cheap to fix, embarrassing to have shipped, and exactly the class of thing a
   render pass surfaces immediately.
10. **Three abstract swatches could not explain the background modes**, because the thing that
    most separates them is a hero tint and the swatches had no hero. Replaced by one small
    details screen. The Cinematic blur had also drifted to 18 dp against the real 30 dp.
11. **The episode list showed the same frame three times**, because the artwork host has no
    per-episode images. Fixed with a second host, unverified - see "Sample artwork" above.

⚠ **The pattern across 8 and 10 is worth naming.** Both were mechanisms added to *guarantee* a
property - "every control does something visible", "you can compare all three at once" - that
cost more in legibility than the property was worth. Neither was wrong on paper. Both needed a
screen to judge, and neither got one before shipping.

### Verification

**Pure suites via `scripts/run-pure-suites.sh`, both repositories, identical: 67 + 29 + 22 =
118 tests, zero failures.** `SetupWizardSteps.kt` is **import-free** like
`StreamRouteSurface.kt`, so this group needs no stubs at all.

The cases worth naming are `everyStepInEveryPlanReachesTheEnd` and its mirror
`everyStepInEveryPlanReachesTheStart`: from any step, in any plan, walking forward terminates
and walking back reaches Welcome. A wizard that gates the app and can be entered at a step it
cannot leave is the failure the whole file exists to prevent - and it is reachable for real,
because installing an addon on the Sources step removes that step from the plan under the
user's feet.

`aSavedStepThatNoLongerExistsFallsBackToTheStart` matters more with every revision. The wizard
persists its position **by name** so that reordering the enum cannot resume someone on the wrong
step - and revision 3 deleted `ContinueWatching` and `Episodes` while revision 4 deleted
`Trakt`, so a wizard restored across an app update can be holding any of them.
`setupStepForSavedName` answers `Welcome` rather than throwing, because this gates the app and a
crash here is one the user cannot get past.

**Parser check clean** over every changed file in both repositories - **necessary, not
sufficient**; see point 3 above.

**Every setup file is now byte-identical across the repositories** - `SetupWizardSteps.kt`,
`SetupSpecimen.kt`, `SetupDiagram.kt`, `SetupWizardScreen.kt`, `SetupSampleTitle.kt`,
`SetupWizardStepsTest.kt` and `run-pure-suites.sh`. Revisions 1 and 2 could not say that:
`SetupPreviewStage.kt` called divergent screen composables and had to be hand-maintained in
both. That hazard is gone with the file.

**String keys cross-checked both ways in both repositories**: every `Res.string.*` the setup
package references exists, and no `setup_` key is defined without a reference. That is the
mechanical half of the miss that broke build 6 - the other half, the host file's import style,
is unchanged here because no new key went into `SettingsRootPage.kt`.

**CI is green in both repositories on the first push** - `nuvio-z@24e8bb4` (run 31712467776:
host suite and `:androidApp:assembleFullDebug`) and `NuvioZDesktop@7a553ab` (run 31712454251:
`ci.yml` including the Windows MSI job, which is what compiles `desktopMain` and therefore the
wizard's desktop path). Revisions 1 and 2 each needed a second push to compile; 3 and 4 did not.

**Not verified:**

1. ⚠ **Nothing Compose has been rendered here, again.** Gradle still cannot configure in the
   sandbox - `com.android.application:9.2.0` is unresolvable because `dl.google.com` is
   blocked - so the `ImageComposeScene` harness cannot be run from here. **This is the gap that
   let revision 2's unreadable sheet reach a device**, so it is worth closing on the machine
   that can: a ready-to-run `SetupWizardRenderHarness.kt` is provided. It renders every
   `SetupSpecimen` at 420 dp and 1100 dp, at the smallest and largest card settings, in every
   Continue Watching / episode / background variant, in all seven palettes and in AMOLED, and
   writes PNGs to `composeApp/build/setup-wizard-render/`. Each band is drawn inside a frame
   120 dp taller than its declared height, so content overflowing shows as a spill rather than
   being cropped by the scene edge. Unlike revision 2's stage this is now *possible*, because
   the specimens take every setting as a parameter and touch no repository.
   **Delete the harness again after reading the output** - it asserts nothing.
2. **The band heights are arithmetic, not observation.** They were fitted by hand against the
   largest settings each specimen can be asked to draw. Clipping is what the harness above is
   for, and failing that, device check 2.
3. The metahub artwork URLs have still never returned a byte here - the sandbox blocks that
   host. **`episodes.metahub.space` is new in revision 4 and is the least proven thing in this
   change**: its URL shape is inferred from the ecosystem's conventions, not observed.
4. **The chip-centring fix is one line and has not been seen either.** It is the first thing to
   confirm, because it is the one defect here that was visible in a screenshot from the start.

## The setup wizard device script

Run after the phase-1 script.

Checks 1-5 are the regressions from revisions 2 and 3 and come first.

1. ⚠ **Chip labels are centred.** "Poster / Wide", "Dense / Balanced / Large", "Sharp /
   Classic / Pill", "Card / Wide / Poster". This shipped left-aligned in three builds; it is
   the cheapest thing here to confirm and the most embarrassing to miss again.
2. ⚠ **Nothing renders behind the panel text, on any step.** If any artwork, row title or
   poster is visible through the panel, the layout is wrong, not the alpha.
3. ⚠ **The band does not move while you are on a step.** On Home, toggle the banner and then
   change the Continue Watching style: the banner should expand and collapse *inside* a band
   whose top and bottom edges stay put, and the Continue Watching row must remain visible in
   both states. On Details, all four controls act on one mock. If the band swaps what it is
   showing, revision 3's behaviour has come back.
4. ⚠ **Episode stills differ per row.** This is the `episodes.metahub.space` check and the
   least proven thing in the change. If all three rows show the same image, that host or the
   URL shape is wrong and the backdrop fallback is hiding it - say so rather than assuming it
   is fine, because the fallback makes failure look like a design choice.
5. ⚠ **The playback-mode diagram changes with the mode.** Classic highlights nothing,
   Streamlined highlights one release, Instant fades the list away and fills the play circle.
   If it is static, the diagram is decoration and should be cut.
6. **No step scrolls inside the panel**, at default font size on a phone. The playback-mode step
   must show all three `PlaybackModeCard`s; the Cards step all four control groups starting with
   "Card shape". Check the band is not clipping either - a card cut off at the top or bottom
   means a `preferredHeight` is too small.
7. **The band tweens, it does not snap.** On Cards change size, corners, and poster↔wide: each
   should animate. Toggling titles should slide them in rather than jump.
8. **Step transitions slide in the direction of travel**, and Back visibly reverses Next.
9. **The details step's three backgrounds are distinguishable.** Plain and Blurred art are
   *supposed* to look close - the real screen scrims the blur at 0.92. What must be obvious is
   Matched colour, where the tint reaches into the hero's bottom fade.
10. **Fresh profile → the wizard appears and the sample artwork loads.** Poster, backdrop and
    logo all present. If a logo is missing, swap that IMDb id in `SetupSampleTitle`.
11. **Aeroplane mode, fresh profile.** Every step readable and every option distinguishable with
    no artwork. Cards should show their title on the skeleton fill, not be blank grey boxes.
12. **Android API 30 or below.** `Modifier.blur` is a no-op there, so "blur what's next" and
    "blur unwatched episodes" look inert in the band - the same as in the real app, so this is
    expected. Confirm nothing else differs.
13. **Skip for now** on the welcome step → the app is exactly as it is today, and the wizard does
    not return on relaunch.
14. Force-stop and relaunch after finishing → no wizard. Then **sign out and back in** → still no
    wizard. That is the sync-key check, and editing that key set is what wiped the playback
    settings in `0.4.0-beta`.
15. **Second profile** → the wizard runs again for it, and its choices do not disturb the first
    profile's.
16. **Upgrade from `debug-v0.4.14-beta.8`** → the wizard appears again, because the revision went
    to 4. Intended, not a bug. If the device had a wizard open when it updated it must resume on
    Welcome rather than crash - `Trakt` was deleted from the enum.
17. Settings → About → **Run setup again** → opens dismissible, escapable in one Back press,
    does not gate the app.
18. **Theme step:** all seven palettes and AMOLED. Band and panel must both stay legible, and the
    band's sample button, progress bar and chips should take the accent.
19. **Sources step with a deliberately bad URL** → a named error, still skippable. Then a good
    one → "Added <name>", and the Sources step is absent on a re-run.
20. **Copy reads "Nuvio Z"** on Welcome, Home and Sources, and the apostrophes render as
    apostrophes ("We'll", not "We\'ll"). ⚠ **The wordmark graphic above it still says "Nuvio"** -
    the name is baked into `app_logo_wordmark.png` as pixels. Known and accepted; splash and
    auth have the same mismatch. Redrawing the PNG fixes all three.
21. **No Trakt step.**
22. **Desktop, resized wide and narrow:** the band stays full-bleed at both, the panel stays
    centred and capped at 620 dp rather than stretching, and nothing overflows horizontally.

A step that was not run is not a pass.

## The 0.5.0-beta polish pass (2026-08-11, unreleased)

**The first build going to other people**, so this is a bugfix pass rather than a feature
release. Instant stays withheld. Reading the Streamlined flow end to end turned up one family
of faults rather than a list of unrelated ones:

> **Every dead end that was not one of the two fixed in `0.4.10-beta` still left the user on a
> covered screen with nothing behind it.**

`entry<StreamRoute>` stacks four things over one `StreamsScreen` — the opaque hand-off surface,
the quality sheet, the progress overlay, and the list itself — and each was decided by its own
inline expression over the same six flags. Nothing held the rule that matters: whatever is on
top, the user must be able to act on it.

### 1. Backing out of the player landed on a blank screen

This was flagged in `0.4.10-beta` as check 1 and never run. It is real, and it was reachable on
**every** Streamlined play.

A mode with a failure chain deliberately leaves `StreamRoute` on the back stack, and
`NavDisplay(onBack = { navController.popBackStack() })` pops the player straight onto it. The
in-app back *button* calls `onBackToDetails`, which pops past it — which is why this was never
seen by whoever tested with the button. The **system Back gesture** is the common path and lands
there. `playbackRouteDecision` was a plain `remember` and `NavDisplay` composes only the top
entry, so it came back null, while `reuseHandled` — which is saved — stayed true and blocked the
effect that would set it again. No sheet, no overlay, opaque surface still painting.

⚠ **The surface consumed no pointer input**, so the invisible source list underneath was fully
tappable. A blank screen that starts a random episode if you touch it.

Fixed by `streamRouteSurface` (new `features/playback/StreamRouteSurface.kt`), which decides the
whole stack in one place. **It has no imports and never may** — that is what lets the route's
covering rules be compiled and run outside Gradle, which they never could be before.
`PlaybackProgress.isVisible` is gone: it answered only "does the overlay cover the list?", and
hiding the overlay while the surface underneath stayed up traded a blank screen for a blank
screen one layer down.

⚠ **`playbackRouteDecision` is saved, not re-derived.** Re-running the router on the way back is
not a substitute: the play just wrote a reuse-last-link entry, so the same inputs answer
`ReuseLastLink` where they first answered `AutoPick`, and Instant's retry chain is gated on that
answer. `openSelectedStream` also now sets `playbackHandedOff`, which that flag's own comment
already claimed happened at every exit to playback — it did not, and the Streamlined sticky-pin
path reaches the player through there.

### 2. Three more dead ends, all the same shape

Routed through one `fallBackToSourceList`, which always says something:

- **Declining the P2P consent dialog** retired the chain and set no flag, so the overlay sat on
  "Starting playback" for a playback that had just been called off. Retiring is correct —
  declining P2P is a decision about every torrent candidate, not about this one.
- **`requestOrOpenP2pStream`'s two early returns** called `skipAutoPlayStream` and discarded the
  answer, so a refusal on the last candidate advanced to nothing.
- **Uncached "Start anyway"** called `openSelectedStream` directly, making it the one Streamlined
  start with no chain behind it — and the start most likely to need one. It now seeds a chain of
  one, which buys the *path* rather than the fallbacks.

### 3. The mid-playback retry could not work

`STATUS.md` recorded this as "verified by reading, not by running". Reading it again against
`StreamsRepository` says it did not hold.

`consumeAutoPlay` clears `activeRequestKey`, so returning to the stream route after a play always
misses `load`'s no-op guard and does a full reload — and that reload was a blanket
`_uiState.value = StreamsUiState(requestToken)`, discarding `autoPlayStream` and
`autoPlayCandidates`. So `failOverAfterPlaybackStarted` re-armed the chain, popped to the stream
route, and the route's `StreamsScreen` re-mount wiped it. Nothing refilled it either: Streamlined
and Instant both load with `manualSelection = true`, so `isAutoPlayEnabled` is false.

A chain armed for the same request now survives, carried across the resets on the way *to* a
result but **not onto a terminal empty state** — "no addons installed" is not a screen a retained
candidate should be playing over. The rule is hoisted into `carriedAutoPlayChain` /
`withCarriedChain` so it is executable.

### 4. Best available was ranked by different rules from every other card

`rankingFor` leads with three keys — implausible sizes last, torrents behind everything, then
evidence of a cached copy. `bestAvailable` sorted with a **bare** `SourceRanking.comparator` and
applied none of them, so the top card, the one most people tap, was the only place the
catalogue's worst traps still led. `LARGEST_UNDER_CAP` sorts size descending, so an 85 GB "1080p"
season pack headed it every time — the precise defect `0.4.9-beta` fixed for the banded rows and
never applied here.

⚠ **It failed silently, which is why nothing caught it.** `requiredMbpsFor` returns null above the
plausibility ceiling, so a card led by a season pack drew no bandwidth figure and no connection
meter at all. The ceiling was protecting the label while the pick walked straight past it.

### 5. Two unbounded waits

- `isStreamlinedSelectionReady` closes every *known* way the settle signal fails to arrive, but it
  is still a wait on a condition owned by addons the app does not control. Twenty seconds, then
  the source list with a reason. One new string key, `playback_sources_timed_out`.
- A **backstop** for the dead ends nobody has found yet: overlay showing, no candidate armed,
  nothing resolving, fetch settled and matching — so whatever it is waiting for is not coming.
  Reachable today by a retry whose reload lands on a terminal empty state. The grace period is
  what makes it safe: every legitimate state there is transient.

### 6. Codec, HDR and audio-language preferences now apply to playback

Named twice in this file as a real defect deferred to its own commit. Two findings changed the
shape of the fix:

- ⚠ **`PlaybackSourceSelector.rank` had no *production* callers.** It was one of the two places
  listed as needing preferences wired in, which would have been wiring them into a function no
  playback ever reached. Deleted; `PlaybackQualityOptions.rankingFor` is now the only ordering.
  ⚠ **It did have one test caller**, and the first claim here said "no callers in either
  repository" - which was wrong, because the grep behind it covered `commonMain` only. That
  broke `testAndroidHostTest` on the first real CI run of this branch. The old comparator now
  lives in `PlaybackSourceSelectorTest` as `rankForGateTests`, unchanged, because those cases
  are about the protocol and cache gates and only need a deterministic input order.
  **`PlaybackSourceSelectorTest` is the one file the standalone harness cannot compile** - it
  reaches the real AIO types - so it is exactly where a deletion like this hides.
- **Only one of the three preferences existed.** Codec and dynamic range lived solely on
  `DownloadPreset`, so this adds `playback_codec_preference` and `playback_dynamic_range_policy`
  as profile-scoped keys with all three actuals across both repos, settings rows, search entries
  and both sync paths — through `syncKeysToClear`, unchanged.

⚠ **`ANY` means "no opinion", not "prefer nothing".** `preferencesFor` sets dynamic range *by
resolution* on purpose and that reasoning is sound, so an explicit setting composes with it rather
than replacing it. Leaving the new rows alone keeps today's behaviour exactly.

⚠ **`default`, `device` and `original` are not languages.** They instruct the player's own track
selection and match no release. `PlayerSettingsUiState.rankableAudioLanguage` resolves them in one
place, because `PlayerNextEpisodeAutoPlay` builds its own selection context — a rule applied in
only one of them holds for the first episode and not the next.

### 7. Android has a stall watchdog at last

Flagged after `0.4.10-beta`. Desktop has polled and force-closed a silent stream since
`0.4.5-beta`; Android sat on OkHttp's hardcoded 60-second read timeout, which consulted
`DownloadsTiming` not at all. Cancelling the call unblocks the read; the flag is set **before** the
cancel, and checked before `isCancelled`, because a user pause arrives as the same exception and
reporting a stall as a pause leaves the queue waiting for a resume that never comes.

Both deadlines now come from one rule in `DownloadTransfer.kt`: **the watchdog must decide before
the read timeout**, because only the watchdog knows why the connection ended.

### 8. A dead debrid link looped forever inside the player

**Reported after the rest of this pass landed**, and it is the one a user actually hits first:
choose a source from the Streamlined sheet, get the loading screen with the series logo, the
video loads, the player shows for about a second, then back to the loading screen — forever.

⚠ **It is inside the player**, which is why nothing above bounded it and why the failure chain
never ran. `tryRefreshCredentialedSourceAfterError` guarded against repeating itself by
remembering the URL it had already tried, and **the reset block keyed on `activeSourceUrl` nulled
that guard** — while a successful re-mint is precisely what changes `activeSourceUrl`. The next
line of the same block sets `initialLoadCompleted = false`, which is the logo overlay coming
back. Two independent reasons the guard never bit: it was cleared every iteration, and a re-mint
returns a freshly signed URL so the comparison would have failed anyway.

⚠ **`onError` returns early when the refresh is accepted**, so `onFatalPlaybackError` was never
invoked. Every error was swallowed. The failure chain, the attempt counter and every bail-out in
items 1–5 above were unreachable on this path.

Bounded by a budget now, not by a URL, and scoped to the item being watched rather than to the
source — because re-minting *is* a new source. One refresh: the premise is a link that expired
while playing, so a fresh one fixes it; if the replacement also dies in a second, the source is
the problem and declining is what lets the chain name it and move on. A deliberate source pick
by the user earns a new budget; an automatic retry does not.

**Not Streamlined-specific in the code** — any debrid source that fails early reaches it.
Streamlined hits it constantly because it picks one without the user vetting it, and essentially
every debrid link satisfies `hasLikelyExpiringPlaybackCredentials` (the key set includes the bare
`t` and `e`).

### 9. The stream route re-decided itself on every return

Found while tracing item 8, and it silently defeated item 1 for the content being tested.

`effectiveVideoId` is resolved asynchronously, and its effect — which restarts every time the
route re-enters composition, so every return from the player — **blanked it to `launch.videoId`
first**. So the value went resolved → placeholder → resolved on each return.
`playbackRouteDecision`, `reuseHandled` and `autoPlayHandled` were all keyed on it and were
therefore discarded twice per return, and `StreamsScreen` issued **two full stream loads**, the
first against the parent id.

That is exactly the saved decision item 1 added to stop the blank screen — discarded, for series
episodes specifically. Movies were unaffected. They now key on `route.launchId`, as every other
flag in that route already did, and the resolve effect no longer blanks an id it has resolved.

### 10. Backing out of a player that had not started relaunched it

Found by re-reading this branch, not by report, and the same shape as item 8: a cycle with no
way out.

The re-arm effect in `entry<StreamRoute>` decided "this is a retry" from state — route current
again, playback handed off, a candidate still armed. ⚠ **A back press produces exactly that
state**, because nothing consumes the chain until the first frame plays. So pressing system Back
during a slow debrid mint relaunched the source just walked out of, forever. The in-app back
button escaped only by accident: it pops this route on its way to details, so it never reached
the effect. The system gesture does — the same asymmetry as item 1.

A retry is now **signalled** by the player's fatal handler rather than deduced. Silence means the
user left, so the chain is retired instead of left armed, which also lets the surface rules
uncover the list. One-shot, and cleared by both `consumeAutoPlay` and `seedAutoPlayCandidates`.

Smaller, same pass: the credential-refresh budget from item 8 was refunded inside
`switchToSource`, which also serves automatic downshifts, the debug forced swap and its own
re-entrant debrid resolve — so an automatic retry of a dying source would have been handed a
fresh budget every swap, which is the loop that budget exists to stop. Only a user's own pick
refunds it now. Unreachable today because auto-downshift is Instant-only and Instant is withheld,
which is exactly why it would have shipped.

### 11. Backing out of Streamlined landed on the source list, and took two presses

**Found on the device, in the first Tier 1 pass.** Two faults in one report.

The route uncovered the source list when the user came back from the player. It was the wrong
destination *and* it did not finish the job: `consumeAutoPlay` clears `activeRequestKey`, so
`StreamsScreen` re-fetched the moment it was uncovered, and what the user actually saw was the
source **loading** screen. Nothing popped the route, so a second Back was needed to leave.

⚠ **The destination was wrong on principle, and the codebase already said so.** The quality
sheet's own dismiss carries the comment *"backing out of the quality sheet means 'not now', so
it returns to details rather than uncovering the Classic source list the user chose Streamlined
to avoid"*. The player-return path violated the rule the sheet already followed.

The rule, stated once: **in Streamlined and Instant the source list appears only when the app
could not choose — never because the user left.** Classic and an explicit manual launch came
*from* the list, so they still return to it.

- `streamRouteSurface` now answers `HandOff` for anything after a hand-off, in both directions,
  and `entry<StreamRoute>` pops itself through to details. `isRouteCurrent` and
  `hasArmedFailureChain` are gone from its inputs; the rule table is shorter than before.
- ⚠ **`HandOff` is therefore a navigation in flight and never a resting state**, which the pure
  function cannot enforce because it cannot see a navigation. Two things hold it: the route sets
  `manualSourceListRequested` if the pop no-ops - the same guard `qualitySheetDismissRequested`
  already carries - and the stall backstop now covers `HandOff` as well as the overlay, so
  resting on it after the fetch settles falls back to the list within `1.5 s`.

**Failure still goes to the list, with a reason.** An exhausted chain, no safely playable
source, or a timed-out fetch is the escape hatch `PLAYBACK_MODES_PLAN.md` specifies, and the
user is then one tap from choosing. Confirmed with the maintainer rather than assumed.

## Verification for 0.5.0-beta

**Standalone compile-and-run of the shipped sources** (`AGENTS.md` item 2), in **both**
repositories, with identical results:

- `PlaybackQualityOptionsTest` **45**, `StreamRouteSurfaceTest` **11**, `PlaybackModeRouterTest`
  **11** — 67 tests, zero failures.
- `DownloadTransferTest` **22** and `PlaybackUrlCredentialsTest` **7**, zero failures, both
  compiled with no stubs at all. **95 tests in total**, and the same 95 in `NuvioZDesktop`.
- **Stubbed neighbours, never a file under test:** `SourceFacts`, `SourceFactsExtractor`,
  `StreamItem` and its behaviour-hint types, `PlaybackMode`, and the three ranking enums.
  `SourceRanking`, `PlaybackSourceSelector`, `PlaybackQualityOptions`, `StreamRouteSurface`,
  `PlaybackModeRouter` and `DownloadTransfer` are the real shipped files, unmodified.
- **The harness is in the repository now**, at `scripts/run-pure-suites.sh`, with its neighbour
  stubs beside it. It takes an optional repository path and work directory, fetches `kotlinc`
  and the JUnit jars on first run, and works in both repositories. It previously lived in a
  session-scoped scratchpad, which meant the next agent could not run it and would have fallen
  back to "verified by reading" - the exact habit this release exists to break.
  **Run it before trusting any change to the playback selection logic.**

**Parser check** clean over every changed file in both repositories.

**Thirteen shared files confirmed byte-identical** across the two repositories after mirroring.

**CI is now green on `nuvio-z` (run `31486710102`, commit `3178ae9`).** The Android host suite
passes and `:androidApp:assembleFullDebug` builds, which is the **first time any of this pass
has been compiled at all** — everything before it was a parser pass plus the standalone pure
suites. Two things it settled, and one it did not:

- Every change to `App.kt`, the player runtime and the two new settings keys compiles on
  Android, and the full host suite passes with them.
- ⚠ It took two runs. The first failed to compile `commonTest`, because deleting
  `PlaybackSourceSelector.rank` left a caller in `PlaybackSourceSelectorTest` — see item 6.
- ⚠ **It says nothing about `desktopMain`.** Two new `expect` members landed this pass, and only
  `:composeApp:desktopTest` in `NuvioZDesktop` (or its Windows CI job) proves their desktop
  actuals exist. **Run that before the release.**

**Not covered, and CI is the gate:**

1. `PlaybackSourceSelectorTest` reaches the real AIO types and cannot run standalone — unchanged
   from `0.4.13-beta`.
2. **Nothing Compose was run at all.** `App.kt` and the player runtime files are parser-checked
   only; every behavioural claim about the route and the player above is reasoning from the code
   plus the pure tests underneath it. That covers items 1–3, 8's wiring and all of 9.
3. **The Android stall watchdog has no automated coverage and cannot get any here.** The desktop
   harness drives the *desktop* downloader; `FaultyMediaServer.GoSilent` already existed. What was
   missing was the Android implementation, and only CI compiles it and only a device runs it.
4. Two new `expect` members, so **`desktopTest` in `NuvioZDesktop` matters before release** — it is
   what catches a missing desktop actual locally.

## The 0.5.0-beta device script

**Hold the version bump until this has been run.** Set Playback mode = Streamlined.

1. Play an episode, let it start, **press system Back out of the player** (the gesture, not the
   on-screen button — they take different paths and only the gesture reaches the defect). Expect
   **the details screen, in one press**. Never a blank screen, never the quality sheet again,
   and never the source list — that is item 11.
2. Same again, then tap where a source row would be on the screen you land on. Nothing should
   happen.
3. An episode whose top source is uncached: it should name the source and move on, not stop.
4. Force chain exhaustion: expect the source list, not the overlay.
5. A P2P-only source with P2P disabled, then decline the consent dialog. Expect the source list.
6. A title with a known season pack in its catalogue: **Best available must not name the pack**,
   and must show a size and a speed figure.
7. Set a codec and an HDR preference. Confirm the pick changes, then force-stop, relaunch, and
   sign out and back in — both must survive. That is the sync-key check, and editing that key set
   is what wiped the playback settings in `0.4.0-beta`.
8. Three consecutive episodes: the resolution holds and Back works every time.
9. **Back out of a slow start.** Tap a quality, and while it is still preparing press **system
   Back**. Expect the details screen in one press — never to be thrown back into the player, and
   never a second press to escape.
10. **The loop, and the reason for this second pass.** Play a Streamlined episode from a debrid
   provider and let a source fail on its own. Expect **one** toast naming it and **one** advance
   to the next candidate — never the logo screen a second time for the same choice. If the chain
   runs out, expect the source list.
11. **Downloads, Android:** start one and cut the connection without disconnecting (aeroplane mode
   mid-transfer). The row should fail with a named reason and retry, not sit on its percentage.

Report what each step actually showed. A step that was not run is not a pass.

## A fresh line estimate suppressed probing a new host (merged into 0.5.0-beta)

**Was held on `claude/network-strength-sources-4rrysy` for whatever shipped next. That is
`0.5.0-beta`**, so it has been merged into the release branch in both repositories rather than
queued a second time.

`estimateAgeMs` answered with `peek`'s exact-then-generic fallback, so it reported the age of
the number the sheet would *show* rather than of the key the probe would *write*. A two-minute
old line-wide reading therefore declared a brand-new debrid host freshly measured, the host was
never probed, and it went on borrowing a figure measured somewhere else - which is precisely
what the per-provider keying exists to prevent.

⚠ **The obvious fix is wrong and would have been worse.** Making the check exact-key-only breaks
the other direction: a source that still needs minting has no direct URL, so the probe falls
back to the CDN and files the answer under **no** provider. That host's own entry then never
fills, an exact-key check is never satisfied, and the sheet re-probes 4 MiB on *every single
open*. On debrid - the main path - that is most opens.

So the freshness question now names the key the probe would write:
`plan` resolves its target first, then gates on the host's age when it will pull from that host
and file the answer under it, and on the line's age whenever it falls back to the CDN or the
source carries no provider. `Inputs` takes both ages because only `plan` knows which applies.

**Verified:** the three pure suites, **39 tests**, up from 34 - five new cases pin both
directions of the trap, including `aCdnBoundProbeIsJudgedByTheLineNotTheHost`. CI is the gate.

## The 0.4.13 picker showed nothing at all (2026-08-10, `0.4.14-beta`)

**Reported on sight, with a screenshot: the quality sheet had no connection line and no meters
on any card.** Not a rendering fault - it is what `0.4.13-beta` was built to do, and the design
was wrong. `estimatedMbps` was passed as null unless a measurement existed, so the header
collapsed to its non-breaking space *and* `connectionFit` returned null for every option, taking
the meters and the over-connection warnings with it. A connection that could not be measured
therefore displayed **less than before any of this existed**.

Three ways to reach that screen, and all three were silent:

1. ⚠ **The probe cancelled itself.** Its `LaunchedEffect` was keyed on `qualityProbeTarget`,
   which derives from `playbackQualityOptions` - rebuilt *every time an addon answers*
   (`App.kt:2779`). Every new source restarted the effect and killed the transfer part way
   through. The comment above it claimed it "runs beside the source fetch"; it was being killed
   by that fetch. It now launches into a `rememberCoroutineScope`, so re-triggering is harmless
   (`probe` refuses to start a second one while the first is in flight) and cancellation happens
   when the sheet actually leaves composition.
2. **Metered connections were skipped entirely**, which left mobile data - the connection whose
   speed varies most and matters most - as the one case still decided by a preset. **The skip is
   gone**: metered is probed too, at about 4 MB once per network per ten minutes, on the
   maintainer's explicit instruction.
3. **A failed probe reported nothing** - non-2xx, or a sample under the 512 KiB floor.

**The sheet now always shows a figure and labels its provenance:** `Estimated ~50 Mb/s for this
connection` until measured, `Your connection: about 42 Mb/s` after, `Checking your connection…`
while a probe is in flight. The meters are back on every card, scored against whichever figure
is current. This is a deliberate step back from "never show an unmeasured number" - the label
carries the truth instead, and a meter comparing 33 against 48 Mb/s is useful even when the
baseline is rough.

One new string key in both repos: `playback_quality_estimated_connection`.

**Verified:** the three pure suites still pass standalone - **34 tests** across
`NetworkThroughputMeterTest`, `NetworkStrengthProbeTest` (metered now asserts a probe *is*
planned) and `NetworkQualityRepositoryTest`. CI is the gate, as before.

⚠ **Still not device-verified, and the screenshot above is the reason that matters.** No amount
of green CI would have caught this; it took someone looking at the screen. The outstanding
checks below are unchanged and this release adds one: confirm the labelled figure appears on a
first play, and that the probe now completes rather than being cancelled.

## Network strength is measured, not assumed (2026-08-10, `0.4.13-beta`)

**The quality sheet was printing a preset as if it were a measurement.**
`NetworkQualityRepository.defaultMbps` returns 50 Mbps for any Wi-Fi, 100 for Ethernet, 10 for
cellular, and `PlaybackQualitySheet` rendered that verbatim as *"Your connection: about 50 Mb/s"*
with no hedge. Every `ConnectionMeter` and every "May be more than your connection carries" was
scored against a guess. Four things kept it one, and all four are now fixed:

- **Nothing measured throughput before the first play.** New `core/network/NetworkStrengthProbe.kt`
  runs a bounded ranged GET — 4 MiB or 2500 ms, whichever first — while the source fetch the
  skeleton is already waiting on is still running.
- **The playback signal was a lower bound that could not correct downwards.** New
  `core/network/NetworkThroughputMeter.kt` converts `bufferedPositionMs` growth against the
  playing file's bitrate into a real rate. `recordSustainedBitrate` stays, monotonic, as the
  fallback for sources whose size nobody reported.
- **Estimates were in-memory only**, so every cold start was a preset again. They now persist
  through `core/network/NetworkQualityStorage.kt` (a new `expect` with android/ios/**desktop**
  actuals), aged out at seven days, capped at 32 entries.
- **The sheet read the estimate wrong** — `NetworkQualityRepository.current()` called directly in
  composition, so no recomposition when a measurement landed, and no provider scope. It now
  collects `uiState` and reads through the new pure `peek(providerId)`, scoped to the host that
  would actually serve the stream.

⚠ **The probe measures the source, not the line.** When the top option has a direct URL it pulls
from that host with that source's own `proxyHeaders`, and files the answer under its provider id.
Only a candidate that still needs `clientResolve`, or a manifest, falls back to
`speed.cloudflare.com`, and that result is stored against **no provider** — a fast CDN must never
vouch for a slow debrid. **No debrid link is ever minted to run a probe.** Metered connections are
never probed at all; the buffer meter covers them for free.

⚠ **A flat buffer and a draining buffer are not the same reading.** A full buffer back-pressures
the transfer down to the file's own bitrate, so a flat window measures the *file*; two of them
stop the meter. A *draining* buffer is the line failing to keep up and is reported even when it is
below an earlier window, because suppressing it is how an estimate survives being disproved.

**Best available now says what the user gets.** Its card had no resolution badge and no bandwidth
figure, so `describeRelease` — `WEB-DL · TorBox` — was the whole card: the two facts named were
which rip it came from and which host serves it. It now leads with
`PlaybackSourceSelector.describeBestRelease` (`4K · DV · 18.2 GB`), quotes a real
`Needs 21 Mb/s` from `PlaybackQualityOptions.requiredMbpsFor` on the source that would actually
open, and therefore has a `ConnectionMeter` and an over-connection warning for the first time.
Resolution cards are unchanged except for gaining the dynamic-range token. Unknown fields are
omitted, never placeholdered, and a null `previewSelection` still yields an empty line rather than
`candidates.first()`.

**One new string key in both repos:** `playback_quality_checking_connection`. The header line is
now always drawn at fixed height with three states — the measured figure, "Checking your
connection…", or a non-breaking space — so the grid never jumps when a figure lands.

**Instant is still withdrawn, and this does not change that.** But `PlaybackMode.isSelectable`'s
comment was stale — it blamed tier-picking that `PlaybackQualityOptions` replaced — and now names
the real blocker: no device evidence for the failure chain, downshift, or metered consent.

**Verified — CI is green on both repositories** (`nuvio-z@6f55f51`, `NuvioZDesktop@eadaece`):
Android host suite **767 tests, zero failures**, debug APK built; desktop tests and the
**Windows MSI job**, which is what compiles `desktopMain` and therefore exercises both new
desktop actuals. That took three red runs to reach, and each failure was real:

1. `playback_quality_checking_connection` was in `strings.xml` but not imported —
   `PlaybackQualitySheet` imports every key explicitly, so `compileAndroidMain` failed.
2. `response.body` is non-null on androidMain and **nullable on desktopMain**, so
   `response.body.byteStream()` compiled for Android and failed `compileKotlinDesktop`. The
   read loop now takes a nullable `ResponseBody`, as every other body reader in that file does.
3. `theProbeMeasuresTheSourceThatWouldOpenNotTheFirstCandidate` asserted a premise its fixture
   did not meet: `isUncachedDebrid` treats a debrid-backed candidate whose `isDebridReady` is
   **null** as uncached, so both fixtures were uncached and `previewSelection` fell through to
   the first candidate. Production path unchanged; the fixture is now explicitly cached.

**None of the three was reachable by the sandbox checks below** — they catch syntax and pure
logic, never resources, never `expect`/`actual` nullability skew, never a wrong test premise.
Treat a green CI run as the gate, not this section.

Also verified in the sandbox:

- **Parser check** over every changed file in both repositories: clean.
- **Standalone compile-and-run of the shipped sources** with JUnit, per item 2:
  `NetworkThroughputMeterTest` **10/10**, `NetworkStrengthProbeTest` **9/9**,
  `NetworkQualityRepositoryTest` **15/15** (the six pre-existing cases plus nine new ones,
  covering the downward correction, `PROBED`, seven-day expiry, the cold-start restore as
  `CACHED`, a corrupt blob, and `peek` not publishing).
- **Stubbed neighbours, never a file under test:** `NetworkQualityPlatform` and
  `PlatformNetworkQuality`/`NetworkConnectionType` (the real file also declares the `expect`),
  `NetworkQualityStorage`, `DownloadsClock`, `VideoResolution`, and `httpMeasureThroughput`.
  `NetworkQualityRepository`, `NetworkThroughputMeter` and `NetworkStrengthProbe` are the
  shipped sources, unmodified.
- **`PlaybackSourceSelectorTest`'s five new cases are parser-checked only** — the real
  `StreamItem` reaches the generated resource bundle, so they need CI. **CI compiles them on
  push; treat that run as the gate.**

⚠ **`AGENTS.md` says `desktop-release.yml mode=build-only` is "the only thing that compiles
`desktopMain`". That is out of date** — `ci.yml`'s Windows MSI job compiles it on every push to
`NuvioZDesktop`, and it is what caught defect 2 above. Keep running build-only before a desktop
release, but the every-push safety net is better than that line claims.

**Not verified, and the next steps:**

1. **Nothing has run against a real socket or a real device.** Everything above is compile plus
   pure logic; `httpMeasureThroughput`'s three actuals have never opened a connection in anger,
   and the iOS one is compiled by **no** CI job at all (the Android job disables `iosArm64` /
   `iosSimulatorArm64` — cinterop cannot cross-compile on Linux). It uses Ktor's
   `bodyAsChannel()` / `readAvailable`, which nothing here has verified.
2. **Extend the desktop download harness with a rate-limited endpoint** and assert
   `httpMeasureThroughput` measures a known 20 Mbps server, that a delayed first byte does not
   depress the figure, and that both the time cap and the early exit fire. Nothing has yet
   exercised the platform actuals against a real socket.
3. **Render the sheet off-screen at 420 dp and 1100 dp** in both header states and confirm the
   height does not change, and that the Best available card's longest line
   (`4K · DV · 128.4 GB` plus `Needs 78 Mb/s`) fits the 280 dp `QUALITY_CARD_MIN_WIDTH`. Both
   defects `0.4.12-beta` caught this way were on that card.
4. **On device:** `DebugBandwidthThrottle` at 5 Mbps — the sheet should converge near 5 rather
   than sitting on the 50 Mbps Wi-Fi preset, with `PlaybackDiagnosticsHud` showing
   `confidence=PROBED`/`PASSIVE` and the provider key. Then cellular: confirm no probe runs.
5. Fresh install → play → force-stop → relaunch: the estimate survives and the sheet shows a
   figure immediately instead of "Checking your connection…".

## The debug update line (2026-08-08)

Debug builds install as `com.nuvio.app.z.debug`, so the stable channel's APKs can never update
them and testing a fix meant sideloading a file by hand every time. Debug builds now read GitHub
**prereleases** tagged `debug-v*` from `Zokaper/nuvio-z`. The stable channel already discarded
prereleases, so the two lines cannot see each other and the release flow is untouched — verified
after publishing: `0.4.9-beta` is still `latest`, `debug-v0.4.9-beta.1` is `prerelease`.

Only the Android **full debug** build takes this path; every other `isDebugBuild` actual (iOS,
desktop, Playstore) is `false`.

**Three pieces, and each exists for a reason that is not obvious:**

- **`androidApp/nuvio-debug.keystore` is committed**, with an explicit `.gitignore` negation. It
  is not a secret — it signs debug builds only. It exists because Android refuses an install
  whose signature changed, and AGP's default debug key lives in `~/.android/` per machine, so
  two machines (or a machine and CI) produce mutually un-installable debug APKs. The release
  keystore is still excluded and must stay that way.
- **`DEBUG_BUILD` in `Version.xcconfig`** is the debug counter. It produces a fourth version
  component (`0.4.9-beta.1`) and a derived `versionCode` (`releaseCode * 1000 + n`). Without it
  every debug APK cut from one release version looks identical to the installed one and no update
  is ever offered. **Bump it for every debug build you publish** — that is the whole mechanism.
- **`VersionUtils.normalize` strips `debug-` before `v`.** Left on, `debug-v0.4.9-beta.2`
  tokenises to `[4, 9, 2]` — the leading zero is lost with the `v0` token — and every debug
  release outranks every local version permanently. `DebugChannelVersionTest` pins this and the
  four-component ordering.

⚠ **The signing key changed, so the currently installed debug app must be uninstalled once.**
Every debug build after `0.4.9-beta.1` updates in place from inside the app.

**Published for the 0.5.0-beta device run: `debug-v0.4.14-beta.4`** (versionCode 124004), cut
from `claude/release-0.5.0-beta-polish-ivcjsl` at `3178ae9`. The installed debug app is
`0.4.9-beta.3` at 119003, so it should offer the update. The marketing version stays at
`0.4.14-beta` deliberately — bumping it to 0.5.0 now would break the release line's bump-last
rule with phase 2 still to come, so the debug counter carries the identity instead.

**Publishing a debug build (2026-08-11): dispatch `debug-release.yml`.** Bump `DEBUG_BUILD` in
`Version.xcconfig`, push, then run the workflow against whatever branch you want the build cut
from. It runs the host suite, builds `:androidApp:assembleFullDebug` and publishes
`debug-v<version>` as a prerelease with the APK attached.

It replaces the manual `gh release create` ritual, which required a machine with a working
Gradle and so could not be done from the agent sandbox at all - every device-testing loop had to
wait for the maintainer. `ci.yml` builds the same APK on every push but only uploads an Actions
artifact, which an installed app cannot update from.

⚠ **The tag is single-use** and the workflow refuses to run if it already exists: republishing
one would strand installs that already took it on older code carrying a newer name. Bump
`DEBUG_BUILD` instead. It targets the dispatched commit rather than `main`, so a working branch
is fine. The workflow file must also exist on `main`, because that is where GitHub looks to
decide whether `workflow_dispatch` is available at all.

**Not mirrored to `NuvioZDesktop`** — a deliberate divergence, not an oversight. Its updater is a
different architecture (`AppUpdaterPlatform.releaseSource`) and its Android build points at the
`Zokaper/NuvioZDesktop` release line with `includePrereleases` already `true`, so this channel
split does not apply there.

**Verified:** Android **706 tests, zero failures** (six new `DebugChannelVersionTest` cases).
APK inspected: `com.nuvio.app.z.debug`, `versionCode 119001`, `versionName 0.4.9-beta.1`, signed
`CN=Nuvio Z Debug`. The in-app update flow itself is **not** device-tested — the first real proof
is publishing `debug-v0.4.9-beta.2` and watching `.1` offer it.

## One card per resolution, bands stacked inside it (2026-08-10, `0.4.12-beta`)

**Reported on sight: the `0.4.11-beta` selector reads worse than the stacked list it
replaced.** It was a flat grid of one card per `PlaybackQualityOption`, so "1080p High",
"1080p Mid" and "1080p Low" were three unrelated tiles competing with "4K" and "720p" — the
resolution, which is the first thing the user is choosing, said three times to say it once.
That layout shipped without anyone looking at it; this is the first correction from seeing it.

**The hierarchy already existed in the data and the sheet was flattening it.**
`PlaybackQualityOptions.build` emits Best available, then buckets high→low resolution, each
split High/Mid/Low. `PlaybackQualityOptions.group` now makes that explicit and the sheet draws
it: one card per resolution, badge once, bands stacked inside as the tap targets.

**Presentation only.** The option set, the banding, the ranking, `PlaybackSourceSelector` and
the `PlaybackQualitySheet` signature are untouched — which is why **`App.kt` and `strings.xml`
were not edited in either repository** and all four changed files were `cp`'d rather than
hand-ported. No new string keys.

⚠ **Bands are stacked full-width rows, not a row of chips, and that was the deciding
constraint.** Chips were the original sketch. A 3-across chip on a phone is about 105 dp,
which holds the band word and the figures and nothing else — so it would have cost both
`sourceLine` and the over-connection sentence. Neither can be lifted to card level: they are
**per-option**, so a card-level provider line names a release two of the three bands never
open, and the warning is true of one band and false of the one under it. `0.4.10-beta` added
that provider line precisely because naming `candidates.first()` was the same untruth.

⚠ **The card is not a tap target; the rows are.** A card holds up to three options with no
sensible default among them, so a tap on the header would either do nothing or silently pick
one.

**No source count in the header**, though the sketch had one: `option.candidates` is the whole
bucket including candidates the protocol and cache gates skip, so any number there overstates
what can play.

**Three constants moved, and each was documented in terms of the old card:**

- `QUALITY_CARD_MIN_WIDTH` 240 → **280 dp**. Its stated reason — the width below which the
  over-connection warning stops fitting on two lines — still holds, but that warning is now
  two levels of padding in rather than one.
- `NuvioComponentTokens.wideDialogMaxWidth` 880 → **920 dp**, following it: 880 was exactly
  three 240 dp columns and would have silently fallen to two. **Safe to change** —
  `wideDialogMaxWidth` is read only by `PlaybackQualitySheet.kt` in both repos, and
  `NuvioZDesktop`'s `TrackingAdaptivePicker.kt:132` / `TrackingProviderCards.kt:710` read
  `dialogMaxWidth` (460 dp), untouched. That check is the reason the two tokens are separate.
- `SKELETON_CARD_HEIGHT` / `SKELETON_CARD_COUNT` re-derived (taller cards, three not four).
  The skeleton exists so the surface does not jump when the figures arrive, so it is wrong the
  moment the real card's footprint changes.

The band row is drawn as an `overlayHover` lift plus a hairline `borderSubtle`, not a second
`Surface` colour: `surfaceCard` on `surfaceCard` is invisible and there is no third card
colour in the token set — the same trap that kept `NuvioSurfaceCard` off this sheet.

**This one was looked at before being called done, and looking caught two defects the suites
could not.** Both were on the Best available card, and both were the layout's own stated fault
committed again:

- It carried a **`★` badge over a row that then said "Best available"** — the same thing said
  twice, which is exactly what grouping by resolution exists to stop. The badge now carries
  the name, and `variantLabel` returns `""` for `BEST` for the same reason it does for
  `SINGLE`: the badge above it already names it.
- It **repeated the sheet's own description sentence** three lines under itself.
  `optionSummary` falls back to `playback_quality_description` when `requiredMbps` is null,
  which only Best available is — so only real figures take the trailing slot now.

With both gone the row held one muted caption in a full-height box and read as empty, so the
provider line is promoted to `bodyMedium`/`textPrimary` when it is a row's only content.

⚠ **Compose can be looked at on this machine without a device, and this is the general point,
not a footnote to this change.** `desktopTest` can construct an `ImageComposeScene`, render any
composable against synthetic state and write a PNG — `compose.desktop.currentOs` is already on
the desktop test classpath, so it needs no new dependency. That is the only local check that
sees layout at all; both suites and a careful read missed two defects it caught in one pass.

The harness used here is kept at
`C:\Users\Rayoa\.claude\plans\QualitySheetRenderHarness.kt` — drop it into
`NuvioZDesktop/composeApp/src/desktopTest/kotlin/com/nuvio/app/features/playback/`, run
`:composeApp:desktopTest --tests "*QualitySheetRenderHarness"`, and read the PNGs from
`composeApp/build/quality-sheet-render/`. It renders at 420 dp and 1100 dp, either side of the
768 dp threshold, so one run covers the bottom-sheet and centred-panel branches.
**Delete it again afterwards** — it asserts nothing, so it is not a test, and leaving it in
adds a rendering pass to every CI run.

**Verified:** Android **735 tests across 101 classes** and desktop **940 tests across 131
classes**, both zero failures, errors or skips — the 729 / 934 baselines plus exactly the six
new `group` cases, which run on both targets, so nothing was displaced. The desktop run
compiled `desktopMain`. `PlaybackQualitySheet.kt`, `PlaybackQualityOptions.kt`,
`PlaybackQualityOptionsTest.kt` and `Tokens.kt` are byte-identical across the repositories.

⚠ **Check the arithmetic, not the green tick.** An earlier desktop run here reported 939 and
was taken as passing; the real cause was that `PlaybackQualityOptionsTest.kt` had not been
re-copied after a sixth case was added, so the desktop target was silently running five of
six. A `cp` that happens before the last edit to a shared file is the failure mode, and a
green suite cannot see it — **`diff -q` all four shared files immediately before quoting a
count**, which is what AGENTS.md's mirroring rule is really protecting.

⚠ **`DesktopDownloadQueueE2ETest > a source that trickles and drops forever fails instead of
retrying forever` failed once here, then passed on a re-run — treat it as load-sensitive, not
flaky-in-principle.** It timed out at 240 s during a run that overlapped other Gradle work,
and passed on an idle machine. The scenario is documented above as taking ~134 s against that
240 s ceiling on a **real-time** backoff schedule (2 + 5 + 15 + 30 s per budget, twice), so
its margin is under 2x. Nothing in this change touches downloads. If it starts failing on an
idle machine, the fix is a `retryBackoffScale` in `DownloadsTiming` beside the stall and
watchdog knobs — not a longer timeout, and not shortening the schedule, which several other
scenarios observe.

**Published and checked after the fact, not just dispatched.** `0.4.12-beta` is `latest` on
both repositories, draft `false` and prerelease `false`, so the stable channel offers it and the
`debug-v*` line is unaffected. The arm64 APK was pulled back down from the release and
inspected: `com.nuvio.app.z`, `versionCode 122`, `versionName 0.4.12-beta`, signer SHA-256
`2325A339…84787C` — the same CI certificate as every release from `0.3.3`, which is what makes
the in-app update land rather than fail as "App not installed". Desktop shipped
`Nuvio-Z-Windows-x64-0.4.12-beta.msi` plus `SHA256SUMS.txt`.

**Committed directly on `main` / `Dev`** in both repositories and released as `0.4.12-beta`;
no branch, since the release procedure needs the bump as the last commit on the default branch.
`CurrentReleaseNotes` was rewritten for this release in the same pre-bump commit, per
`AGENTS.md`. **Still not smoke-tested in the running app**: the rendered PNGs are the real
composable but not the real data, so the five outstanding `0.4.11-beta` checks below carry
forward unchanged. Add two: a title with a three-way 1080p split should show one card with
three rows, and a title with one source per resolution should show single-row cards with no
band word.

## The quality selector is a grid, and it waits for its numbers (2026-08-09, `0.4.11-beta`)

⚠ **The flat-grid layout described here was replaced on 2026-08-10 — see "One card per
resolution" above.** Everything else in this section still stands: the three-state body, the
responsive container, the meter/warning coupling, the new width token and the dismiss
behaviour are all unchanged by that follow-up.


Streamlined's quality selector was a scrolling stack of rows in a `BasicAlertDialog` —
identical on a phone and on a 1080p desktop window, off the design system (raw
`MaterialTheme.colorScheme` and hardcoded `.dp` throughout, never `MaterialTheme.nuvio`), and
showing figures that changed under the user as addons answered. Branch
`claude/quality-selector-grid` in **both** repositories. The option set, the ranking, the
banding and everything under `PlaybackSourceSelector` are untouched: this is presentation, one
navigation change, and one gate on when the options become visible.

**1. Nothing is shown until the numbers are final.** `isLoading` used to only grey the rows
out, so partial options sat on screen for the whole time they were still changing — a row could
say "Needs about 9 Mb/s · 3.2 GB" and, a second later, say something else, with rows appearing
and re-banding around it. The body now renders **one of three states and never a blend**:
a skeleton grid on the same card footprint while loading; the option grid with final figures
once settled; and, for settled-but-nothing-selectable — which `isStreamlinedSelectionReady`
treats as terminal — the existing `playback_quality_no_match` text rather than an empty grid
with only "Choose source manually" under it, which is a dead end wearing a grid. No new state
and no timer: the gate is the `isLoading` value the call site already computed.

⚠ **`isLoading` and `isSelecting` are two parameters on the sheet and must not be merged
back.** The call site's old single flag was `tokenMismatch || isAnyLoading ||
streamlinedSelectionPending`, and that third term flips true the moment the user taps a card.
Under the old rendering it only greyed the rows out; under the three-state body it would have
replaced the grid with a skeleton *after* the user had chosen — and the uncached-debrid path
leaves the sheet composed under the consent dialog, so they would have watched it happen. So
`isLoading` now means only "the figures are still moving" and owns the skeleton, while
`isSelecting` leaves the grid exactly as it is and only stops it accepting taps (disabled, not
removed: a second tap would re-arm the selection effect against a different option). While
selecting, the subtitle is the progress overlay's own `playback_progress_choosing` rather than
"Finding available sources…", which after a choice is simply untrue.

⚠ **This trades an early wrong-looking choice for a wait**, deliberately. `StreamsRepository`
does settle, so the wait is bounded — but **how long the skeleton is up is the first thing to
watch in the smoke test.** If it feels broken, the fix is a "still searching" line, not
reinstating partial figures.

**2. A responsive surface, chosen from the real window.** `BoxWithConstraints` is at the top so
it measures `entry<StreamRoute>`'s full-screen `Box`, not a phone-sized dialog — a
`BasicAlertDialog` here clamps width and would have silently shipped the phone layout
everywhere. Wide (≥768 dp, the repo's threshold at `App.kt:2051` and
`ProfileSelectionScreen.kt:112`) gets a scrim and a centred panel; narrow gets
`NuvioModalBottomSheet`. The wide branch must **not** use the bottom sheet:
`usesNativeNuvioBottomSheet` is false on desktop, so it falls through to Material's
`ModalBottomSheet` and would pin a phone sheet to the bottom of a 1080p window. The body is a
`LazyVerticalGrid(GridCells.Adaptive(240.dp))`, so one composable serves phone, tablet and
desktop, and the height ceiling is derived from the measured window rather than the old literal
420 dp — which is what made the third quality band unreachable.

**3. The meter and the warning cannot disagree.** `PlaybackQualityOptions.connectionFit`
absorbed the sheet's private `isOverConnection` predicate, so the bar and the sentence are two
renderings of one pure function rather than two expressions of the same comparison in different
files. The track runs to twice the estimate, so the marker at its midpoint *is* the connection
and a fill past it is the warning restated. The over-connection text keeps its wording, its
prominence and its strong colour — it is the part of the old sheet the user asked to keep. The
marker's position is derived from `MAX_LOAD_FRACTION` rather than restating its inverse as a
literal, since the two constants live in different files.

**Everything is on `MaterialTheme.nuvio` tokens.** `NuvioSurfaceCard` and `NuvioInfoBadge` were
**not** reused, and that is not an oversight: both take their colour from `colors.surface` /
`colors.surfaceCard`, and `surfaceSheet == surface` in the token set, so a `NuvioSurfaceCard` on
this sheet would have been invisible against its own background, and `NuvioInfoBadge` invisible
against the card.

**4. `NuvioComponentTokens.wideDialogMaxWidth` (880 dp) is new, and is not a widening of
`dialogMaxWidth`.** 460 dp leaves 420 dp of content and therefore exactly **one** 240 dp column.
`dialogMaxWidth` looks unused in `nuvio-z` but **`NuvioZDesktop`'s `TrackingAdaptivePicker.kt`
and `TrackingProviderCards.kt` lay out against it**, so widening it would have stretched two
desktop settings pickers as a side effect. 880 dp is three columns plus their gaps and padding.

**5. Dismiss returns to details; only "Choose source manually" reaches the source list.**
`onDismiss` and `onChooseManually` were byte-identical — both set `manualSourceListRequested`,
uncovering the Classic source list. Tolerable behind a dialog, wrong behind a bottom sheet,
where a stray swipe drops the user into the list they chose Streamlined to avoid. Dismiss now
calls `onBack`.

⚠ **`onBack` can silently do nothing, so it has a fallback.** `rememberGuardedPopBackStack`
pops only while its route is current and returns `Unit`, so the caller cannot tell; with
`qualitySheetDismissed` set and `manualSourceListRequested` still false, the opaque hand-off
`Box` keeps painting over `StreamsScreen` and the user gets a blank screen with no affordance —
the same class of fault as `onPlaybackFailureExit`'s silent no-op. A `withFrameNanos` effect
re-checks the current route and uncovers the source list if the pop no-oped.

⚠ **That effect is declared beside the route's flags, not inside the sheet's `if`.** `onDismiss`
sets `qualitySheetDismissed = true`, which is part of that `if`'s own condition, so an effect
declared inside it would be cancelled mid-`withFrameNanos` by the very state change it exists to
observe — the fallback would never fire and the blank-screen strand would ship.

⚠ **The other five `manualSourceListRequested = true` sites are untouched**, deliberately: they
are the failure-chain exhaustion and uncached-debrid paths, and the exhaustion one is the
"hang wearing a spinner" the `0.4.10-beta` section exists to kill.

**Verified:** Android **729 tests across 101 classes** and desktop **934 tests across 131 classes**, both zero
failures, errors or skips — the 724 / 929 baselines plus the same five new `connectionFit` cases
(unknown estimate, unknown requirement, exactly at the line, over the line, and the display
clamp). The desktop run compiled `desktopMain`. `PlaybackQualitySheet.kt`,
`PlaybackQualityOptions.kt`, `PlaybackQualityOptionsTest.kt` and `Tokens.kt` are byte-identical
across the repos and were `cp`'d; `App.kt` and `strings.xml` were hand-ported.

**Not smoke-tested on a device or an installed desktop app — nothing here has been looked at.**
Compose is verified by compilation only, so the entire layout is unseen. In order:

1. **Numbers never change once visible.** On a plugin-heavy profile, watch from the first frame:
   skeleton, then the grid, and no figure that moves afterwards. Time the skeleton.
2. Swipe the sheet away → **details, not the source list**; hardware back the same; and never a
   blank opaque screen (the `onBack` no-op fallback).
3. An **uncached debrid** option → the consent `AlertDialog` is readable and on top. It is now a
   Material `AlertDialog` over a `ModalBottomSheet` — **two platform dialog windows on Android**,
   which the old `BasicAlertDialog` pairing did not produce. This is the most likely new defect.
4. Exhaustion (an episode whose whole chain fails) → still uncovers the source list, not details.
5. Desktop: resize across the 768 dp threshold with the sheet open — panel ↔ sheet without
   losing state, and 2–3 columns when wide.

## Streamlined made to work, Instant withdrawn, downloads that cannot loop (2026-08-09, `0.4.10-beta`)

Four commits in `nuvio-z`, mirrored to `NuvioZDesktop`. Reported as: AIOStreams errors
("stream not cached", an unnamed error) dead-ending Streamlined since `0.4.9-beta`; the
resolution selector being a plain list with no middle option; and a Punisher episode stuck at
5.8 of 6.2 GB saying "Retrying" forever, which pause/resume could not clear.

**1. Streamlined had no failure chain, and never had one.**
`completeStreamlinedOptionSelection` called `PlaybackSourceSelector.select`, took
`Play.stream` and threw `Play.fallbacks` away, so one `NotCached` answer from the provider was
the end of the road — while Instant, seeding the very same chain through
`seedAutoPlayCandidates`, stepped past it. `1df19a17` (the plausibility ceiling that shipped in
`0.4.9-beta`) is the likeliest *trigger*, because it changed which candidate heads each row,
but the missing chain is the fault and the fix is right either way. **Nobody should spend time
proving the 0.4.9 connection.** Streamlined now seeds the whole row and hands off to the
auto-play effect, so resolve failures, P2P, reuse-last-link and the attempt counter behave
identically in both modes. Every `isInstantAutoPlay` test in that effect was really asking "is
there a next candidate?", so they became one `hasFailureChain`.

Each advance names the source and the reason — stepping past a dead candidate silently is
indistinguishable from a hang, and "unknown error" is what the absence of that looks like.
An exhausted chain uncovers the source list instead of leaving the progress overlay up.

Cache evidence became the **third** ranking key, below plausibility and torrent-ness. Promoting
it above plausibility would let an implausible cached season pack head the row again and it
would not show, because `credibleBitrateMbps` filters the display — only what actually plays
would regress. Pinned by a test.

**2. Instant is withdrawn**, behind a single `PlaybackMode.isSelectable` predicate — the
`isImplemented()` machinery was deleted in `0.4.1-beta`, so this is new construction. A stored
`INSTANT` is coerced to `STREAMLINED` at **read** time; `fromStorage` still answers `INSTANT`,
so the key survives and those profiles come back if the mode returns. Auto source-swap needed
no behavioural change — `maybeDownshift` already returns early unless the mode is `INSTANT` —
only a caption saying it is withheld rather than broken.

**3. A third quality band.** Buckets split three ways once their spread reaches 2.25
(`SPLIT_RATIO²`) on geometric thirds, two ways at 1.5, otherwise not at all. Mid is a band, not
a fixed row, so a title with nothing in the middle still shows exactly High and Low — which is
why no existing test expectation moved. **A lone "Mid" row is unreachable**: the cheapest source
always falls below the lower boundary and the dearest always reaches the upper one, so High and
Low are always occupied. The collapse guard is kept for whoever moves those boundaries later,
not because a hole is open; `aThreeWaySplitAlwaysHasBothEnds` pins it. The
`PlaybackQualityTier` storage key set was **not** touched.

The sheet was two `Text`s in a `Column`. Rows now lead with a resolution badge and name the
provider and release of the source that would really open — `previewSelection`, not
`candidates.first()`, because the protocol and cache gates can skip several candidates.

**4. Downloads could retry forever.** `DownloadsRepository.kt:887` zeroed `attemptCount` on
*any* forward byte, so a source that trickled and dropped refreshed its budget every cycle:
`shouldRetry` never returned false and the row cycled Downloading → trickle → drop → Queued
indefinitely. Pause/resume could not clear it because nothing was wedged — during the backoff
the item is `Queued` with no handle, so `pauseDownload` had nothing to cancel and
`resumeDownload` zeroed `attemptCount`, which is what the loop was already doing to itself.

Now the budget only resets on progress measured from `retryCycleStartBytes`; an exhausted
budget restarts from zero **once** on a fresh link, then fails with a named message. No new
`expect` was needed: setting `downloadedBytes = 0` makes the existing downloaders take their
`appendToTemp = false` path and delete the `.part` themselves.

- **`meaningfulProgressBytes` scales both ways.** The plan called for `max(16 MiB, 1%)`, which
  is wrong at the small end — the harness serves 6 MiB episodes, so a flat 16 MiB floor could
  never be cleared and would have replaced one stuck state with another. It is now 1% on large
  files, capped at a quarter of the file on small ones.
- **`reclaimLostTransfersLocked` now charges an attempt.** It was a second, independent
  unbounded cycle: it never went through `onTransferFailed` and never touched `attemptCount`,
  so the queue watchdog could recycle an item forever for free. The two mechanisms cannot
  double-bill: `DownloadQueuePlanner.lostTransfers` (`:71-80`) matches only `Downloading` and
  system-paused items, never a `Queued` item waiting out `nextRetryAtEpochMs`. **Re-check that
  if the filter ever grows a `Queued` arm.**

**Verified:** Android **724 tests**, desktop **929 tests**, zero failures — baselines were 700
and 908. Three new harness scenarios drive the real queue against twelve queued
`DropConnection` faults: trickle-and-drop ends in a named failure, the restart happens exactly
once, and a source that recovers still completes.

**The harness is now slower on purpose.** `retryBackoffMs` is real time and deliberately not
turned down — 2 + 5 + 15 + 30 seconds per budget, twice, plus the restart between them, so a
trickling source takes about 130 s to be allowed to give up. Measured: the two exhaustion
scenarios take 134 s each and the recovery one 82 s, so `DesktopDownloadQueueE2ETest` went from
about 290 s to about 470 s. If that becomes intolerable, add a `retryBackoffScale` to
`DownloadsTiming` beside the stall and watchdog knobs rather than shortening the schedule
itself — but re-run the whole harness afterwards, because several existing scenarios observe
the `Queued`/backoff window and a short scale makes that window smaller than the poll interval.

**Not device-verified.** Released on the maintainer's explicit instruction with tests as the
only evidence. Two behaviours no test reaches:

1. **Streamlined no longer pops `StreamRoute`** (`if (!hasFailureChain) popUpTo<StreamRoute>`).
   Required for retries, but it changes Back-from-player for the *default* mode. Check that
   Back after a **successful** Streamlined start shows neither a re-displayed quality sheet nor
   a stuck progress overlay.
2. **Exhaustion sets `manualSourceListRequested`** while `qualitySheetDismissed` and
   `streamlinedPlaybackStarting` are both true. Confirm the source list actually wins over the
   overlay — that is the "hang wearing a spinner" this exists to kill.
3. The plan's "a Streamlined retry advances rather than re-seeding" case has **no unit test**:
   it is Compose state inside `entry<StreamRoute>`. Verified by reading — the re-arm effect at
   `App.kt:2929` touches only `playbackHandedOff`/`autoPickAttempt`, and the selection effect
   self-clears `streamlinedSelectionPending` — but not by running.

**Flagged, not fixed: Android has no active stall watchdog.** Desktop polls and force-closes a
silent stream (`DownloadsPlatformDownloader.desktop.kt:60-84`); Android relies on a
**hardcoded** `readTimeout(60s)` (`android.kt:26-34`) that ignores `DownloadsTiming.stallTimeoutMs`,
so the harness cannot exercise Android's stall path at all. Its own commit.

**Also deliberately out of scope:** `PlaybackSourceSelector.rank` and
`PlaybackQualityOptions.preferencesFor` still hardcode `CodecPreference.ANY` /
`DynamicRangePolicy.ANY` and never set `preferredAudioLanguage`. A real defect, but it changes
what gets picked for anyone with those preferences set — its own commit, its own smoke test.

## Instant's failure chain died the moment playback started (2026-08-08)

**Reported as "the debug video player keeps kicking me out": the logo overlay appears, the
episode plays for about a second, and the user is dropped back on the details screen.** That is
not a diagnostics bug. Instant's three-source failure chain was unreachable for the most common
failure there is - a source that opens, starts, and then dies.

Two independent defects, both in the same handler (`App.kt`, `onFatalPlaybackError`):

1. **It read state that had already been cleared.** `onPlaybackStarted` fires on the first
   `!wasPlaying && isPlaying` edge and calls `consumeAutoPlay()`, which nulls `autoPlayStream`
   **and** empties `autoPlayCandidates`. The handler then read `autoPlayStream`, found null,
   concluded `hasNext = false`, and took the exhausted branch - the "no automatic source" toast -
   on the *first* failure, with two ranked candidates untried. The chain only ever worked for
   sources that failed before rendering a frame.
   Consuming on the first frame is not the bug and must not be "fixed": Instant deliberately
   leaves `StreamRoute` on the back stack, so an unconsumed chain means backing out of the player
   relaunches it. `StreamsRepository` now *retains* what `consumeAutoPlay` retired, and
   `failOverAfterPlaybackStarted()` re-arms it and advances past the dead source. It is
   single-shot and is dropped by `seedAutoPlayCandidates`, so a chain can never fail over to
   candidates ranked for different content.
2. **It navigated past the thing that does the retrying.** The handler called
   `onBackToDetails()`, whose every branch pops `StreamRoute` - and `StreamRoute` is where the
   whole chain lives: the auto-play `LaunchedEffect` keyed on `autoPlayStream`, `autoPickAttempt`,
   and the "Finding a source" overlay. So even with a next candidate correctly selected, nothing
   was left alive to launch it. The comment at the `playbackHandedOff` declaration
   ("Instant deliberately leaves StreamRoute on the back stack so the failure chain survives")
   states the invariant this violated. `onPlaybackFailureExit` now pops only the `PlayerRoute`,
   falling back to `onBackToDetails()` when there is no `StreamRoute` to return to (the
   reuse-last-link and P2P paths both produce that) **and** when the pop itself no-ops.
   That second case matters: `popBackStack(expectedRoute)` returns `false` without moving if the
   player is not on top, and `instantFailureHandled` is already spent by then, so a silent no-op
   would strand the user on a dead player with neither a retry nor an exit.

Exhaustion now lands on `StreamRoute` too, not details. With `autoPlayStream` cleared that route
renders the plain source list, which is what `PLAYBACK_MODES_PLAN.md` specifies: *"Only after the
chain is exhausted does it fall back to the Classic source list with a reason."* It was going to
details instead - a deviation from the plan that no test covered because the whole chain is
UI-level navigation.

Returning to `StreamRoute` also had to un-hide the progress overlay: `playbackHandedOff` survives
in `rememberSaveable(route.launchId)` and forces `PlaybackProgress.isVisible` false, so a retry
would otherwise land on a bare source list. A `LaunchedEffect` gated on *this route being current*
resets it and advances `autoPickAttempt`. The gate matters - Instant leaves `autoPlayStream` set
while the player is open, so without it the reset fires at hand-off and uncovers the overlay
underneath the player.

⚠ **`instantSelectionHandled` must stay latched — do not reset it alongside `playbackHandedOff`.**
It guards the effect that *selects* Instant's source and calls `seedAutoPlayCandidates`. Clearing
it on a retry would re-seed the chain back to candidate 1, and the failure would loop forever
instead of advancing.

**Verified:** `:composeApp:testAndroidHostTest` in `nuvio-z` - **700 tests, zero failures**,
including five new `AutoPlayFailoverTest` cases. `:composeApp:desktopTest` in `NuvioZDesktop` -
**908 tests, zero failures**, and it compiled `desktopMain`. `:androidApp:assembleFullDebug`
rebuilt so the installed APK contains the fix. `StreamsRepository.kt` was hand-ported (the repos
already differ at `presentStreamGroup`), `App.kt` hand-ported per the never-`cp` rule, and the
test file copied. **Not smoke-tested on a device.**

⚠ **This makes the failure recoverable and visible; it does not explain why the source died after
a second.** To capture that, enable Settings → Playback → **Playback diagnostics HUD** before
playing: `f3a30dcb` makes the player retain the real error instead of exiting. Note the HUD flag
is a non-persisted `mutableStateOf`, so it resets on every app start and must be re-enabled.

## Playback connection-drop diagnostics (2026-08-08, Phase 1 complete in code)

The instrumented build from `~/.claude/plans/okay-we-need-to-humble-balloon.md` is implemented
in both repositories. It is debug-gated and off until Settings -> Playback -> **Playback
diagnostics HUD** is enabled. In a debug build the normally advanced automatic-downshift row
is visible without enabling all advanced settings.

The HUD reports real buffer ahead/position/duration and labels it with the live engine
(ExoPlayer or libmpv), source resolution/release group/provider/addon, the provider-keyed
network estimate and confidence, and every state-machine field plus time remaining to the
trigger. Android ExoPlayer can be throttled live to Off / 20 / 10 / 5 / 2 Mbps. The HUD also
forces one safe step down or up in the same release group and resets the automatic swap budget.
It explicitly warns when libmpv is live because the ExoPlayer throttle cannot affect it.

Every automatic or forced swap is recorded in a bounded, in-memory, copyable log: elapsed
timestamp, reason, from/to quality, group, provider and addon, buffer at trigger, position
before/after, and the gap until the replacement actually plays. Automatic downshift now shows
a user-facing toast instead of changing quality silently. Manual source choices are not logged
or toasted. No Phase 2 buffer tuning or Phase 3 automatic upshift/default change was made.

**Local verification:** Android host tests and desktop tests pass, including the new forced
upshift and swap-log cases; desktopMain compiled with the new debug actual. A clean
`:androidApp:assembleFullDebug` passes and produces the side-by-side-installable debug APK.
The first combined debug/release packaging attempt hit a stale Gradle transform pointing to
the repository's old path; cleaning generated build outputs fixed the debug build. A standalone
`:androidApp:assembleFullRelease` then compiled, passed lint, R8/minification and resource
optimization, and stopped only at final APK packaging because this checkout has no release
keystore (`SigningConfig "release" is missing storeFile`). No device verification.

### Device test script

0. Settings -> Playback: set **Playback mode = Instant**, enable **Switch source when buffering
   persists**, and enable **Playback diagnostics HUD**.
1. Start a 4K episode and confirm the HUD says **ExoPlayer**. If it says libmpv, throttle tests
   are invalid. Record buffer ahead after it settles.
2. Tap **Force down**. Check the preserved position, audio/subtitle selection, replacement
   quality, and the measured gap in **Log**.
3. Restart playback, confirm ExoPlayer again, let it settle for at least 15 seconds, then select
   **2 M**. Confirm the starvation run builds and fires after roughly 21 seconds total
   (15-second settle plus 6-second sustained starvation).
4. Turn the throttle **Off** and confirm there is no oscillation.
5. Tap **Reset budget**, restart if needed, and repeat with **10 M** to test a partial drop.
6. Copy the log and report it together with the settled buffer-ahead value and whether the
   forced swap preserved position and tracks.

## Instant predictability, and the missing desktop Next Episode button (2026-08-08)

Two user reports from the `0.4.9-beta` build: Instant "feels like spinning a roulette wheel on
what resolution I'm going to get", and there is still no Next Episode button in the player.
Branch `claude/instant-predictability-next-ep` in **both** repositories.

**The Next Episode button was a desktop-only gap, and not where it looked.** The Compose
player has had one since forever - `PlayerControls.kt` renders a `SkipNext` pill whenever
`nextEpisodeInfo?.hasAired == true`, and that file is byte-identical across the repos. But
**desktop never mounts that control bar.** `5b3fc81d` ("feat: skip intro/outro to native
player") moved the desktop player to a native HTML overlay
(`desktopMain/resources/player-ui/controls.html` + `controls.js`, driven by
`NativePlayerController`), and its action row had resize/speed/subs/audio/sources/episodes and
no next-episode entry. Added one: `data-command="nextEpisode"` →
`PlayerControlsAction.NextEpisode` → the same `playNextEpisode()` the Compose pill calls, with
`nextEpisodeLabel`/`showNextEpisode` crossing the bridge beside the `nextEpisodeVisible` fields
that were already there. Reuses `#icon-skip-next` and the existing `player_next_episode`
string, so no new icon and no new string key.

⚠ **The `!isDesktop` guards in the desktop `PlayerScreenRuntimeUi.kt` are correct - do not
"fix" them.** `showNextEpisodeCard && !isDesktop` and `activeSkipInterval.takeUnless
{ isDesktop }` suppress the *Compose* card and skip prompt because the HTML layer owns both
(`#nextEpisodeCard`, `#skipPrompt`). Removing them double-renders.

**Instant was never random - it was opaque, and it churned.** Checked before changing
anything: no `shuffled`/`Random` anywhere in `features/playback/` or `features/streams/`, and
`SourceRanking`'s comparator ends in `.thenBy(addonOrderOf).thenBy(stableUrlOf)`. The one
plausible real race was ruled out too - `isAnyLoading` cannot flip false while a debrid cache
check is outstanding, because a group awaiting annotation is not republished until
`publishAddonGroup` runs *inside* the availability job (`StreamsRepository.kt:302-339`), so the
pre-completion `isLoading = true` copy is what `anyLoading` sees. Instant genuinely waits for
settled cache state.

What actually varies between two taps that look identical: the derived rows come from *this*
episode's catalogue (an empty bucket produces no row), and the estimate ratchets upward as you
watch. Both are correct; neither is visible. So:

- **`PlaybackQualityOptions.stickyAffordable`** - `highestAffordable` biased towards the
  resolution this series already got in this sitting. It will not override a metered cap, will
  not hold a resolution the estimate can no longer carry, and will not invent a row the
  episode does not have. A tie-break towards stability, never a ceiling or a floor.
- **Instant now says what it opened** - a toast, `Playing 1080p · WEB-DL · TorBox`, raised
  before navigation so it works on both platforms without a Compose overlay over the desktop's
  native surface.

Two traps worth not re-stepping on:

- **The pin is written where a source *opens*, not where Instant *chooses*.** Instant's failure
  chain (`skipAutoPlayStream`) can advance past a dead or evicted candidate to a different
  resolution. Pinning the choice would record something that never played, and the next episode
  would then prefer a resolution that just failed - reintroducing exactly the churn this
  removes. Same reasoning for the toast.
- **`BingeGroupCacheRepository.sessionPin` could not be reused for this**, despite being the
  obvious home. `StickySourcePin.isEmpty` ignores `resolutionHeight`, so a resolution-only pin
  is *discarded* on save; and a non-empty one would make Streamlined skip its quality sheet.
  `sessionInstantHeight`/`saveSessionInstantHeight` is a separate map in the same file, keyed
  by `parentMetaId`, session-scoped for the same reason the sticky pins are, and cleared by the
  same `clearSessionPins()`.

**Two known gaps, both deliberate, neither started:**

- **No max-quality ceiling for Instant.** The user has no lever over resolution at all. It
  wants a profile-scoped key, which means three `PlayerSettingsStorage` actuals across both
  repos plus `syncKeys` and both sync-payload paths - and editing that key set is what wiped
  the playback settings in `0.4.0-beta`. Left for its own commit.
- **User codec/HDR/audio-language preferences are dead on the playback path.**
  `PlaybackSourceSelector.rank` hardcodes `CodecPreference.ANY` / `DynamicRangePolicy.ANY` and
  never populates `preferredAudioLanguage`; `PlaybackQualityOptions.preferencesFor` does the
  same. They work for downloads only. This is a real defect, not a missing feature, and fixing
  it changes what Instant picks for anyone who has set them - so it needs its own commit and
  its own smoke test.

**Verified:** `:composeApp:testAndroidHostTest` in `nuvio-z` - **687 tests across 96 classes**,
zero failures, errors or skips, including six new `stickyAffordable` cases.
`:composeApp:desktopTest` in `NuvioZDesktop` - **895 tests across 127 classes**, zero failures,
errors or skips, and it compiled `desktopMain`, which is the only local check that the new
`PlayerControlsAction.NextEpisode` arm and the `PlayerControlsState` fields actually build.
Four shared files are byte-identical across the repos
(`PlaybackQualityOptions.kt`, `BingeGroupCacheRepository.kt`, `PlaybackQualityOptionsTest.kt`,
`PlayerControls.kt`); `App.kt` and `PlayerScreenRuntimeUi.kt` were hand-ported.

⚠ **`controls.html` and `controls.js` have no automated coverage at all** - `desktopTest`
compiles `desktopMain` Kotlin and never parses the resources, so a typo there ships silently
and a duplicate `const` would blank the entire overlay. Checked by hand instead:
`node --check controls.js` passes, and `nextEpisodeLabel`/`nextEpisodeButton` are each declared
exactly once in the JS and appear exactly once as an id in the HTML.

**Not smoke-tested.** No Android device and no installed desktop app were available. **Nobody
has clicked the new button** - item A is verified by code inspection and a compiling desktop
build only. Still outstanding: the desktop button on a series (and hidden on a movie and on a
last episode), that the native next-episode card and skip prompt still work, three consecutive
Instant episodes holding one resolution, and Instant on a metered connection with a pin in play.

## Derived options: first smoke test, three fixes (2026-08-07, `0.4.9-beta`)

`0.4.8-beta` was tested on device and desktop. Three findings, all fixed:

- **"High" was the biggest file in the catalogue.** A Daredevil episode offered 85 GB as
  1080p High - 227 Mbps, which is a season pack's torrent-level size, not an episode.
  `SourceRanking` sorts size descending, so the largest number always headed the row and the
  quoted bandwidth was fiction. There is now a per-resolution plausibility ceiling (1080p
  50 Mbps, 2160p 150 - above the ~128 Mbps UHD Blu-ray maximum, so a genuine remux still
  leads). Implausible sizes cannot head a row, set its bandwidth, or set its displayed size;
  they sort last within it and stay reachable, because a pack often still resolves to the
  right file. A bucket with nothing credible falls back to an approximate estimate.
- **Instant still chose 1080p on a connection watching 4K.** Two causes, both too cautious.
  `HEADROOM` was 0.6 - a 1.67x margin, so a 19 Mbps 4K release read as needing 31. That suits
  a live ladder with no buffer, not a VOD player buffering seconds ahead with downshift behind
  it; it is now 0.75. And the Wi-Fi first-play default of 25 Mbps sat exactly on the boundary
  for a 7 GB 4K episode, so defaults are now Wi-Fi 50 / Ethernet 100 / cellular 10 /
  unknown 15. These are first-play guesses only; one minute of clean playback replaces them.
- **Streamlined on desktop skipped the sheet and went straight to the player.** Not a
  regression - a stored sticky pin outranks the quality sheet by design, and the pin was on
  that device and not on the phone. But a *persisted* pin turns Streamlined into Instant for
  that season with nothing in the UI to clear it and no clue why the sheet stopped appearing.
  Sticky pins are now session-scoped (`BingeGroupCacheRepository.sessionPin` /
  `saveSessionPin`), held in memory and gone on restart. The binge-group cache is untouched:
  it is a different key space (`parentMetaId`, not `stickyContentId`) and genuinely long-lived.

**Verified:** Android host suite and desktop suite both pass. Not re-smoke-tested.

## Quality options are derived from the catalogue (2026-08-07)

The preset model ran backwards. A fixed list of `PlaybackQualityTier`s was the input and the
addons' answers were filtered to fit it, so the sheet offered rows that matched nothing for a
given title, hid quality that was on offer, and quoted the preset's nominal bandwidth rather
than what the file you would actually receive costs.

Now the catalogue leads. `features/playback/PlaybackQualityOptions.kt` (pure, repository-free,
testable outside Gradle) buckets the real candidates by resolution, splits each bucket into
High/Low when its top source costs at least 1.5x its cheapest, and quotes
`fileBitrate / 0.6` - the connection speed that source actually needs. **An empty bucket
produces no row**, so a title with no 4K release simply has no 4K option. Instant takes the
highest option the estimate can carry; Streamlined shows them all with their bandwidth.

Details worth knowing:

- Bitrate uses the file's own runtime when an addon reports one. `SourceFacts.durationSeconds`
  now carries `clientResolve.parsed.duration`, which the extractor previously dropped; the unit
  is undocumented so it is inferred and discarded when not credible. Falls back to the title
  runtime, then the shared 45/120 minute default.
- `parseResolution` reads a bare `uhd`/`hd` out of a display name. A mislabel used to just fail
  a filter; now it would mint a visible 4K row that plays a 720p file, so a source whose bitrate
  is below the floor for what it claims is **demoted** to what its bitrate supports. Demotion
  only - a bloated 1080p remux is still 1080p.
- HDR policy follows resolution (2160 prefers, SD avoids), not the row's rank.
- Headroom is applied in exactly one place. `PlaybackQualityTier.sizeCapBytes` folded the same
  0.6 into a byte cap and is no longer on this path.
- **The network estimate had to move in the same change.** The effective gate was
  `fileBitrate <= 0.6 x estimate`, and the hardcoded WiFi default of 3 Mbps made that ~1.8 -
  below a real 720p encode. It only looked fine because unknown-size candidates skipped the cap
  entirely, which derived options remove. Defaults were raised again after this was written and
  are WiFi 50 / Ethernet 100 / cellular 10 / unknown 15 — and as of the network-strength work
  below they are never shown to the user as a connection speed. `recordSustainedBitrate` feeds
  the estimate from playback rather than
  only from downloads. It is **monotonic**: a stream arrives at the file's own bitrate and no
  faster, so a clean playback is a lower bound; smoothing it in would have dragged the estimate
  down towards whatever the user last watched and cost Instant its top qualities over time.
  Armed when a source is chosen, confirmed after a minute of unstarved playback. It reuses
  `AutoDownshiftDetector.SETTLE_GRACE_MS` before judging anything: a snapshot starts with
  `isLoading = true` and an empty buffer, so without the grace every source disqualified
  itself on frame one and the measurement could never fire.
- Two guards on erring high. `highestAffordable` returns null rather than Best available when
  a metered cap excludes every option - Best available is ordered resolution-descending, so
  the fallback would have handed a 4K remux to a capped mobile connection. And
  `resolutionForEstimate`, which feeds the download button that never asks, will not reach
  2160 on a `PLATFORM_DEFAULT` estimate; over-reaching costs a hiccup when streaming and ten
  times the disk when downloading.
- **`PlaybackQualityTier` is dormant, not deleted.** Nothing reads it to choose a source. Its
  storage key, sync entries and `mergeStoredTiers` are untouched on purpose: editing that key
  set is what wiped the playback settings in `0.4.0-beta`, and the removal buys nothing the user
  can see. Remove it in its own commit. `presetForTier` became `presetForResolution`, fed by a
  small `resolutionForEstimate` ladder, so the details-screen download button no longer keeps a
  second picker alive.
- Two stuck-spinner paths from the `0.4.3` smoke follow-up are fixed here, because this change
  rewrites the code they live in: `isStreamlinedSelectionReady` now treats "settled with streams
  but nothing selectable" as terminal (`toEmptyStateReason` reports no empty state in that
  case), and every early return in the streamlined effect clears the pending flag. Option ids
  are `resolution + variant` so they survive the `rememberSaveable` round-trip through a
  refetch.

**Verified:** `:composeApp:testAndroidHostTest` in `nuvio-z` - 675 tests, all pass;
`:composeApp:desktopTest` in `NuvioZDesktop` - passes, and it is the only local `desktopMain`
compile. Thirteen shared files are byte-identical across the repos; `App.kt`,
`MetaDetailsScreen.kt` and the three player runtime files were hand-ported.
**Not smoke-tested on a device or the desktop app yet.**

## Playback modes: Classic / Streamlined / Instant (2026-08-06, in progress)

**The plan is `PLAYBACK_MODES_PLAN.md` in this repository, and it is self-contained** — a cold
agent can execute it without this conversation. It covers both repositories. Its execution
ledger is the resume point; keep it current in the same commit as the code.

Three global playback modes, chosen once on a new first-launch selector, with a per-play escape
hatch (long-press on mobile, right-click on desktop) that always reaches the Classic source list:

- **Classic** — today's flow, unchanged, and the fallback when auto-pick misjudges a user's
  plugins or debrid.
- **Streamlined** — pick a quality tier, the app picks the source. Optional sticky pin
  (release group → bingeGroup → addon/provider/resolution) makes the rest of a season one tap.
- **Instant** — quality and source chosen from the network connection; metered connections ask
  before playing.

**Phase 1 landed so far — logic and persistence only, nothing user-visible.** The mode is
stored and defaults to `CLASSIC`, and no code reads it yet, so behaviour is unchanged:

- `features/playback/PlaybackModeModels.kt` — `PlaybackMode`, `PlaybackQualityTier` (a
  *bandwidth* budget, deliberately not a `DownloadPreset`, with a 60% headroom constant),
  `mergeStoredTiers` mirroring `mergeStoredPresets`, and `StickySourcePin` with a scored match.
- `features/playback/PlaybackModeRouter.kt` — the precedence table as a pure function.
- `playback_mode` and `playback_mode_selector_seen` through `PlayerSettingsStorage` with
  **all three actuals** (android, ios, and the desktop one in `NuvioZDesktop`), added to
  `syncKeys` and both sync payload paths, and surfaced on `PlayerSettingsUiState` with
  `setPlaybackMode` / `markPlaybackModeSelectorSeen`.

**A correction to this document's own build advice: Gradle works on the maintainer's Windows
machine.** It only needs `JAVA_HOME` (Android Studio's JBR) and `ANDROID_HOME` set per
invocation — the failure without them is "SDK location not found" during task dependency
resolution, which reads like a configuration failure and is not one. `AGENTS.md` now records
the exact invocation. The sandbox limitation is real but is a sandbox limitation, not a
project one, and the standalone-kotlinc workarounds should be a second choice from now on.

Verification of the above: `:composeApp:testAndroidHostTest` run in full on this machine —
**576 tests, zero failures, errors or skips**, across 82 classes. That is the documented 554
baseline plus the 22 new cases exactly, so nothing was displaced. `PlaybackModeRouterTest` (11)
and `PlaybackQualityTierTest` (11) execute against the shipped sources — including the
regression case that a sticky pin must outrank reuse-last-link, which is the specific bug the
precedence table exists to prevent.
Shared files are mirrored and the only diffs against `NuvioZDesktop` are the pre-existing,
documented ones (its `AppFeaturePolicy` external-player gating and the NVIDIA RTX setting).

`NuvioZDesktop :composeApp:desktopTest` also passed in full: **782 tests, zero failures,
errors or skips**, across 112 classes — the 760 baseline plus the same 22 cases, which run on
the desktop target too. **This compiled `desktopMain`, so the new desktop `actual` is verified
rather than assumed**, and the download harness stayed green. Since `desktopTest` compiles
`desktopMain` on the real machine, `desktop-release.yml mode=build-only` is now only *CI's*
way of catching a missing actual, not the only way.

**The mode is now reachable in the UI.** Settings → Playback → Player → **Playback mode** opens
a `PlaybackModeDialog` listing all three modes with descriptions, and the row is in the settings
search index. Streamlined and Instant are selectable but carry a "Not ready yet - plays like
Classic for now" caption, since nothing consumes them until Phases 2 and 3; `isImplemented()`
in `PlaybackSettingsPage.kt` is the one place to update as each lands. Nine new string keys in
both `values/strings.xml`; the other 24 locales fall back to English as usual.

Both suites re-run green after the UI landed: Android **576** and desktop **782**, zero
failures, errors or skips. Note the settings files genuinely differ between the repositories
(desktop gates the external player behind `AppFeaturePolicy`, renamed the Trakt page to
Tracking, and builds the search rows with `buildList`/`add` rather than `listOfNotNull`), so
these were hand-ported, not copied — a straight `cp` would have broken the desktop build, and
the first attempt did pass two arguments to a single-argument `add(...)`.

**Phase 1 is complete.** Also landed: `PlaybackModeSelectorScreen`, shown on first launch to
everyone (existing installs included) and pre-selected to Classic, so dismissing it changes
nothing. It is gated by **wrapping the `AppGateScreen.Main` branch in `App.kt` rather than
adding a gate value** — five separate transitions set the gate to `Main`, and wrapping covers
every one of them with a single decision instead of five edits that could drift.

Two findings from building the UI:

- **The manual-selection escape hatch already existed.** `MetaDetailsScreen` has always had a
  "Play manually" action in the episode long-press overlay, using the `onPlayManually` callback
  `App.kt` already threads through — it was just gated on
  `StreamAutoPlayPolicy.isEffectivelyEnabled(...)`. Showing it when the mode is not `CLASSIC`
  was a one-condition change, not new plumbing, and since `onPlayManually` sets
  `manualSelection = true` it already satisfies precedence rule 1.
- **`entry<StreamRoute>` wiring is deliberately deferred to Phase 2**, and is its first step.
  In Phase 1 every mode resolves to the source list, so calling `PlaybackModeRouter.decide`
  there would refactor the riskiest block in the app — ~550 lines carrying reuse-last-link,
  auto-play evaluation, debrid resolution and P2P consent — for zero behaviour change. The
  router and its tests are in place and unchanged, waiting for it.

Both suites green after the UI landed: Android **576**, desktop **782**, zero failures, errors
or skips. `App.kt` and `MetaDetailsScreen.kt` were hand-ported, not copied — both already
differ between the repositories.

**Still not smoke-tested on a device or a real desktop install.** No Android device was
attached (`adb devices` empty), so nothing has run against real storage. When one is available:
launch and confirm the selector appears once and only once; pick Classic and confirm nothing
about playback changes; change the mode in Settings, force-stop, relaunch, confirm it held;
switch profiles and confirm the mode is per-profile. The selector shows for existing installs
too, so **that first-launch behaviour is the thing most worth watching** on a device that
already has data.

### Phase 2 complete — picker and Streamlined (2026-08-06)

- `entry<StreamRoute>` now delegates precedence to `PlaybackModeRouter`: explicit manual play,
  completed local download (consumed before the route), matching season pin, valid cached link,
  then playback mode. Non-Classic modes cannot run the legacy `streamAutoPlayMode` picker.
- Plugin scraper metadata now survives ingestion as `PluginStreamMeta`; quality, byte size,
  seeders/peers, provider, and language no longer depend on parsing the display subtitle.
  `SourceFacts` adds plugin-structured provenance, seeders, and release-group extraction.
- Download and playback selection share `SourceRanking` while retaining separate protocol gates.
  Streamlined allows HTTP(S), HLS/DASH, safe debrid candidates, and opt-in torrents only with a
  known healthy seeder count; download protocol policy is unchanged.
- Streamlined shows configured quality tiers plus Best available, handles uncached debrid as an
  explicit choice, and can pin a manually chosen release for the rest of a season through the
  widened `BingeGroupCacheRepository`. The pin outranks cached-link reuse and falls through when
  no candidate matches.
- Quality tiers and the torrent auto-pick toggle are profile-scoped and included in settings sync;
  Android, iOS, and desktop actuals are present. The old Stream auto-play section is disabled with
  an explanation outside Classic, and only Instant retains the not-ready caption.
- Verification: forced full Android host run **585 tests across 85 classes**, and forced full
  desktop run **791 tests across 115 classes**; both had zero failures, errors, or skips. The
  desktop run compiled `desktopMain` and ran the complete download harness. Nine new cases cover
  plugin metadata, release groups, shared ranking, selector gates/caps/fallbacks, and sticky
  release-group precedence. Focused suites also passed on both targets before the full runs.
- **Not verified:** no Android device was attached and no installed Windows build was launched,
  so the quality sheet, manual sticky prompt, persistence across app restart/profile switching,
  plugin-heavy/debrid pick quality, and HLS/DASH playback remain runtime smoke-test work.

### Phase 3 implementation complete — Instant and network quality (2026-08-06)

- Added `NetworkQualityPlatform` using Android `ConnectivityManager`/`NetworkCapabilities`, iOS
  `NWPathMonitor`, and an unmetered Ethernet desktop actual. The per-network estimator caches
  passive throughput separately for each debrid/provider and resolves configured quality tiers.
- Real download progress now feeds bounded throughput samples into the estimator. Unknown
  networks remain conservative at 720p until a real measurement is available.
- `PlaybackRouteDecision.AutoPick` selects the estimated tier, re-checks provider-specific
  throughput, and seeds the existing `StreamsRepository` auto-play candidates in ranked order.
- Instant retries at most three sources. A player error or failure to start within eight seconds
  advances the existing chain; exhaustion returns to the Classic source list with a reason.
- Metered connections ask once per network per app session. The capped choice uses the
  profile-scoped, synced `playback_metered_cap_height` (720p default); full quality applies only
  to that session. Instant's not-ready caption has been removed.
- Added five common estimator tests. Desktop main and test source sets compile successfully.
- Verification: Android host **590 tests across 86 classes** and desktop **796 tests across 116
  classes**, both zero failures, errors, or skips. Desktop main compiled.
- **Not verified:** iOS cannot compile on this Windows host, and no device or installed Windows
  smoke test has been performed. Metered-session behavior and eight-second runtime failover
  therefore still need real-device/installed-app coverage.

Findings from the exploration that shaped it, worth recording independently of the plan:

- **Plugin sources are structurally invisible to the auto picker.**
  `AutomaticDownloadDiscovery` builds `DownloadSourceCandidate` from installed addons only, so
  nothing a JS scraper returns is ever a candidate.
- **Plugin metadata is destroyed on ingest.** `PluginRuntimeResult` carries `quality`, `size`,
  `seeders`, `peers`, `provider`, `language`; `StreamFetchSupport.kt:85`
  `PluginRuntimeResult.toStreamItem()` joins some into a `" • "` display string and **drops
  `seeders` and `peers` entirely**. `SourceFactsExtractor` then regexes that prose back apart.
  For a plugin-heavy profile the picker is guessing.
- **No seeder signal exists anywhere** in `StreamItem` or `SourceFacts` — the strongest
  predictor of whether a torrent source will actually start playing.
- **Two selection mechanisms already run inside `entry<StreamRoute>`.** Verified ordering:
  `manualSelection` gates the local-download check (`App.kt:1584`); the reuse-last-link effect
  (`App.kt:2525`) is gated on `!launch.manualSelection` and fires **before** auto-play
  evaluation. A third picker added without a precedence rule would break Streamlined outright
  (reuse-last-link would pre-empt the quality sheet). The plan's precedence table is normative
  and `streamAutoPlayMode` becomes Classic-only.
- **Reuse, do not rebuild:** `StreamsRepository.skipAutoPlayStream` (`StreamsRepository.kt:767`)
  is already the "candidate failed, advance to the next" mechanism the Instant failure chain
  needs; `BingeGroupCacheRepository` is already per-content release memory and should be widened
  to carry a `StickySourcePin` rather than gaining a parallel store.
- **Mid-playback source switching already exists and preserves position.**
  `PlayerScreenRuntimeSourceActions.kt:229` `switchToSource` captures `playbackSnapshot.positionMs`
  and restores it via `activeInitialPositionMs`. Automatic downshift (Phase 4) is a trigger on
  top of shipped, hand-testable plumbing — not adaptive bitrate, and not phase 1.
- **Nothing detects network type, metered status, or throughput.** `NetworkStatusRepository` is
  a reachability prober only. Instant needs a new `expect`/`actual` across Android, iOS and
  desktop.
- **There is no onboarding anywhere in the app**, so the mode selector is new construction on
  the `AppGateScreen` state machine. It needs `playback_mode_selector_seen` persisted separately
  from `playback_mode`, or "chose Classic" is indistinguishable from "never chose".

### Phase 4 complete — auto source-swap, opt-in and default off (2026-08-07)

**The precondition found a real bug, which is the main result of this phase.** Phase 4 was
gated on verifying that `bufferedPositionMs` is meaningful on libmpv, not just ExoPlayer. It
is not, on one platform:

- Android mpv does `maxOf(positionMs, cachePositionMs)` (`PlayerEngine.android.kt:1249`) and
  the desktop C++ does `cacheTime - effectivePosition` (`player_bridge.cpp:1896`). Both treat
  mpv's `demuxer-cache-time` as what it is: an **absolute** stream timestamp for the end of
  the cache.
- iOS did `position + cached` (`MPVPlayerBridge.swift:883`), treating that same absolute
  timestamp as a *duration ahead of the position*. So iOS reported a buffer that grew with
  playback position and never looked starved.

Two of three implementations of one libmpv property disagreed with the third, which settles
it without a device. **This was already a live bug**, not only a Phase 4 blocker:
`PlayerScreenRuntimeUi.kt` derives its user-visible buffer readout from
`bufferedPositionMs - positionMs`. Fixed to match Android exactly. **The Swift change cannot
be compiled on this Windows host and is unverified** — it is three lines and mirrors a
verified implementation, but it has not been run.

What landed on top of that:

- `features/playback/AutoDownshiftDetector.kt` — the trigger, pure and clock-free, plus
  `AutoDownshiftCandidates` for the swap constraints. **The run is measured in wall-clock
  time, not snapshot counts.** Android polls the player every ~250 ms and desktop every
  500 ms, so the plan's "≥3 consecutive snapshots" would have meant 0.75 s on one platform
  and 1.5 s on the other — neither is "sustained", and they would not have agreed. A
  duration threshold (4 s buffer-ahead, held 6 s continuously, minimum 3 samples) makes both
  platforms behave identically with no per-platform tuning.
- Arming conditions, each of which can otherwise burn the one-swap budget on a false
  positive: a 15 s settle grace (desktop's `effectiveCachePositionSeconds()` clamps the cache
  position to the resume point after a seek, so buffer-ahead is untrustworthy early); a run
  reset on pause, seek, or source change; and a stall (`paused-for-cache`) counted as
  starvation whatever the reported buffer says.
- Swap constraints: same release group only, never upward, manifests exempt (HLS/DASH adapt
  internally), never onto an uncached debrid candidate, and null — no swap — whenever the
  release group or resolution is unknown.
- `playback_auto_downshift` through `PlayerSettingsStorage` with **all three actuals**, in
  `syncKeys` and both sync payload paths, surfaced as an Instant-only settings row.

**"One swap per session" is read as one per playback session, reset on a new episode**, not
one per source: a position-preserving switch keeps the budget spent. The budget is charged by
`consumeSwap` at the call site, never by the detector — whether a swap is even possible
depends on the candidate list, and charging for one that never happened would silently
disable the feature for the rest of the episode.

**Identifying the playing source cannot be done by URL.** `switchToSource` re-enters with the
debrid-*resolved* stream, so `activeSourceUrl` holds a minted URL no candidate in the source
list carries, and a P2P source holds a sentinel URL that matches nothing. Since Instant's
users are mostly on debrid, URL matching would have made this a silent no-op on its main
path. `matchesActiveSource` tries info-hash, then identity key, then URL, then
addon + label — the last arm being the one that survives resolution, which rewrites `url`,
`filename` and `videoSize` but leaves `addonId`, `streamLabel` and `streamSubtitle` alone.

Verified: Android host **607 tests across 87 classes** and desktop **813 tests across 117
classes**, both zero failures, errors or skips — the documented 590/796 baselines plus the 17
new `AutoDownshiftDetectorTest` cases, which run on both targets. The desktop run compiled
`desktopMain`, so the new desktop `actual` is verified rather than assumed.

**Not covered:** the iOS Swift fix (no macOS host), and any on-device or installed-app
behaviour — no Android device was attached and the Windows app was not installed at any
point. The setting is off by default, so nothing here changes playback until a user opts in.

### Phase 5 complete — the download entry point follows the mode (2026-08-07)

**This phase existed because a decision had no phase.** "Modes change the download *entry
point*, not the download engine" was recorded in the plan's **Decisions taken** section and
never assigned to a numbered phase, so finishing Phases 1–4 left it unbuilt. `playbackMode`
did not reach `features/downloads/` at all.

- Classic downloading a **single** item opens the source list and the chosen release is
  downloaded. A season still gets the preset dialog — hand-picking twenty releases is a
  chore, not control.
- Streamlined keeps today's preset dialog, unchanged.
- Instant starts with no dialog, using the preset that matches the connection tier, capped
  by the same `allowMeteredNetwork = false` default the dialog itself uses.

**Routing Classic to the source list was not sufficient, and this is the part worth
remembering.** That screen plays on tap and offers download only from the long-press sheet,
so the Download button silently behaved as Play. `StreamLaunch.downloadIntent` now carries
the intent, `StreamsScreen.downloadOnSelect` makes a tap enqueue instead of playing, and the
same flag forces `streamManualSelection` so no automatic playback path can fire under a
Download press. `onDownloadManually` deliberately does **not** go through
`launchPlaybackWithDownloadPreference`: that short-circuits to playing a completed local
download, which is right for a play and wrong for a download request.

Every branch degrades rather than dead-tapping: no manual route (no handler, or no single
resolvable video) and no configured presets both fall back to the preset dialog.
`DownloadsRepository`, the queue, the transfer stack and `PresetSourceSelector` are
untouched, per the plan's non-goal of destabilising the download stack.

Verified: Android host **615 tests across 88 classes** and desktop **821 tests across 118
classes**, both zero failures, errors or skips, with `desktopMain` compiled. Not smoke-tested
on a device or an installed app.

### Uncached debrid auto-played, fixed in 0.4.2-beta (2026-08-07)

Instant started an ElfHosted placeholder — the two-minute `MEDIA_NOT_CACHED_YET` slate —
on a source whose display name plainly carried the not-cached hourglass. This is the exact
outcome `PLAYBACK_MODES_PLAN.md` said Instant must never produce, and it survived because
two gaps lined up:

- `SourceFactsExtractor` learned cache state only from the structured `debridCached` and
  `clientResolve.isCached` fields. Many debrid addons advertise it **only in the display
  name**, so `isDebridReady` was *null* — unknown — rather than false.
- `PlaybackSourceSelector.isUncachedDebrid` excluded only an explicit `false`, so unknown
  passed straight through to auto-play.

**The rule is now: unknown is not cached.** Auto-pick requires positive evidence of a cached
copy, and uncached candidates are kept out of the `fallbacks` list too — otherwise the
failure chain lands on a placeholder one retry later instead of never.

⚠ **The fail-safe is scoped to debrid-backed candidates only** (`debridService` set,
`clientResolve` present, or a direct debrid stream). Plugin scrapers and plain direct HTTP
links legitimately have no cache state at all; gating on null globally would empty the
candidate set and leave Instant unable to play anything. `aNonDebridSourceWithNoCacheStateStillPlays`
is the regression guard for that over-application — do not remove it.

Display-name parsing (`parseDebridCacheMarker`) is a second layer, not the fix. Negatives are
checked before positives so "not cached" cannot read as "cached", and `instant` is excluded
from the positive set because *Instant Family* exists.

Verified: Android **624 tests across 89 classes**, desktop **830 across 119**, zero failures.

### 0.4.0-beta regressions, fixed in 0.4.1-beta (2026-08-07)

Both found within minutes of installing `0.4.0-beta` on a real phone, and neither was
reachable by any unit test. This is the concrete cost of the "not smoke-tested" caveat that
had been carried since Phase 1.

**1. Every sync pull deleted the new playback settings.**
`PlayerSettingsStorage.replaceFromSyncPayload` cleared *all* of `syncKeys` before applying
the payload. The remote blob is authoritative for settings it knows about — but it had never
heard of any `playback_*` key, because none existed when it was last written. So a signed-in
user lost `playback_mode`, the quality tiers, the metered cap, the torrent toggle and
`playback_mode_selector_seen` on every pull. The visible symptom was the first-launch
selector reappearing straight after pressing Continue; silently, the chosen mode was being
reset to Classic at the same time.

Fixed by clearing only the keys the payload actually carries, through a shared
`syncKeysToClear` in `commonMain` so the rule cannot drift between the three actuals.
`SyncKeysToClearTest` reproduces the old-blob shape directly.

⚠ **The same wipe-then-apply pattern is still present in `MdbListSettingsStorage`,
`StreamBadgeSettingsStorage`, `TmdbSettingsStorage` and `TraktCommentsStorage`.** None of
them gained a key in this release, so none is currently losing data — but the next key added
to any of them will hit exactly this. Fix them before adding one.

**2. The selector captioned Instant "Not ready yet".**
`PlaybackModeSelectorScreen` had its own hardcoded `mode == PlaybackMode.INSTANT` check,
separate from `isImplemented()` in `PlaybackSettingsPage`. The plan said that function was
"the single place to update" and that was simply wrong — there were two. Both are gone now,
along with the dead gate and the unused string.

Verified: Android **619 tests across 89 classes**, desktop **825 across 119**, both zero
failures, `desktopMain` compiled.

## Polish pass on 0.4.2-beta (2026-08-07, unreleased)

Surface-tested `0.4.2-beta` on a phone: the playback-mode logic works, the presentation had not
caught up with it. Five workstreams, landing in order, each verified on both targets.

### 1 — The sync wipe pattern, everywhere it existed

`STATUS.md` named four stores still carrying `replaceFromSyncPayload`'s clear-everything-first
bug. **There were six.** `DebridSettingsStorage` and `ThemeSettingsStorage` had the same shape and
were not on the list; Debrid is the worst of them, because its key list is built at runtime from
`DebridProviders.all()`, so adding a provider would have deleted stored API keys for the others on
the next pull.

`syncKeysToClear` **moved from `features/player/PlayerSettingsStorage.kt` to
`core/sync/SyncPreferenceJson.kt`** (`com.nuvio.app.core.sync`), next to the `decodeSync*` helpers
every one of these stores already imports. It was `internal` in a feature package that five
unrelated features would have had to import from.

All six stores now clear only the keys the payload carries, across **19 actuals** (android, ios,
and the six desktop ones in `NuvioZDesktop`). `TraktCommentsStorage.desktop.kt` needed a `syncKeys`
list of its own — it had been removing its single key unconditionally, so a payload that omitted
`comments_enabled` silently switched comments back off.

`SyncKeysToClearTest` moved to `commonTest/.../core/sync/` and gained six cases, one per store,
each reproducing that store's old-blob shape.

### 2 — Instant and Streamlined no longer show the source list

The complaint that started this pass. `entry<StreamRoute>` rendered `StreamsScreen` unconditionally
as the base of its `Box` and drew the quality sheet on top, so Instant users watched a wall of
releases populate and then get replaced.

**The overlay covers `StreamsScreen`; it does not replace it.** `StreamsScreen.kt:203` owns
`LaunchedEffect { StreamsRepository.load(...) }` — composing it away would cancel the very fetch
the overlay reports on. This is the constraint that shaped the whole edit.

`features/playback/PlaybackProgressOverlay.kt` is new, and its decision half is pure:
`PlaybackProgress.step(...)` and `PlaybackProgress.isVisible(...)` are testable functions, with the
composable a thin renderer over them.

**Every step maps to state that already existed** — no timed or faked sequence:
`FindingSources` from `isAnyLoading`/`requestToken`, `ChoosingSource` from `instantSelectionHandled`,
`ResolvingLink` from the existing `resolvingDebridStream` flag, `StartingPlayback` otherwise.
Resolving is checked **first**, because a slow addon can leave `isAnyLoading` true long after the
pick while the debrid mint is the thing actually being waited on.

Two new `rememberSaveable(route.launchId)` flags: `streamlinedPlaybackStarting` (set when a tier is
picked, so Streamlined is covered from there to playback) and `autoPickAttempt` (advanced only by
the failure chain, so a silent retry reads as "Attempt 2 of 3" rather than a hang).

⚠ **The overlay uncovers the list for every path that needs the user**: `manualSourceListRequested`
(all four bail-outs already set it), the metered sheet, the uncached sheet, the sticky-pin prompt
and P2P consent. `everyBailOutToTheSourceListUncoversIt` is the regression guard — a spinner over a
screen the user has to read or answer is worse than never covering it.

⚠ **Scope boundary:** the overlay ends at navigation to `PlayerRoute`. The 8-second startup budget
and the `onFatalPlaybackError` / `onPlaybackStarted` retry callbacks run on the **player** screen
and are a separate surface. Classic and every manual path keep the old lighter scrim during debrid
resolution, because there the source list behind it is what the user chose from.

Verified: Android **639 tests across 90 classes**, desktop **845 across 120**, both zero failures,
errors or skips; `desktopMain` compiled. `App.kt` was hand-ported, not copied. **Not smoke-tested
on a device** — the step labels and the attempt counter are exactly what a device run is for.

### 3 — The modes explain themselves

`PlaybackModeCard` is one composable, used by both the first-launch selector and
`PlaybackModeDialog` in settings. Each mode is a card with a tagline and two labelled blocks,
**Streaming** and **Downloading**.

⚠ **Those two files describing the modes separately is how Instant kept a stale "Not ready yet"
caption in `0.4.0-beta`** after the other copy had been fixed (see the `0.4.1-beta` section).
`playbackModeTitle`/`playbackModeDescription` in `PlaybackSettingsPage` are gone; the shared
`playbackModeName` replaced them.

`PlaybackModeDownloadCopyTest` pins the download lines to `PlaybackModeDownloadRouter.decide`.
Classic is the only mode whose entry point depends on whether the scope is a single item, and its
card is the only one that says so — copy contradicting the router is worse than no copy.

### 4 — A global "Show advanced settings" toggle

One switch in Settings → Advanced. Rows tagged `isAdvanced = true` render nothing when it is off,
via `LocalShowAdvancedSettings` and a parameter on `SettingsNavigationRow` / `SettingsSwitchRow`.
Per-row annotation rather than restructuring pages: `PlaybackSettingsPage` alone is ~3700 lines,
and a defaulted parameter is something a future row gets right for free.

**The default when unset is the part most likely to read as data loss, so it does not guess how
old an install is.** `hasTunedAnAdvancedSetting` (`features/player/AdvancedSettingsDefault.kt`)
asks the question that actually matters — has this profile ever *stored* a value for an advanced
setting? — and a profile that has keeps them visible. An explicit stored `false` counts as
touched: turning something off is as deliberate as turning it on.

⚠ **Settings search deliberately ignores the flag.** `SettingsSearch` keeps indexing hidden rows
and reveals them on the page it lands on; ordinary navigation back to Root clears the reveal.
Hiding a setting the user just searched for by name would be worse than showing it.

`settings_show_advanced` is profile-scoped and went through `syncKeys` and both payload paths in
all three actuals — which is why item 1 landed first.

Currently tagged: the Advanced page row, torrent auto-pick, auto-downshift, reuse-last-link and
its cache duration, decoder priority, DV7→HEVC and tunneled playback. Deliberately small; nothing
a normal user changes is tagged.

### 5 — What's New, rebuilt with version history

**It did not work because it was never merged.** `codex/whats-new` was one local commit in both
repos; no shipped build contained it. Cherry-picked and then finished, because it had three gaps:

- **No `desktopMain` actual** for `internal expect object WhatsNewStorage` — the trap `AGENTS.md`
  flags twice. Added, plus the missing `WhatsNewStorage.initialize` in the desktop repo's
  `MainActivity`.
- **Single version, no history.** `AppUpdaterRepository` already fetched `releases?per_page=20`
  and discarded everything but the newest. `fetchRecentReleaseNotes` reads that same response, so
  the history costs no new kind of request.
- **Markdown rendered raw.** `ReleaseNotesDialog` pushed `update.notes` through a plain `Text`, so
  every heading showed as `## Fixes` and every bullet kept its literal `- `. `parseReleaseNotes`
  handles headings, bullets and paragraphs and strips inline markers; unrecognised syntax falls
  through as a paragraph, which is the safe direction — showing a line we did not understand beats
  dropping it. Both the What's New history and the update banner now use it.

The current version's notes stay **curated and offline** (`CurrentReleaseNotes`), because the
screen has to work on the first launch after an update and on builds where the updater is off. It
is **not** gated on `AppFeaturePolicy.inAppUpdaterEnabled` for the same reason; only the fetched
history degrades, to "needs a connection".

Also reachable on demand from Settings → About, dismissible there, and that path deliberately does
**not** record the version as seen — otherwise opening it early would skip the post-update showing.

⚠ **This needs a curated entry per release, committed before the version bump.** The bump-last
rule is enforced and a docs commit after the bump fails the release.

### Two faults found reviewing the pass, both fixed here

**1. The overlay never learned that playback had started — a regression this pass introduced.**
`isVisible` gated on `reuseNavigated`, which is set **only** in the reuse-last-link branch.
Nothing set it when the auto-play effect, `openSelectedStream` or `openExternalPlayback` reached
the player. Instant deliberately does **not** `popUpTo<StreamRoute>` (that is what keeps the
failure chain alive), so `StreamRoute` stays on the back stack with `instantSelectionHandled`
true — and backing out of the player landed on an opaque full-bleed overlay reading "Starting
playback" with nothing to interact with. `rememberSaveable` meant it survived process death too.
Before this pass that screen showed the source list: odd, but usable.

Fixed with `playbackHandedOff`, set at **every** exit to playback (six sites), and
`playbackHavingStartedHidesTheOverlay` is the regression guard. That test replaced
`theAttemptBudgetMatchesTheFailureChain`, which asserted `MAX_ATTEMPTS == 3` — a constant pinned
to itself, claiming more than it checked.

**2. Desktop What's New compared the wrong version.** The hand-port used
`AppVersionConfig.VERSION_NAME` in five places. On the desktop target that is the **base/mobile**
version; `AppVersionPolicy.displayVersionName` is `DESKTOP_VERSION_NAME`. They are equal today
(one shared version line since `0.4.0-beta`), so nothing misbehaves yet — but if they ever
diverge, `shouldShowWhatsNew` would compare against a string that does not change when the
desktop version bumps, and What's New would show once and never again. Swapped, matching what
the desktop `SettingsRootPage` already did.

Also: the displayed attempt is now `coerceAtMost(MAX_ATTEMPTS)`, because the seeded candidate
list is not itself capped, so "Attempt 5 of 3" was reachable.

**Known gap:** "Show advanced settings" is in the settings search index; the **What's New About
row is not**, because it opens a dialog rather than a page and would need a new
`SettingsSearchTarget` variant handled in all four `openSearchTarget` implementations.

### Verification for the whole pass

Android **653 tests across 94 classes**, desktop **859 across 124**, both zero failures, errors or
skips; `desktopMain` compiled. `App.kt`, `PlaybackSettingsPage.kt`, `SettingsScreen.kt`,
`SettingsRootPage.kt`, `SettingsComponents.kt`, `AppUpdater.kt`, `AppUpdaterBanner.kt` and
`strings.xml` were **hand-ported** — all of them already differed between the repositories, and
the desktop `SettingsRootPage` needed `AppVersionPolicy.displayVersionName` where mobile uses
`AppVersionConfig.VERSION_NAME`.

**Nothing here is smoke-tested on a device or an installed desktop app.** The parts a device run
has to cover, because no unit test reaches them:

1. Instant on Wi-Fi: progress overlay with changing step labels, never the source list.
2. Instant with the chosen source killed mid-flight: "Attempt 2 of 3", still no source list.
3. Instant on mobile data: the metered sheet appears *instead of* the overlay, once.
4. Streamlined: quality sheet → tier → overlay → player; "Choose source manually" still works.
5. Sign in on a second device and pull: playback mode, MDBList, TMDB, badge, Trakt-comment and
   **debrid API keys** all survive.
6. Advanced off/on, and settings search still finding and revealing a hidden row.
7. Install over an older build: What's New shows once, lists previous versions, does not reappear,
   and still opens from Settings → About with no network.

## Current Snapshot

- Base: NuvioMobile commit `979d5680`.
- Working branches: released `main` (`nuvio-z`) and `Dev` (`NuvioZDesktop`).
- Official repository is configured as `upstream`.
- Public `origin` repository: `https://github.com/Zokaper/nuvio-z`
  (public so the unauthenticated in-app updater can read its releases).
- Android identity: Nuvio Z, `com.nuvio.app.z`
  (`com.nuvio.app.z.debug` for debug).
- Signed personal release builds use an ignored local keystore.
- Latest signed arm64 APK was installed successfully on a Samsung Android
  device and launches alongside official Nuvio.

## Completed

- Added Saver, Balanced, and Quality download presets with editable resolution,
  GB/hour cap, codec, HDR/Dolby Vision, and audio-language preferences.
- Added movie, episode, season, and selected-season batch planning.
- Added an unwatched-only season scope so a season in progress downloads from the
  current episode onwards instead of the whole season.
- Added generic Stremio source normalization and bounded AIOStreams structured
  metadata support.
- Added global addon allowlisting and nested AIO provider restrictions.
- Added automatic source ranking, direct/debrid resolution, size verification,
  unknown-size review, and manual-source fallback.
- Added persistent batch/download models, resumable Android transfers,
  concurrency limits, network constraints, notifications, and queue actions.
- Added Nuvio Z application identity and launcher assets while retaining the
  upstream Kotlin namespace and callback schemes.
- Configured the official Nuvio backend through ignored local build properties;
  fixed the earlier `https://localhost` authentication fallback.
- Fixed preset editing crashes by enabling structured JSON map keys in download
  persistence.
- Fixed false “Conflicting source metadata” results by separating authoritative
  byte reports from rounded filename/display estimates and tolerating equivalent
  hard reports while retaining the largest cap-enforcement size.
- Promoted downloads from a settings page to a first-class part of the app:
  a dedicated Downloads tab, artwork-driven queue and on-device lists, and live
  download state on movie and series entries.
- Reworked download transfers so a finished byte loop is only treated as a
  completed download when the bytes on disk match the authoritative total, and a
  total is never inferred from a transfer that stopped early.
- Added `If-Range` validators, correct 416 handling, and honest short/overrun
  outcomes to the Android, desktop, and iOS downloaders.
- Made pause a first-class outcome rather than a swallowed cancellation, split
  user pauses from system pauses, and added automatic resume on app foreground,
  reload, and connectivity recovery (including the missing iOS foreground hook).
- Added an explicit `Queued` state with persisted queue ranks, append-on-enqueue
  ordering, menu-based reordering with preemption, and retry with backoff.
- Coalesced progress persistence and notification updates instead of rewriting the
  whole payload on every chunk, and serialised repository mutations behind a lock.
- Made background source discovery visible: a Preparing section in the Downloads
  tab with per-episode state, and an ongoing Android notification while any batch
  is preparing.

## Download freezing (2026-08-05, unreleased)

Four separate faults, found while chasing downloads that stopped around 80% and
one that refused a source for exceeding the preset cap after it had already been
approved. Reported on the Windows desktop build through TorBox.

- **Desktop transfers could block forever.** `HttpRequest.timeout` bounds the
  arrival of the *response*, and with `BodyHandlers.ofInputStream` the response
  arrives with the headers - every byte after that was read from a stream with no
  deadline. A connection that went quiet without closing parked the read
  permanently, `job.cancel()` could not interrupt it, so pause and cancel did
  nothing and the item held one of the two transfer slots for good. Two of them
  stopped the queue outright. Added a stall watchdog that closes the stream, and
  a handle that closes it on cancel.
- **Nothing recovered a transfer the queue lost.** An item recorded as
  `Downloading` with no handle behind it was invisible to the planner (which
  starts queued items) and to the system-pause recovery (which looks at paused
  ones). `DownloadQueuePlanner.lostTransfers` now names that state and
  `startPendingTransfers` takes those items back, together with any transfer that
  has gone silent for far longer than the platform watchdog allows. A platform
  that refuses to start no longer strands the item either - Android's
  `JobScheduler` declines a user-initiated job outright when the app may not
  start one, and that used to throw straight out of `start()`.
- **The size cap stopped transfers it should not have.** It fired mid-transfer on
  the larger of the bytes received and the size reported, cancelled the handle and
  paused for approval - including for sources the user had already approved in the
  batch review dialog, because `queueBatch` never carried that decision onto the
  download. On resume it fired again at the resumed offset, so an item could wedge
  at the same percentage repeatedly, and `resumeDownload` refused those items so
  the resume button did nothing. The cap now decides which source to pick and
  nothing more; an oversize file is noted on the row and finishes. Items already
  stuck this way are healed on load.
- **Debrid links were minted once and never refreshed.** Preparation resolved
  every episode of a batch up front while only two transfer at a time, so links
  were routinely first used hours after they were minted - against a resolver that
  already treats a cached link as good for fifteen minutes. An expired TorBox
  `requestdl` URL answers 401/403/404/410, which classified as `Fatal`, so the
  download failed with a retry button that replayed the same dead URL forever.
  Those statuses are now `SourceExpired`, downloads carry a `DownloadSourceOrigin`
  (the stream before resolution), and a stale or rejected link is re-minted before
  the transfer starts and on retry.

Also: `StreamItem` and its nested models are now `@Serializable` so the origin can
be persisted faithfully, and an episode with no runtime of its own falls back to
the series runtime before the 45-minute default that was under-capping hour-long
episodes.

### The 4K preset split (same release)

The `Quality` preset asked for 2160p while capping at **4 GB/hour** - a 4 GB
ceiling for an hour-long episode, under every real 4K source. `PresetSourceSelector`
rejected all of them and reported that they exceeded the cap, which is the same
complaint the freezing work started from arriving by a different route. One cap
cannot serve both a 2160p web encode and a remux, so it is now two tiers:

| id | name | cap | ~54-minute episode |
| --- | --- | --- | --- |
| `quality_4k_low` | 4K Low | 8 GB/h | ~7.2 GB |
| `quality_4k_high` | 4K High | 15 GB/h | ~13.5 GB |

Presets are **persisted**, so a new built-in would have reached only fresh
installs - an existing device would have updated and seen no 4K tiers at all.
`mergeStoredPresets` appends built-ins the stored list has never seen and drops a
retired one that still matches its old default exactly; an edited copy is a
decision the user made and survives. Anything added to `BuiltIns` in future needs
nothing further, but anything *removed* needs an entry in `RetiredBuiltIns` or it
will linger on existing installs forever.

## Downloads that stopped moving, and a harness that finds them (2026-08-05, unreleased)

Reported as: a download reports a bare "closed", recovers on its own, but by the time
it does, the next item in the queue has started and *that* one sits unfinished.

**One fault, and it is a race, not a recovery gap.** A transfer does not stop when it
is cancelled; the read it is parked in has to end first, and the last thing it reports
arrives afterwards, from its own thread. Callbacks were keyed by download id alone, so
that last word was applied to whichever attempt was running by then - and the queue
routinely cancels and restarts an item in the *same locked section*, which is exactly
the window it lands in (`reclaimLostTransfersLocked`, and the preemption path behind
"move to top"). The stale report:

- took the live transfer's handle out of `activeHandles`, leaving a transfer nothing
  could pause or cancel and a slot the queue believed was free, and
- stamped the item `Paused/System` at the byte count the *previous* attempt reached.

From the outside that is a row frozen partway through while the queue moves on. The
download is not dead at that instant - its transfer is still running, invisibly, and
`onTransferProgress` drops every update because the item no longer reads as
downloading - so it becomes permanent only when that transfer also stops: a failure
reported against an item not marked downloading is recorded and never retried.
Nothing on desktop resumes a system pause either, so it stayed there until a restart.

Fixed by giving every attempt an `ActiveTransfer` carrying a **generation**. The
listener holds it and each of the five callbacks checks it, so a replaced attempt can
no longer speak for its download. The same object holds the slot while a source URL is
re-minted, which retires `PendingResolveTaskHandle` - a shared singleton that could
not tell two concurrent resolutions apart either.

Belt and braces, since a state nothing can leave is worth closing off for good:
`DownloadsPlatformDownloader.recoversSystemPauses` says whether the platform that
system-pauses transfers also brings them back. Android's background job and iOS's
foreground hook do; desktop has neither half, so there `lostTransfers` now takes a
system-paused item with no transfer behind it back into the queue. **The generation
fence alone fixes the reported fault** - verified by running the harness with only
that half in place - so this is a safety net, not the fix.

### The harness

`composeApp/src/desktopTest/.../DesktopDownloadQueueE2ETest.kt`, with
`FaultyMediaServer.kt` beside it. It drives the real `DownloadsRepository` through the
real desktop downloader onto real disk; only the media host stands in, because the
faults that matter are things a *server* does. On a raw socket rather than
`com.sun.net.httpserver`, since a well-behaved HTTP server will not produce them:

| Fault | What it reproduces |
| --- | --- |
| `DropConnection` | the bare "closed" - a body that simply stops |
| `GoSilent` | headers, some bytes, then an open connection with nothing on it |
| `Reject(403)` | a signed link that expired before its turn came |
| `Placeholder` | the "your file is being prepared" video, complete and valid |

`DownloadsTiming` exists so the harness can turn the 60s stall deadline and the 5min
queue watchdog down to seconds; the shipped defaults are never changed outside one.
`DownloadsRepository.resolvePlayableStream` is a variable for the same reason - it is
the only way to reach re-minting without a real debrid account and a link left to
expire.

Run it with `./gradlew :composeApp:desktopTest`; CI already does, on every push. The
Gradle task points `user.home`, `APPDATA` and `XDG_CONFIG_HOME` at the build directory
so a test run cannot touch a developer's own Nuvio Z install, and the test asserts it
landed somewhere disposable before writing anything.

**Against real sources:** set `NUVIO_DOWNLOAD_TEST_URLS` to a comma-separated list of
direct media URLs and `real sources download end to end` runs the same queue against
them at the shipped deadlines; it skips when the variable is unset. That proves real
transfer and concurrency behavior only: raw signed links have no provider/hash origin
and cannot be re-minted.

The provider-backed TorBox case uses `NUVIO_TORBOX_TEST_SOURCES` to name a local JSON
fixture containing the original info hashes/file selectors and reads the API key from
`NUVIO_TORBOX_API_KEY`. It pre-resolves the entire season as production preparation
does, can wait more than fifteen minutes, then enqueues every resolved link with its
durable origin. Every transfer must perform a fresh real provider check and the final
files must match the queue's exact totals. `scripts/run-torbox-download-e2e.ps1`
prompts for the key without putting it in shell history, uses a single-use Gradle
daemon, and clears the environment afterward. The fixture and key are not logged or
committed.

## Preset UI and the mid-range size preference (2026-08-05, released in 0.3.10 / 0.1.23-alpha)

Both preset surfaces were plain Material defaults that ignored the app's own
components, and the toast raised when a batch starts pointed nowhere.

- **A third `SizePreference`, `MID_RANGE`.** The choice used to be only
  `LARGEST_UNDER_CAP` or `SMALLEST`. `MID_RANGE` targets the **median size of the
  candidates that actually fit the cap** - a real candidate size rather than a share
  of the cap, so it stays meaningful when every source for a title sits far below
  the limit, and sizes above the cap are excluded so an unusable 20 GB remux cannot
  drag the target upwards. The upper middle of an even-sized list keeps it
  deterministic; an unknown size is treated as `Long.MAX_VALUE` away and still sorts
  last; with nothing to aim at, ordering falls back to largest-under-cap. The target
  is computed once over every matching candidate while the comparator still only
  decides *within* a tie group, so resolution, language, dynamic range, codec and
  release quality continue to outrank size. Built-in presets keep their existing
  preference: `mergeStoredPresets` never rewrites a stored preset, so changing one
  would split behaviour between existing and fresh installs.
- **The preset picker** (`PresetDownloadDialog.kt`) is rebuilt on `BasicAlertDialog`
  and the Nuvio tokens: a subtitle naming what will be downloaded, season chips with
  All/None instead of a checkbox list (and localised season names - it used to
  hardcode English), and one selectable card per preset carrying a plain-language
  summary. A preset is now **selected and then started** by a button; tapping one
  used to queue a whole season on the spot. The default selection is the preset of
  the newest batch.
- **The preset editor** (`DownloadsSettingsScreen.kt`) drops the `−`/`+` steppers and
  the rows that silently cycled an enum on tap. Resolution, codec, HDR policy and
  file size are `NuvioDropdownChip` pickers, the cap is a slider showing what it
  works out to for an episode and a film, and the switches carry descriptions. Raw
  enum names (`AVOID_HDR`) are gone: `PresetLabels.kt` holds one set of labels used
  by both surfaces. `DownloadsRepository.resetPresets()`, which had no UI at all, is
  wired to a confirmed "Reset presets" action.
- **The toast can be tapped through to the Downloads tab.** `NuvioToastMessage`
  carries an optional label and a typed `NuvioToastAction`; `App.kt` resolves
  `OpenDownloads` by selecting the tab and, under Compose navigation, unwinding the
  stack back to `TabsRoute` so the tab is actually visible from the details screen a
  download is started from. A typed action rather than a lambda keeps navigation out
  of `core/ui`, which is what let the dialog raise it at all. The download toast now
  lasts 5s rather than 2.5s so the link can be read and reached.

New string keys live in `values/strings.xml` in both repositories; the other 24
locales fall back to English until translated.

## Verification

- Download reliability pass (2026-08-05):
  - Added the opt-in full-provider TorBox season case described above, its local
    fixture example, and a masked secure runner. The desktop suite passes **760
    tests**, zero failures/errors.
  - Ran that case against a real TorBox account with three cached episode files
    totalling about 228 MB. It prepared the three provider links up front, held them
    for **960 seconds**, then forced a fresh provider readiness check/re-mint for each
    queue transfer. The case completed in **1,004.398 seconds**, with zero failures or
    errors; all three files completed at the exact provider/HTTP totals and the queue
    stranded nothing. The report contained no skip marker.
  - The first live invocation exposed a harness-runner fault rather than a download
    fault: Gradle could reuse the earlier credential-free skip as an up-to-date test
    result. The secure runner now passes `--rerun-tasks` and disables configuration
    caching, matching the successful live run. A targeted post-run scan found zero copies
    of the credential in the temp log, fixture, disposable test profile, XML, or HTML
    reports; the temporary fixture/log were removed and the isolated runtime reset.
  - Extended the desktop E2E harness from 8 local fault/queue cases to 30. New
    coverage exercises every reorder direction under load, ranged preemption,
    user pause/resume during transfer and retry backoff, cancel and bulk delete,
    active queue reload with preserved rank/partial files, controls during a
    suspended re-mint, expiry after 20% and 90%, one-time and permanent re-mint
    failures, provider hangs, 429/503, dead accounts, changed/truncated identity,
    and the cached/not-cached/evicted/unknown/placeholder readiness outcomes.
  - The harness reproduced four production faults before their fixes: permanent
    re-mint failure retried forever; a hung provider call held a transfer slot
    forever; and a re-minted same-sized different file was appended to the old
    `.part` and marked complete; and a materially truncated replacement was
    accepted at its shorter HTTP total. It also proved that a fresh resolved URL
    skipped the provider cache check and downloaded even after the source was evicted.
  - Fixed those paths by applying the finite re-resolution budget before transfer,
    bounding provider calls at 60 seconds, retaining validators across re-mint so
    `If-Range` resets changed objects, bypassing the resolver's 15-minute success
    cache for download readiness, rejecting materially contradictory refreshed
    provider sizes, and distinguishing not-ready, retryable, changed, and fatal
    provider outcomes. Direct HTTP downloads remain direct.
  - `NuvioZDesktop :composeApp:desktopTest` passed in full: **760 tests**, zero
    failures/errors/skips, including all **30** local desktop download harness cases
    plus the opt-in real-provider case's safe no-credential path.
  - `nuvio-z :composeApp:testAndroidHostTest`: **554 passed**, zero failures,
    errors, or skips. `:androidApp:assembleFullDebug` also completed successfully.
    The four changed common files are byte-identical between repositories.
  - CI is green on both code commits: `nuvio-z` `a6170825` passed Android host
    tests and the debug APK build in run `31043186788`; `NuvioZDesktop`
    `223a396e` passed desktop tests and the Windows MSI build in run `31043196526`.
  - Real TorBox provider/hash coverage is complete as described above. The older
    `NUVIO_DOWNLOAD_TEST_URLS` raw-URL mode remains useful only for direct transfer
    and concurrency checks; it is not used as evidence for provider re-minting.
- Stranded downloads and the harness (2026-08-05). The first download work here with
  runtime evidence rather than an argument. Gradle still cannot configure in the
  sandbox, so Kotlin 2.3.0 was driven directly, describing the source set to the
  compiler as two fragments (`-Xfragments=common,desktop -Xfragment-refines`) so the
  real `expect`/`actual` pairs compile as they do in the build:
  - The harness ran against the **shipped** `DownloadsRepository`,
    `DownloadQueuePlanner`, `DownloadTransfer` and the **shipped**
    `DownloadsPlatformDownloader.desktop.kt`, over a real socket, writing real files.
    Stand-ins only for what is outside the download stack: compose-resources,
    `NetworkStatusRepository`, the debrid resolver, `ProfileRepository`,
    `AppFeaturePolicy`.
  - **The fault was reproduced.** With callback fencing disabled the regression case
    fails every time: episode 1 ends `Paused/System` at 1,687,355 of 6,291,456 bytes
    and never moves again, while episode 2 - the next in line - completes. That is the
    report, exactly.
  - **With the fix, 8/8 pass in ~18s, four runs in a row, no flakes.** Re-run with
    only the generation fence and not the desktop system-pause recovery: still green,
    which is how the fence is known to be the load-bearing half.
  - `DownloadQueueTest` (2 new cases) and `DownloadTransferTest` were re-run against
    the shipped sources alongside it: **44 tests pass** in total.
  - The changed Android and iOS actuals passed the parser-only check but are not
    compiled by anything in the sandbox, so CI was the check that mattered for the
    new `recoversSystemPauses` `expect`. **Both repositories are green** at
    `1aa45d2` / `d2ab738`: nuvio-z ran the Android host suite and built the debug
    APK, and the desktop run passed both "Desktop tests" - which is where the
    harness itself now runs, on every push - and the Windows MSI job, the only
    thing that compiles `desktopMain`.
  - The first desktop run failed at configuration: `java.time.Duration` does not
    resolve in the Kotlin DSL, where `java` is Gradle's Java extension. Fixed with
    an import in `d2ab738`.
  - **Not run against a real debrid link.** The `NUVIO_DOWNLOAD_TEST_URLS` mode has
    never been exercised, because the sandbox has no route to a media host - the
    desktop downloader's `HttpClient` does not read the system proxy either. That
    run is part of the next step below.
- Preset UI and mid-range size preference (2026-08-05). **Nothing has run on a
  device or a real desktop install.** What was done:
  - `ci.yml` is green on both repositories at `ea6d95a` / `461d56d4`: nuvio-z ran
    the Android host suite and built the debug APK, and the desktop run passed both
    "Desktop tests" and the Windows MSI job - the only thing that compiles
    `desktopMain`.
  - Every changed Kotlin file in both repositories passed the parser-only check.
  - `PresetDownloadsTest.kt` was run against the **shipped** `PresetDownloads.kt` and
    `SourceFacts.kt`: **18 of its 25 cases passed**, including the three new
    size-preference cases. The seven excluded ones reach `DownloadsRepository`'s
    codec, the HTTP discovery path, or the batch models, none of which compile
    outside Gradle; only `StreamItem` and its nested stream models were stubbed. CI
    runs the class in full.
  - Both `values/strings.xml` files parse as XML and every string key the new code
    references resolves in both repositories.
  - Released as `0.3.10` (versionCode 109) and `0.1.23-alpha` (code 23) from the
    bump commits `b03d6ba` / `16c28910`. `android-release.yml mode=publish` on
    `main` attached the four ABI APKs; `desktop-release.yml mode=publish
    target=windows` on `Dev` attached the MSI and `SHA256SUMS.txt`. The separate
    `desktop-release.yml mode=build-only` pre-check was **skipped**: `ci.yml` now
    carries a Windows MSI job which compiled the exact release commit and passed,
    so AGENTS' claim that the release workflow is the only `desktopMain` compile is
    out of date.
  - **Still to do:** a device/desktop smoke test of the new picker, the editor
    controls, and the toast link. This shipped without any runtime testing.
- Download freezing work (2026-08-05). Gradle still cannot configure here, so
  Kotlin 2.3.0 was fetched and used directly:
  - `DownloadTransferTest` and `DownloadQueueTest` compiled against the **shipped**
    `DownloadTransfer.kt`, `DownloadQueuePlanner.kt` and `DownloadsModels.kt` and
    executed: **34 tests passed**, including the three new lost-transfer cases and
    the expired-link retry budget.
  - The whole downloads package - `DownloadsRepository.kt`, `DownloadsModels.kt`,
    `DownloadBatches.kt`, `DownloadQueuePlanner.kt`, `DownloadTransfer.kt`,
    `PresetDownloads.kt`, `SourceFacts.kt`, `DownloadPresence.kt` - plus the real
    `StreamModels.kt` **type-checks clean** with the serialization plugin, against
    stubs only for the platform singletons, the debrid resolver and atomicfu.
  - `DownloadsPlatformDownloader.desktop.kt` type-checks standalone, which is
    worth noting because `desktop-release.yml` is otherwise the only thing that
    compiles `desktopMain`.
  - The desktop freeze was **reproduced and confirmed fixed** against a local
    server that sends headers and part of a body then goes silent without
    closing: the transfer now ends after ~75s with
    `Transient: The source stopped sending data. Retrying.` instead of hanging
    forever, and cancelling a stalled transfer takes **2ms** instead of never
    returning.
  - Every changed Kotlin file in both repositories additionally passed a
    parser-only check.
  - After the preset split the three download suites were re-run the same way:
    **56 tests passed**. One pre-existing case,
    `disallowedAddonsAreRemovedBeforeDiscoveryRequests`, is excluded from the
    local harness because it reaches into the addon/network stack there is no
    stand-in for; CI runs it.
  - `ci.yml` passed on both repositories, and `desktop-release.yml`
    `mode=build-only`, `target=windows` compiled `desktopMain` - the only job
    that does, and where the stall watchdog lives.
  - **Not yet exercised on a device or a real desktop install.** The debrid
    re-resolution path in particular has no runtime coverage at all: it needs a
    real TorBox account and a batch left running past the fifteen-minute link
    window, which a 4K season batch will produce naturally.
- Earlier comprehensive Android host suite: 477 tests passed.
- Latest focused source/preset suite:
  - `SourceFactsExtractorTest`: 8 passed.
  - `PresetDownloadsTest`: 10 passed (12 after the unwatched-scope tests were
    added; not yet executed, see below).
- The downloads integration redesign **compiles**: `assembleFullRelease`
  succeeded in CI on the third attempt and published `0.3.4`. The first two
  attempts failed on `MetaScreenSectionKey.DOWNLOADS`, first a non-exhaustive
  `when` in `MetaScreenSettingsPage.kt`, then the two Compose resource
  accessors that file needs as explicit imports.
- `DownloadPresenceTest` (11 tests) has **not been executed**: the release
  workflow only assembles, and no Gradle task can configure in the sandbox.
  Nothing in the redesign has been exercised on a device yet.
- Download transfer/queue rework (2026-08-03): Gradle still cannot configure here,
  so `DownloadTransfer.kt` and `DownloadQueuePlanner.kt` were compiled standalone
  against Kotlin 2.3.0 together with the two new test files, and all 27 tests
  (71 assertions) passed. This exercised the shipped sources, not copies, but it
  covers only those two files; no Android/iOS/desktop code was compiled. Every
  changed Kotlin file additionally passed a parser-only check.
- Preparation visibility work (2026-08-04): Gradle still cannot configure here,
  so `DownloadBatches.kt` was compiled standalone against Kotlin 2.3.0 together
  with the new `DownloadBatchPreparationTest`, and all 4 tests passed. That
  exercised the shipped source, but the neighbouring types it needs
  (`DownloadPreset`, `DownloadSourcePolicy`, `SourceSelectionResult`) were local
  stubs, because the real ones reach into the Compose resource and stream stacks.
  Every changed Kotlin file additionally passed a parser-only check. CI was the
  first real compiler, and it was green on the first attempt in both
  repositories:
  - nuvio-z `CI` on `55e8ccb`: Android host tests **passed** and
    `assembleFullDebug` succeeded. This is the first time the host suite has
    actually executed on the redesign and the transfer/queue rework, so
    `DownloadPresenceTest`, `DownloadQueueTest` and `DownloadTransferTest` have
    now all run for real, not just the two files compiled by hand.
  - NuvioZDesktop `desktop-release.yml` `build-only`/`windows` on `d74779f2`:
    `compileKotlinDesktop` succeeded and the MSI built and verified, so the
    desktop actual is in place and `desktopMain` compiles.
- Signed `assembleFullRelease` completed successfully after the latest metadata
  fix.
- On-device preset smoke test:
  - edited and restored Saver and Quality controls;
  - survived process death and persistence reload;
  - no `AndroidRuntime` crash.
- On-device Daredevil Season 3 Balanced discovery:
  - all 13 episodes reached review with normal 1080p selections;
  - displayed sizes were approximately 0.3–0.8 GB;
  - no conflicting-metadata approvals;
  - review was dismissed without queuing downloads.

## Pending / Follow-up

### NEXT: make a download behave like a Netflix download

**This is the current priority, and it is the standard to hold the work to.** A
download in this app should be as boring and as certain as one in Netflix: you
start it, you can reorder it, pause it, resume it, close the app, lose the
network, come back tomorrow - and it either finishes or tells you plainly why it
cannot. No row that stops moving. No state only a restart can leave. Nothing that
needs the user to know what a debrid link is.

The harness in `NuvioZDesktop`
(`composeApp/src/desktopTest/.../DesktopDownloadQueueE2ETest.kt` and
`FaultyMediaServer.kt`) is where that gets proven. It now covers the local,
deterministic parts of items 1-3 below: queue controls under load and across a
repository reload, provider failures and controls during them, byte identity
across re-mint, and provider readiness immediately before transfer. The harness
was extended first and reproduced every production fault fixed in this pass.
The real-account and real connectivity-transition work in item 4 remains.

**1. The queue controls, under load - covered locally.** Every one of these
cancels a running transfer, and cancelling is what the stranding bug came out of.

- Reorder while transferring: move to top, up, down, to bottom; the promoted item
  starts at once and the preempted one keeps its `.part` file and resumes from
  where it stopped rather than restarting.
- Pause and resume, by hand, mid-transfer and mid-retry-backoff. A user pause is
  sticky - it must survive a queue nudge, a reclaim sweep and an app restart, and
  must never be undone by the recovery paths.
- Cancel and delete mid-transfer, including the last item and the only running
  one; files and `.part` files actually go.
- Reorder, pause and resume *while a fault is in flight* - during the re-mint
  round trip, during a backoff, in the window where a cancelled transfer is
  reporting its last word. That window is exactly where the fixed bug lived, and
  the other three controls reach it the same way the reclaim sweep did.
- Close and reopen: a queue that was mid-transfer comes back in the same order,
  from the same bytes, with user pauses still paused. `loadFromDiskLocked` has
  never been exercised against a queue in a real intermediate state.

**2. Provider failures - covered locally except a real connectivity observer
transition.** `FaultyMediaServer` and the re-mint stand-in now fail on demand:

- a link that expires *mid-transfer* rather than before it starts, at 20% and
  again at 90%;
- re-minting that fails once, then succeeds; that fails every time (the download
  must end `Failed` with a message a human can act on, not retry forever);
- a re-minted link that points at a *different or truncated* file - `If-Range`
  and the overrun/short checks should catch it rather than silently corrupting
  the `.part` file;
- the provider timing out or hanging rather than answering - re-mint runs off the
  lock while holding a slot, and nothing bounds it today;
- 429 and 5xx from the provider, and the whole account failing (every call 401)
  while a season batch is in flight;
- the network dropping entirely and coming back, which on desktop only
  `NetworkStatusRepository` reports.

**3. Cached-on-the-debrid, checked immediately before transfer - implemented and
covered through the provider seam.** This was the weakest link behind "download
queued" placeholders reaching the disk.

Today readiness is whatever the *addon* claimed at selection time
(`SourceFacts.isDebridReady` from `aio.debridCached` / `clientResolve.isCached`),
consulted once in `PresetSourceSelector` and only when `preferCachedSources` is
on. Nothing ever asks the provider directly, and nothing re-checks between
planning a season and reaching episode 9 an hour later. The placeholder check
(`isImplausiblySmallForMedia`) is the only real defence and it is *post-hoc* - it
downloads the wrong file first, then retries on a 1-to-10-minute backoff.

The queue now bypasses the resolver's fifteen-minute success cache and asks the
provider again **before every debrid transfer starts**. Not-cached sources wait
without touching the media URL, provider uncertainty retries with a visible
reason, dead accounts fail plainly, and a placeholder that arrives after a
successful check is still rejected. Cached, not cached, cached-then-evicted,
provider unsure, and post-check placeholder outcomes all have harness cases.

**4. Prove it against a real account - still pending.** The local server cannot imitate provider
quirks, which is where every fault so far has come from. Run the same queue
against TorBox with `NUVIO_DOWNLOAD_TEST_URLS`, and run a real season batch left
going long enough to cross the fifteen-minute link window - that is the only
thing that exercises re-minting for real, and it has still never been done.

Whatever this turns up: fix it in `nuvio-z` and mirror to `NuvioZDesktop`, keep
the harness green in CI on both, and record here what was covered and what was
found. A fault reproduced in the harness is worth more than a fix argued for in a
commit message.

### Preset/discovery work: code complete, release not cut

All five planned pieces have landed. `4ba89f7`/`59fa2ecb` carried the first
three; `55e8ccb` (nuvio-z) and `d74779f2` (NuvioZDesktop), both on
`claude/status-md-continuation-tkc41p`, carry the last two. What is done:

- Per-preset `sizePreference`: `Balanced`/`Quality` take the largest source that
  still fits the cap, `Saver` keeps taking the smallest. This reversed the old
  behaviour, which sorted size ascending and so picked the *smallest* under the
  cap.
- Per-preset `preferCachedSources` (default on). `SourceFacts.isDebridReady` is
  now its own tie-break below every quality key, so cached never costs a
  resolution tier, and an uncached debrid winner is sent to review instead of
  started.
- `PresetDownloadDialog` no longer awaits preparation or blocks dismissal.
- A Preparing section in `DownloadsScreen.kt`, above review, driven by batches
  with any entry still `DISCOVERING`/`RESOLVING`: artwork, title, a
  "Finding sources · 4 of 13" count, a progress bar and per-episode state. A
  batch is held *out* of the review section while it is still preparing, so the
  user is not asked to review a list that is still growing.
- `DownloadsLiveStatusPlatform.onBatchesChanged(batches)` with all four actuals
  (android and ios in both repositories, desktop in `NuvioZDesktop`), and an
  ongoing low-priority Android notification while any batch is preparing. It is
  called from every batch mutation as well as from `publishLocked`: preparation
  moves through `saveBatch`/`updateBatchEntry`, which never touch the item list,
  so hanging it off item changes alone would show nothing for the whole
  discovery pass.
- The unreachable in-dialog review branch is gone from `PresetDownloadDialog`,
  along with the `batch`/`error`/`approveUnknown` state and the `onQueued` and
  `onChooseManually` parameters behind it.

Remaining:

1. **Smoke-test preparation on-device.** Start a season batch and confirm the
   Preparing section fills in episode by episode, that the ongoing notification
   appears and clears, and that the batch moves to review or straight to the
   queue when discovery finishes.
2. **Check the desktop in-app update path.** `0.1.20-alpha` is installed on a
   Windows machine and launches with a responsive main window and no matching
   Application event-log crash. The actual `0.1.19-alpha` to `0.1.20-alpha`
   in-app update path has not been exercised.

### Latest release: CI verified, runtime testing pending

Two changes shipped in `0.3.8` / `0.1.21-alpha`. The merged release branches
passed Android host tests/debug assembly in run `30944119268` and desktop tests/
Windows MSI assembly in run `30944124462`. Publish runs `30944744977` and
`30944920882` then built and published the signed APKs and verified MSI. They
have not been runtime-smoke-tested. On 2026-08-04 the release was explicitly
approved without an Android device; device verification remains a post-release
follow-up.

The former `claude/status-md-continuation-tkc41p` branches are merged. The code
below is released from `main` / `Dev`.

#### (a) The two missing preset controls

`4ba89f7`/`59fa2ecb` added `preferCachedSources` and `sizePreference` to
`DownloadPreset` and wired them into `PresetSourceSelector`, but **never added
editor UI**, so they were stuck at their built-in defaults and the user could not
reach them. Added to `PresetSettingsCard` in `DownloadsSettingsScreen.kt`:

- a row that toggles `sizePreference` between `LARGEST_UNDER_CAP` and `SMALLEST`;
- a `Prefer cached sources` switch for `preferCachedSources`.

Four new strings in both `strings.xml` files:
`download_preset_size_preference`, `download_preset_size_largest`,
`download_preset_size_smallest`, `download_preset_prefer_cached`.

Both fields are already `@Serializable` on `DownloadPreset` and go through
`DownloadsRepository.updatePreset`, so persistence needed no change.

#### (b) Series page and Downloads page disagreeing (reported bug)

**Symptom.** Delete everything from the Downloads tab, then open the series page:
episodes still show download states - some "downloading", some "downloaded".

**Cause.** `buildTitleDownloadState` (`DownloadPresence.kt`) layers batch entries
underneath persisted items, items winning. The old `publishLocked` only synced an
entry when a matching item still existed (`?: return@map entry`), so deleting a
download left its batch entry frozen at `DOWNLOADING`/`COMPLETED`/`QUEUED`
forever. With the item gone the detail screen fell through to that stale entry.
The Downloads tab looked correct because it renders items, not entries.

**Fix as written.** A new pure `reconcileBatches(batches, items)` in
`DownloadBatches.kt`, called from both `publishLocked` and `loadFromDiskLocked`:

- an entry with a matching item follows that item's status, as before;
- an entry in an *item-backed* state whose item is gone becomes `CANCELLED`,
  which `toPresence()` already maps to `DownloadPresence.None`;
- a batch whose entries are now all `CANCELLED` is dropped entirely;
- `isItemBacked` covers `QUEUED`, `DOWNLOADING`, `PAUSED`, `COMPLETED` and
  **deliberately excludes `FAILED`**, because discovery failures and queueing
  failures land there with no item ever created, and those entries must stay in
  review so the user can still pick a source by hand. The trade-off: deleting a
  *failed* download leaves the episode reading as failed until the batch is
  dismissed. Left as-is on purpose; revisit only with a way to tell the two
  failures apart.

Calling it from `loadFromDiskLocked` is what heals **installs that are already
broken**, including the reporter's device - it reconciles on the next launch
rather than waiting for the next queue change. That path also had to widen its
persist condition to `normalized != stored.items || reconciledBatches !=
stored.batches`.

`DownloadBatchReconcileTest` (8 tests) covers the delete cases, the `FAILED`
carve-out, idempotence, and the empty-batch case. It ran successfully in both
CI suites above.

#### Next steps, in order

1. **Smoke-test the bug fix when a device is available**, because this is a
   persistence fix and no test touches real storage: queue a season, let some
   episodes finish, delete everything from the Downloads tab, reopen the series
   page and confirm every episode reads as not downloaded; then force-stop,
   relaunch, and confirm it still does.
2. **Exercise the desktop updater** from the installed `0.1.20-alpha` to
   `0.1.21-alpha`; merely launching `0.1.20-alpha` did not verify replacement.


- No Gradle task can configure in this sandbox: `dl.google.com` is denied by
  the egress policy, so the Android Gradle Plugin never resolves. CI is the only
  compiler available here, which makes each fix a full release-run round trip.
  Run `.\gradlew.bat :composeApp:testAndroidHostTest` locally to get the host
  suite, including the new `DownloadPresenceTest`, actually executed.
- The download transfer/queue rework **compiles** - CI built and published
  `0.3.6` from it - but its behaviour is still unverified. Only the two new
  pure-logic files have executing tests (see Verification); the repository, the
  three platform downloaders and the screen have never been run. Run
  `.\gradlew.bat :composeApp:testAndroidHostTest` locally to execute the host
  suite, which CI's assemble-only release job never runs.
- Smoke-test the reworked transfers on-device with a deliberately small file:
  pause/resume mid-transfer, resume after the source URL has expired (must not
  report a completed download at the partial size), process death mid-transfer,
  background/foreground on iOS, and a season batch to confirm E01 starts first and
  that "Download next" preempts.
- The unwatched-season download work has **not** been compiled or tested in this
  environment either: the sandbox blocks `dl.google.com`, so the Android Gradle
  Plugin cannot be resolved and no Gradle task can configure. Run
  `.\gradlew.bat :composeApp:testAndroidHostTest` and an `assembleFullDebug`
  locally before trusting it.
- Smoke-test the unwatched season download on-device: open a partly watched
  season, use the season download menu, and confirm only the current episode
  onwards is queued.
- Smoke-test the downloads redesign on-device: confirm the Downloads tab appears
  in the classic, adaptive and tablet nav bars; queue one small episode and check
  that the episode card ring, the tab's “Downloading now” row, and pause/resume
  stay in sync; confirm the “Downloaded” section appears on the entry once the
  transfer completes and disappears after deleting.
- `onBatchesChanged` is a no-op on iOS and desktop. The iOS bridge publishes one
  live item to Swift and a second payload needs matching Swift work; desktop has
  no notification surface at all. Both show preparation in the Downloads tab.
- A batch cannot be cancelled while it is preparing, on any platform. See the
  Work Log entry for why the obvious button would lie.
- The iOS Downloads tab currently falls back to the `arrow.down.circle.fill` SF
  Symbol. Add a `NuvioTabDownloads` xcasset to match the other tab icons.
- Existing profiles get the new meta-screen “Downloaded” section appended last in
  their saved section order, because `normalizePreferences` sorts unknown keys to
  the end. New profiles get it right after Actions.
- The local workspace directory is still named `stremio-z`; renaming it is
  deferred.
- Run the full host suite again after the next substantial code change.
- Test a real transfer end-to-end, including pause/resume, process death,
  network constraints, and cap-crossing approval, using a deliberately small
  file.
- Review lifecycle/cleanup for prepared batches dismissed from the review
  dialog so cancelled all-ready batches do not remain as hidden persisted
  records.
- Trakt functionality requires local client credentials and has not been
  reconfigured for this personal build.
- iOS parity gaps in the preset download feature, all in platform seams:
  `freeStorageBytes()` returns `-1` so low-space warnings and
  storage-triggered review never fire; `allowMeteredNetwork` is ignored
  because the iOS session hardcodes cellular access; downloads pause on
  app background because iOS uses a foreground `NSURLSession`.
- `DownloadsStorage.ios.kt` no longer profile-scopes its payload key,
  unlike every other iOS storage and unlike the desktop fork. Decide
  whether that de-scoping was intended.
- Desktop CI cannot be verified from a sandbox that blocks `dl.google.com`;
  the Android Gradle Plugin will not resolve there.
- `0.3.6` (versionCode 105) is released from `main` and is the first build to
  carry the download transfer/queue rework. `assembleFullRelease` succeeded, so
  the merged redesign and rework compile together; nothing in the rework has
  been exercised on a device yet.
- Queue reordering has a known rough edge: the needs-attention section is
  filtered out of the queue list, so a Move up/down that would swap with an
  attention item looks like it did nothing. "Download next" is unaffected.
- `Zokaper/nuvio-z` is public, which the unauthenticated updater requires.
  `0.3.7` (versionCode 106) is the current release; `0.3.6`, `0.3.5`, `0.3.4`
  and `0.3.3` precede it. All carry signed APKs for all four ABIs.
- CI release signing is stable: `0.3.3`, `0.3.4` and `0.3.5` all carry signer
  certificate SHA-256
  `2325A3399F9BBF5ECE1391EBE6B5A0E0F016058520FB1597B1CF30CF6184787C`.
  A locally built APK signed with a different keystore cannot be updated over
  by these releases, and Android reports only "App not installed". The installed
  build's version identifies which key it carries, because `0.3.3` and later
  exist only as CI output.
- The earlier "App not installed" in-app update failure is **resolved**: the
  in-app update from `0.3.5` to `0.3.6` succeeded on the Samsung device. It was
  the signing-key mismatch rather than Auto Blocker - once the installed build
  came from CI, later CI-signed releases update over it cleanly. A locally built
  APK still cannot be updated over by a CI release, so a local build has to be
  uninstalled first.
- `NuvioZDesktop` desktop releases are now Windows-only. Every macOS job failed
  at "Configure desktop runtime" because the repository holds none of the Apple
  signing and notarisation secrets it requires, so the target choice was
  narrowed to `windows`; the macOS job is still in the workflow behind a guard
  that can no longer match. Restoring macOS means adding the secrets and
  putting the options back.
- Compiling the desktop mirror for the first time found that the redesign added
  a `downloads` parameter to the `publishNativeTabTitles` expect and updated the
  Android and iOS actuals but not the desktop one. Fixed in `NuvioZDesktop`.
  A Windows build of the pre-redesign commit compiles, which is what identified
  the redesign mirror rather than the transfer rework as the source.
- The desktop Windows job now runs `compileKotlinDesktop` as its own step
  without `--stacktrace`, because packaging with it buried the compiler's `e:`
  lines under roughly 250 lines of Gradle internals.
- `NuvioZDesktop` compiles and produces a verified MSI in CI. `0.1.20-alpha` is
  the current release and `0.1.19-alpha` (2026-08-03) precedes it, each carrying
  one Windows x64 MSI and a `SHA256SUMS.txt`. `0.1.20-alpha` is installed and
  launches on Windows; the in-app replacement flow is still untested.

## Work Log

### 2026-08-05 (download freezing, the 4K preset split, and the releases)

- Traced downloads that stopped around 80% on the Windows build through TorBox to
  four separate faults, detailed above: a desktop body read with no deadline, a
  queue that could not see a transfer it had lost, a size cap enforced
  mid-transfer over sources already approved, and debrid links minted once and
  never refreshed.
- Split the 4K preset into 4K Low (8 GB/h) and 4K High (15 GB/h) after finding
  that the old `Quality` preset - 2160p capped at 4 GB/hour - rejected every real
  4K source with the same "exceeds the calculated size cap" message the freezing
  report started from.
- Fetched Kotlin 2.3.0 directly and ran the download suites against the shipped
  sources (56 passing), type-checked the whole downloads package with the
  serialization plugin, and reproduced the desktop freeze against a stalling
  server to confirm the watchdog ends it.
- Released `0.3.9` (versionCode 108) and `0.1.22-alpha` (code 22), both
  published: four APKs and one Windows x64 MSI with `SHA256SUMS.txt`. The Windows
  `build-only` job was run before the version bumps, since `desktopMain` compiles
  nowhere else and a failed publish would have burnt the version number.
- Note for the next release: the local `main` in a fresh checkout can lag
  `origin/main`, which produces a merge whose first parent is a stale commit.
  Reset to `origin/main` before merging.

### 2026-08-04 (desktop startup latency)

- Root-caused the roughly 20-second cold desktop launch. `Main` called the
  misleadingly named `preloadNativePlayerBridgeAsync` before creating the
  Compose window, but referencing `NativePlayerBridge` synchronously loaded its
  native runtime first. The Windows package also left
  `compose.application.resources.dir` empty and embedded the runtime in the app
  JAR, so each launch extracted the bundled player bridge, the approximately
  110 MB `libmpv-2.dll`, and its runtime DLLs before the first window. The same
  JAR also embedded the approximately 55 MB TorrServer executable.
- Made the complete native-player bootstrap genuinely asynchronous, including
  Kotlin object initialization and DLL loading.
- Moved the Windows player runtime and TorrServer into Compose native
  distribution app resources. Packaged playback now loads the DLLs directly and
  P2P resolves TorrServer directly; the JAR extraction paths remain only as
  development/backward-compatible fallbacks.
- Updated the desktop release workflow to reject native executables left in the
  app JAR and require each one under the packaged `app/resources` directory.
- Fast-forwarded the verified fix into desktop `Dev` at `4a4f4b88`, so the next
  desktop release will include it.
- Verification: both changed Kotlin files passed the standalone parser check.
  GitHub CI run `30948292711` on desktop commit `4a4f4b88` passed the desktop
  tests and built/uploaded the Windows MSI. Local Gradle verification was
  abandoned after its dependency resolver stalled in an HTTPS download; this
  local-machine failure was not attributed to the cloud-sandbox restriction.
- Remaining: run a timed cold and warm launch from the optimized MSI. The
  current installed `0.1.21-alpha` reached a first window in approximately 5.1
  seconds on a warm launch, and its temp extraction timestamps confirmed the old
  per-launch native-runtime path.

### 2026-08-04 (preset controls, batch reconciliation, and releases)

- Exposed the cached-source and file-size preferences in the preset editor.
- Reconciled persisted batch entries against download items on mutation and
  startup, fixing deleted episodes that still appeared downloaded or active on
  series pages; added eight regression tests.
- Merged and verified both repositories: Android host tests/debug APK and desktop
  tests/Windows MSI all passed on the release branches.
- Published `0.3.8` (versionCode 107) with four signed Android APKs and
  `0.1.21-alpha` (versionCode 21) with a verified Windows x64 MSI and checksum.
- Fixed desktop release-note generation so direct merge commits remain visible
  in first-parent repository history. The first desktop publish attempt stopped
  before building because its notes were empty; the corrected retry succeeded.
- Released without Android device smoke testing at the user's explicit direction;
  runtime verification remains pending.

### 2026-08-04 (preparation visibility and dialog cleanup)

- Added the Preparing section to the Downloads tab. Discovery had been moved to
  the background but nothing rendered it, so between the toast and the first
  queued episode the app looked idle for as long as a season took to resolve.
- Deliberately gave the preparing card no cancel: `PresetDownloadCoordinator`
  calls `saveBatch` again when discovery finishes, so a removal during
  preparation would silently come back. Cancelling a batch mid-discovery needs
  the coordinator to be interruptible first.
- Held preparing batches out of the review section, so review is only offered on
  a finished list.
- Added `DownloadsLiveStatusPlatform.onBatchesChanged` and updated all four
  actuals in the same change, including the desktop one - the mistake that broke
  the desktop build with `publishNativeTabTitles` was updating only Android and
  iOS, which compile fine without it.
- Wired the new hook into every batch mutation, not just `publishLocked`.
  Preparation only ever moves through `saveBatch`/`updateBatchEntry`, which never
  touch the item list, so the `publishLocked` call site alone would never have
  fired while a batch was preparing.
- Removed the unreachable in-dialog review branch from `PresetDownloadDialog`
  and the `onQueued`/`onChooseManually` plumbing behind it in both details
  screens.
- Released `0.3.7` (versionCode 106) from `main` and `0.1.20-alpha` from the
  desktop fork's `Dev`, both published for the in-app updaters. Android carries
  signed APKs for all four ABIs; desktop carries the Windows x64 MSI. Both
  release runs were green on the first attempt.

### 2026-08-03 (later: transfer/queue rework and the 0.3.6 release)

- Reworked download transfers so a finished byte loop only counts as a completed
  download when the bytes on disk match the authoritative total. The read loop
  had treated any end of stream as success and then adopted the truncated file's
  own length as the total, so a cut-short transfer rendered as finished at
  whatever byte count it had reached.
- Added `If-Range` on resume, correct 416 handling that finalizes an already
  complete `.part` instead of refetching, cooperative pause reporting, retry
  with backoff, an explicit `Queued` state with persisted ranks, menu-based
  reordering with preemption, and coalesced progress persistence.
- Reconciled this work with the downloads redesign. The two were siblings off
  the same base rather than one built on the other, so both had rewritten
  `DownloadsRepository`, `DownloadsModels` and `DownloadsScreen`. The redesign's
  `deleteDownloadsForTitle`/`ForSeason` auto-merged but still called the
  `publish`/`persist` helpers the rework had replaced, and touched
  `activeHandles` unsynchronised; both were rewritten onto the locked path.
- Released `0.3.6` from `main`. `assembleFullRelease` succeeded on the first
  attempt.
- Narrowed `NuvioZDesktop` desktop releases to Windows after every macOS job
  failed on missing Apple credentials, and fixed the desktop
  `publishNativeTabTitles` actual the redesign had left behind.
- Verified the two new pure-logic files by compiling them standalone against
  Kotlin 2.3.0 with their tests: 27 tests, 71 assertions, all passing. Gradle
  still cannot configure in the sandbox, so everything else was checked only by
  a parser pass locally and then by CI.

### 2026-08-03

- Added `DownloadPresence.kt`: a shared, Compose-free download-state layer
  (`DownloadPresence`, `ContentDownloadState`, `TitleDownloadState`,
  `buildTitleDownloadState`) that merges persisted downloads with in-flight batch
  entries so a title reads as “preparing” the moment a batch is created.
- Promoted the private `buildLogicalKey` to a shared `downloadLogicalKey` and
  pointed `DownloadsRepository` and `DownloadBatchPlanner` at it, so batch
  planning and download storage can no longer drift apart.
- Added `DownloadsRepository.deleteDownloadsForTitle` / `deleteDownloadsForSeason`
  and `DownloadsUiState.bytesOnDisk`.
- Made Downloads a top-level tab (`AppScreenTab.Downloads`,
  `NativeNavigationTab.Downloads`) across the classic, adaptive and tablet nav
  bars, the desktop sidebar, and the iOS native tab bar; widened the tab-title
  bridge with a `downloads` slot through to `ContentView.swift`.
- Split the old settings-only downloads page in two: `DownloadsSettingsScreen`
  keeps presets and allowed sources under Settings, while `DownloadsScreen`
  became the tab root with artwork, a needs-attention section, live transfers,
  and an on-device list grouped per title with per-title and per-season deletes.
- Added `DownloadStateButton` and `DownloadManageSheet`, and wired them into both
  episode card styles so a card shows idle / preparing / progress / paused /
  failed / downloaded and manages the download in place.
- Added a configurable `MetaScreenSectionKey.DOWNLOADS` section listing what is
  on the device for a title, and made the hero download action reflect a movie's
  own download state.
- Added `DownloadPresenceTest` covering key derivation, both presence mappings,
  item-over-batch precedence, season roll-ups, and prefix collisions.
- Mirrored the whole change into `Zokaper/NuvioZDesktop`, where the Downloads tab
  and sidebar entry are gated behind `AppFeaturePolicy.downloadsEnabled`.
- Root-caused the "resume says it completed" report: the read loop treated any end
  of stream as success, and `onSuccess(uri, totalBytes ?: finalSize)` adopted the
  truncated file's own length as the total, so a cut-short transfer rendered as a
  finished download at whatever byte count it had reached.
- Added `DownloadTransfer.kt` with the completion rule, retry policy, HTTP failure
  classification, progress-throttle thresholds, and the `resolveTotalBytes` /
  `parseContentRangeTotal` helpers that had been duplicated verbatim in all three
  platform downloaders.
- Replaced the platform downloader's loose callbacks with `DownloadTransferListener`
  so a pause can be reported as a pause; Android previously caught
  `CancellationException` in a generic `catch (Throwable)` and reported it as a
  failure, which only avoided showing up as a failed download because of a status
  check that lost the race.
- Fixed the 416 branch: an already complete `.part` is now finalized instead of
  being deleted and downloaded again from zero.
- Added `DownloadStatus.Queued`, persisted queue ranks, and `DownloadQueuePlanner`.
  Enqueue appends rather than prepends, so a season batch no longer downloads in
  reverse episode order.
- Added queue reordering (`Download next` / up / down / bottom) as a menu so it
  works with a TV remote, with preemption of the lowest priority running transfer.
- Split `DownloadPauseReason.User` from `System` and added
  `resumeSystemPausedDownloads`, wired to reload, connectivity recovery, and a new
  `applicationWillEnterForeground` hook on iOS — backgrounding the app used to
  pause every download permanently.
- Serialised repository mutations behind an atomicfu lock and coalesced persistence,
  replacing a full JSON payload rewrite on every 16 KiB chunk.
- Set aside an unparseable payload under a separate key instead of silently
  discarding every download, batch, and preset.
- Mirrored the same change into `Zokaper/NuvioZDesktop`, including the desktop
  downloader.

### 2026-08-02

- Added `DownloadScope.SeasonUnwatched`, an unwatched filter in
  `DownloadBatchPlanner`, and watch-state resolution in
  `PresetDownloadCoordinator`, so a season batch can skip everything already
  watched while keeping the episode currently in progress.
- Added `WatchingState.isEpisodeSeen` as the single watched-or-completed rule and
  pointed the details screen helper at it.
- Added the season download menu (whole season vs unwatched episodes) to the
  season header and a matching row in the season action sheet; both hide the
  unwatched option when a season has nothing left to watch.
- Stopped persisting a batch when a scope resolves to no episodes and reported
  the empty result in the preset dialog instead of queuing zero downloads.
- Added `PresetDownloadsTest` coverage for the unwatched scope, including the
  already-downloaded exclusion.
- Mirrored the same change into `Zokaper/NuvioZDesktop`.
- Reviewed iOS viability and distribution options for sharing personal
  builds; recorded the platform gaps above.
- Ported the preset and bulk download feature to the desktop fork
  (`Zokaper/NuvioZDesktop`, branch `claude/preset-bulk-downloads`) as a
  cherry-pick, since that repository shares history with this one.
  Resolved four conflicts, threaded the download callbacks through the
  desktop fork's two details layouts, and implemented `freeStorageBytes()`
  for desktop from the downloads directory's usable space.
- Excluded the Nuvio Z Android branding, the repository handoff documents,
  and the iOS profile-scoping change from that port.
- Added a `CI` workflow to both repositories: Android host tests and an
  unsigned debug APK artifact on every push, plus desktop compilation and
  tests in the desktop fork. Neither requires signing secrets.
- Not verified by a compiler in this environment; CI is the first real
  build of the ported feature.
- Repointed the in-app updater from `NuvioMedia/NuvioMobile` to
  `Zokaper/nuvio-z`, and the desktop fork's updater to
  `Zokaper/NuvioZDesktop`. Cleared the release channel filter, which
  matched a branch name against a release `targetCommitish` that is
  actually a commit SHA and so rejected every release.
- Scanned the full history of both repositories for committed secrets
  before recommending public visibility; none were found.

### 2026-07-30

- Forked the inspected NuvioMobile base and implemented Nuvio Z preset/bulk
  downloads.
- Built and installed the signed arm64 release through ADB.
- Diagnosed sign-in failure from device logs and restored production backend
  configuration locally.
- Reproduced and fixed preset persistence crash; added regression coverage.
- Reproduced season-wide AIO metadata false conflicts; fixed and verified all 13
  Daredevil Season 3 selections on-device.
- Added shared `AGENTS.md`, Claude handoff instructions, and this status log.
- Created the private `Zokaper/nuvio-z` repository, preserved official Nuvio as
  `upstream`, configured the private fork as `origin`, and pushed `main`.
