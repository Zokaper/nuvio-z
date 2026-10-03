import importlib.util
import pathlib
import json
import plistlib
import shutil
import subprocess
import sys
import tempfile
import zipfile
import unittest

spec = importlib.util.spec_from_file_location("route", pathlib.Path(__file__).with_name("sidestore-release-channel.py"))
channel = importlib.util.module_from_spec(spec)
spec.loader.exec_module(channel)


class ReleaseChannelTest(unittest.TestCase):
    def test_planned_stable(self):
        self.assertEqual(("distribution/sidestore/source.json", "com.nuvio.app.z"), channel.route("0.5.4-z1+127"))

    def test_legacy_v_stable(self):
        self.assertEqual(("distribution/sidestore/source.json", "com.nuvio.app.z"), channel.route("v0.4.13-z1"))

    def test_debug(self):
        for tag in ("debug-v0.4.13-z1.76", "debug-v0.4.13-z1.77"):
            self.assertEqual(("distribution/sidestore/source-debug.json", "com.nuvio.app.z.debug"), channel.route(tag))

    def test_unrelated_and_helpers_fail_closed(self):
        for tag in ("arbitrary", "vgarbage", "ios-setup-v1.0.0", "installer-v0.5.4-z1+127", "debug-vios-setup-v1", "0.5.4-z1+127-extra", "debug-v0.4.13-z1.77+127", "0.5.4-z1+127\n"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                channel.route(tag)

    def test_dry_publication_only_mutates_the_selected_feed(self):
        root = pathlib.Path(__file__).resolve().parent.parent
        for tag, version, build in (("0.5.4-z1+127", "0.5.4-z1", "126"), ("debug-v0.4.13-z1.77", "0.4.13-z1.77", "77")):
            with self.subTest(tag=tag), tempfile.TemporaryDirectory() as temp:
                work = pathlib.Path(temp)
                feeds = ("distribution/sidestore/source.json", "distribution/sidestore/source-debug.json")
                for feed in feeds:
                    target = work / feed
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(root / feed, target)
                before = {feed: (work / feed).read_bytes() for feed in feeds}
                selected, bundle = channel.route(tag)
                ipa = work / "synthetic.ipa"
                with zipfile.ZipFile(ipa, "w") as archive:
                    archive.writestr("Payload/Nuvio.app/Info.plist", plistlib.dumps({
                        "CFBundleIdentifier": bundle, "CFBundleShortVersionString": version,
                        "CFBundleVersion": build, "MinimumOSVersion": "16.0",
                        "CFBundleName": "Nuvio Z", "CFBundleDisplayName": "Nuvio Z",
                    }))
                notes = work / "notes.md"
                notes.write_text("Synthetic isolation regression", encoding="utf-8")
                result = subprocess.run([
                    sys.executable, str(root / "scripts/update-store-source.py"),
                    "--source", str(work / selected), "--expected-bundle-id", bundle,
                    "--ipa", str(ipa), "--release-notes", str(notes), "--release-version", version,
                    "--release-date", "2026-10-03T00:00:00Z",
                    "--download-url", f"https://example.test/releases/{tag}/synthetic.ipa",
                ], capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                for feed in feeds:
                    if feed == selected:
                        self.assertNotEqual(before[feed], (work / feed).read_bytes())
                        entry = json.loads((work / feed).read_text(encoding="utf-8"))["apps"][0]["versions"][0]
                        self.assertEqual(version, entry["version"])
                        self.assertEqual(build, entry["buildVersion"])
                    else:
                        self.assertEqual(before[feed], (work / feed).read_bytes())

    def test_workflow_routes_before_downloading_or_mutating(self):
        workflow = (pathlib.Path(__file__).resolve().parent.parent / ".github/workflows/update-store-source.yml").read_text(encoding="utf-8")
        self.assertNotIn("if: startsWith", workflow)
        self.assertLess(workflow.index("sidestore-release-channel.py"), workflow.index("Download and verify published IPA"))
        self.assertIn('--source "${TARGET_SOURCE}"', workflow)
        self.assertNotIn('"${is_prerelease}" == "true" ||', workflow)


if __name__ == "__main__":
    unittest.main()
