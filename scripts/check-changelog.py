#!/usr/bin/env python3
"""The release side of the shipped changelog (Phase 9, plan 4.10).

    check-changelog.py --family mobile --serial 127 check   # exit 1 when the serial has no notes
    check-changelog.py --family mobile --serial 127 notes   # the curated notes as markdown

The changelog is composeApp/src/commonMain/composeResources/files/changelog.json - the same file the
app reads offline for What's New. A release whose RELEASE_SERIAL has no entries fails `check`, so
notes are always written before the version bump (the bump-last rule forbids adding them after).
"""
import argparse
import json
import pathlib
import sys

CHANGELOG = pathlib.Path(__file__).resolve().parent.parent / "composeApp/src/commonMain/composeResources/files/changelog.json"
HEADINGS = [("feature", "New features"), ("improvement", "Improvements"), ("fix", "Bug fixes")]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--family", required=True, choices=["mobile", "desktop"])
    parser.add_argument("--serial", required=True, type=int)
    parser.add_argument("--file", default=str(CHANGELOG))
    parser.add_argument("mode", choices=["check", "notes"])
    args = parser.parse_args()

    releases = json.loads(pathlib.Path(args.file).read_text(encoding="utf-8")).get("releases", [])
    release = next((r for r in releases if r.get("family") == args.family and r.get("serial") == args.serial), None)
    entries = (release or {}).get("entries", [])

    if args.mode == "check":
        if not entries:
            print(
                f"changelog.json has no {args.family} entries for RELEASE_SERIAL {args.serial}. "
                "Add the release's notes before the version bump.",
                file=sys.stderr,
            )
            return 1
        if release.get("date") in (None, "", "unreleased"):
            print(f"warning: {args.family} {args.serial} is still dated '{release.get('date')}'.", file=sys.stderr)
        print(f"changelog: {args.family} {release['version']} ({args.serial}) has {len(entries)} entries.")
        return 0

    out = []
    for category, heading in HEADINGS:
        items = [e for e in entries if e.get("category") == category]
        if not items:
            continue
        out.append(f"#### {heading}")
        for entry in items:
            platforms = ", ".join(p.capitalize() if p != "ios" else "iOS" for p in entry.get("platforms", []))
            body = f" {entry['body']}" if entry.get("body") else ""
            out.append(f"- **{entry['title']}**{body} _({platforms})_")
        out.append("")
    # Bytes, not text: the Windows runner's console encoding cannot carry "→".
    sys.stdout.buffer.write("\n".join(out).encode("utf-8"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
