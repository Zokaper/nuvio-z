#!/usr/bin/env python3
"""Tests for scripts/check-changelog.py. Run: python3 -m unittest scripts/test_check_changelog.py"""
import copy
import importlib.util
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parent / "check-changelog.py"
spec = importlib.util.spec_from_file_location("check_changelog", SCRIPT)
cc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cc)


def ship(version, serial, date="unreleased"):
    return {"version": version, "serial": serial, "date": date}


def entry(title, platforms, category="feature", **extra):
    return dict({"category": category, "platforms": platforms, "title": title, "body": f"{title} body"}, **extra)


BASE = {
    "events": [
        {
            "seq": 3,
            "ships": {"ios": ship("0.5.4-z2", 128, "2026-10-20")},
            "entries": [entry("iOS sign-in fix", ["ios"], "fix")],
        },
        {
            "seq": 2,
            "summary": "Big one",
            "ships": {
                "desktop": ship("0.1.26-alpha-z1", 132),
                "android": ship("0.5.4-z1", 127, "2026-10-10"),
                "ios": ship("0.5.4-z1", 127, "2026-10-10"),
            },
            "entries": [
                entry("Everywhere", ["desktop", "android", "ios"]),
                entry("Phones only", ["android", "ios"], "improvement"),
                entry("Desktop only", ["desktop"], "fix"),
            ],
        },
        {
            "seq": 1,
            "ships": {"desktop": ship("0.1.23-alpha-z7", 131, "2026-10-02")},
            "entries": [entry("Older desktop", ["desktop"])],
        },
    ]
}


class ValidateTest(unittest.TestCase):
    def test_a_sound_file_is_valid(self):
        self.assertEqual([], cc.validate(BASE))

    def test_duplicate_and_unordered_seqs_are_rejected(self):
        data = copy.deepcopy(BASE)
        data["events"][0]["seq"] = 2
        problems = cc.validate(data)
        self.assertIn("duplicate seq 2", problems)
        data["events"][0]["seq"] = 0
        self.assertTrue(any("positive integer seq" in p for p in cc.validate(data)))
        data = copy.deepcopy(BASE)
        data["events"].reverse()
        self.assertIn("events must be listed newest (highest seq) first", cc.validate(data))

    def test_a_serial_that_goes_backwards_on_a_platform_is_rejected(self):
        data = copy.deepcopy(BASE)
        data["events"][1]["ships"]["desktop"]["serial"] = 130
        self.assertTrue(any("desktop serial does not increase" in p for p in cc.validate(data)))

    def test_android_and_ios_share_one_version_and_serial(self):
        data = copy.deepcopy(BASE)
        data["events"][1]["ships"]["ios"]["serial"] = 128
        self.assertTrue(any("different versions or serials" in p for p in cc.validate(data)))

    def test_an_entry_for_a_platform_the_event_does_not_ship_on_is_rejected(self):
        data = copy.deepcopy(BASE)
        data["events"][0]["entries"].append(entry("Desktop thing", ["desktop"]))
        self.assertTrue(any("does not ship on" in p for p in cc.validate(data)))

    def test_bad_categories_platforms_dates_and_actions_are_rejected(self):
        data = copy.deepcopy(BASE)
        data["events"][2]["entries"].append(entry("x", ["desktop"], "bugfix"))
        data["events"][2]["entries"].append(entry("y", ["tizen"]))
        data["events"][2]["ships"]["desktop"]["date"] = "2 Oct 2026"
        data["events"][2]["entries"].append(entry("z", ["desktop"], action="open_downloads"))
        problems = "\n".join(cc.validate(data))
        for expected in ("unknown category", "unknown platform 'tizen'", "YYYY-MM-DD", "unknown action"):
            self.assertIn(expected, problems)

    def test_a_release_after_an_unreleased_ship_on_one_platform_is_rejected(self):
        data = copy.deepcopy(BASE)
        data["events"][2]["ships"]["desktop"]["date"] = "unreleased"
        data["events"][1]["ships"]["desktop"]["date"] = "2026-10-11"
        self.assertTrue(any("released after unreleased" in p for p in cc.validate(data)))


class EventForTest(unittest.TestCase):
    def test_the_event_is_found_by_platform_serial(self):
        event, _ = cc.event_for(BASE, ["desktop"], 132)
        self.assertEqual(2, event["seq"])
        event, _ = cc.event_for(BASE, ["android", "ios"], 127)
        self.assertEqual(2, event["seq"])
        event, _ = cc.event_for(BASE, ["ios"], 128)
        self.assertEqual(3, event["seq"])

    def test_a_family_must_ship_together(self):
        event, error = cc.event_for(BASE, ["android", "ios"], 128)
        self.assertIsNone(event)
        self.assertIn("not on android", error)

    def test_no_event_for_a_serial(self):
        event, error = cc.event_for(BASE, ["desktop"], 133)
        self.assertIsNone(event)
        self.assertIn("0 events", error)


