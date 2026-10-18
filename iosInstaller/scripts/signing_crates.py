#!/usr/bin/env python3
"""The upstream crates the installer and Nuvio Z sign and install with, for the auto-release workflow.

  check  [--cooldown-days 3] [--simulate NAME=VERSION ...]   JSON of the bumps to adopt, on stdout
  apply  BUMPS_JSON                                          rewrites the "=x.y.z" pins in Cargo.toml
  diff   BUMPS_JSON OUT_DIR                                  downloads both versions of each crate and writes
                                                             OUT_DIR/<crate>-<from>-to-<to>.diff
  servers [--cooldown-days 1]                                iloader's anisette server list (its latest release's
                                                             src/pages/Settings.tsx) against ours, as JSON
  apply-servers SERVERS_JSON                                 writes iloader's list into core/src/account.rs and its
                                                             first server into core/src/anisette.rs
  bump-version                                               next installer version in Cargo.toml and
                                                             app/tauri.conf.json (alpha.N -> alpha.N+1, else
                                                             patch + 1); prints it

A version is adopted only once it has been on crates.io for the cooldown (a broken or tampered release is
usually yanked within days) and is not yanked. Pins are exact ("=0.4.6"), so nothing moves without this.
`--simulate isideload=0.4.5` pretends the pin is 0.4.5: a dry run that exercises the review against the last
real upgrade without changing anything.
Standard library only; run from iosInstaller/.
"""
import argparse
import datetime as dt
import io
import json
import pathlib
import re
import subprocess
import sys
import tarfile
import urllib.request

CRATES = ["isideload", "idevice"]
# iloader is the same author's sideloader on the same crates. When Apple changes what it accepts, the fix often
# ships there as a different anisette server list before (or instead of) a crate release, as on 2026-10-18.
ILOADER = "nab138/iloader"
ACCOUNT_RS = pathlib.Path("core/src/account.rs")
ANISETTE_RS = pathlib.Path("core/src/anisette.rs")
CARGO_TOML = pathlib.Path("Cargo.toml")
USER_AGENT = "nuvio-z-ios-installer-auto-release (github.com/Zokaper/nuvio-z)"


def read(path: pathlib.Path) -> str:
    """Keeps the line endings, so a rewrite changes only the line it means to."""
    with open(path, encoding="utf-8", newline="") as handle:
        return handle.read()


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def version_key(version: str):
    core, _, pre = version.partition("-")
    numbers = tuple(int(part) for part in core.split("."))
    # A prerelease sorts before its release.
    return numbers, (0, pre) if pre else (1, "")


def pinned(toml_text: str, crate: str) -> str:
    match = re.search(rf'^{re.escape(crate)}\s*=\s*\{{\s*version\s*=\s*"=([^"]+)"', toml_text, re.M)
    if not match:
        sys.exit(f"{crate}: no exact pin (version = \"=x.y.z\") in {CARGO_TOML}")
    return match.group(1)


def check(cooldown_days: int, simulate: dict) -> list:
    toml_text = read(CARGO_TOML)
    now = dt.datetime.now(dt.timezone.utc)
    bumps = []
    for crate in CRATES:
        current = simulate.get(crate) or pinned(toml_text, crate)
        info = json.loads(fetch(f"https://crates.io/api/v1/crates/{crate}"))
        candidates = []
        for version in info["versions"]:
            created = dt.datetime.fromisoformat(version["created_at"].replace("Z", "+00:00"))
            if version["yanked"] or "-" in version["num"]:
                continue
            if (now - created).days < cooldown_days:
                print(f"{crate} {version['num']}: published {created:%Y-%m-%d}, waiting out the cooldown", file=sys.stderr)
                continue
            if version_key(version["num"]) > version_key(current):
                candidates.append(version["num"])
        if candidates:
            newest = max(candidates, key=version_key)
            bumps.append({"crate": crate, "from": current, "to": newest, "repository": info["crate"].get("repository")})
    return bumps


def apply(bumps: list) -> None:
    text = read(CARGO_TOML)
    for bump in bumps:
        pattern = rf'^({re.escape(bump["crate"])}\s*=\s*\{{\s*version\s*=\s*"=)[^"]+(")'
        text, count = re.subn(pattern, rf'\g<1>{bump["to"]}\g<2>', text, flags=re.M)
        if count != 1:
            sys.exit(f"{bump['crate']}: pin not found")
    CARGO_TOML.write_text(text, encoding="utf-8", newline="")


def unpack(crate: str, version: str, into: pathlib.Path) -> pathlib.Path:
    data = fetch(f"https://crates.io/api/v1/crates/{crate}/{version}/download")
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        archive.extractall(into, filter="data")
    return into / f"{crate}-{version}"


