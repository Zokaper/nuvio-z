#!/usr/bin/env bash
# Renders every installer screen to PNGs with headless Edge, for design review.
#   bash scripts/render-screens.sh <out-dir> [theme=dark] [width=1040] [height=720] [step ...]
# No steps given: every step in ui/steps.js.
set -u
out="${1:?out dir}"; theme="${2:-dark}"; w="${3:-1040}"; h="${4:-720}"; shift $(( $# < 4 ? $# : 4 ))
here="$(cd "$(dirname "$0")/.." && pwd -W 2>/dev/null || pwd)"
edge="${EDGE:-/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe}"
ui="file:///$(echo "$here/ui/index.html" | sed 's/ /%20/g')"
steps=("$@")
[ ${#steps[@]} -eq 0 ] && mapfile -t steps < <(grep -oE '^    id: "[a-z0-9-]+"' "$here/ui/steps.js" | sed -E 's/.*"(.*)"/\1/')
mkdir -p "$out"
for s in "${steps[@]}"; do
  "$edge" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
    --window-size="$w,$h" --virtual-time-budget=1500 --user-data-dir="${TEMP:-/tmp}/nuvioz-installer-render" \
    --screenshot="$out/$s-$theme-${w}x$h.png" "$ui?step=$s&theme=$theme" >/dev/null 2>&1
  echo "$s"
done