class NotesTest(unittest.TestCase):
    def test_desktop_notes_lead_with_desktop_entries_and_set_the_rest_apart(self):
        event, _ = cc.event_for(BASE, ["desktop"], 132)
        notes = cc.render_notes(event, ["desktop"])
        self.assertTrue(notes.startswith("Big one\n\n_Desktop 0.1.26-alpha-z1 · Android 0.5.4-z1 · iOS 0.5.4-z1_"))
        own, others = notes.split("#### Also in this update, on other devices")
        self.assertIn("**Everywhere**", own)
        self.assertIn("**Desktop only**", own)
        self.assertNotIn("Phones only", own)
        self.assertIn("**Phones only** Phones only body _(Android, iOS)_", others)

    def test_mobile_notes_tag_entries_that_do_not_cover_both_phones(self):
        data = copy.deepcopy(BASE)
        data["events"][1]["entries"].append(entry("Android only", ["android"]))
        event, _ = cc.event_for(data, ["android", "ios"], 127)
        notes = cc.render_notes(event, ["android", "ios"])
        self.assertIn("**Android only** Android only body _(Android)_", notes)
        self.assertIn("- **Phones only** Phones only body\n", notes)

    def test_an_event_on_one_platform_lists_only_that_version(self):
        event, _ = cc.event_for(BASE, ["ios"], 128)
        self.assertIn("_iOS 0.5.4-z2_", cc.render_notes(event, ["ios"]))


class CliTest(unittest.TestCase):
    def run_cli(self, data, *args, peer_text=None):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "changelog.json"
            path.write_text(json.dumps(data), encoding="utf-8")
            extra = []
            if peer_text is not None:
                peer = pathlib.Path(tmp) / "peer.json"
                peer.write_bytes(peer_text.encode("utf-8"))
                extra = [str(peer)]
            result = subprocess.run(
                [sys.executable, str(SCRIPT), "--file", str(path), *args, *extra],
                capture_output=True, text=True,
            )
            return result.returncode, result.stdout + result.stderr

    def test_check_passes_for_a_settled_event(self):
        code, out = self.run_cli(BASE, "--family", "mobile", "--serial", "127", "check", "--version", "0.5.4-z1")
        self.assertEqual(0, code, out)

    def test_check_refuses_an_open_qa_marker(self):
        data = copy.deepcopy(BASE)
        data["events"][1]["entries"][0]["qa"] = "iPhone lock acceptance open"
        code, out = self.run_cli(data, "--platform", "desktop", "--serial", "132", "check")
        self.assertEqual(1, code)
        self.assertIn("open acceptance", out)
        code, _ = self.run_cli(data, "--platform", "desktop", "--serial", "132", "check", "--allow-qa")
        self.assertEqual(0, code)

    def test_check_refuses_a_version_mismatch_and_a_missing_event(self):
        code, out = self.run_cli(BASE, "--family", "desktop", "--serial", "132", "check", "--version", "0.1.26-alpha-z2")
        self.assertEqual(1, code)
        self.assertIn("but this release is", out)
        code, out = self.run_cli(BASE, "--family", "desktop", "--serial", "140", "check")
        self.assertEqual(1, code)
        self.assertIn("no event for this release", out)

    def test_check_refuses_an_invalid_file(self):
        data = copy.deepcopy(BASE)
        data["events"][0]["seq"] = 2
        code, out = self.run_cli(data, "--family", "desktop", "--serial", "132", "check")
        self.assertEqual(1, code)
        self.assertIn("duplicate seq 2", out)

    def test_compare_is_exact_except_for_line_endings(self):
        text = json.dumps(BASE)
        code, _ = self.run_cli(BASE, "compare", peer_text=text.replace("\n", "\r\n"))
        self.assertEqual(0, code)
        changed = copy.deepcopy(BASE)
        changed["events"][0]["entries"][0]["title"] = "Different"
        code, out = self.run_cli(BASE, "compare", peer_text=json.dumps(changed))
        self.assertEqual(1, code)
        self.assertIn("differs", out)

    def test_compare_can_ignore_ship_dates_only(self):
        dated = copy.deepcopy(BASE)
        dated["events"][1]["ships"]["desktop"]["date"] = "2026-10-12"
        code, _ = self.run_cli(BASE, "compare", "--ignore-dates", peer_text=json.dumps(dated))
        self.assertEqual(0, code)
        code, _ = self.run_cli(BASE, "compare", peer_text=json.dumps(dated))
        self.assertEqual(1, code)


class ShippedFileTest(unittest.TestCase):
    def test_the_shipped_changelog_is_valid(self):
        data = json.loads(cc.CHANGELOG.read_text(encoding="utf-8"))
        self.assertEqual([], cc.validate(data))
        debug = cc.CHANGELOG.with_name("changelog-debug.json")
        self.assertEqual([], cc.validate_debug(json.loads(debug.read_text(encoding="utf-8"))))


if __name__ == "__main__":
    unittest.main()