def diff(bumps: list, out: pathlib.Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    work = out / "sources"
    for bump in bumps:
        old = unpack(bump["crate"], bump["from"], work / "old").relative_to(work)
        new = unpack(bump["crate"], bump["to"], work / "new").relative_to(work)
        result = subprocess.run(["diff", "-ruN", old.as_posix(), new.as_posix()], capture_output=True, text=True, cwd=work)
        target = out / f"{bump['crate']}-{bump['from']}-to-{bump['to']}.diff"
        target.write_text(result.stdout, encoding="utf-8")
        print(f"{target}: {len(result.stdout.splitlines())} lines", file=sys.stderr)


def our_servers() -> list:
    block = re.search(r"pub const ANISETTE_SERVERS: &\[&str\] = &\[(.*?)\];", read(ACCOUNT_RS), re.S)
    if not block:
        sys.exit("no ANISETTE_SERVERS in core/src/account.rs")
    return re.findall(r'"https://([^"]+)"', block.group(1))


def iloader_servers(cooldown_days: int) -> dict:
    release = json.loads(fetch(f"https://api.github.com/repos/{ILOADER}/releases/latest"))
    tag, published = release["tag_name"], release["published_at"]
    source = fetch(f"https://raw.githubusercontent.com/{ILOADER}/{tag}/src/pages/Settings.tsx").decode("utf-8")
    block = re.search(r"^let anisetteServers = \[(.*?)^\];", source, re.S | re.M)  # not the commented-out old list
    if not block:
        sys.exit(f"{ILOADER} {tag}: no anisetteServers list in src/pages/Settings.tsx")
    theirs = [m.group(1) for line in block.group(1).splitlines()
              if not line.strip().startswith("//") and (m := re.search(r'\["([^"]+)"', line))]
    ours = our_servers()
    age = (dt.datetime.now(dt.timezone.utc) - dt.datetime.fromisoformat(published.replace("Z", "+00:00"))).days
    changed = bool(theirs) and theirs != ours
    if changed and age < cooldown_days:
        print(f"{ILOADER} {tag}: published {published[:10]}, waiting out the cooldown", file=sys.stderr)
        changed = False
    return {"changed": changed, "tag": tag, "published": published, "ours": ours, "iloader": theirs,
            "release_notes": release.get("body", "")[:4000]}


def apply_servers(servers: dict) -> None:
    hosts = servers["iloader"]
    text = read(ACCOUNT_RS)
    newline = "\r\n" if "\r\n" in text else "\n"
    entries = "".join(f'    "https://{host}",{newline}' for host in hosts)
    text, count = re.subn(r"(pub const ANISETTE_SERVERS: &\[&str\] = &\[).*?(\];)",
                          lambda m: m.group(1) + newline + entries + m.group(2), text, count=1, flags=re.S)
    if count != 1:
        sys.exit("ANISETTE_SERVERS not found")
    with open(ACCOUNT_RS, "w", encoding="utf-8", newline="") as handle:
        handle.write(text)
    anisette = read(ANISETTE_RS)
    anisette, count = re.subn(r'(pub const DEFAULT_SERVER: &str = ")[^"]+(";)', rf"\g<1>https://{hosts[0]}\g<2>", anisette, count=1)
    if count != 1:
        sys.exit("DEFAULT_SERVER not found")
    with open(ANISETTE_RS, "w", encoding="utf-8", newline="") as handle:
        handle.write(anisette)


def bump_version() -> str:
    toml_text = read(CARGO_TOML)
    match = re.search(r'^version = "([^"]+)"', toml_text, re.M)
    if not match:
        sys.exit("no workspace version in Cargo.toml")
    current = match.group(1)
    core, _, pre = current.partition("-")
    if pre and re.fullmatch(r"[a-z]+\.\d+", pre):
        label, number = pre.split(".")
        new = f"{core}-{label}.{int(number) + 1}"
    else:
        major, minor, patch = core.split(".")
        new = f"{major}.{minor}.{int(patch) + 1}"
    CARGO_TOML.write_text(toml_text.replace(f'version = "{current}"', f'version = "{new}"', 1), encoding="utf-8", newline="")
    tauri = pathlib.Path("app/tauri.conf.json")
    tauri_text = read(tauri)
    if f'"version": "{current}"' not in tauri_text:
        sys.exit(f"app/tauri.conf.json is not at {current}")
    tauri.write_text(tauri_text.replace(f'"version": "{current}"', f'"version": "{new}"', 1), encoding="utf-8", newline="")
    return new


def main() -> None:
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    check_parser = commands.add_parser("check")
    check_parser.add_argument("--cooldown-days", type=int, default=3)
    check_parser.add_argument("--simulate", action="append", default=[], metavar="CRATE=VERSION")
    commands.add_parser("apply").add_argument("bumps")
    diff_parser = commands.add_parser("diff")
    diff_parser.add_argument("bumps")
    diff_parser.add_argument("out")
    commands.add_parser("bump-version")
    servers_parser = commands.add_parser("servers")
    servers_parser.add_argument("--cooldown-days", type=int, default=1)
    commands.add_parser("apply-servers").add_argument("servers")
    args = parser.parse_args()

    if args.command == "check":
        simulate = dict(item.split("=", 1) for item in args.simulate)
        print(json.dumps(check(args.cooldown_days, simulate)))
    elif args.command == "apply":
        apply(json.loads(args.bumps))
    elif args.command == "diff":
        diff(json.loads(args.bumps), pathlib.Path(args.out))
    elif args.command == "servers":
        print(json.dumps(iloader_servers(args.cooldown_days)))
    elif args.command == "apply-servers":
        apply_servers(json.loads(args.servers))
    else:
        print(bump_version())


if __name__ == "__main__":
    main()
