#!/usr/bin/env python3
"""The release side of the global changelog.

    check-changelog.py validate                                  # structure of changelog.json (+ changelog-debug.json)
    check-changelog.py --family mobile --serial 127 check        # the release guard: the event this serial ships
    check-changelog.py --platform desktop --serial 132 check --version 0.1.26-alpha-z1
    check-changelog.py --family desktop --serial 132 notes       # that event as release-note markdown
    check-changelog.py compare ../nuvio-z/composeApp/src/commonMain/composeResources/files/changelog.json
    check-changelog.py compare https://raw.githubusercontent.com/... --ignore-dates

The changelog is composeApp/src/commonMain/composeResources/files/changelog.json, the same file the app
reads offline for What's New, and byte-identical in nuvio-z and nuviozdesktop. It is a list of release
events: each has a global `seq` and, per platform, the version / release serial / date it shipped in.
A release finds its event by `ships.<platform>.serial`. `check` fails a release whose serial ships no
event, whose event still carries a `qa` (open acceptance) marker, or whose version disagrees, so notes
are always written - and settled - before the version bump (the bump-last rule forbids adding them after).

`--family mobile` means Android and iOS together (one version, one serial); `--family desktop` means
desktop. This is the interface the release workflows already call.
"""
import argparse
import json
import pathlib
import re
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
CHANGELOG = ROOT / "composeApp/src/commonMain/composeResources/files/changelog.json"
PLATFORMS = ["desktop", "android", "ios"]
FAMILIES = {"mobile": ["android", "ios"], "desktop": ["desktop"]}
LABELS = {"desktop": "Desktop", "android": "Android", "ios": "iOS"}
CATEGORIES = [("feature", "New features"), ("improvement", "Improvements"), ("fix", "Bug fixes")]
DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
UNRELEASED = "unreleased"


def load_text(source):
    if re.match(r"^https?://", source):
        with urllib.request.urlopen(source, timeout=30) as response:
            return response.read().decode("utf-8")
    return pathlib.Path(source).read_text(encoding="utf-8")


def normalized(text):
    return text.replace("\r\n", "\n")


def is_released(ship):
    return ship.get("date") not in (None, "", UNRELEASED)


def validate(data):
    """Every structural problem in a parsed changelog.json, as strings. Mirrors `validateChangelog`."""
    problems = []
    events = data.get("events")
    if not isinstance(events, list) or not events:
        return ["changelog.json has no `events` list"]
    seqs = []
    for index, event in enumerate(events):
        where = f"event #{index}"
        if not isinstance(event, dict):
            problems.append(f"{where} is not an object")
            continue
        seq = event.get("seq")
        if not isinstance(seq, int) or isinstance(seq, bool) or seq <= 0:
            problems.append(f"{where} has no positive integer seq")
            continue
        where = f"seq {seq}"
        seqs.append(seq)
        summary = event.get("summary")
        if summary is not None and (not isinstance(summary, str) or not summary.strip()):
            problems.append(f"{where} summary must be a non-empty string when present")
        ships = event.get("ships")
        if not isinstance(ships, dict) or not ships:
            problems.append(f"{where} ships nowhere")
            ships = {}
        for platform, ship in ships.items():
            if platform not in PLATFORMS:
                problems.append(f"{where} ships on unknown platform '{platform}'")
                continue
            if not isinstance(ship, dict):
                problems.append(f"{where} {platform} ship is not an object")
                continue
            if not isinstance(ship.get("version"), str) or not ship["version"].strip():
                problems.append(f"{where} {platform} has no version")
            serial = ship.get("serial")
            if not isinstance(serial, int) or isinstance(serial, bool) or serial <= 0:
                problems.append(f"{where} {platform} has no positive integer serial")
            date = ship.get("date")
            if date != UNRELEASED and not (isinstance(date, str) and DATE.match(date)):
                problems.append(f"{where} {platform} date must be YYYY-MM-DD or '{UNRELEASED}'")
        android, ios = ships.get("android"), ships.get("ios")
        if isinstance(android, dict) and isinstance(ios, dict):
            if android.get("serial") != ios.get("serial") or android.get("version") != ios.get("version"):
                problems.append(f"{where} ships Android and iOS under different versions or serials")
        entries = event.get("entries")
        if not isinstance(entries, list) or not entries:
            problems.append(f"{where} has no entries")
            entries = []
        for entry in entries:
            title = entry.get("title") if isinstance(entry, dict) else None
            label = f"{where} '{title}'"
            if not isinstance(entry, dict) or not isinstance(title, str) or not title.strip():
                problems.append(f"{where} has an entry without a title")
                continue
            if entry.get("category") not in [c for c, _ in CATEGORIES]:
                problems.append(f"{label} has unknown category '{entry.get('category')}'")
            platforms = entry.get("platforms")
            if not isinstance(platforms, list) or not platforms:
                problems.append(f"{label} names no platform")
                platforms = []
            for platform in platforms:
                if platform not in PLATFORMS:
                    problems.append(f"{label} names unknown platform '{platform}'")
                elif platform not in ships:
                    problems.append(f"{label} names {platform}, which the event does not ship on")
            if "action" in entry and entry["action"] not in ("advanced_setup",):
                problems.append(f"{label} has unknown action '{entry['action']}'")
            if "qa" in entry and (not isinstance(entry["qa"], str) or not entry["qa"].strip()):
                problems.append(f"{label} qa must describe the open acceptance item")
    duplicates = sorted({s for s in seqs if seqs.count(s) > 1})
    problems += [f"duplicate seq {s}" for s in duplicates]
    if seqs != sorted(seqs, reverse=True):
        problems.append("events must be listed newest (highest seq) first")
    valid = [e for e in events if isinstance(e, dict) and isinstance(e.get("seq"), int) and isinstance(e.get("ships"), dict)]
    for platform in PLATFORMS:
        line = sorted(
            ((e["seq"], e["ships"][platform]) for e in valid if isinstance(e["ships"].get(platform), dict)),
            key=lambda pair: pair[0],
        )
        for (seq_a, a), (seq_b, b) in zip(line, line[1:]):
            if not isinstance(a.get("serial"), int) or not isinstance(b.get("serial"), int):
                continue
            if b["serial"] <= a["serial"]:
                problems.append(f"{platform} serial does not increase from seq {seq_a} to {seq_b}")
            if b.get("version") == a.get("version"):
                problems.append(f"{platform} version {b.get('version')} repeats at seq {seq_b}")
            if not is_released(a) and is_released(b):
                problems.append(f"{platform} seq {seq_b} is released after unreleased seq {seq_a}")
    return problems


