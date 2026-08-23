#!/usr/bin/env bash
#
# shared-code-drift.sh -- what has drifted between the two KMP repositories.
#
# `nuvio-z` (Android/iOS) and `NuvioZDesktop` (Windows) carry the same
# `composeApp/src/commonMain`, and until now the sync mechanism was `cp`. That is
# not divergence, it is drift: nothing detects it, so a change made in one repo
# and never copied looks identical to a change deliberately kept apart.
#
# The two repos DO share history, at mobile's fork base 979d5680, so the other
# repo is a real remote and shared changes can flow by `git merge` instead of by
# copying files. This script is the measurement that makes that usable: it turns
# silent deltas into a number that can be trended and a list that can be reviewed.
#
# Usage:
#   scripts/shared-code-drift.sh [other-ref]      # human-readable report
#   scripts/shared-code-drift.sh --format=counts  # "total <set>:<n> ..."
#   scripts/shared-code-drift.sh --expected       # only unexpected differences
#
# Exit codes: 0 ok, 1 usage/repo error, 2 the other ref is missing (not fetched).

set -euo pipefail

# The counterpart repository's branch, as this clone sees it.
DEFAULT_OTHER_REF="desktop/claude/upstream-doctrine-stage0"
OTHER_NAME="desktop"

# Shared source sets. `desktopMain` is deliberately absent: it is this repo's own,
# and mobile's copy is 3 vestigial files.
SOURCE_SETS=(commonMain commonTest androidMain iosMain)

# Differences that are DELIBERATE and must not be "fixed" by copying. Recorded in
# AGENTS.md; repeated here so the report can subtract them rather than making a
# reader re-derive the list every time.
#
# SetupHomeStill.kt is the sharpest one: it is a per-target file and copying it
# has broken the setup wizard before.
declare -a EXPECTED_DIFFS=(
  'composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/MetaDetailsScreen.kt'
  'composeApp/src/commonMain/kotlin/com/nuvio/app/features/setup/SetupHomeStill.kt'
  'composeApp/src/commonMain/composeResources/values/strings.xml'
  'composeApp/src/commonMain/kotlin/com/nuvio/app/core/build/AppFeaturePolicy.kt'
)

FORMAT="report"
ONLY_UNEXPECTED="false"
OTHER_REF=""

for arg in "$@"; do
  case "$arg" in
    --format=*)  FORMAT="${arg#--format=}" ;;
    --expected)  ONLY_UNEXPECTED="true" ;;
    -h|--help)   sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*)          echo "unknown option: $arg" >&2; exit 1 ;;
    *)           OTHER_REF="$arg" ;;
  esac
done

OTHER_REF="${OTHER_REF:-${DEFAULT_OTHER_REF}}"

git rev-parse --git-dir >/dev/null 2>&1 || { echo "not a git repository" >&2; exit 1; }
cd "$(git rev-parse --show-toplevel)"

if ! git rev-parse --verify --quiet "${OTHER_REF}^{commit}" >/dev/null; then
  cat >&2 <<EOF
'${OTHER_REF}' not found.

The counterpart repository is a remote of this one. Fetch it:
    git fetch ${OTHER_NAME} <branch>

If the remote is missing entirely, see the "first clone" section of AGENTS.md.
EOF
  exit 2
fi

is_expected() {
  local candidate="$1"
  local known
  for known in "${EXPECTED_DIFFS[@]}"; do
    [ "$candidate" = "$known" ] && return 0
  done
  return 1
}

total=0
unexpected_total=0
declare -a counts_out=()
declare -a report_body=()

for set_name in "${SOURCE_SETS[@]}"; do
  files="$(git diff --name-only "HEAD" "${OTHER_REF}" -- "composeApp/src/${set_name}" || true)"
  n=0
  [ -n "$files" ] && n="$(printf '%s\n' "$files" | wc -l | tr -d ' ')"
  total=$((total + n))
  counts_out+=("${set_name}:${n}")

  shown=""
  while IFS= read -r file; do
    [ -z "$file" ] && continue
    if is_expected "$file"; then
      [ "$ONLY_UNEXPECTED" = "true" ] && continue
      shown+="  ~ ${file}   (deliberate)"$'\n'
    else
      shown+="  ! ${file}"$'\n'
      unexpected_total=$((unexpected_total + 1))
    fi
  done <<<"$files"

  if [ -n "$shown" ]; then
    report_body+=("${set_name}  (${n})"$'\n'"${shown}")
  fi
done

case "$FORMAT" in
  counts)
    echo "total:${total} unexpected:${unexpected_total} ${counts_out[*]}"
    ;;
  report)
    echo "this repo     $(git rev-parse --short HEAD)"
    echo "counterpart   ${OTHER_REF}  $(git rev-parse --short "$OTHER_REF")"
    echo "merge base    $(git rev-parse --short "$(git merge-base HEAD "$OTHER_REF")")"
    echo
    echo "${total} differing shared files, ${unexpected_total} of them unexplained"
    echo
    for block in "${report_body[@]}"; do
      printf '%s\n' "$block"
    done
    echo "  !  unexplained. TWO different causes, and they need different fixes:"
    echo "       upstream-fork-gap - the two repos forked from DIFFERENT upstreams"
    echo "         (NuvioMobile:cmp-rewrite vs NuvioDesktop:Dev) at different times,"
    echo "         so a file one side inherited and the other did not shows up here."
    echo "         SIMKL and the newer locales are this. Settled by an upstream sync,"
    echo "         NOT by copying. This count stays large until both repos have synced."
    echo "       missed cp - one of OUR changes that never made it across. This is the"
    echo "         real bug, and the reason this script exists."
    echo "  ~  deliberate - see AGENTS.md. Do not copy these."
    echo
    echo "Shared changes should flow by 'git merge ${OTHER_NAME}/<branch>', not by cp."
    ;;
  *)
    echo "unknown format: $FORMAT (want report or counts)" >&2
    exit 1
    ;;
esac
