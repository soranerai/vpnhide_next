#!/usr/bin/env python3
"""Print the effective build version for vpnhide_next artifacts.

Used by every packaging step (module.prop, APK versionName, CI
artifact names) so dev builds are unambiguously identifiable at a
glance. Called from `app/build.gradle.kts` on every Gradle build, so
stays on stdlib only — Gradle shouldn't need `uv` / external deps to
assemble the APK.
"""

from __future__ import annotations

import sys

from build_lib import get_build_version


def main() -> int:
    print(get_build_version())
    return 0


if __name__ == "__main__":
    sys.exit(main())