def validate_debug(data):
    problems = []
    if data.get("family") not in FAMILIES:
        problems.append("changelog-debug.json family must be 'mobile' or 'desktop'")
    notes = data.get("notes")
    if not isinstance(notes, list):
        return problems + ["changelog-debug.json has no `notes` list"]
    builds = []
    for note in notes:
        build = note.get("build") if isinstance(note, dict) else None
        if not isinstance(build, int) or isinstance(build, bool) or build <= 0:
            problems.append("a debug note has no positive integer build")
            continue
        if not isinstance(note.get("text"), str) or not note["text"].strip():
            problems.append(f"debug note {build} has no text")
        builds.append(build)
    problems += [f"duplicate debug build {b}" for b in sorted({b for b in builds if builds.count(b) > 1})]
    return problems


def event_for(data, platforms, serial):
    """The event the release `serial` ships on `platforms` (all of them must agree)."""
    found = []
    for event in data.get("events", []):
        ships = event.get("ships", {})
        if any(isinstance(ships.get(p), dict) and ships[p].get("serial") == serial for p in platforms):
            found.append(event)
    if len(found) != 1:
        return None, f"{len(found)} events ship serial {serial} on {'/'.join(platforms)}"
    event = found[0]
    missing = [p for p in platforms if not (isinstance(event["ships"].get(p), dict) and event["ships"][p].get("serial") == serial)]
    if missing:
        return None, f"seq {event['seq']} ships serial {serial} on {'/'.join(p for p in platforms if p not in missing)} but not on {'/'.join(missing)}"
    return event, None


def render_notes(event, platforms):
    """Release-note markdown: the event's identity line, this release's entries, then the rest."""
    ships = event.get("ships", {})
    out = []
    if event.get("summary"):
        out += [event["summary"], ""]
    versions = " · ".join(f"{LABELS[p]} {ships[p]['version']}" for p in PLATFORMS if p in ships)
    out += [f"_{versions}_", ""]
    entries = event.get("entries", [])
    own = [e for e in entries if set(e.get("platforms", [])) & set(platforms)]
    others = [e for e in entries if not set(e.get("platforms", [])) & set(platforms)]

    def line(entry, tag_all):
        tagged = [LABELS[p] for p in PLATFORMS if p in entry.get("platforms", [])]
        covers_all = set(platforms) <= set(entry.get("platforms", []))
        tag = f" _({', '.join(tagged)})_" if tag_all or not covers_all else ""
        body = f" {entry['body']}" if entry.get("body") else ""
        return f"- **{entry['title']}**{body}{tag}"

    for category, heading in CATEGORIES:
        items = [e for e in own if e.get("category") == category]
        if items:
            out.append(f"#### {heading}")
            out += [line(e, tag_all=False) for e in items]
            out.append("")
    if others:
        out.append("#### Also in this update, on other devices")
        out += [line(e, tag_all=True) for e in others]
        out.append("")
    return "\n".join(out)


