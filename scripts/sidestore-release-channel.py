#!/usr/bin/env python3
"""Fail-closed routing for published iOS app tags (never installer/helper tags)."""
import argparse
import re

STABLE = re.compile(r"v?\d+\.\d+\.\d+(?:-(?:alpha|beta|rc))?-z\d+(?:\+\d+)?")
DEBUG = re.compile(r"debug-v\d+\.\d+\.\d+(?:-(?:alpha|beta|rc))?-z\d+\.\d+")


def route(tag):
    if DEBUG.fullmatch(tag):
        return "distribution/sidestore/source-debug.json", "com.nuvio.app.z.debug"
    if STABLE.fullmatch(tag):
        return "distribution/sidestore/source.json", "com.nuvio.app.z"
    raise ValueError(f"Not an iOS app release tag: {tag}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("tag")
    args = parser.parse_args()
    try:
        source, bundle = route(args.tag)
    except ValueError as error:
        parser.error(str(error))
    print(f"TARGET_SOURCE={source}")
    print(f"EXPECTED_BUNDLE_ID={bundle}")


if __name__ == "__main__":
    main()
