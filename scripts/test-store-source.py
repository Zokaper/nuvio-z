#!/usr/bin/env python3
"""
Test suite for SideStore source feeds and update-store-source.py isolation.
Verifies:
  1. source.json structure (com.nuvio.app.z)
  2. source-debug.json structure (com.nuvio.app.z.debug)
  3. update-store-source.py allows debug IPA -> source-debug.json
  4. update-store-source.py rejects debug IPA -> source.json
  5. update-store-source.py rejects stable IPA -> source-debug.json
  6. update-store-source.py allows stable IPA -> source.json
"""

import json
import os
import plistlib
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
UPDATE_SCRIPT = REPO_ROOT / "scripts" / "update-store-source.py"
SOURCE_STABLE = REPO_ROOT / "distribution" / "sidestore" / "source.json"
SOURCE_DEBUG = REPO_ROOT / "distribution" / "sidestore" / "source-debug.json"


def create_mock_ipa(dest: Path, bundle_id: str, version: str, build_version: str) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    info = {
        "CFBundleIdentifier": bundle_id,
        "CFBundleShortVersionString": version,
        "CFBundleVersion": build_version,
        "MinimumOSVersion": "15.0",
        "CFBundleDisplayName": "Nuvio Z Debug" if "debug" in bundle_id else "Nuvio Z",
    }
    plist_bytes = plistlib.dumps(info)

    with zipfile.ZipFile(dest, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("Payload/TestApp.app/Info.plist", plist_bytes)


def run_test():
    print("Testing SideStore source feed isolation...")

    # 1. Verify static feeds exist and are valid JSON
    assert SOURCE_STABLE.is_file(), f"Missing {SOURCE_STABLE}"
    assert SOURCE_DEBUG.is_file(), f"Missing {SOURCE_DEBUG}"

    with SOURCE_STABLE.open(encoding="utf-8") as f:
        stable_data = json.load(f)
    with SOURCE_DEBUG.open(encoding="utf-8") as f:
        debug_data = json.load(f)

    assert stable_data["name"] == "Nuvio Z", f"Unexpected stable name: {stable_data.get('name')}"
    assert stable_data["apps"][0]["bundleIdentifier"] == "com.nuvio.app.z", (
        f"Unexpected bundleId in stable: {stable_data['apps'][0].get('bundleIdentifier')}"
    )

    assert debug_data["name"] == "Nuvio Z Debug", f"Unexpected debug name: {debug_data.get('name')}"
    assert debug_data["apps"][0]["bundleIdentifier"] == "com.nuvio.app.z.debug", (
        f"Unexpected bundleId in debug: {debug_data['apps'][0].get('bundleIdentifier')}"
    )
    print("[OK] Static source feed structures verified.")

    # Create temporary directory for testing dynamic updates
    with tempfile.TemporaryDirectory() as tmpdir:
        tmp = Path(tmpdir)
        notes_file = tmp / "notes.md"
        notes_file.write_text("Test release notes\n", encoding="utf-8")

        mock_debug_ipa = tmp / "mock-debug.ipa"
        create_mock_ipa(mock_debug_ipa, "com.nuvio.app.z.debug", "0.4.13-z1", "99")

        mock_stable_ipa = tmp / "mock-stable.ipa"
        create_mock_ipa(mock_stable_ipa, "com.nuvio.app.z", "0.4.13-z1", "99")

        test_debug_source = tmp / "test-source-debug.json"
        shutil.copy(SOURCE_DEBUG, test_debug_source)

        test_stable_source = tmp / "test-source-stable.json"
        shutil.copy(SOURCE_STABLE, test_stable_source)

        # Test Case 3: Debug IPA -> Debug source (SHOULD SUCCEED)
        cmd_debug_ok = [
            sys.executable,
            str(UPDATE_SCRIPT),
            "--source", str(test_debug_source),
            "--ipa", str(mock_debug_ipa),
            "--release-notes", str(notes_file),
            "--release-version", "0.4.13-z1",
            "--release-date", "2026-09-22T12:00:00+00:00",
            "--download-url", "https://github.com/Zokaper/nuvio-z/releases/download/debug-v0.4.13-z1.99/test.ipa",
            "--expected-bundle-id", "com.nuvio.app.z.debug",
        ]
        res = subprocess.run(cmd_debug_ok, capture_output=True, text=True)
        assert res.returncode == 0, f"Debug update failed: {res.stderr}"
        with test_debug_source.open(encoding="utf-8") as f:
            updated_debug = json.load(f)
        assert updated_debug["apps"][0]["versions"][0]["buildVersion"] == "99"
        print("[OK] Debug IPA successfully updated source-debug.json.")

        # Test Case 4: Debug IPA -> Stable source (MUST FAIL - Cross-talk rejection)
        cmd_debug_to_stable = [
            sys.executable,
            str(UPDATE_SCRIPT),
            "--source", str(test_stable_source),
            "--ipa", str(mock_debug_ipa),
            "--release-notes", str(notes_file),
            "--release-version", "0.4.13-z1",
            "--release-date", "2026-09-22T12:00:00+00:00",
            "--download-url", "https://github.com/Zokaper/nuvio-z/releases/download/debug-v0.4.13-z1.99/test.ipa",
            "--expected-bundle-id", "com.nuvio.app.z",
        ]
        res = subprocess.run(cmd_debug_to_stable, capture_output=True, text=True)
        assert res.returncode != 0, "Security failure: Debug IPA was accepted into stable source!"
        assert "does not match expected bundle identifier 'com.nuvio.app.z'" in res.stderr
        print("[OK] Cross-talk rejected: Debug IPA cannot touch source.json.")

        # Test Case 5: Stable IPA -> Debug source (MUST FAIL - Cross-talk rejection)
        cmd_stable_to_debug = [
            sys.executable,
            str(UPDATE_SCRIPT),
            "--source", str(test_debug_source),
            "--ipa", str(mock_stable_ipa),
            "--release-notes", str(notes_file),
            "--release-version", "0.4.13-z1",
            "--release-date", "2026-09-22T12:00:00+00:00",
            "--download-url", "https://github.com/Zokaper/nuvio-z/releases/download/v0.4.13-z1/test.ipa",
            "--expected-bundle-id", "com.nuvio.app.z.debug",
        ]
        res = subprocess.run(cmd_stable_to_debug, capture_output=True, text=True)
        assert res.returncode != 0, "Security failure: Stable IPA was accepted into debug source!"
        assert "does not match expected bundle identifier 'com.nuvio.app.z.debug'" in res.stderr
        print("[OK] Cross-talk rejected: Stable IPA cannot touch source-debug.json.")

        # Test Case 6: Stable IPA -> Stable source (SHOULD SUCCEED)
        cmd_stable_ok = [
            sys.executable,
            str(UPDATE_SCRIPT),
            "--source", str(test_stable_source),
            "--ipa", str(mock_stable_ipa),
            "--release-notes", str(notes_file),
            "--release-version", "0.4.13-z1",
            "--release-date", "2026-09-22T12:00:00+00:00",
            "--download-url", "https://github.com/Zokaper/nuvio-z/releases/download/v0.4.13-z1/test.ipa",
            "--expected-bundle-id", "com.nuvio.app.z",
        ]
        res = subprocess.run(cmd_stable_ok, capture_output=True, text=True)
        assert res.returncode == 0, f"Stable update failed: {res.stderr}"
        with test_stable_source.open(encoding="utf-8") as f:
            updated_stable = json.load(f)
        assert updated_stable["apps"][0]["versions"][0]["buildVersion"] == "99"
        print("[OK] Stable IPA successfully updated source.json.")

        # Test Case 7: Stale lower build -> Debug source (MUST FAIL - Out-of-order race prevention)
        mock_stale_ipa = tmp / "mock-stale.ipa"
        create_mock_ipa(mock_stale_ipa, "com.nuvio.app.z.debug", "0.4.13-z1", "40")
        cmd_stale_debug = [
            sys.executable,
            str(UPDATE_SCRIPT),
            "--source", str(test_debug_source),
            "--ipa", str(mock_stale_ipa),
            "--release-notes", str(notes_file),
            "--release-version", "0.4.13-z1",
            "--release-date", "2026-09-22T10:00:00+00:00",
            "--download-url", "https://github.com/Zokaper/nuvio-z/releases/download/debug-v0.4.13-z1.40/stale.ipa",
            "--expected-bundle-id", "com.nuvio.app.z.debug",
        ]
        res = subprocess.run(cmd_stale_debug, capture_output=True, text=True)
        assert res.returncode != 0, "Race condition vulnerability: Stale/lower build was allowed to overwrite newer canonical feed!"
        assert "cannot overwrite newer canonical build" in res.stderr
        print("[OK] Race condition prevented: Stale lower build rejected from canonical feed.")

        # Test Case 8: Version history retention in descending order
        with test_debug_source.open(encoding="utf-8") as f:
            debug_history = json.load(f)
        versions_list = debug_history["apps"][0]["versions"]
        assert len(versions_list) >= 2, f"Expected multiple retained versions, got {len(versions_list)}"
        build_numbers = [int(v["buildVersion"]) for v in versions_list if v.get("buildVersion", "").isdigit()]
        assert build_numbers == sorted(build_numbers, reverse=True), (
            f"Versions not retained in descending order: {build_numbers}"
        )
        print("[OK] Version history preserved in descending build order.")

        # Test Case 9: Metadata verification
        latest_entry = versions_list[0]
        assert latest_entry["buildVersion"] == "99"
        assert latest_entry["size"] == mock_debug_ipa.stat().st_size
        assert latest_entry["sha256"]
        assert latest_entry["downloadURL"].endswith("test.ipa")
        print("[OK] IPA metadata (size, sha256, URL, version) accurately recorded.")

        # Test Case 10: debug-release workflow targets canonical main feed
        workflow_path = REPO_ROOT / ".github" / "workflows" / "debug-release.yml"
        assert workflow_path.is_file(), f"Missing workflow file: {workflow_path}"
        workflow_text = workflow_path.read_text(encoding="utf-8")
        assert "canonical-main" in workflow_text, "Workflow must clone canonical main for feed update"
        assert "push origin main" in workflow_text, "Workflow must push feed update to canonical main"
        assert "git merge" not in workflow_text, "Workflow must not merge feature branches into main"
        assert 'grep -q "source\\.json"' in workflow_text, "Workflow must verify stable source.json is untouched"
        print("[OK] Workflow static checks pass: targets canonical main without feature branch merge.")

    print("\nAll SideStore feed isolation and workflow promotion tests PASSED successfully!")


if __name__ == "__main__":
    run_test()