def without_dates(data):
    for event in data.get("events", []):
        for ship in event.get("ships", {}).values():
            if isinstance(ship, dict):
                ship.pop("date", None)
    return data


def main():
    parser = argparse.ArgumentParser()
    scope = parser.add_mutually_exclusive_group()
    scope.add_argument("--family", choices=sorted(FAMILIES))
    scope.add_argument("--platform", choices=PLATFORMS)
    parser.add_argument("--serial", type=int)
    parser.add_argument("--version", help="check: the version the release ships; must match the event")
    parser.add_argument("--allow-qa", action="store_true", help="check: accept open `qa` markers (never for a publish)")
    parser.add_argument("--ignore-dates", action="store_true", help="compare: ignore ships.*.date")
    parser.add_argument("--file", default=str(CHANGELOG))
    parser.add_argument("mode", choices=["validate", "check", "notes", "compare"])
    parser.add_argument("peer", nargs="?", help="compare: a path or http(s) URL of the other repository's changelog.json")
    args = parser.parse_intermixed_args()

    text = load_text(args.file)
    try:
        data = json.loads(text)
    except json.JSONDecodeError as error:
        print(f"changelog.json is not valid JSON: {error}", file=sys.stderr)
        return 1

    if args.mode == "compare":
        if not args.peer:
            parser.error("compare needs the peer changelog.json (path or URL)")
        try:
            peer_text = load_text(args.peer)
        except Exception as error:  # noqa: BLE001 - reported, not swallowed
            print(f"Could not read the peer changelog {args.peer}: {error}", file=sys.stderr)
            return 2
        if args.ignore_dates:
            same = without_dates(json.loads(text)) == without_dates(json.loads(peer_text))
        else:
            same = normalized(text) == normalized(peer_text)
        if not same:
            print(f"changelog.json differs from {args.peer}. The global changelog must be identical in both "
                  "repositories: copy the newer file across in the same change.", file=sys.stderr)
            return 1
        print(f"changelog.json matches {args.peer}{' (ignoring ship dates)' if args.ignore_dates else ''}.")
        return 0

    problems = validate(data)
    debug_file = pathlib.Path(args.file).with_name("changelog-debug.json")
    if args.mode == "validate" and debug_file.exists():
        problems += validate_debug(json.loads(debug_file.read_text(encoding="utf-8")))
    if problems:
        print("changelog.json is not valid:\n  " + "\n  ".join(problems), file=sys.stderr)
        return 1
    if args.mode == "validate":
        print(f"changelog.json is valid: {len(data['events'])} events.")
        return 0

    if args.serial is None or not (args.family or args.platform):
        parser.error(f"{args.mode} needs --family or --platform, and --serial")
    platforms = FAMILIES[args.family] if args.family else [args.platform]
    event, error = event_for(data, platforms, args.serial)

    if args.mode == "check":
        if event is None:
            print(f"changelog.json has no event for this release ({error}). "
                  "Add the release's event before the version bump.", file=sys.stderr)
            return 1
        failures = []
        for p in platforms:
            if not is_released(event["ships"][p]):
                failures.append(f"seq {event['seq']} {p} needs its actual ship date before release")
        if args.version and any(event["ships"][p].get("version") != args.version for p in platforms):
            failures.append(f"seq {event['seq']} ships {[event['ships'][p].get('version') for p in platforms]}, "
                            f"but this release is {args.version}")
        open_qa = [e["title"] for e in event.get("entries", []) if e.get("qa")]
        if open_qa and not args.allow_qa:
            failures.append(f"seq {event['seq']} still has open acceptance (`qa`) on: " + "; ".join(open_qa))
        if failures:
            print("\n".join(failures), file=sys.stderr)
            return 1
        print(f"changelog: seq {event['seq']} ships {'/'.join(platforms)} serial {args.serial} "
              f"with {len(event.get('entries', []))} entries.")
        return 0

    if event is None:
        print(f"changelog.json has no event for this release ({error}).", file=sys.stderr)
        return 1
    # Bytes, not text: the Windows runner's console encoding cannot carry "→".
    sys.stdout.buffer.write(render_notes(event, platforms).encode("utf-8"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
