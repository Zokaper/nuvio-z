# Vanilla bugs Nuvio Z inherits

**This file exists so a bug is investigated once.** Nuvio Z is a mod: it inherits vanilla Nuvio's
features and it inherits vanilla's bugs with them. Without a register, the same upstream defect gets
picked up as a suspected Z regression every few weeks, re-diagnosed from scratch, and put down again
when it turns out not to be ours. That has already happened.

**The rule that governs this file is Rule 7 in `Docs/UPSTREAM.md`.** In short: note it, do not fix
it, unless it breaks something of ours. Read the rule before adding a fix rather than a row.

## How to use it

- **A row is a claim about where a bug lives**, so it carries how confident that claim is.
  `confirmed` means it was reproduced on a vanilla build. `suspected` means it looks upstream and
  nobody has checked. A `suspected` row settles nothing - it is a note that the check is worth
  doing, not a reason to stop looking.
- **Check the drift before adding a row.** `scripts/upstream-drift.sh` says how far behind vanilla
  we are. A bug in that window may already be fixed, and the next sync will take it away for free.
- **Delete rows a sync fixes**, and say so in the sync's commit. The register is a list of live
  inherited defects, not a history.
- **If it breaks a Z feature or a `ROADMAP.md` exit gate**, it stops being a row here and becomes a
  patch - with a `Docs/PATCH-SURFACE.md` entry and a `drop-at-next-sync` tag. Note in the row that
  it was promoted, and where.

## The register

| # | Bug | Where | Confidence | Notes |
| --- | --- | --- | --- | --- |
| **V1** | The home screen sometimes draws only the Continue Watching row; the catalog rows are absent. Intermittent. Scrolling down sometimes forces them to load. | Desktop, observed. **Did not reproduce on the post-sync `0.1.22-alpha-z1` build (2026-09-04):** the home screen came up complete on the addon-heavy `big z` profile - Continue Watching plus three fully-populated catalogue rows, read from the Compose semantic tree rather than from a screenshot. One clean launch is not a closed intermittent bug, so this stays open at lower confidence; ~90 commits of vanilla may have closed it for free. Still unchecked on mobile and web. | **suspected, weakened** | A failed catalogue fetch would not be rescued by a scroll, so this reads as row virtualisation or a lazy-load viewport calculation losing its first pass rather than a fetch failure. Recorded in `Docs/Z-FEATURES.md` §12 as the one open defect. **The check that settles it: run a vanilla Nuvio desktop build on an addon-heavy profile and try to reproduce.** Until that is done this row proves nothing - if it turns out to be ours it is serious, because a home screen that intermittently shows nothing is the first thing a new user sees. |

---

**Related:** `Docs/UPSTREAM.md` (Rule 7, and the sync procedure), `Docs/PATCH-SURFACE.md` (what we
patch in upstream files and why), `Docs/Z-FEATURES.md` (§12, what has and has not been watched).
