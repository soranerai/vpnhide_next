"""Shared helpers for build scripts.

Used by scripts/build-version.py.

Stdlib-only on purpose: scripts/build-version.py is invoked from
lsposed/app/build.gradle.kts on every Gradle build, so adding pip/uv
dependencies here would break the APK build for anyone without those
tools available.
"""

from __future__ import annotations

import subprocess
from pathlib import Path


def get_build_version(repo_root: Path | None = None) -> str:
    """Get the effective build version for vpnhide artifacts.

    - HEAD on a tag vX.Y.Z        -> "X.Y.Z"          (release build)
    - N commits after tag vX.Y.Z  -> "X.Y.Z-N-gSHA"   (dev build)
    - working tree dirty          -> additional "-dirty" suffix
    - no git / no matching tag    -> falls back to VERSION file
    """
    if repo_root is None:
        repo_root = Path(__file__).resolve().parent.parent

    result = subprocess.run(
        ["git", "describe", "--tags", "--match", "v*", "--dirty"],
        cwd=repo_root,
        capture_output=True,
        text=True,
    )
    if result.returncode == 0 and result.stdout.strip():
        return result.stdout.strip().removeprefix("v")

    version_file = repo_root / "VERSION"
    return version_file.read_text(encoding="utf-8").strip()
