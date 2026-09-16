#!/usr/bin/env python3
"""Regression tests for compact and legacy compatibility-matrix rows."""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("codegen-compatibility.py")
SPEC = importlib.util.spec_from_file_location("codegen_compatibility", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
CODEGEN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CODEGEN)


class CompatibilityCodegenTest(unittest.TestCase):
    def load(self, built_in: object) -> list[CODEGEN.Release]:
        source_data = {
            "schema": 1,
            "releases": [
                {
                    "lsposed": "2.5.8",
                    "bridge": "2.5.8",
                    "built-in": built_in,
                    "kmod": "2.5.8",
                },
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "compatibility.json"
            source.write_text(json.dumps(source_data), encoding="utf-8")
            return CODEGEN.load_releases(source)

    def test_legacy_single_version_is_accepted(self) -> None:
        releases = self.load("2.5.8")

        self.assertEqual(["2.5.8"], releases[0]["built_in"])
        self.assertIn('builtIn = listOf("2.5.8")', CODEGEN.render(releases))

    def test_compact_multiple_versions_are_accepted(self) -> None:
        releases = self.load(["2.5.3", "2.5.5", "2.5.8"])

        self.assertEqual(["2.5.3", "2.5.5", "2.5.8"], releases[0]["built_in"])
        self.assertIn(
            'builtIn = listOf("2.5.3", "2.5.5", "2.5.8")',
            CODEGEN.render(releases),
        )

    def test_duplicate_compact_versions_are_rejected(self) -> None:
        with self.assertRaisesRegex(SystemExit, "duplicate versions"):
            self.load(["2.5.8", "2.5.8"])


if __name__ == "__main__":
    unittest.main()
