#!/usr/bin/env bash
#
# upstream-drift.sh -- measure how far this fork has drifted from vanilla.
#
# Nuvio Z is a mod: a bounded set of patches riding on a stated vanilla base.
# That claim is only true if the drift is measurable, so this script prints the
# three numbers that decide how expensive the next sync will be:
#
#   1. ahead / behind        -- how many commits each side has that the other lacks
#   2. the patch surface     -- files upstream owns that we have modified. This is
#                               what Docs/PATCH-SURFACE.md tracks, and it should
#                               shrink over time as features move behind seams.
#   3. the conflict surface  -- the subset of those that upstream has ALSO touched
#                               since our fork base. This is what will actually
#                               conflict at the next merge.
#
# Usage:
#   scripts/upstream-drift.sh [upstream-ref]     # human-readable report
#   scripts/upstream-drift.sh --format=counts    # "ahead behind patch conflict"
#   scripts/upstream-drift.sh --format=markdown  # for the weekly drift issue
#
# Exit codes: 0 ok, 1 usage/repo error, 2 the upstream ref is missing (not fetched).

set -euo pipefail

# The branch this fork tracks. Overridable by argument or UPSTREAM_REF.
DEFAULT_UPSTREAM_REF="upstream/cmp-rewrite"

FORMAT="report"
UPSTREAM_REF=""

for arg in "$@"; do
  case "$arg" in
    --format=*) FORMAT="${arg#--format=}" ;;
    -h|--help)  sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*)         echo "unknown option: $arg" >&2; exit 1 ;;
    *)          UPSTREAM_REF="$arg" ;;
  esac
done

UPSTREAM_REF="${UPSTREAM_REF:-${UPSTREAM_REF_ENV:-${DEFAULT_UPSTREAM_REF}}}"

git rev-parse --git-dir >/dev/null 2>&1 || { echo "not a git repository" >&2; exit 1; }
cd "$(git rev-parse --show-toplevel)"

if ! git rev-parse --verify --quiet "${UPSTREAM_REF}^{commit}" >/dev/null; then
  cat >&2 <<EOF
upstream ref '${UPSTREAM_REF}' not found.

Fetch it first:
    git fetch upstream ${UPSTREAM_REF#upstream/}

If this clone has no 'upstream' remote at all, see the "first clone" section of
AGENTS.md.
EOF
  exit 2
fi

BASE="$(git merge-base HEAD "$UPSTREAM_REF")"
read -r AHEAD BEHIND <<<"$(git rev-list --left-right --count "HEAD...${UPSTREAM_REF}")"

# Files we changed since the fork base.
OURS="$(git diff --name-only "$BASE" HEAD | sort)"
# Files upstream changed since the fork base.
THEIRS="$(git diff --name-only "$BASE" "$UPSTREAM_REF" | sort)"
# Files that already existed upstream at the fork base -- i.e. upstream owns them.
OWNED="$(git ls-tree -r --name-only "$BASE" | sort)"

# Patch surface: our edits to files upstream owns. New files we added are not
# patch surface -- that is the whole point of building features as new modules.
PATCH_SURFACE="$(comm -12 <(printf '%s\n' "$OURS") <(printf '%s\n' "$OWNED"))"
# Conflict surface: the patch surface upstream has also moved under us.
CONFLICT_SURFACE="$(comm -12 <(printf '%s\n' "$PATCH_SURFACE") <(printf '%s\n' "$THEIRS"))"

count() { [ -z "$1" ] && echo 0 || printf '%s\n' "$1" | wc -l | tr -d ' '; }
N_OURS="$(count "$OURS")"
N_PATCH="$(count "$PATCH_SURFACE")"
N_CONFLICT="$(count "$CONFLICT_SURFACE")"

BASE_SHORT="$(git rev-parse --short "$BASE")"
BASE_DATE="$(git log -1 --format=%ad --date=short "$BASE")"
TIP_SHORT="$(git rev-parse --short "$UPSTREAM_REF")"
TIP_DATE="$(git log -1 --format=%ad --date=short "$UPSTREAM_REF")"

case "$FORMAT" in
  counts)
    echo "$AHEAD $BEHIND $N_PATCH $N_CONFLICT"
    ;;
  markdown)
    echo "| metric | value |"
    echo "| --- | --- |"
    echo "| upstream ref | \`${UPSTREAM_REF}\` |"
    echo "| fork base | \`${BASE_SHORT}\` (${BASE_DATE}) |"
    echo "| upstream tip | \`${TIP_SHORT}\` (${TIP_DATE}) |"
    echo "| ahead | ${AHEAD} |"
    echo "| **behind** | **${BEHIND}** |"
    echo "| files we changed | ${N_OURS} |"
    echo "| patch surface | ${N_PATCH} |"
    echo "| **conflict surface** | **${N_CONFLICT}** |"
    echo
    if [ "$N_CONFLICT" -gt 0 ]; then
      echo "<details><summary>Conflict surface (${N_CONFLICT} files upstream moved under us)</summary>"
      echo
      printf '%s\n' "$CONFLICT_SURFACE" | sed 's/^/- `/; s/$/`/'
      echo
      echo "</details>"
      echo
    fi
    if [ "$N_PATCH" -gt 0 ]; then
      echo "<details><summary>Patch surface (${N_PATCH} upstream-owned files we modify)</summary>"
      echo
      printf '%s\n' "$PATCH_SURFACE" | sed 's/^/- `/; s/$/`/'
      echo
      echo "</details>"
    fi
    ;;
  report)
    echo "upstream ref   ${UPSTREAM_REF}"
    echo "fork base      ${BASE_SHORT}  ${BASE_DATE}"
    echo "upstream tip   ${TIP_SHORT}  ${TIP_DATE}"
    echo
    echo "${AHEAD} ahead, ${BEHIND} behind"
    echo
    echo "patch surface     ${N_PATCH}  (upstream-owned files we modify)"
    if [ "$N_PATCH" -gt 0 ]; then
      printf '%s\n' "$PATCH_SURFACE" | sed 's/^/  /'
    fi
    echo
    echo "conflict surface  ${N_CONFLICT}  (of those, upstream has also touched since the base)"
    if [ "$N_CONFLICT" -gt 0 ]; then
      printf '%s\n' "$CONFLICT_SURFACE" | sed 's/^/  /'
    fi
    ;;
  *)
    echo "unknown format: $FORMAT (want report, counts or markdown)" >&2
    exit 1
    ;;
esac
